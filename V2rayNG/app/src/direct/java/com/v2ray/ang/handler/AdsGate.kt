package com.v2ray.ang.handler

import android.app.Activity

/** The direct build carries no ads; the Play build's AdsGate lives in src/play. */
object AdsGate {
    const val SHOWS_ADS = false

    fun start(activity: Activity) = Unit

    fun showAfterConnect(activity: Activity, onSkipped: () -> Unit) = Unit
}
