package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import java.net.URI

/**
 * The EthaVPN subscription: what the service's /sub/<token> link looks like, what its response
 * headers say (Subscription-Userinfo, Profile-Title, Profile-Update-Interval, Announce,
 * Support-Url, Profile-Web-Page-Url) and where that lands on the SubscriptionItem.
 *
 * Server semantics, kept exactly: total = 0 means unlimited data, expire = 0 means no expiry;
 * -1 on the item means the header was absent. A malformed header never discards a profile.
 * The pure functions here have no Android dependency and are covered by JVM unit tests.
 */
object EthaSubscription {

    data class UserInfo(val upload: Long, val download: Long, val total: Long, val expire: Long)

    /** `upload=0; download=123; total=0; expire=0` → UserInfo (absent fields -1); nothing usable → null. */
    fun parseUserInfo(header: String?): UserInfo? {
        if (header.isNullOrBlank()) return null
        var up = -1L; var down = -1L; var total = -1L; var expire = -1L; var any = false
        header.split(';', ',').forEach { part ->
            val kv = part.split('=', limit = 2)
            if (kv.size != 2) return@forEach
            val v = kv[1].trim().toDoubleOrNull()?.toLong() ?: return@forEach
            when (kv[0].trim().lowercase()) {
                "upload" -> { up = v; any = true }
                "download" -> { down = v; any = true }
                "total" -> { total = v; any = true }
                "expire" -> { expire = v; any = true }
            }
        }
        return if (any) UserInfo(up, down, total, expire) else null
    }

