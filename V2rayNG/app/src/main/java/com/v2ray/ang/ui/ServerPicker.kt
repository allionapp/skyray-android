package com.v2ray.ang.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.v2ray.ang.R
import com.v2ray.ang.handler.AutoSelect
import com.v2ray.ang.handler.MmkvManager

/**
 * The server choice: a sheet from the bottom (Home's server panel and the Settings row). `Auto (fastest)` on top
 * as the highlighted card with the server it is using now, then the five fastest servers with a coloured ping dot
 * (green under 500 ms, amber under 800, red failed or slower), `Show all N` for the rest, `Ping all` in the header.
 * Picking a server pins it; picking Auto hands the choice back to [AutoSelect] (and, connected, reconnects).
 */
object ServerPicker {

    const val SHOWN_FIRST = 5
    private val ETHA_NAME = Regex("^EthaVPN-([A-Za-z0-9]+)-[^-]+-(.+?)-(\\d+)$")

    /** One row of the list; guid null = Auto. */
    data class Row(val guid: String?, val text: String)

    /**
     * The server names the subscription carries are `EthaVPN-XHTTP-fra-CleanIP1-443`; a customer
     * needs `CleanIP1 · XHTTP/443`. Anything else is shown as it is.
     */
    fun displayName(remarks: String): String {
        val m = ETHA_NAME.find(remarks.trim()) ?: return remarks
        val (proto, kind, port) = m.destructured
        return "$kind · $proto/$port"
    }

    /** Pure: Auto first, then the lines fastest first (untested, then failed, at the end); the ping leads each row. */
    fun rows(candidates: List<AutoSelect.Candidate>, nameOf: (String) -> String, auto: String, untested: String, failed: String): List<Row> {
        val sorted = sortedCandidates(candidates)
        return listOf(Row(null, auto)) + sorted.map { c ->
            val ping = when {
                c.delayMs > 0 -> "${c.delayMs} ms"
                c.delayMs < 0 -> failed
                else -> untested
            }
            Row(c.guid, "$ping  ·  ${displayName(nameOf(c.guid))}")
        }
    }

    fun sortedCandidates(candidates: List<AutoSelect.Candidate>): List<AutoSelect.Candidate> =
        candidates.sortedWith(compareBy({ if (it.delayMs > 0) 0 else if (it.delayMs == 0L) 1 else 2 }, { it.delayMs }, { it.order }))

    /** What the server panel shows: Auto with the line it picked (once one is selected), or the pinned line. */
    fun currentLabel(context: Context, pinned: Boolean): String {
        val guid = MmkvManager.getSelectServer()
        val profile = guid?.let { MmkvManager.decodeServerConfig(it) }
        val delay = guid?.let { MmkvManager.decodeServerAffiliationInfo(it)?.testDelayMillis } ?: 0L
        val line = profile?.let { if (delay > 0) "${displayName(it.remarks)} (${delay} ms)" else displayName(it.remarks) }
        return when {
            !pinned && line == null -> context.getString(R.string.etha_server_auto)
            !pinned -> context.getString(R.string.etha_auto_picked, line)
            line == null -> context.getString(R.string.etha_server_auto)
            else -> line
        }
    }

    /** The current line's name without its ping (the panel's value), or null when none is selected yet. */
    fun currentName(): String? =
        MmkvManager.getSelectServer()?.let { MmkvManager.decodeServerConfig(it)?.remarks }?.let { displayName(it) }

    /** The current line's last ping in ms, 0 when untested or failed. */
    fun currentDelay(): Long =
        MmkvManager.getSelectServer()?.let { MmkvManager.decodeServerAffiliationInfo(it)?.testDelayMillis }?.takeIf { it > 0 } ?: 0L

    /** The dot's colour for a ping. */
    fun dotColor(context: Context, delayMs: Long): Int = ContextCompat.getColor(
        context,
        when {
            delayMs <= 0 -> R.color.etha_red
            delayMs < 500 -> R.color.etha_green_light
            delayMs < 800 -> R.color.etha_amber
            else -> R.color.etha_red
        }
    )

