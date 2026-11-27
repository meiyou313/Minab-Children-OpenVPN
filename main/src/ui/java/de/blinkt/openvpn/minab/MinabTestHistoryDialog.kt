/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * MinabTestHistoryDialog: browse saved test runs by network & time, rename
 * network labels ("Home Wi-Fi", "University", etc.), inspect healthy servers,
 * connect directly, or re-apply historical test results onto the main list.
 */

package de.blinkt.openvpn.minab

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import de.blinkt.openvpn.R
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ProfileManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MinabTestHistoryDialog {

    fun show(context: Context, onConnectProfile: (VpnProfile) -> Unit) {
        val root = LayoutInflater.from(context).inflate(R.layout.minab_test_history_dialog, null)
        val filterRow = root.findViewById<LinearLayout>(R.id.minab_history_filters)
        val listContainer = root.findViewById<LinearLayout>(R.id.minab_history_list)
        val emptyView = root.findViewById<TextView>(R.id.minab_history_empty)
        val clearAllBtn = root.findViewById<TextView>(R.id.minab_history_clear_all)
        val closeBtn = root.findViewById<ImageButton>(R.id.minab_history_close)

        val dialog = AlertDialog.Builder(context)
            .setView(root)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        closeBtn?.setOnClickListener { dialog.dismiss() }

        var selectedFilter: String? = null
        val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        fun render() {
            val allRuns = MinabTestHistory.getRuns(context)
            filterRow.removeAllViews()
            listContainer.removeAllViews()

            val distinctNets = allRuns.map { it.networkLabel }.distinct()
            val filterOptions = listOf<String?>(null) + distinctNets
            val dp = context.resources.displayMetrics.density
            for (opt in filterOptions) {
                val active = opt == selectedFilter
                val chip = TextView(context).apply {
                    text = opt ?: context.getString(R.string.minab_all_tab)
                    textSize = 11f
                    setBackgroundResource(
                        if (active) R.drawable.minab_chip_bg_selected else R.drawable.minab_chip_bg
                    )
                    setTextColor(
                        ContextCompat.getColor(
                            context,
                            if (active) R.color.minab_teal_dark else R.color.minab_chip_text
                        )
                    )
                    setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
                    setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = (6 * dp).toInt() }
                    setOnClickListener {
                        selectedFilter = opt
                        render()
                    }
                }
                filterRow.addView(chip)
            }

            val shown = if (selectedFilter == null) allRuns
            else allRuns.filter { it.networkLabel == selectedFilter }

            emptyView.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
            clearAllBtn.visibility = if (allRuns.isEmpty()) View.GONE else View.VISIBLE

            for (run in shown) {
                val card = LayoutInflater.from(context)
                    .inflate(R.layout.minab_test_history_item, listContainer, false)
                val typeView = card.findViewById<TextView>(R.id.minab_history_item_type)
                val netView = card.findViewById<TextView>(R.id.minab_history_item_network)
                val timeView = card.findViewById<TextView>(R.id.minab_history_item_time)
                val delBtn = card.findViewById<ImageButton>(R.id.minab_history_item_delete)
                val sumView = card.findViewById<TextView>(R.id.minab_history_item_summary)
                val serversView = card.findViewById<TextView>(R.id.minab_history_item_servers)
                val applyBtn = card.findViewById<Button>(R.id.minab_history_item_apply)
                val viewBtn = card.findViewById<Button>(R.id.minab_history_item_view)
                val connectBtn = card.findViewById<Button>(R.id.minab_history_item_connect)

                typeView.text = if (run.isRealTest) {
                    context.getString(R.string.minab_real_test)
                } else {
                    context.getString(R.string.minab_test_ping)
                }
                typeView.setBackgroundResource(
                    if (run.isRealTest) R.drawable.minab_ping_badge_fast
                    else R.drawable.minab_btn_primary
                )
                netView.text = "✎ " + run.networkLabel
                netView.setOnClickListener {
                    showRenameLabelDialog(context, run) { render() }
                }
                timeView.text = timeFormat.format(Date(run.timestampMs))

                val targetSuffix = if (run.isRealTest && run.targetHost.isNotEmpty()) {
                    " · ${run.targetHost}"
                } else ""
                sumView.text = context.getString(
                    R.string.minab_history_summary_fmt,
                    run.workingCount,
                    run.testedCount
                ) + targetSuffix

                val topWorking = run.results.filter { it.passed }.take(3)
                serversView.text = if (topWorking.isEmpty()) {
                    context.getString(R.string.minab_history_none_working)
                } else {
                    topWorking.joinToString("\n") { "• ${it.name} (${it.ms} ms)" }
                }

                delBtn.setOnClickListener {
                    MinabTestHistory.deleteRun(context, run.id)
                    render()
                }
                applyBtn.setOnClickListener {
                    val count = MinabTestHistory.applyRunToProfiles(context, run)
                    Toast.makeText(
                        context,
                        context.getString(R.string.minab_history_applied, count),
                        Toast.LENGTH_SHORT
                    ).show()
                    dialog.dismiss()
                }
                viewBtn.setOnClickListener {
                    showServersListDialog(context, run) { profile ->
                        dialog.dismiss()
                        onConnectProfile(profile)
                    }
                }
                val bestItem = topWorking.firstOrNull()
                connectBtn.isEnabled = bestItem != null
                connectBtn.alpha = if (bestItem != null) 1f else 0.45f
                connectBtn.setOnClickListener {
                    val targetUuid = bestItem?.uuid ?: return@setOnClickListener
                    val profile = try {
                        ProfileManager.getIfPresent(context, targetUuid)
                    } catch (_: Exception) {
                        null
                    }
                    if (profile != null) {
                        dialog.dismiss()
                        onConnectProfile(profile)
                    } else {
                        Toast.makeText(context, R.string.minab_no_profiles_yet, Toast.LENGTH_SHORT).show()
                    }
                }
                listContainer.addView(card)
            }
        }

        clearAllBtn.setOnClickListener {
            MinabTestHistory.clearAll(context)
            selectedFilter = null
            render()
        }

        render()
        dialog.show()
    }

    private fun showRenameLabelDialog(
        context: Context,
        run: MinabTestHistory.TestRunRecord,
        onDone: () -> Unit
    ) {
        MinabDialogs.showTextInput(
            context = context,
            title = context.getString(R.string.minab_history_rename_network),
            initialText = run.networkLabel,
            hint = context.getString(R.string.minab_history_label_hint)
        ) { newLabel ->
            MinabTestHistory.renameNetworkLabel(context, run.id, newLabel)
            onDone()
        }
    }

    private fun showServersListDialog(
        context: Context,
        run: MinabTestHistory.TestRunRecord,
        onConnect: (VpnProfile) -> Unit
    ) {
        val passed = run.results.filter { it.passed }
        if (passed.isEmpty()) {
            Toast.makeText(context, R.string.minab_history_none_working, Toast.LENGTH_SHORT).show()
            return
        }
        val items = passed.map { item ->
            MinabDialogs.ActionItem(
                title = item.name,
                iconRes = R.drawable.ic_minab_power,
                badgeText = "${item.ms} ms"
            ) {
                val p = try {
                    ProfileManager.getIfPresent(context, item.uuid)
                } catch (_: Exception) {
                    null
                }
                if (p != null) {
                    onConnect(p)
                } else {
                    Toast.makeText(context, R.string.minab_no_profiles_yet, Toast.LENGTH_SHORT).show()
                }
            }
        }
        MinabDialogs.showActionList(
            context = context,
            title = "${run.networkLabel} (${passed.size})",
            iconRes = R.drawable.ic_minab_history,
            items = items
        )
    }
}