    /** `base64:…` (how the API sends a title or a notice) or plain text; blank or undecodable → null. */
    fun decodeHeaderText(value: String?): String? {
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) return null
        if (!v.startsWith("base64:", ignoreCase = true)) return v
        val decoded = base64Decode(v.substring(7).trim()) ?: return null
        return String(decoded, Charsets.UTF_8).trim().ifEmpty { null }
    }

    /** Profile-Update-Interval is hours; the app schedules minutes, never under its minimum. */
    fun updateIntervalMinutes(header: String?): Long? {
        val hours = header?.trim()?.toLongOrNull() ?: return null
        if (hours <= 0) return null
        return maxOf(AppConfig.SUBSCRIPTION_MIN_INTERVAL_MINUTES, hours * 60)
    }

    /** Days left for the account card: null = unknown, Long.MAX_VALUE = no expiry, else whole days (rounded up, never negative). */
    fun daysLeft(expire: Long, nowSec: Long = System.currentTimeMillis() / 1000): Long? = when {
        expire < 0 -> null
        expire == 0L -> Long.MAX_VALUE
        else -> maxOf(0L, (expire - nowSec + 86399) / 86400)
    }

    /** Whether a subscription fetched at `lastUpdated` (ms; ≤ 0 = never) is due for a quiet refresh. */
    fun isStale(lastUpdated: Long, nowMs: Long = System.currentTimeMillis(), maxAgeMs: Long = AppConfig.ETHA_SUB_STALE_MS): Boolean =
        lastUpdated <= 0L || nowMs - lastUpdated >= maxAgeMs

    /** An EthaVPN link: https, one of our hosts, exactly /sub/<token> (a fragment is fine, it names the profile). */
    fun isSubLink(text: String?): Boolean {
        val uri = try { URI(text?.trim() ?: return false) } catch (_: Exception) { return false }
        val host = uri.host ?: return false
        if (!"https".equals(uri.scheme, ignoreCase = true)) return false
        if (AppConfig.ETHA_SUB_HOSTS.none { it.equals(host, ignoreCase = true) }) return false
        val path = uri.path ?: return false
        if (!path.startsWith(AppConfig.ETHA_SUB_PATH)) return false
        val token = path.substring(AppConfig.ETHA_SUB_PATH.length)
        return token.length >= 8 && token.none { it == '/' }
    }

    /** Where a link's host sits in ETHA_SUB_HOSTS: 0 = the current address; -1 = not one of ours. */
    fun hostRank(url: String?): Int {
        val host = try { URI(url?.trim() ?: return -1).host } catch (_: Exception) { return -1 } ?: return -1
        return AppConfig.ETHA_SUB_HOSTS.indexOfFirst { it.equals(host, ignoreCase = true) }
    }

    /** The account token of one of our links (what stays the same when the address moves), else null. */
    fun tokenOf(url: String?): String? {
        if (!isSubLink(url)) return null
        return URI(url!!.trim()).path.substring(AppConfig.ETHA_SUB_PATH.length)
    }

    /**
     * The URL a subscription moves to after a fetch: the service names its current link in
     * Profile-Web-Page-Url on every answer. Taken only when both are our links (listed hosts), the
     * token is the same and the address differs; the name after '#' stays. Else null: keep the URL.
     */
    fun adoptedUrl(current: String?, webPageUrl: String?): String? {
        val token = tokenOf(current) ?: return null
        val cur = current!!.trim()
        val next = webPageUrl?.trim()?.substringBefore('#') ?: return null
        if (tokenOf(next) != token) return null
        if (URI(cur).host.equals(URI(next).host, ignoreCase = true)) return null
        val name = cur.substringAfter('#', "")
        return if (name.isEmpty()) next else "$next#$name"
    }

    /** Of two links to the same account, the one to keep: the address earlier in ETHA_SUB_HOSTS (the newer). */
    fun better(a: String, b: String): String {
        val ra = hostRank(a); val rb = hostRank(b)
        return if (rb >= 0 && (ra < 0 || rb < ra)) b else a
    }

    /** The link inside pasted text (a Telegram message adds words and punctuation around it). */
    fun extractSubLink(text: String?): String? =
        text?.split(Regex("\\s+"))
            ?.map { it.trim().trimEnd('.', ',', ')', ']', '؛', '،') }
            ?.firstOrNull { isSubLink(it) }

    /** Copies what the headers say onto the item. Returns true when at least one known header was present. */
    fun applyHeaders(sub: SubscriptionItem, headers: Map<String, String>): Boolean {
        var seen = false
        parseUserInfo(headers["subscription-userinfo"])?.let {
            sub.upload = it.upload; sub.download = it.download; sub.total = it.total; sub.expire = it.expire; seen = true
        }
        if (headers.containsKey("profile-title")) { sub.profileTitle = decodeHeaderText(headers["profile-title"]); seen = true }
        if (headers.containsKey("announce")) { sub.announce = decodeHeaderText(headers["announce"]); seen = true }
        if (headers.containsKey("support-url")) { sub.supportUrl = headers["support-url"]?.trim()?.ifEmpty { null }; seen = true }
        if (headers.containsKey("profile-web-page-url")) { sub.webPageUrl = headers["profile-web-page-url"]?.trim()?.ifEmpty { null }; seen = true }
        updateIntervalMinutes(headers["profile-update-interval"])?.let { minutes ->
            if (sub.updateInterval != minutes) sub.updateInterval = minutes
            sub.autoUpdate = true
            seen = true
        }
        if (seen) sub.infoUpdated = System.currentTimeMillis()
        return seen
    }

    // ---------------------------------------------------------------- storage-backed (Android)

    /**
     * The EthaVPN subscription if there is one (the newest address first), else the first enabled
     * subscription (the app also works as a plain client).
     */
    fun find(): SubscriptionCache? {
        val subs = MmkvManager.decodeSubscriptions()
        return subs.filter { isSubLink(it.subscription.url) && it.subscription.enabled }
            .minByOrNull { hostRank(it.subscription.url) }
            ?: subs.firstOrNull { it.subscription.enabled }
    }

    /**
     * One subscription per account. A phone that imported the new link beside the old one (before this
     * version knew the new address), or whose two copies both moved, holds the same token twice: they
     * become the one with the selected server (else the first), on the newest address; the others go
     * with their servers. Never call it inside a loop over the subscriptions. True when something changed.
     */
    fun mergeDuplicates(): Boolean {
        var changed = false
        MmkvManager.decodeSubscriptions()
            .filter { tokenOf(it.subscription.url) != null }
            .groupBy { tokenOf(it.subscription.url) }
            .values.filter { it.size > 1 }
            .forEach { same ->
                val keep = same.firstOrNull { selectedIsIn(it.guid) } ?: same.first()
                val url = same.map { it.subscription.url }.reduce { a, b -> better(a, b) }
                same.filter { it.guid != keep.guid }.forEach {
                    SubscriptionUpdater.cancelOne(subId = it.guid)
                    MmkvManager.removeSubscription(it.guid)
                }
                if (keep.subscription.url != url) {
                    keep.subscription.url = url
                    MmkvManager.encodeSubscription(keep.guid, keep.subscription)
                }
                changed = true
            }
        return changed
    }

    /**
     * "Delete account": every subscription with its servers and test results, the chosen line
     * and the account preferences go; the deleted link is remembered so the clipboard import
     * does not put it straight back (a tap on the link in Telegram, Paste or a QR code still
     * does). Language and expert mode are the phone's, not the account's, and stay.
     */
    fun deleteAll() {
        val subs = MmkvManager.decodeSubscriptions()
        val link = subs.map { it.subscription.url }.firstOrNull { isSubLink(it) } ?: ""
        subs.forEach {
            SubscriptionUpdater.cancelOne(subId = it.guid)
            MmkvManager.removeSubscription(it.guid)
        }
        MmkvManager.removeAllServer()
        MmkvManager.setSelectServer("")
        MmkvManager.encodeSettings(AppConfig.PREF_ETHA_PINNED, false)
        MmkvManager.encodeSettings(AppConfig.PREF_ETHA_LAST_TEST, 0L)
        MmkvManager.encodeSettings(AppConfig.PREF_ETHA_DELETED_LINK, link)
    }

    /** Whether the selected server belongs to this subscription. */
    fun selectedIsIn(subId: String): Boolean {
        val guid = MmkvManager.getSelectServer() ?: return false
        return MmkvManager.decodeServerList(subId).contains(guid)
    }

    // ---------------------------------------------------------------- a tiny base64 decoder
    // (java.util.Base64 needs API 26 and android.util.Base64 is not there in JVM tests)
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun base64Decode(input: String): ByteArray? {
        val clean = input.filter { !it.isWhitespace() }.replace('-', '+').replace('_', '/').trimEnd('=')
        if (clean.isEmpty()) return null
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (ch in clean) {
            val v = ALPHABET.indexOf(ch)
            if (v < 0) return null
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
