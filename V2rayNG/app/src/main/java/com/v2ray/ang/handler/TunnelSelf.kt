package com.v2ray.ang.handler

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import libv2ray.Libv2ray
import libv2ray.SocketProtector

/**
 * Whether SkyRay's own traffic rides its own tunnel.
 *
 * v2rayNG leaves its own app out of the VPN, so the core's sockets never loop into the tunnel
 * they serve; the price is that everything else the app sends — the ad SDK's requests among
 * it — leaves on the real network, from the user's real address. The Play build instead keeps
 * the app inside the tunnel, like any other app on the phone, and exempts only the core's own
 * sockets: with VpnService.protect() while the service runs, else by binding them to the
 * underlying network, which the VPN allows for that (allowBypass).
 */
object TunnelSelf {
    /** The build that shows ads keeps the app inside its tunnel; the direct build is unchanged. */
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
     * Keeps every socket the core dials in this process out of the tunnel: through the running
     * VPN service's protect(), or — with no service here, e.g. a delay test right after it
     * stopped — by binding the socket to the underlying network. With no VPN up at all the
     * socket is left alone, so a test when disconnected behaves as it always did.
     */
    @Synchronized
    fun installProtector(context: Context) {
        if (!wanted || installed) return
        installed = true
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        Libv2ray.registerSocketProtector(object : SocketProtector {
            override fun protect(fd: Long): Boolean {
                vpnService?.let { if (it.protect(fd.toInt())) return true }
                return cm == null || bindPastVpn(cm, fd.toInt())
            }
        })
    }

    private fun bindPastVpn(cm: ConnectivityManager, fd: Int): Boolean {
        val networks = cm.allNetworks.mapNotNull { n -> cm.getNetworkCapabilities(n)?.let { n to it } }
        if (networks.none { it.second.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }) return true
        val underlying = networks.filter { (_, caps) ->
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.sortedByDescending { it.second.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
            .firstOrNull()?.first ?: return false
        return try {
            ParcelFileDescriptor.fromFd(fd).use { underlying.bindSocket(it.fileDescriptor) }
            true
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "TunnelSelf: could not bind a socket past the VPN: ${e.message}")
            false
        }
    }
}
