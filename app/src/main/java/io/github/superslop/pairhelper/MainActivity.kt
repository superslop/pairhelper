package io.github.superslop.pairhelper

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PairHelper's single screen: a setup checklist until the device is paired
 * and connected, then the two actions Termux/proot users actually need —
 * switch adbd to plain TCP 5555, and (once self-granted) turn Wireless
 * debugging back on after a reboot.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var actionButton: Button
    private lateinit var refreshButton: Button
    private lateinit var tcpipButton: Button
    private lateinit var wirelessButton: Button
    private lateinit var copyButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        actionButton = findViewById(R.id.actionButton)
        refreshButton = findViewById(R.id.refreshButton)
        tcpipButton = findViewById(R.id.tcpipButton)
        wirelessButton = findViewById(R.id.wirelessButton)
        copyButton = findViewById(R.id.copyButton)

        refreshButton.setOnClickListener { checkStatus() }
        tcpipButton.setOnClickListener { enableTcpip5555() }
        wirelessButton.setOnClickListener { enableWirelessDebugging() }
        copyButton.setOnClickListener { copyTermuxCommand() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Keep the newest intent so onResume can see EXTRA_FROM_PAIRING.
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()

        // Coming straight back from a successful pairing: skip the cached
        // connection status, which is still pre-pairing.
        val fromPairing = intent?.getBooleanExtra(EXTRA_FROM_PAIRING, false) == true
        if (fromPairing) {
            intent.removeExtra(EXTRA_FROM_PAIRING)
        }
        checkStatus(forceCheck = fromPairing)
    }

    private fun checkStatus(forceCheck: Boolean = false) {
        lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) {
                AdbBridge.getConnectionStatus(this@MainActivity, forceCheck)
            }
            when (status) {
                AdbBridge.ConnectionStatus.CONNECTED -> {
                    showConnectedState()
                    stopPairingService()
                    SettingsManager.setHasPairedBefore(this@MainActivity)
                    // A working connection is the only chance to pick up the
                    // permission that lets PairHelper re-enable wireless
                    // debugging on its own after a reboot.
                    acquireWirelessDebuggingPermission()
                }
                else -> showSetupChecklist()
            }
        }
    }

    private fun acquireWirelessDebuggingPermission() {
        if (permissionGrantAttempted) return
        if (WirelessDebugging.canToggle(this)) return
        permissionGrantAttempted = true
        lifecycleScope.launch {
            WirelessDebugging.tryAcquireTogglePermission(this@MainActivity)
        }
    }

    private fun checkNotificationPermission(): Boolean =
        NotificationManagerCompat.from(this).areNotificationsEnabled()

    private fun showConnectedState() {
        statusText.text = buildString {
            append("✅ Connected to this device's ADB.\n\n")
            append("For Termux / proot, tap \"Enable ADB on port 5555\" below, ")
            append("then in Termux run:\n\n")
            append("adb connect 127.0.0.1:5555\n\n")
            append("Use \"Copy Termux Command\" below to put it on the clipboard.\n\n")
            append("No pairing code is needed again until this app's key is revoked.")
        }
        actionButton.isEnabled = false
        actionButton.text = getString(R.string.btn_connected)
        tcpipButton.visibility = Button.VISIBLE
        copyButton.visibility = Button.VISIBLE
        wirelessButton.visibility = Button.GONE
        refreshButton.visibility = Button.VISIBLE
    }

    private fun showSetupChecklist() {
        val devModeEnabled = DeveloperOptions.isEnabled(this)
        val devModeVerified = devModeEnabled && DeveloperOptions.isReadable
        val notificationPermission = checkNotificationPermission()
        val hasPaired = SettingsManager.hasPairedBefore(this)
        val wirelessOn = WirelessDebugging.isEnabled(this)
        val canAutoEnableWireless = WirelessDebugging.canToggle(this)

        val step1 = if (devModeVerified) "✅" else if (devModeEnabled) "ℹ️" else "⬜"
        val step2 = if (notificationPermission) "✅" else "⬜"
        val step3 = if (devModeEnabled && notificationPermission) "⬜" else "⚪"

        statusText.text = buildString {
            append("Setup Progress:\n\n")
            append("$step1 Step 1: Enable Developer Options\n")
            if (!devModeVerified) {
                append("   • Settings → About Phone\n")
                append("   • Tap \"Build Number\" 7 times\n\n")
            } else {
                append("   Complete!\n\n")
            }
            append("$step2 Step 2: Enable Notifications\n")
            if (!notificationPermission) {
                append("   • Required to enter the pairing code from the\n")
                append("     notification shade, without leaving Settings\n\n")
            } else {
                append("   Complete!\n\n")
            }
            if (hasPaired) {
                append("⬜ Step 3: Reconnect\n")
                append("   • Pairing is already done. Turn Wireless debugging on")
                if (!wirelessOn) append(" (currently off)")
                append(", then tap \"Connect\".\n")
            } else {
                append("$step3 Step 3: Pair with Wireless ADB\n")
                if (devModeEnabled && notificationPermission) {
                    append("   • Tap \"Start Pairing\" below\n")
                    append("   • In Wireless debugging, tap \"Pair device with pairing code\"\n")
                    append("   • Pull down the notification shade and reply with the code —\n")
                    append("     do not switch apps, the dialog dies if you do\n")
                } else {
                    append("   Complete previous steps first\n")
                }
            }
        }

        tcpipButton.visibility = Button.GONE
        copyButton.visibility = Button.GONE
        wirelessButton.visibility = Button.GONE

        when {
            !notificationPermission -> {
                actionButton.text = "Enable Notifications"
                actionButton.isEnabled = true
                actionButton.setOnClickListener { requestNotificationPermission() }
            }
            hasPaired && !wirelessOn && canAutoEnableWireless -> {
                actionButton.text = "Enable Wireless Debugging"
                actionButton.isEnabled = true
                actionButton.setOnClickListener { enableWirelessDebugging() }
            }
            hasPaired -> {
                actionButton.text = "Connect"
                actionButton.isEnabled = true
                actionButton.setOnClickListener { connect() }
            }
            devModeEnabled && notificationPermission -> {
                actionButton.text = "Start Pairing"
                actionButton.isEnabled = true
                actionButton.setOnClickListener { showPairingDialog() }
            }
            !devModeEnabled -> {
                actionButton.text = "Open Settings"
                actionButton.isEnabled = true
                actionButton.setOnClickListener {
                    try {
                        startActivity(Intent(Settings.ACTION_SETTINGS))
                    } catch (e: Exception) {
                        Toast.makeText(this, "Please open Settings manually", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    /** Reconnect with the stored key. Falls back to pairing if adbd rejects it. */
    private fun connect() {
        actionButton.isEnabled = false
        actionButton.text = "Connecting…"
        lifecycleScope.launch {
            val result = AdbBridge.testConnection(this@MainActivity)
            result.onSuccess {
                Toast.makeText(this@MainActivity, "✅ Connected", Toast.LENGTH_LONG).show()
                checkStatus(forceCheck = true)
            }
            result.onFailure {
                Toast.makeText(
                    this@MainActivity,
                    "Could not connect. Pair again to refresh the key.",
                    Toast.LENGTH_LONG
                ).show()
                showPairingDialog()
            }
        }
    }

    private fun enableTcpip5555() {
        tcpipButton.isEnabled = false
        tcpipButton.text = "Enabling…"
        lifecycleScope.launch {
            val result = AdbBridge.enableTcpip5555(this@MainActivity)
            tcpipButton.isEnabled = true
            tcpipButton.text = getString(R.string.btn_tcpip)
            result.onSuccess {
                copyTermuxCommand(showToast = false)
                statusText.text = buildString {
                    append("✅ adbd is now listening on plain TCP port 5555.\n\n")
                    append("The Termux command is already on your clipboard — ")
                    append("switch to Termux and paste:\n\nadb connect 127.0.0.1:5555\n\n")
                    append("⚠️ Plain-TCP adbd is unauthenticated: any device on the ")
                    append("same network that can reach port 5555 gets a shell. ")
                    append("Use it on your own hotspot / offline LAN, and reboot ")
                    append("or turn Wireless debugging off to close it.")
                }
                Toast.makeText(this@MainActivity, "Port 5555 is up — Termux command copied", Toast.LENGTH_LONG).show()
            }
            result.onFailure { error ->
                Toast.makeText(this@MainActivity, "❌ ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Copies the one Termux-side step to the clipboard, so the flow is:
     * tap Enable 5555 here (which auto-copies), switch to Termux, paste, Enter.
     * The `adb devices` half confirms the connection in the same paste.
     */
    private fun copyTermuxCommand(showToast: Boolean = true) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("PairHelper Termux command", TERMUX_COMMAND))
        if (showToast) {
            Toast.makeText(this, "Copied — paste it in Termux", Toast.LENGTH_SHORT).show()
        }
    }

    private fun enableWirelessDebugging() {
        lifecycleScope.launch {
            val on = WirelessDebugging.enable(this@MainActivity)
            if (on) {
                Toast.makeText(this@MainActivity, "Wireless debugging is on", Toast.LENGTH_SHORT).show()
                checkStatus(forceCheck = true)
            } else {
                Toast.makeText(
                    this@MainActivity,
                    "PairHelper cannot toggle it yet — turn Wireless debugging on in Developer options.",
                    Toast.LENGTH_LONG
                ).show()
                openDeveloperOptions()
            }
        }
    }

    private fun stopPairingService() {
        stopService(Intent(this, PairingInputService::class.java))
    }

    private fun showPairingDialog() {
        // Foreground service: it must survive while the user is in Settings.
        ContextCompat.startForegroundService(this, Intent(this, PairingInputService::class.java))
        openDeveloperOptions()
    }

    private fun openDeveloperOptions() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (ex: Exception) {
                Toast.makeText(
                    this,
                    "Please open Settings → Developer Options → Wireless Debugging manually",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_POST_NOTIFICATIONS
            )
            return
        }
        // Granted at runtime level but switched off in settings (or pre-13):
        // the only switch lives in system Settings.
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", packageName, null))
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Please enable notifications for PairHelper in Settings", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        /** Set by [PairingInputService] when it brings the app forward after pairing. */
        const val EXTRA_FROM_PAIRING = "io.github.superslop.pairhelper.FROM_PAIRING"

        private const val REQUEST_POST_NOTIFICATIONS = 1001

        const val TERMUX_COMMAND = "adb connect 127.0.0.1:5555 && adb devices"

        // Only try the self-grant once per session.
        private var permissionGrantAttempted = false
    }
}