    fun show(context: Context, subId: String, pinned: Boolean, onPick: (String?) -> Unit, onTest: () -> Unit): ServerSheet =
        ServerSheet(context, subId, pinned, onPick, onTest).also { it.show() }
}

/** The sheet itself; [render] again when pings land while it is open. */
class ServerSheet(
    context: Context,
    private val subId: String,
    private var pinned: Boolean,
    private val onPick: (String?) -> Unit,
    private val onTest: () -> Unit
) : BottomSheetDialog(context) {

    private val root: View = LayoutInflater.from(context).inflate(R.layout.sheet_servers, null)
    private val rowsView: LinearLayout = root.findViewById(R.id.rows)
    private val more: MaterialButton = root.findViewById(R.id.btn_more)
    private var expanded = false

    init {
        setContentView(root)
        behavior.skipCollapsed = true
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        setOnShowListener { behavior.state = BottomSheetBehavior.STATE_EXPANDED }   // never half-open: the rows must show
        root.findViewById<View>(R.id.row_auto).setOnClickListener { dismiss(); onPick(null) }
        root.findViewById<View>(R.id.btn_sheet_test).setOnClickListener { onTest() }
        more.setOnClickListener { expanded = !expanded; render() }
        render()
    }

    fun render() {
        val ctx = context
        val candidates = ServerPicker.sortedCandidates(AutoSelect.candidates(subId))
        com.v2ray.ang.util.LogUtil.i(com.v2ray.ang.AppConfig.TAG, "ServerSheet: ${candidates.size} servers, expanded=$expanded")
        val selected = MmkvManager.getSelectServer()
        pinned = MmkvManager.decodeSettingsBool(com.v2ray.ang.AppConfig.PREF_ETHA_PINNED, false)

        val now = ServerPicker.currentName()
        val nowMs = ServerPicker.currentDelay()
        val nowText = when {
            now == null -> ctx.getString(R.string.etha_auto_hint)
            nowMs > 0 -> ctx.getString(R.string.etha_auto_now, "$now · $nowMs ms")
            else -> ctx.getString(R.string.etha_auto_now, now)
        }
        root.findViewById<TextView>(R.id.tv_auto_now).text = nowText
        root.findViewById<View>(R.id.iv_auto_check).isVisible = !pinned
        root.findViewById<TextView>(R.id.tv_count).text = ctx.getString(R.string.etha_servers_count, candidates.size)

        val shown = if (expanded) candidates else candidates.take(ServerPicker.SHOWN_FIRST)
        rowsView.removeAllViews()
        val inflater = LayoutInflater.from(ctx)
        for (c in shown) {
            val row = inflater.inflate(R.layout.row_server, rowsView, false)
            val name = MmkvManager.decodeServerConfig(c.guid)?.remarks ?: c.guid
            row.findViewById<TextView>(R.id.tv_name).text = ServerPicker.displayName(name)
            row.findViewById<TextView>(R.id.tv_ms).text = when {
                c.delayMs > 0 -> "${c.delayMs} ms"
                c.delayMs < 0 -> ctx.getString(R.string.etha_ping_failed)
                else -> ctx.getString(R.string.etha_ping_untested)
            }
            row.findViewById<View>(R.id.dot).background.mutate().setTint(
                if (c.delayMs == 0L) ContextCompat.getColor(ctx, R.color.etha_muted) else ServerPicker.dotColor(ctx, c.delayMs)
            )
            row.findViewById<ImageView>(R.id.iv_check).isVisible = pinned && c.guid == selected
            row.setOnClickListener { dismiss(); onPick(c.guid) }
            rowsView.addView(row)
        }
        more.isVisible = candidates.size > ServerPicker.SHOWN_FIRST
        more.text = if (expanded) ctx.getString(R.string.etha_show_fewer) else ctx.getString(R.string.etha_show_all, candidates.size)
    }
}
