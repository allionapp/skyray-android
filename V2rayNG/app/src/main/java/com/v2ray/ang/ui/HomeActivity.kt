package com.v2ray.ang.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.widget.ArrayAdapter
import androidx.core.content.ContextCompat
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.databinding.ActivityHomeBinding
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.AutoSelect
import com.v2ray.ang.handler.EthaSubscription
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.handler.AdSignalOverride
import com.v2ray.ang.handler.AdsGate
import com.v2ray.ang.handler.Updates
import com.v2ray.ang.handler.UpdateCheckerManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The one screen a customer needs: the subscription (from the link, the clipboard or a QR
 * code), one Connect button that picks the best line by itself, and the account card built
 * from the subscription headers. Everything v2rayNG exposes stays reachable under Advanced.
 */
class HomeActivity : HelperBaseActivity() {
    companion object {
        const val EXTRA_LINK = "etha_link"
        const val EXTRA_TEST = "etha_test"          // Settings asked for a "test again"
        private const val CONNECT_GUARD_MS = 25_000L
        private const val TEST_GUARD_MS = 60_000L
        private const val CONNECTING_SCREEN_MAX_MS = 90_000L
    }

    private val binding by lazy { ActivityHomeBinding.inflate(layoutInflater) }
    private val mainViewModel: MainViewModel by viewModels()
    private var sub: SubscriptionCache? = null
    private var connecting = false        // waiting for the core to report started / stopped
    private var wasRunning: Boolean? = null   // null until the first state arrives: opening Home while connected is no connect
    private var refreshingQuietly = false // a background subscription refresh is running
    private var clipboardTried: String? = null   // the link last taken from the clipboard (no second import of the same one)
    private var pendingConnect = false    // waiting for a real-delay batch to pick the line
    private var updateResult: CheckUpdateResult? = null
    private var rows: List<ServerPicker.Row> = emptyList()
    private var progressJob: Job? = null   // the connecting screen's percentage while it is up
    private var progress = 0f
    private var progressCap = 0f

    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            startCore()
        } else {
            connecting = false
            hideConnecting()
            render()
        }
    }
    private val requestActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (SettingsChangeManager.consumeRestartService() && mainViewModel.isRunning.value == true) {
            restartCore()
        }
        SettingsChangeManager.consumeSetupGroupTab()
        refreshSubscription()
        render()
        if (it.data?.getBooleanExtra(EthaSettingsActivity.EXTRA_SERVER_CHANGED, false) == true) onServerChoiceChanged()
        if (it.data?.getBooleanExtra(EXTRA_TEST, false) == true) testAgain()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = false, title = getString(R.string.app_name))

        binding.btnConnect.setOnClickListener { onConnectClick() }
        binding.btnPaste.setOnClickListener { pasteLink() }
        binding.btnScan.setOnClickListener { scanLink() }
        binding.btnRefresh.setOnClickListener { refreshServers() }
        binding.btnAddLink.setOnClickListener { addAnotherLink() }
        binding.tvSubName.setOnClickListener { showLinks() }
        binding.btnRenew.setOnClickListener { Utils.openUri(this, AppConfig.ETHA_RENEW_URL) }
        // Google Play takes payment for digital services through its own billing only, so the
        // Play build does not send anyone off to buy; Support still reaches the same people.
        binding.btnRenew.isVisible = !Updates.isPlay()
        binding.spaceRenew.isVisible = !Updates.isPlay()
        binding.btnSupport.setOnClickListener { Utils.openUri(this, AppConfig.ETHA_SUPPORT_URL) }
        binding.btnTest.setOnClickListener { testAgain() }
        binding.ddServer.setOnItemClickListener { _, _, position, _ ->
            val guid = rows.getOrNull(position)?.guid
            if (guid == null) {
                MmkvManager.encodeSettings(AppConfig.PREF_ETHA_PINNED, false)
            } else {
                MmkvManager.setSelectServer(guid)
                MmkvManager.encodeSettings(AppConfig.PREF_ETHA_PINNED, true)
            }
            onServerChoiceChanged()
        }
        binding.tvUpdate.setOnClickListener { Updates.open(this) }

        mainViewModel.isRunning.observe(this) { running ->
            connecting = false
            render()
            if (running) mainViewModel.testCurrentServerRealPing()
            if (running && wasRunning == false) {
                // The Play build's ad, over the tunnel and with the exit's locale (neutral until the
                // probe names the country); the connection lasts only if it is watched through.
                // The connecting screen stays up until the ad is on screen, or none will come.
                raiseProgress(95f)
                AdSignalOverride.ensure()   // usually on already, from the network watch
                AdsGate.showAfterConnect(this, onSkipped = {
                    CoreServiceManager.stopVService(this)
                    toastError(R.string.skyray_ad_required)
                }, onReady = { finishConnecting() })
            }
            if (!running) {
                AdsGate.onTunnelDown()
                if (!pendingConnect) hideConnecting()
            }
            wasRunning = running
        }
        mainViewModel.updateTestResultAction.observe(this) {
            binding.tvLine.text = lineText(it)
            // "(DE) 1.2.3.4", seen through the tunnel: the ad signals follow the exit's country.
            AdSignalOverride.refine(AdSignalOverride.countryFromProbe(it?.lines()?.lastOrNull()))
        }
        mainViewModel.testsFinished.observe(this) {
            AutoSelect.markTested()
            if (pendingConnect) {
                pendingConnect = false
                connectWithBest()
            } else {
                // Auto means the best line of the latest test: re-pick, and move over if connected.
                val s = sub
                if (s != null && !isPinned()) {
                    val best = AutoSelect.pickBest(s.guid)
                    if (best != null && best != MmkvManager.getSelectServer()) {
                        MmkvManager.setSelectServer(best)
                        if (mainViewModel.isRunning.value == true) {
                            connecting = true
                            CoreServiceManager.stopVService(this)
                            lifecycleScope.launch {
                                delay(700)
                                connecting = false
                                startVpnFlow()
                            }
                        }
                    }
                }
                render()
            }
        }
        // The exit's locale while the VPN carries this app, the device's otherwise.
        AdSignalOverride.watch()
        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
        SubscriptionUpdater.sync()
        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {}

        refreshSubscription()
        render()
        intent?.getStringExtra(EXTRA_LINK)?.let { importLink(it) }
        checkForUpdateDaily()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_LINK)?.let { importLink(it) }
    }

    override fun onResume() {
        super.onResume()
        refreshSubscription()
        render()
        refreshQuietlyIfStale()
    }

    /**
     * No account yet: the landing page copies the customer's link to the clipboard before the
     * download, so the app can add the account by itself — the card underneath only says "tap
     * Paste link" for the phones that hand nothing over. Android gives the clipboard to the app
     * only once its window has focus, hence here, on every focus gain while there is no
     * subscription; a phone that answers the first read with nothing (the focus not yet
     * registered, a vendor's clipboard prompt) gets a second read a moment later. One of our
     * links is imported once, whatever the outcome.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        if (!importFromClipboard()) binding.root.postDelayed({ importFromClipboard() }, 400)
    }

    /** Returns true when the clipboard had text (a link or not) — false means nothing came back. */
    private fun importFromClipboard(): Boolean {
        if (sub != null || connecting || pendingConnect) return true
        val text = try { Utils.getClipboard(this) } catch (_: Exception) { "" }
        if (text.isBlank() || text == "null") return false
        val link = EthaSubscription.extractSubLink(text) ?: return true
        if (link == clipboardTried || link == MmkvManager.decodeSettingsString(AppConfig.PREF_ETHA_DELETED_LINK)) return true
        clipboardTried = link
        LogUtil.i(AppConfig.TAG, "A link on the clipboard, importing")
        importLink(link)
        return true
    }

    // ---------------------------------------------------------------- state

    private fun refreshSubscription() {
        sub = EthaSubscription.find()
        mainViewModel.subscriptionIdChanged(sub?.guid ?: "")
    }

    private fun isPinned() = MmkvManager.decodeSettingsBool(AppConfig.PREF_ETHA_PINNED, false)

    private fun render() {
        val s = sub
        val servers = s?.let { MmkvManager.decodeServerList(it.guid) } ?: emptyList()
        val empty = s == null || servers.isEmpty()
        binding.cardEmpty.isVisible = empty
        binding.cardStatus.isVisible = !empty
        binding.cardAccount.isVisible = !empty

        val running = mainViewModel.isRunning.value == true
        binding.tvState.text = getString(
            when {
                pendingConnect -> R.string.etha_state_finding
                connecting -> R.string.etha_state_connecting
                running -> R.string.etha_state_connected
                else -> R.string.etha_state_not_connected
            }
        )
        binding.tvConnectHint.text = getString(if (running) R.string.etha_tap_to_disconnect else R.string.etha_tap_to_connect) +
            if (!running && AdsGate.SHOWS_ADS) "\n" + getString(R.string.skyray_ad_hint) else ""
        binding.btnConnect.isEnabled = !connecting && !pendingConnect
        binding.btnConnect.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (running) R.color.colorPing else R.color.md_theme_primary)
        )
        binding.progress.isVisible = connecting || pendingConnect
        binding.tvLine.text = lineText(null)
        renderServerDropdown(s?.guid)
        if (s != null) renderAccount(s.subscription)
    }

    /** The server field: Auto (with the line it picked) or a pinned line; the list carries every ping. */
    private fun renderServerDropdown(subId: String?) {
        rows = if (subId == null) emptyList() else ServerPicker.rows(
            AutoSelect.candidates(subId),
            nameOf = { guid -> MmkvManager.decodeServerConfig(guid)?.remarks ?: guid },
            auto = getString(R.string.etha_server_auto),
            untested = getString(R.string.etha_ping_untested),
            failed = getString(R.string.etha_ping_failed)
        )
        binding.ddServer.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, rows.map { it.text }))
        binding.ddServer.setText(ServerPicker.currentLabel(this, isPinned()), false)
    }

    // ---------------------------------------------------------------- the server choice

    /** A new choice while connected reconnects with it (Auto: the best line after a fresh test). */
    private fun onServerChoiceChanged() {
        render()
        if (mainViewModel.isRunning.value != true) return
        connecting = true
        render()
        CoreServiceManager.stopVService(this)
        lifecycleScope.launch {
            delay(700)
            connecting = false
            if (!isPinned()) MmkvManager.encodeSettings(AppConfig.PREF_ETHA_LAST_TEST, 0L)   // Auto: a fresh test before connecting
            onConnectClick()
        }
    }

    private fun testAgain() {
        val s = sub ?: return
        if (MmkvManager.decodeServerList(s.guid).isEmpty()) return
        toast(R.string.etha_state_finding)
        mainViewModel.testAllRealPing()
    }

    private fun lineText(latency: String?): String {
        if (mainViewModel.isRunning.value != true) return ""
        val guid = MmkvManager.getSelectServer()
        val name = guid?.let { MmkvManager.decodeServerConfig(it)?.remarks }.orEmpty()
        if (name.isEmpty()) return ""
        return getString(R.string.etha_line, ServerPicker.displayName(name)) + (latency?.let { "\n$it" } ?: "")
    }

    private fun renderAccount(item: SubscriptionItem) {
        val links = EthaSubscription.all()
        val name = sub?.let { s -> links.indexOfFirst { it.guid == s.guid }.takeIf { it >= 0 }?.let { EthaSubscription.labels(links)[it] } }
            ?: item.profileTitle ?: item.remarks
        binding.tvSubName.text = if (links.size > 1) "$name  ▾" else name
        val days = EthaSubscription.daysLeft(item.expire)
        when {
            days == null -> { binding.tvDays.text = "–"; binding.tvDaysLabel.text = getString(R.string.etha_days_label) }
            days == Long.MAX_VALUE -> { binding.tvDays.text = "∞"; binding.tvDaysLabel.text = getString(R.string.etha_no_expiry) }
            days == 0L -> { binding.tvDays.text = "0"; binding.tvDaysLabel.text = getString(R.string.etha_expired) }
            else -> { binding.tvDays.text = days.toString(); binding.tvDaysLabel.text = getString(R.string.etha_days_label) }
        }
        val used = fmtBytes(maxOf(0L, item.download) + maxOf(0L, item.upload))
        when {
            item.total < 0 -> { binding.tvData.text = "–"; binding.tvDataLabel.text = getString(R.string.etha_data_unknown) }
            item.total == 0L -> { binding.tvData.text = used; binding.tvDataLabel.text = getString(R.string.etha_data_unlimited) }
            else -> { binding.tvData.text = used; binding.tvDataLabel.text = getString(R.string.etha_data_label_of, fmtBytes(item.total)) }
        }
        binding.tvAnnounce.isVisible = !item.announce.isNullOrBlank()
        binding.tvAnnounce.text = item.announce
    }

    private fun fmtBytes(b: Long): String = when {
        b >= 1L shl 30 -> String.format(Locale.getDefault(), "%.1f GB", b / 1073741824.0)
        b >= 1L shl 20 -> String.format(Locale.getDefault(), "%.0f MB", b / 1048576.0)
        else -> String.format(Locale.getDefault(), "%.0f KB", b / 1024.0)
    }

    // ---------------------------------------------------------------- connect

    private fun onConnectClick() {
        val s = sub ?: return
        if (mainViewModel.isRunning.value == true) {
            connecting = true
            render()
            CoreServiceManager.stopVService(this)
            guardConnecting()
            return
        }
        connecting = true
        showConnecting()
        val selectedOk = EthaSubscription.selectedIsIn(s.guid)
        when {
            isPinned() && selectedOk -> startVpnFlow()
            selectedOk && AutoSelect.resultsFresh() -> {
                AutoSelect.pickBest(s.guid)?.let { MmkvManager.setSelectServer(it) }
                startVpnFlow()
            }
            else -> {
                // Test every line of the subscription through the core, then connect with the best.
                pendingConnect = true
                mainViewModel.testAllRealPing()
                lifecycleScope.launch {
                    delay(TEST_GUARD_MS)
                    if (pendingConnect) {
                        pendingConnect = false
                        connectWithBest()
                    }
                }
            }
        }
        render()
    }

    private fun connectWithBest() {
        val s = sub ?: run { connecting = false; render(); return }
        val best = AutoSelect.pickBest(s.guid)
        if (best != null) {
            MmkvManager.setSelectServer(best)
        } else {
            // Nothing answered the test: try the line we had, or the first one, rather than give up.
            val fallback = MmkvManager.getSelectServer()?.takeIf { EthaSubscription.selectedIsIn(s.guid) }
                ?: MmkvManager.decodeServerList(s.guid).firstOrNull()
            if (fallback == null) {
                connecting = false
                hideConnecting()
                render()
                toastError(R.string.etha_no_line)
                return
            }
            toast(R.string.etha_no_line)
            MmkvManager.setSelectServer(fallback)
        }
        startVpnFlow()
    }

    private fun startVpnFlow() {
        raiseProgress(55f)
        render()
        if (SettingsManager.isVpnMode()) {
            val intent = VpnService.prepare(this)
            if (intent == null) startCore() else requestVpnPermission.launch(intent)
        } else {
            startCore()
        }
    }

    private fun startCore() {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            connecting = false
            hideConnecting()
            render()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN && MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING)) {
            checkAndRequestPermission(PermissionType.ACCESS_LOCAL_NETWORK) {}
        }
        CoreServiceManager.startVService(this)
        guardConnecting()
    }

    /** The core answers with a broadcast; if none comes, the button must not stay dead. */
    private fun guardConnecting() {
        lifecycleScope.launch {
            delay(CONNECT_GUARD_MS)
            if (connecting) {
                connecting = false
                hideConnecting()
                render()
            }
        }
    }

    // ---------------------------------------------------------------- the connecting screen

    /**
     * Covers Home from the tap on Connect until the connection is ready to use: the best line
     * is picked, the tunnel comes up, and the ad that comes first loads through it. The figure
     * climbs toward the stage reached and never stops, so a slow step still shows movement.
     */
    private fun showConnecting() {
        if (binding.overlayConnecting.isVisible) return
        binding.tvConnectingHint.setText(if (AdsGate.SHOWS_ADS) R.string.etha_preparing_hint else R.string.etha_preparing_hint_plain)
        binding.overlayConnecting.isVisible = true
        progress = 0f
        progressCap = 30f
        showProgress()
        progressJob?.cancel()
        progressJob = lifecycleScope.launch {
            var elapsed = 0L
            while (true) {
                delay(100)
                elapsed += 100
                progress += (progressCap - progress) * 0.05f
                showProgress()
                if (elapsed > CONNECTING_SCREEN_MAX_MS) { hideConnecting(); break }   // never a screen that stays
            }
        }
    }

    private fun raiseProgress(cap: Float) {
        progressCap = maxOf(progressCap, cap)
    }

    private fun showProgress() {
        val value = progress.toInt().coerceIn(0, 100)
        binding.tvConnectingPercent.text = String.format(Locale.getDefault(), "%d%%", value)
        binding.progressConnecting.setProgressCompat(value, true)
    }

    /** Ready: 100%, a moment to see it, and the screen goes. */
    private fun finishConnecting() {
        if (!binding.overlayConnecting.isVisible) return
        progressJob?.cancel()
        progress = 100f
        showProgress()
        progressJob = lifecycleScope.launch {
            delay(300)
            hideConnecting()
        }
    }

    private fun hideConnecting() {
        progressJob?.cancel()
        progressJob = null
        binding.overlayConnecting.isVisible = false
    }

    private fun restartCore() {
        if (mainViewModel.isRunning.value == true) CoreServiceManager.stopVService(this)
        lifecycleScope.launch {
            delay(500)
            startCore()
        }
    }

    // ---------------------------------------------------------------- the link

    private fun pasteLink() {
        val text = try { Utils.getClipboard(this) } catch (_: Exception) { "" }
        val link = EthaSubscription.extractSubLink(text)
        if (link == null) {
            toastError(R.string.etha_clipboard_empty)   // the way back: the link in Telegram opens here
            return
        }
        clipboardTried = link
        importLink(link)
    }

    private fun scanLink() {
        launchQRCodeScanner { result ->
            val link = EthaSubscription.extractSubLink(result)
            if (link == null) toastError(R.string.etha_link_invalid) else importLink(link)
        }
    }

    // ---------------------------------------------------------------- several links

    /** A second (third…) link next to the first: from the clipboard or a QR code, as on the empty card. */
    private fun addAnotherLink() {
        AlertDialog.Builder(this)
            .setTitle(R.string.etha_add_another_link)
            .setItems(arrayOf(getString(R.string.etha_paste_link), getString(R.string.etha_scan_qr))) { _, which ->
                if (which == 0) pasteLink() else scanLink()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Every link on the phone: tap one to use it, or remove the one in use. */
    private fun showLinks() {
        val links = EthaSubscription.all()
        if (links.isEmpty()) return
        val current = links.indexOfFirst { it.guid == sub?.guid }
        AlertDialog.Builder(this)
            .setTitle(R.string.etha_links_title)
            .setSingleChoiceItems(EthaSubscription.labels(links).toTypedArray(), current) { dialog, which ->
                dialog.dismiss()
                if (which != current) useLink(links[which].guid)
            }
            .setPositiveButton(R.string.etha_add_another_link) { _, _ -> addAnotherLink() }
            .setNeutralButton(R.string.etha_link_remove) { _, _ -> confirmRemoveLink() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Home switches to this link; while connected, the connection moves to its best line. */
    private fun useLink(subId: String) {
        if (sub?.guid == subId) return
        EthaSubscription.setActive(subId)
        MmkvManager.encodeSettings(AppConfig.PREF_ETHA_PINNED, false)
        refreshSubscription()
        sub?.let { toast(getString(R.string.etha_link_using, EthaSubscription.label(it))) }
        onServerChoiceChanged()
    }

    private fun confirmRemoveLink() {
        val s = sub ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.etha_link_remove)
            .setMessage(getString(R.string.etha_link_remove_confirm, EthaSubscription.label(s)))
            .setPositiveButton(R.string.etha_delete_account_do) { _, _ -> removeLink(s.guid) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun removeLink(subId: String) {
        val wasRunning = mainViewModel.isRunning.value == true && EthaSubscription.selectedIsIn(subId)
        if (wasRunning) CoreServiceManager.stopVService(this)
        EthaSubscription.remove(subId)
        refreshSubscription()
        render()
        toastSuccess(R.string.etha_link_removed)
    }

    /** Adds the subscription (or refreshes it when it is already there), shows it and connects. */
    private fun importLink(raw: String) {
        val link = EthaSubscription.extractSubLink(raw) ?: raw
        MmkvManager.encodeSettings(AppConfig.PREF_ETHA_DELETED_LINK, "")   // asked for by hand: no longer "deleted"
        val named = if (link.contains('#')) link else "$link#${AppConfig.ETHA_SUB_NAME}"
        val before = MmkvManager.decodeSubscriptions().map { it.guid }.toSet()
        val shownBefore = sub?.guid
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            val (count, countSub) = try {
                AngConfigManager.importBatchConfig(named, "", false)
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to import the link", e)
                0 to 0
            }
            // The same link again (a renewal, a reinstall): refresh it instead of failing, and judge
            // it by that fetch. Its old servers alone are no proof: a link the server no longer
            // knows would otherwise read "added" and connect to lines that are gone.
            var fetched = true
            if (count + countSub == 0) {
                fetched = try {
                    EthaSubscription.findByLink(link)?.let { AngConfigManager.updateConfigViaSub(it).successCount > 0 } ?: false
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Failed to refresh", e)
                    false
                }
            }
            // The link just added (or found again) is the one Home shows from now on.
            val added = MmkvManager.decodeSubscriptions().firstOrNull { it.guid !in before }
            val target = added ?: EthaSubscription.findByLink(link)
            val targetServers = target?.let { MmkvManager.decodeServerList(it.guid) }.orEmpty()
            val works = fetched && targetServers.isNotEmpty()
            if (target != null && works) {
                EthaSubscription.setActive(target.guid)
            } else if (added != null) {
                // A new link that brought no servers is not kept: it must not push aside one that
                // works, and adding it again later starts clean.
                EthaSubscription.remove(added.guid)
            }
            withContext(Dispatchers.Main) {
                hideLoading()
                refreshSubscription()
                render()
                SubscriptionUpdater.sync(forceReschedule = true)   // the background refresh, timed from this fetch
                if (!works) {
                    toastError(R.string.etha_link_not_working)
                } else {
                    toastSuccess(R.string.etha_link_added)
                    if (shownBefore != null && sub?.guid != shownBefore) {
                        // Another link now: a fresh pick of its best line, and a move over if connected.
                        MmkvManager.encodeSettings(AppConfig.PREF_ETHA_PINNED, false)
                        if (mainViewModel.isRunning.value == true) onServerChoiceChanged() else render()
                    }
                    if (mainViewModel.isRunning.value != true && !connecting && !pendingConnect) onConnectClick()
                }
            }
        }
    }

    /**
     * The subscription refreshes by itself: a WorkManager job every ETHA_SUB_UPDATE_MINUTES (the
     * API's Profile-Update-Interval, applied on every fetch) and — because Android may hold that
     * job back for hours on a battery-saving phone — a quiet refresh whenever this screen comes
     * up and the last fetch is older than ETHA_SUB_STALE_MS. No spinner, no toast: a failure
     * just leaves the current servers in place.
     */
    private fun refreshQuietlyIfStale() {
        val s = sub ?: return
        if (refreshingQuietly || !EthaSubscription.isStale(s.subscription.lastUpdated)) return
        refreshingQuietly = true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                AngConfigManager.updateConfigViaSubAll()
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Quiet refresh failed", e)
            }
            withContext(Dispatchers.Main) {
                refreshingQuietly = false
                refreshSubscription()
                render()
                SubscriptionUpdater.sync(forceReschedule = true)
            }
        }
    }

    private fun refreshServers() {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            val result = mainViewModel.updateConfigViaSubAll()
            withContext(Dispatchers.Main) {
                hideLoading()
                refreshSubscription()
                render()
                SubscriptionUpdater.sync(forceReschedule = true)   // the background refresh, timed from this fetch
                if (result.successCount > 0) {
                    toastSuccess(getString(R.string.title_update_config_count, result.configCount))
                } else {
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    // ---------------------------------------------------------------- updates

    private fun checkForUpdateDaily() {
        if (Updates.isPlay()) return          // Google Play keeps the app current
        val last = MmkvManager.decodeSettingsLong(AppConfig.PREF_ETHA_LAST_UPDATE_CHECK, 0L)
        if (System.currentTimeMillis() - last < AppConfig.ETHA_UPDATE_CHECK_MS) return
        lifecycleScope.launch {
            try {
                val result = UpdateCheckerManager.checkForUpdate(false)
                MmkvManager.encodeSettings(AppConfig.PREF_ETHA_LAST_UPDATE_CHECK, System.currentTimeMillis())
                if (result.hasUpdate) {
                    updateResult = result
                    binding.tvUpdate.isVisible = true
                    binding.tvUpdate.text = if (result.mandatory) {
                        getString(R.string.etha_update_required)
                    } else {
                        getString(R.string.etha_update_available, result.latestVersion)
                    }
                }
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "Update check skipped: ${e.message}")
            }
        }
    }

    // ---------------------------------------------------------------- menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_home, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.etha_settings -> {
            requestActivityLauncher.launch(Intent(this, EthaSettingsActivity::class.java))
            true
        }
        else -> super.onOptionsItemSelected(item)
    }
}
