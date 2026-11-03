package io.github.superslop.pairhelper

import android.content.Context
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.*
import java.io.InputStream

object AdbBridge {

    private const val LOCALHOST = "127.0.0.1"
    private const val DEFAULT_PORT = 5555
    private const val SHELL_READ_TIMEOUT_MS = 3000

    /** Long enough for a slow commit on a big app; short enough to not hang forever. */
    private const val COMMAND_TIMEOUT_MS = 60_000L
    private const val COMMIT_TIMEOUT_MS = 300_000L
    private const val TRANSFER_TIMEOUT_MS = 300_000L

    /** Wireless debugging pairing codes are always exactly six digits. */
    private val PAIRING_CODE_PATTERN = Regex("\\d{6}")

    /** A real pairing handshake completes in well under a second on the loopback. */
    private const val PAIRING_TIMEOUT_MS = 20_000L

    private const val TCPIP_VERIFY_TIMEOUT_MS = 10_000L
    private const val TCPIP_POLL_INTERVAL_MS = 500L

    private const val RETRY_HINT =
        "Tap \"Pair device with pairing code\" again for a fresh code, then reply with the new one."

    /**
     * Hosts pairing attempts that may outlive the caller. Detached on purpose — see [pair].
     */
    private val pairingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Gets the target IP address from settings, falling back to localhost
     */
    private fun getTargetIp(context: Context): String {
        return SettingsManager.getTargetIpAddress(context) ?: LOCALHOST
    }

    enum class ConnectionStatus {
        NOT_CONNECTED,
        CONNECTED,
        NEEDS_PAIRING,
        ERROR
    }

    // Keep track of connection state without constantly reconnecting
    @Volatile
    private var lastConnectionCheck: Long = 0
    @Volatile
    private var lastConnectionStatus: ConnectionStatus = ConnectionStatus.NEEDS_PAIRING
    private const val CONNECTION_CHECK_CACHE_MS = 2000 // Cache for 2 seconds

