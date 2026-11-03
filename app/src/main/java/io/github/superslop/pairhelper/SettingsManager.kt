package io.github.superslop.pairhelper

import android.content.Context

/**
 * Minimal app state: whether pairing has ever succeeded, and the address
 * pairing should target. PairHelper has no settings screen.
 */
object SettingsManager {
    private const val PREFS_NAME = "pairhelper_settings"
    private const val KEY_HAS_PAIRED = "has_paired"

    private fun getPrefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasPairedBefore(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_HAS_PAIRED, false)

    fun setHasPairedBefore(context: Context) {
        getPrefs(context).edit().putBoolean(KEY_HAS_PAIRED, true).apply()
    }

    /** The device's own LAN address, which the mDNS pairing service resolves to. */
    fun getTargetIpAddress(context: Context): String? =
        NetworkUtils.getLocalIpAddress(context)
}
