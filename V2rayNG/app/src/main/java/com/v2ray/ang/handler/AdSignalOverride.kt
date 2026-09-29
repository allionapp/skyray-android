package com.v2ray.ang.handler

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.LocaleList
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import java.util.Locale
import java.util.TimeZone

/**
 * Makes the language, region and time zone that Google's SDKs read from this process match the
 * tunnel exit while connected, so an ad request — which leaves from the exit node once the app
 * rides its own tunnel ([TunnelSelf]) — does not also carry fa/IR/Asia/Tehran in its payload.
 *
 * Applied only while [TunnelSelf.ridesTunnel]: a foreign locale behind the real address would be an
 * inconsistency, not a match. The exit country comes from the probe through the tunnel
 * ([SpeedtestManager.getRemoteIPInfo]); until it is known the values are a neutral en/Etc/UTC,
 * never the device's own. Only the process defaults change: the app's UI language is resolved
 * from the system configuration ([com.v2ray.ang.util.Utils.getSysLocale]) and stays as it is.
 * The defaults live in memory, so a killed process starts again with the device's values.
 *
 * The ad's u_tz is computed by the SDK's own WebView from the system time zone, which an app
 * cannot change; it is left alone, as is everything about the request that is not location.
 */
object AdSignalOverride {
    private var savedLocale: Locale? = null
    private var savedLocales: LocaleList? = null
    private var savedTimeZone: TimeZone? = null

    /** The locale in force while the override is on; null when it is off. */
    @Volatile
    private var current: Locale? = null

    /** exit country -> (language, IANA time zone); anything else -> neutral en / Etc/UTC. */
    private val table = mapOf(
        "US" to ("en" to "America/New_York"), "NL" to ("nl" to "Europe/Amsterdam"),
        "DE" to ("de" to "Europe/Berlin"), "GB" to ("en" to "Europe/London"),
        "FR" to ("fr" to "Europe/Paris"), "CA" to ("en" to "America/Toronto"),
        "SE" to ("sv" to "Europe/Stockholm"), "FI" to ("fi" to "Europe/Helsinki"),
        "NO" to ("no" to "Europe/Oslo"), "DK" to ("da" to "Europe/Copenhagen"),
        "CH" to ("de" to "Europe/Zurich"), "AT" to ("de" to "Europe/Vienna"),
        "PL" to ("pl" to "Europe/Warsaw"), "IE" to ("en" to "Europe/Dublin"),
        "ES" to ("es" to "Europe/Madrid"), "IT" to ("it" to "Europe/Rome"),
        "SG" to ("en" to "Asia/Singapore"), "JP" to ("ja" to "Asia/Tokyo"),
        "AE" to ("en" to "Asia/Dubai"), "TR" to ("tr" to "Europe/Istanbul"),
    )

    /** (locale, time zone) for an exit country; exposed for tests. */
    fun geo(country: String?): Pair<Locale, String> {
        val cc = country?.trim()?.uppercase().orEmpty().takeIf { it.length == 2 && it.all(Char::isLetter) }
            ?: return Locale.forLanguageTag("en") to "Etc/UTC"
        val (language, zone) = table[cc] ?: ("en" to "Etc/UTC")
        return Locale(language, cc) to zone
    }

    /** The country code at the start of the probe's "(DE) 1.2.3.4" line, if there is one. */
    fun countryFromProbe(line: String?): String? =
        line?.let { Regex("^\\(([A-Za-z]{2})\\)").find(it.trim())?.groupValues?.get(1)?.uppercase() }

    /** Points the process at [country]'s values; safe to call again to refine. Needs [TunnelSelf.ridesTunnel]. */
    @Synchronized
    fun apply(country: String?) {
        if (!TunnelSelf.ridesTunnel()) return
        if (current == null) {
            savedLocale = Locale.getDefault()
            savedLocales = LocaleList.getDefault()
            savedTimeZone = TimeZone.getDefault()
            watchForTunnelEnd()
        }
        val (locale, zone) = geo(country)
        current = locale
        setLocale(locale)
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        LogUtil.i(AppConfig.TAG, "AdSignalOverride: $locale / $zone")
    }

    /** Refines to the exit country once the probe knows it; nothing when the override is off. */
    fun refine(country: String?) {
        if (current != null && country != null) apply(country)
    }

    /** Something reset the process locale (a new screen's context): put the exit's back. */
    fun reassert() {
        current?.let { setLocale(it) }
    }

    /**
     * The whole language list, not only its first entry: the ad's WebView sends the list as its
     * Accept-Language, and a list that still went on with the device's own languages would carry
     * them along behind the exit's.
     */
    private fun setLocale(locale: Locale) {
        LocaleList.setDefault(LocaleList(locale))
        Locale.setDefault(locale)
    }

    /**
     * However the tunnel ends — Disconnect, the notification, the tunnel process dying — this
     * process's default network stops being the VPN; the device's values come back right then,
     * and nothing more goes to Google until the next connect.
     */
    private fun watchForTunnelEnd() {
        val cm = try {
            AngApplication.application.getSystemService(ConnectivityManager::class.java)
        } catch (_: Exception) {
            null
        } ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) end(this)
            }

            override fun onLost(network: Network) = end(this)

            private fun end(cb: ConnectivityManager.NetworkCallback) {
                if (TunnelSelf.ridesTunnel()) return
                restore()
                AdsGate.onTunnelDown()
                try { cm.unregisterNetworkCallback(cb) } catch (_: Exception) {}
            }
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "AdSignalOverride: no network watch: ${e.message}")
        }
    }

    /** The device's own values back. Nothing when the override is off. */
    @Synchronized
    fun restore() {
        if (current == null) return
        savedLocales?.let { LocaleList.setDefault(it) }
        savedLocale?.let { Locale.setDefault(it) }
        savedTimeZone?.let { TimeZone.setDefault(it) }
        savedLocales = null
        savedLocale = null
        savedTimeZone = null
        current = null
        LogUtil.i(AppConfig.TAG, "AdSignalOverride: restored ${Locale.getDefault()} / ${TimeZone.getDefault().id}")
    }
}
