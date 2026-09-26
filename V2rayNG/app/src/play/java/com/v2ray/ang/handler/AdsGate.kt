package com.v2ray.ang.handler

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAdLoadCallback
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.v2ray.ang.AppConfig

/**
 * The Google Play build's ad, as in SkyRay 1.0: a rewarded interstitial once after a fresh
 * connect, and the connection lasts only if it is watched through. Closing it early ends the
 * connection; so does swiping the app away while it is still on screen (CoreVpnService reads
 * [AppConfig.PREF_SKYRAY_AD_SHOWN_AT]). When no ad could be loaded at all the connection is
 * kept: a network that cannot reach Google's ad servers must not cost the user the tunnel.
 *
 * Google's UMP consent flow runs first, since it must precede any ad request.
 */
object AdsGate {
    private const val UNIT_ID = "ca-app-pub-8085563084618250/1614985448"

    /** So a line that drops and reconnects does not bring an ad every time. */
    private const val MIN_INTERVAL_MILLIS = 20 * 60 * 1000L

    /** AdMob's hashed ids of the team's phones, as the SDK prints them in logcat. */
    private val TEST_DEVICE_IDS = listOf(
        "07E1BE8FAC62500944093B6D767013B5",
        "185751BEA04B489B45EEFA10336D2AD3",
        "A58AAF34EF557B4CD3BC536159FD50D3",
    )

    private var ad: RewardedInterstitialAd? = null
    private var loading = false
    private var started = false
    private var lastShownAt = 0L

    const val SHOWS_ADS = true

    /** Once per process, from the first screen: UMP needs an Activity. */
    fun start(activity: Activity) {
        if (started) return
        started = true
        // A flag left by a process that died mid-ad must not end a later connection.
        MmkvManager.encodeSettings(AppConfig.PREF_SKYRAY_AD_SHOWN_AT, 0L)
        val appContext = activity.applicationContext
        val consent = UserMessagingPlatform.getConsentInformation(activity)
        consent.requestConsentInfoUpdate(
            activity,
            ConsentRequestParameters.Builder().build(),
            { UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { initialize(appContext) } },
            { initialize(appContext) },
        )
    }

    private fun initialize(context: Context) {
        // Debug builds get test ads, so development never makes impressions AdMob would count.
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            MobileAds.setRequestConfiguration(RequestConfiguration.Builder().setTestDeviceIds(TEST_DEVICE_IDS).build())
        }
        MobileAds.initialize(context) { load(context) }
    }

    private fun load(context: Context) {
        if (loading || ad != null) return
        loading = true
        RewardedInterstitialAd.load(
            context, UNIT_ID, AdRequest.Builder().build(),
            object : RewardedInterstitialAdLoadCallback() {
                override fun onAdLoaded(loaded: RewardedInterstitialAd) {
                    loading = false
                    ad = loaded
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    loading = false
                }
            },
        )
    }

    /**
     * Shows the ad if one is ready and the cooldown has passed. [onSkipped] runs when it was
     * shown but closed before the reward; nothing is reported when no ad could be shown.
     */
    fun showAfterConnect(activity: Activity, onSkipped: () -> Unit) {
        if (!started) return
        val now = System.currentTimeMillis()
        if (now - lastShownAt < MIN_INTERVAL_MILLIS) return
        val ready = ad ?: run { load(activity.applicationContext); return }
        lastShownAt = now
        val context = activity.applicationContext
        var earned = false
        var clicked = false
        ready.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                MmkvManager.encodeSettings(AppConfig.PREF_SKYRAY_AD_SHOWN_AT, System.currentTimeMillis())
            }

            // A tap opens the advertiser; that is the ad's own call to action, not walking out.
            override fun onAdClicked() {
                clicked = true
                MmkvManager.encodeSettings(AppConfig.PREF_SKYRAY_AD_SHOWN_AT, 0L)
            }

            override fun onAdDismissedFullScreenContent() {
                MmkvManager.encodeSettings(AppConfig.PREF_SKYRAY_AD_SHOWN_AT, 0L)
                ad = null
                load(context)
                // The reward can land just after the dismissal; judging the ad skipped in that
                // gap would drop a connection the user had in fact watched it for.
                Handler(Looper.getMainLooper()).postDelayed({ if (!earned && !clicked) onSkipped() }, 2000)
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                MmkvManager.encodeSettings(AppConfig.PREF_SKYRAY_AD_SHOWN_AT, 0L)
                ad = null
                load(context)
            }
        }
        ready.show(activity) { earned = true }
    }
}
