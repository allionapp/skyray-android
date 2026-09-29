package com.v2ray.ang.handler

import android.app.Activity

/** The direct build carries no ads; the Play build's AdsGate lives in src/play. */
object AdsGate {
    const val SHOWS_ADS = false

    fun showAfterConnect(activity: Activity, onSkipped: () -> Unit, onReady: () -> Unit) = onReady()

    fun onTunnelDown() = Unit

    fun privacyChoicesRequired(context: android.content.Context) = false

    fun showPrivacyChoices(activity: Activity, done: (Boolean) -> Unit) = done(false)
}
