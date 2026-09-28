package com.v2ray.ang.handler

import android.app.Activity

/** The direct build carries no ads; the Play build's AdsGate lives in src/play. */
object AdsGate {
    const val SHOWS_ADS = false

    fun showAfterConnect(activity: Activity, onSkipped: () -> Unit) = Unit

    fun onTunnelDown() = Unit
}
