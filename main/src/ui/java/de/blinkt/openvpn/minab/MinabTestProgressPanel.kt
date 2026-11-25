/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * MinabTestProgressPanel: shared controller for the dedicated test progress
 * card (minab_test_progress_panel.xml) on both Home and Configs tabs.
 * Shows live progress %, progress bar, tested/remaining/found counters,
 * current server & ETA, and a prominent Cancel (or Close when done) action.
 */

package de.blinkt.openvpn.minab

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import de.blinkt.openvpn.R

class MinabTestProgressPanel(
    private val context: Context,
    private val root: View,
    onCancel: () -> Unit
) {
    private val spinner: ProgressBar = root.findViewById(R.id.minab_test_spinner)
    private val titleView: TextView = root.findViewById(R.id.minab_test_title)
    private val percentView: TextView = root.findViewById(R.id.minab_test_percent)
    private val progressBar: ProgressBar = root.findViewById(R.id.minab_test_progress)
    private val statDoneView: TextView = root.findViewById(R.id.minab_test_stat_done)
    private val statRemainingView: TextView = root.findViewById(R.id.minab_test_stat_remaining)
    private val statFoundView: TextView = root.findViewById(R.id.minab_test_stat_found)
    private val statFoundLabelView: TextView = root.findViewById(R.id.minab_test_stat_found_label)
    private val detailView: TextView = root.findViewById(R.id.minab_test_detail)
    private val cancelBtn: Button = root.findViewById(R.id.minab_test_cancel)
    private val dismissBtn: Button = root.findViewById(R.id.minab_test_dismiss)

    init {
        cancelBtn.setOnClickListener { onCancel() }
        dismissBtn.setOnClickListener { hide() }
    }

    private fun ensureVisible() {
        if (root.visibility != View.VISIBLE) {
            root.alpha = 0f
            root.visibility = View.VISIBLE
            root.animate().alpha(1f).setDuration(180L).start()
        }
    }

    private fun applyAccent(isReal: Boolean) {
        val accent = ContextCompat.getColor(
            context,
            if (isReal) R.color.minab_connected else R.color.minab_teal
        )
        root.setBackgroundResource(
            if (isReal) R.drawable.minab_test_panel_bg_real
            else R.drawable.minab_test_panel_bg_ping
        )
        spinner.indeterminateTintList = ColorStateList.valueOf(accent)
        progressBar.progressTintList = ColorStateList.valueOf(accent)
        percentView.setTextColor(accent)
    }

    fun showPingProgress(done: Int, total: Int, okCount: Int, latestName: String?) {
        ensureVisible()
        applyAccent(isReal = false)
        spinner.visibility = View.VISIBLE
        cancelBtn.visibility = View.VISIBLE
        dismissBtn.visibility = View.GONE

        val safeTotal = total.coerceAtLeast(0)
        val safeDone = done.coerceIn(0, safeTotal)
        val remaining = (safeTotal - safeDone).coerceAtLeast(0)
        val pct = if (safeTotal > 0) (safeDone * 100 / safeTotal).coerceIn(0, 100) else 0

        titleView.text = context.getString(R.string.minab_test_panel_ping_title)
        percentView.text = context.getString(R.string.minab_test_percent, pct)
        progressBar.progress = pct
        statDoneView.text = safeDone.toString()
        statRemainingView.text = remaining.toString()
        statFoundView.text = okCount.toString()
        statFoundLabelView.text = context.getString(R.string.minab_test_stat_responded)
        detailView.text = if (!latestName.isNullOrBlank() && safeDone > 0) {
            context.getString(R.string.minab_test_ping_detail_latest, latestName, safeDone, safeTotal)
        } else {
            context.getString(R.string.minab_test_ping_detail_total, safeTotal)
        }
    }

    fun showRealPingPhase(done: Int, total: Int, okCount: Int) {
        ensureVisible()
        applyAccent(isReal = true)
        spinner.visibility = View.VISIBLE
        cancelBtn.visibility = View.VISIBLE
        dismissBtn.visibility = View.GONE

        val safeTotal = total.coerceAtLeast(0)
        val safeDone = done.coerceIn(0, safeTotal)
        val remaining = (safeTotal - safeDone).coerceAtLeast(0)
        val pct = if (safeTotal > 0) (safeDone * 100 / safeTotal).coerceIn(0, 100) else 0

        titleView.text = context.getString(R.string.minab_test_panel_real_ping_title)
        percentView.text = context.getString(R.string.minab_test_percent, pct)
        progressBar.progress = pct
        statDoneView.text = safeDone.toString()
        statRemainingView.text = remaining.toString()
        statFoundView.text = okCount.toString()
        statFoundLabelView.text = context.getString(R.string.minab_test_stat_responded)
        detailView.text = context.getString(R.string.minab_test_ping_detail_total, safeTotal)
    }

    fun showRealProgress(
        done: Int,
        total: Int,
        working: Int,
        targetWorking: Int,
        currentName: String?,
        totalSecondsLeft: Int
    ) {
        ensureVisible()
        applyAccent(isReal = true)
        spinner.visibility = View.VISIBLE
        cancelBtn.visibility = View.VISIBLE
        dismissBtn.visibility = View.GONE

        val safeTotal = total.coerceAtLeast(0)
        val safeDone = done.coerceIn(0, safeTotal)
        val remaining = (safeTotal - safeDone).coerceAtLeast(0)
        val pctByCount = if (safeTotal > 0) (safeDone * 100 / safeTotal) else 0
        val pctByTarget = if (targetWorking > 0) (working * 100 / targetWorking) else 0
        val pct = maxOf(pctByCount, pctByTarget).coerceIn(0, 100)

        titleView.text = context.getString(R.string.minab_test_panel_real_title)
        percentView.text = context.getString(R.string.minab_test_percent, pct)
        progressBar.progress = pct
        statDoneView.text = safeDone.toString()
        statRemainingView.text = remaining.toString()
        statFoundView.text = if (targetWorking > 0) {
            context.getString(R.string.minab_test_working_of, working, targetWorking)
        } else {
            working.toString()
        }
        statFoundLabelView.text = context.getString(R.string.minab_test_stat_working)
        val name = currentName?.takeIf { it.isNotBlank() } ?: "—"
        detailView.text = if (totalSecondsLeft > 0) {
            context.getString(R.string.minab_test_real_detail_eta, name, totalSecondsLeft)
        } else {
            context.getString(R.string.minab_test_real_detail_current, name)
        }
    }

    fun showFinished(
        isReal: Boolean,
        summaryText: String,
        tested: Int,
        total: Int,
        found: Int
    ) {
        ensureVisible()
        root.setBackgroundResource(R.drawable.minab_card_bg)
        val accent = ContextCompat.getColor(
            context,
            if (isReal) R.color.minab_connected else R.color.minab_teal
        )
        progressBar.progressTintList = ColorStateList.valueOf(accent)
        percentView.setTextColor(accent)

        spinner.visibility = View.GONE
        cancelBtn.visibility = View.GONE
        dismissBtn.visibility = View.VISIBLE

        val safeTotal = total.coerceAtLeast(tested)
        val remaining = (safeTotal - tested).coerceAtLeast(0)

        titleView.text = context.getString(
            if (isReal) R.string.minab_test_panel_real_done_title
            else R.string.minab_test_panel_ping_done_title
        )
        percentView.text = context.getString(R.string.minab_test_percent, 100)
        progressBar.progress = 100
        statDoneView.text = tested.toString()
        statRemainingView.text = remaining.toString()
        statFoundView.text = found.toString()
        statFoundLabelView.text = context.getString(
            if (isReal) R.string.minab_test_stat_working
            else R.string.minab_test_stat_responded
        )
        detailView.text = summaryText
    }

    fun hide() {
        root.animate().cancel()
        root.visibility = View.GONE
    }
}
