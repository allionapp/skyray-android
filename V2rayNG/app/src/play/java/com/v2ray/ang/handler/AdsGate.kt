package com.v2ray.ang.handler

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
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
import java.lang.ref.WeakReference

/**
 * The Google Play build's ad, as in SkyRay 1.0: a rewarded interstitial once after a fresh
 * connect, and the connection lasts only if it is watched through. Closing it early ends the
 * connection; so does swiping the app away while it is still on screen (CoreVpnService reads
 * [AppConfig.PREF_SKYRAY_AD_SHOWN_AT]). When no ad could be loaded at all the connection is
 * kept: a network that cannot reach Google's ad servers must not cost the user the tunnel.
 *
 * Nothing of Google's runs before the tunnel does: UMP consent, the SDK's start and every ad
 * request wait for a connection that carries the app's own traffic ([TunnelSelf.ridesTunnel()]), so
 * they leave from the exit node, never from the user's real network. The first ad therefore
 * loads after the connect and is shown as soon as it arrives. UMP's consent flow still runs
 * before the SDK starts, since it must precede any ad request.
 */
object AdsGate {
    private const val UNIT_ID = "ca-app-pub-8085563084618250/1614985448"

    /** So a line that drops and reconnects does not bring an ad every time. */
    private const val MIN_INTERVAL_MILLIS = 20 * 60 * 1000L

    /** How long the connecting screen waits for the ad once it has been requested. */
    private const val LOAD_WAIT_MILLIS = 12 * 1000L

    /** The longest it waits at all, the consent form included. */
    private const val MAX_WAIT_MILLIS = 60 * 1000L

    private val main = Handler(Looper.getMainLooper())

    /** AdMob's hashed ids of the team's phones, as the SDK prints them in logcat. */
    private val TEST_DEVICE_IDS = listOf(
        "07E1BE8FAC62500944093B6D767013B5",
        "185751BEA04B489B45EEFA10336D2AD3",
        "A58AAF34EF557B4CD3BC536159FD50D3",
    )

    private var ad: RewardedInterstitialAd? = null
    private var loading = false
    private var consentStarted = false
    private var initialized = false
    private var lastShownAt = 0L

    /** A connect that is still waiting for its ad: where to show it, who to tell, and until when. */
    private var pending: Pending? = null

    private class Pending(val activity: WeakReference<Activity>, val onSkipped: () -> Unit, val onReady: () -> Unit) {
        var deadline = System.currentTimeMillis() + MAX_WAIT_MILLIS
    }

    const val SHOWS_ADS = true

    /**
     * Right after a fresh connect, while the connecting screen is up: shows the ad now if one is
     * ready and the cooldown has passed, otherwise starts Google's side (consent, SDK, request)
     * over the tunnel and shows the ad when it arrives. [onReady] runs exactly once, when the ad
     * goes on screen or when it is clear none will (cooldown, no fill, [LOAD_WAIT_MILLIS] passed):
     * the connecting screen closes then. [onSkipped] runs when the ad was shown but closed before
     * the reward.
     */
    fun showAfterConnect(activity: Activity, onSkipped: () -> Unit, onReady: () -> Unit) {
        val ready = once(onReady)
        if (!TunnelSelf.ridesTunnel() || System.currentTimeMillis() - lastShownAt < MIN_INTERVAL_MILLIS) {
            ready()
            return
        }
        if (ad != null) {
            show(activity, onSkipped, ready)
            return
        }
        pending?.let { giveUp(it) }
        val p = Pending(WeakReference(activity), onSkipped, ready)
        pending = p
        watchDeadline(p)
        if (initialized) load(activity.applicationContext) else startOverTunnel(activity)
    }

    /** The tunnel is down: nothing more goes to Google until the next connect. */
    fun onTunnelDown() {
        pending?.let { giveUp(it) }
    }

    private fun once(block: () -> Unit): () -> Unit {
        var done = false
        return { if (!done) { done = true; block() } }
    }