    fun getConnectionStatus(context: Context, forceCheck: Boolean = false): ConnectionStatus {
        // Use cached status if recent (unless forced)
        val now = System.currentTimeMillis()
        if (!forceCheck && (now - lastConnectionCheck) < CONNECTION_CHECK_CACHE_MS) {
            return lastConnectionStatus
        }

        var stream: AdbStream? = null
        val status = try {
            val manager = AdbConnectionManager.getInstance(context)

            // Try to auto-connect using service discovery (works after pairing)
            if (!manager.autoConnect(context, 3000)) {
                ConnectionStatus.NEEDS_PAIRING
            } else {
                // Actually test the connection with a simple command
                try {
                    stream = manager.openStream("shell:echo test")
                    val buffer = ByteArray(128)
                    val bytesRead = stream.openInputStream().read(buffer)
                    stream.close()

                    // If we got a response, we're connected and authorized
                    if (bytesRead > 0) {
                        ConnectionStatus.CONNECTED
                    } else {
                        ConnectionStatus.NEEDS_PAIRING
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    try {
                        stream?.close()
                    } catch (ex: Exception) {
                        ex.printStackTrace()
                    }
                    // Don't close manager here - let it be reused
                    ConnectionStatus.NEEDS_PAIRING
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ConnectionStatus.NEEDS_PAIRING
        }

        lastConnectionCheck = now
        lastConnectionStatus = status
        return status
    }

    /**
     * Pairs with the local wireless debugging service.
     *
     * The handshake underneath is a blocking socket read with no timeout of its own. A
     * wrong code makes adbd tear the session down mid-handshake, and the read then never
     * returns — which is why a mistyped code used to wedge the pairing notification until
     * the device was rebooted. Everything here exists to guarantee this returns.
     */
    suspend fun pair(context: Context, pairingCode: String, pairingPort: Int): Result<Boolean> {
        val code = pairingCode.trim()
        if (!PAIRING_CODE_PATTERN.matches(code)) {
            return Result.failure(
                Exception("That is not a pairing code. Enter the 6 digits shown in the \"Pair device with pairing code\" dialog.")
            )
        }
        if (pairingPort !in 1..65535) {
            return Result.failure(
                Exception("No pairing port found yet. Tap \"Pair device with pairing code\" in Wireless debugging, then try again.")
            )
        }

        val targetIp = getTargetIp(context)

        // Deliberately not a child of the caller's job. Cancelling a coroutine cannot
        // interrupt a thread parked in a blocking socket read, so on timeout the only
        // option is to abandon this thread and let it unwind whenever the socket
        // finally errors out. Awaiting it would defeat the timeout entirely.
        val attempt = pairingScope.async {
            AdbConnectionManager.getInstance(context).pair(targetIp, pairingPort, code)
        }

        val paired = try {
            withTimeoutOrNull(PAIRING_TIMEOUT_MS) { attempt.await() }
        } catch (e: CancellationException) {
            // The caller went away (the service was stopped). Must not be reported as a
            // pairing failure, or the handler would run against a dead service.
            attempt.cancel()
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            attempt.cancel()
            // The underlying messages are adbd protocol detail; keep the cause for the
            // log and show the user something they can act on.
            return Result.failure(Exception("Pairing failed. $RETRY_HINT", e))
        }

        return when (paired) {
            // pair() reports a rejected code by returning false rather than throwing;
            // ignoring the return value used to report a bad code as a success.
            true -> Result.success(true)
            false -> Result.failure(Exception("The device rejected that code. $RETRY_HINT"))
            null -> {
                attempt.cancel()
                Result.failure(Exception("Pairing timed out. $RETRY_HINT"))
            }
        }
    }

    suspend fun testConnection(context: Context): Result<Boolean> = withContext(Dispatchers.IO) {
        var stream: AdbStream? = null
        return@withContext try {
            val manager = AdbConnectionManager.getInstance(context)

            // Connect to local ADB - this should trigger authorization prompt
            if (!manager.autoConnect(context, 10000)) {
                return@withContext Result.failure(Exception("Could not connect to ADB. Make sure wireless debugging is enabled."))
            }

            // Try to execute a simple command to verify authorization
            stream = manager.openStream("shell:echo test")
            val output = StringBuilder()
            val inputStream = stream.openInputStream()
            val buffer = ByteArray(128)
            var bytesRead: Int

            // Read with timeout
            var totalWait = 0
            while (totalWait < 5000) {
                if (inputStream.available() > 0) {
                    bytesRead = inputStream.read(buffer)
                    if (bytesRead > 0) {
                        output.append(String(buffer, 0, bytesRead))
                        break
                    }
                }
                kotlinx.coroutines.delay(100)
                totalWait += 100
            }

            stream.close()
            manager.close()

            if (output.contains("test")) {
                Result.success(true)
            } else {
                Result.failure(Exception("Connection test failed. Did you authorize the prompt?"))
            }

        } catch (e: Exception) {
            e.printStackTrace()
            try {
                stream?.close()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
            Result.failure(Exception("Authorization required. Check for 'Allow USB debugging?' prompt and tap 'Always allow'."))
        }
    }

    /**
     * Runs a shell command over the existing ADB connection and returns whatever it
     * printed. Silent commands simply return an empty string once the read window
     * elapses, so callers that need certainty should verify the effect separately
     * rather than trusting the output.
     */
    suspend fun runShellCommand(context: Context, command: String): Result<String> = withContext(Dispatchers.IO) {
        var stream: AdbStream? = null
        return@withContext try {
            val manager = AdbConnectionManager.getInstance(context)

            if (!manager.autoConnect(context, 10000)) {
                return@withContext Result.failure(Exception("Could not connect to ADB."))
            }

            stream = manager.openStream("shell:$command")
            val output = StringBuilder()
            val inputStream = stream.openInputStream()
            val buffer = ByteArray(1024)

            var totalWait = 0
            while (totalWait < SHELL_READ_TIMEOUT_MS) {
                if (inputStream.available() > 0) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead <= 0) break
                    output.append(String(buffer, 0, bytesRead))
                } else {
                    kotlinx.coroutines.delay(100)
                    totalWait += 100
                }
            }

            // Leave the shared connection open; the caller is usually mid-flow.
            stream.close()

            Result.success(output.toString().trim())
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                stream?.close()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
            Result.failure(e)
        }
    }

    /**
     * Switches adbd to plain-TCP mode on port 5555, so Termux / proot can run
     * `adb connect 127.0.0.1:5555` with no pairing or authorisation at all.
     *
     * There is no `tcpip` entry in libadb's local-services table: on-device,
     * the equivalent is the shell sequence below. It runs as the shell user
     * over the already-authorised TLS connection. `stop adbd` kills that
     * connection mid-command, so success is judged by the port answering
     * afterwards, not by command output.
     *
     * Plain-TCP adbd is unauthenticated to anything that can reach the port.
     * On loopback-only use (same device) that is the intended trade-off; the
     * caller should warn when the device is on an untrusted network.
     */
    suspend fun enableTcpip5555(context: Context): Result<Boolean> = withContext(Dispatchers.IO) {
        runShellCommand(
            context,
            "setprop service.adb.tcp.port 5555; stop adbd; start adbd"
        )
        // adbd restarts; the TLS connection is gone. Poll the plain port.
        var waited = 0L
        while (waited < TCPIP_VERIFY_TIMEOUT_MS) {
            if (isTcpPortOpen(5555)) return@withContext Result.success(true)
            delay(TCPIP_POLL_INTERVAL_MS)
            waited += TCPIP_POLL_INTERVAL_MS
        }
        Result.failure(Exception("Port 5555 did not come up. adbd may not accept the setprop/stop/start sequence on this build."))
    }

    private fun isTcpPortOpen(port: Int): Boolean = try {
        java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress(LOCALHOST, port), 500)
            true
        }
    } catch (e: Exception) {
        false
    }
}
