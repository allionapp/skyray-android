package com.v2ray.ang.handler

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import com.v2ray.ang.AngApplication
import libv2ray.Libv2ray
import libv2ray.SocketProtector

/**
 * Whether SkyRay's own traffic rides its own tunnel.
 *
 * v2rayNG leaves its own app out of the VPN, so the core's sockets never loop into the tunnel
 * they serve; the price is that everything else the app sends — the ad SDK's requests among
 * it — leaves on the real network, from the user's real address. SkyRay's Google Play build instead
 * keeps the app inside the tunnel, like any other app on the phone, and exempts only the core's own
 * sockets, with VpnService.protect(). The VPN is not bypassable: no app can go around it. The
 * direct build, which shows no ads, stays as v2rayNG has it.
 */
object TunnelSelf {
    /** A build that shows ads keeps the app inside its tunnel: the Google Play build. */
    val wanted: Boolean get() = AdsGate.SHOWS_ADS

    /**
     * Whether this process's traffic rides a VPN right now: its default network is the VPN. That
     * is the ground truth for the app's own requests, wherever the VPN service runs (v2rayNG runs
     * it in a separate process) and whatever happened to it.
     */
    fun ridesTunnel(): Boolean {
        if (!wanted) return false
        return try {
            val cm = AngApplication.application.getSystemService(ConnectivityManager::class.java) ?: return false
            cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        } catch (_: Exception) {
            false
        }
    }

    /** The VPN service while it runs (in this process); its protect() is the direct way out. */
    @Volatile
    var vpnService: VpnService? = null

    private var installed = false

    /**
     * Keeps every socket the core dials out of the tunnel, through the running VPN service's
     * protect(). The delay tests run in the same process as the VPN service, so they are covered
     * too. With no VPN service here the socket is left alone: then there is no tunnel of ours for
     * it to loop into.
     */
    @Synchronized
    fun installProtector() {
        if (!wanted || installed) return
        installed = true
        Libv2ray.registerSocketProtector(object : SocketProtector {
            override fun protect(fd: Long): Boolean = vpnService?.protect(fd.toInt()) ?: true
        })
    }
}