    private fun giveUp(p: Pending) {
        if (pending === p) pending = null
        p.onReady()
    }

    private fun watchDeadline(p: Pending) {
        val wait = maxOf(0L, p.deadline - System.currentTimeMillis())
        main.postDelayed({
            if (pending !== p) return@postDelayed
            if (System.currentTimeMillis() >= p.deadline) giveUp(p) else watchDeadline(p)
        }, wait + 50)
    }

    private fun startOverTunnel(activity: Activity) {
        if (consentStarted) return
        consentStarted = true
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
        if (!TunnelSelf.ridesTunnel()) {
            // The tunnel went down during consent: the SDK starts with the next connect.
            consentStarted = false
            pending?.let { giveUp(it) }
            return
        }
        // Debug builds get test ads, so development never makes impressions AdMob would count.
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            MobileAds.setRequestConfiguration(RequestConfiguration.Builder().setTestDeviceIds(TEST_DEVICE_IDS).build())
        }
        MobileAds.initialize(context) {
            initialized = true
            load(context)
        }
    }

    private fun load(context: Context) {
        if (loading || ad != null || !TunnelSelf.ridesTunnel()) return
        loading = true
        // From the request on, the connecting screen waits only so long for the answer.
        pending?.let { p ->
            p.deadline = minOf(p.deadline, System.currentTimeMillis() + LOAD_WAIT_MILLIS)
            watchDeadline(p)
        }
        RewardedInterstitialAd.load(
            context, UNIT_ID, AdRequest.Builder().build(),
            object : RewardedInterstitialAdLoadCallback() {
                override fun onAdLoaded(loaded: RewardedInterstitialAd) {
                    loading = false
                    ad = loaded
                    showPending()
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    loading = false
                    pending?.let { giveUp(it) }
                }
            },
        )
    }

    /** The ad a connect was waiting for has arrived: shown if that screen is still in front. */
    private fun showPending() {
        val p = pending ?: return
        pending = null
        val activity = p.activity.get()
        val resumed = (activity as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true
        if (activity == null || !TunnelSelf.ridesTunnel() || !resumed || activity.isFinishing ||
            System.currentTimeMillis() > p.deadline || System.currentTimeMillis() - lastShownAt < MIN_INTERVAL_MILLIS
        ) {
            p.onReady()
            return
        }
        show(activity, p.onSkipped, p.onReady)
    }

    private fun show(activity: Activity, onSkipped: () -> Unit, onReady: () -> Unit) {
        val ready = ad ?: return onReady()
        lastShownAt = System.currentTimeMillis()
        val context = activity.applicationContext
        var earned = false
        var clicked = false
        ready.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                MmkvManager.encodeSettings(AppConfig.PREF_SKYRAY_AD_SHOWN_AT, System.currentTimeMillis())
                onReady()
            }

            // A tap opens the advertiser; that is the ad's own call to action, not walking out.
            override fun onAdClicked() {
                clicked = true
                MmkvManager.encodeSettings(AppConfig.PREF_SKYRAY_AD_SHOWN_AT, 0L)
            }

            override fun onAdDismissedFullScreenContent() {
                MmkvManager.encodeSettings(AppConfig.PREF_SKYRAY_AD_SHOWN_AT, 0L)
                ad = null
                // The reward can land just after the dismissal; judging the ad skipped in that
                // gap would drop a connection the user had in fact watched it for. The next ad is
                // fetched only for a connection that goes on, while it still carries the request.
                Handler(Looper.getMainLooper()).postDelayed({
                    if (earned || clicked) load(context) else onSkipped()
                }, 2000)
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                MmkvManager.encodeSettings(AppConfig.PREF_SKYRAY_AD_SHOWN_AT, 0L)
                ad = null
                onReady()
                load(context)
            }
        }
        ready.show(activity) { earned = true }
    }
}
