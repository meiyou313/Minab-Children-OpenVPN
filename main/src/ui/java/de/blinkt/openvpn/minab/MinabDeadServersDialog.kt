/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * MinabDeadServersDialog: surfaces servers that failed every single test
 * across multiple runs / multiple networks so users can inspect and cleanly
 * delete chronically broken configs without risking untested servers.
 */

package de.blinkt.openvpn.minab

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import de.blinkt.openvpn.R
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ProfileManager

object MinabDeadServersDialog {

    private const val MODE_2_FAILS = 0
    private const val MODE_3_FAILS = 1
    private const val MODE_2_NETS = 2

    fun show(
        context: Context,
        allProfiles: Collection<VpnProfile>,
        onDeleted: () -> Unit
    ) {
        val inflater = LayoutInflater.from(context)
        val root = inflater.inflate(R.layout.minab_dead_servers_dialog, null)
        val dialog = AlertDialog.Builder(context).setView(root).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val closeBtn = root.findViewById<ImageButton>(R.id.minab_dead_close)
        val chip2Fails = root.findViewById<TextView>(R.id.minab_dead_filter_2fails)
        val chip3Fails = root.findViewById<TextView>(R.id.minab_dead_filter_3fails)
        val chip2Nets = root.findViewById<TextView>(R.id.minab_dead_filter_2nets)
        val selectBar = root.findViewById<View>(R.id.minab_dead_select_bar)
        val countLabel = root.findViewById<TextView>(R.id.minab_dead_count_label)
        val toggleAllBtn = root.findViewById<TextView>(R.id.minab_dead_toggle_all)
        val emptyView = root.findViewById<TextView>(R.id.minab_dead_empty)
        val scroll = root.findViewById<ScrollView>(R.id.minab_dead_scroll)
        val container = root.findViewById<LinearLayout>(R.id.minab_dead_container)
        val cancelBtn = root.findViewById<Button>(R.id.minab_dead_cancel)
        val deleteBtn = root.findViewById<Button>(R.id.minab_dead_delete_btn)

        val dangerColor = ContextCompat.getColor(context, R.color.minab_danger)
        val dangerTint = ContextCompat.getColor(context, R.color.minab_danger_tint)
        val tealDark = ContextCompat.getColor(context, R.color.minab_teal_dark)
        val chipText = ContextCompat.getColor(context, R.color.minab_chip_text)

        deleteBtn.backgroundTintList = ColorStateList.valueOf(dangerColor)

        var filterMode = MODE_2_FAILS
        var currentEntries: List<Pair<VpnProfile, MinabServerStats.ServerStat>> = emptyList()
        val checkedUuids = LinkedHashSet<String>()

        fun updateDeleteButton() {
            val selectedCount = currentEntries.count { checkedUuids.contains(it.first.getUUIDString()) }
            deleteBtn.isEnabled = selectedCount > 0
            deleteBtn.alpha = if (selectedCount > 0) 1f else 0.45f
            deleteBtn.text = if (selectedCount > 0) {
                context.getString(R.string.minab_delete_count_fmt, selectedCount)
            } else {
                context.getString(R.string.minab_delete_short)
            }
            val allChecked = currentEntries.isNotEmpty() && selectedCount == currentEntries.size
            toggleAllBtn.setText(if (allChecked) R.string.minab_deselect_all else R.string.minab_select_all)
        }

        fun styleFilterChips() {
            val chips = listOf(MODE_2_FAILS to chip2Fails, MODE_3_FAILS to chip3Fails, MODE_2_NETS to chip2Nets)
            for ((mode, chip) in chips) {
                val active = mode == filterMode
                chip.setBackgroundResource(
                    if (active) R.drawable.minab_chip_bg_selected else R.drawable.minab_chip_bg
                )
                chip.setTextColor(if (active) tealDark else chipText)
                chip.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
            }
        }

        fun render() {
            styleFilterChips()
            container.removeAllViews()
            currentEntries = when (filterMode) {
                MODE_3_FAILS -> MinabServerStats.findDeadServers(context, allProfiles, minFails = 3, minNetworks = 1)
                MODE_2_NETS -> MinabServerStats.findDeadServers(context, allProfiles, minFails = 2, minNetworks = 2)
                else -> MinabServerStats.findDeadServers(context, allProfiles, minFails = 2, minNetworks = 1)
            }

            checkedUuids.clear()
            for (entry in currentEntries) {
                checkedUuids.add(entry.first.getUUIDString())
            }

            if (currentEntries.isEmpty()) {
                emptyView.visibility = View.VISIBLE
                scroll.visibility = View.GONE
                selectBar.visibility = View.GONE
            } else {
                emptyView.visibility = View.GONE
                scroll.visibility = View.VISIBLE
                selectBar.visibility = View.VISIBLE
                countLabel.text = context.getString(R.string.minab_dead_found_count, currentEntries.size)
            }

            scroll.layoutParams.height = if (currentEntries.size > 5) {
                (context.resources.displayMetrics.heightPixels * 0.42f).toInt()
            } else {
                ViewGroup.LayoutParams.WRAP_CONTENT
            }

            val rowChecks = mutableListOf<Pair<String, CheckBox>>()
            for ((profile, stat) in currentEntries) {
                val row = inflater.inflate(R.layout.minab_dead_server_row, container, false)
                val check = row.findViewById<CheckBox>(R.id.minab_dead_row_check)
                val flag = row.findViewById<ImageView>(R.id.minab_dead_row_flag)
                val name = row.findViewById<TextView>(R.id.minab_dead_row_name)
                val groups = row.findViewById<TextView>(R.id.minab_dead_row_groups)
                val badge = row.findViewById<TextView>(R.id.minab_dead_row_badge)

                val uuid = profile.getUUIDString()
                check.isChecked = checkedUuids.contains(uuid)
                flag.setImageResource(FlagHelper.flagResForProfile(profile, context))
                name.text = profile.mName ?: ""
                groups.text = "[${profile.getGroupsDisplayText()}]"
                badge.text = context.getString(
                    R.string.minab_dead_badge_fmt,
                    stat.totalTests,
                    stat.failedNetworks.size.coerceAtLeast(1)
                )
                badge.backgroundTintList = ColorStateList.valueOf(dangerTint)

                rowChecks.add(uuid to check)
                row.setOnClickListener {
                    if (!checkedUuids.remove(uuid)) checkedUuids.add(uuid)
                    check.isChecked = checkedUuids.contains(uuid)
                    updateDeleteButton()
                }
                container.addView(row)
            }

            toggleAllBtn.setOnClickListener {
                val allChecked = currentEntries.isNotEmpty() &&
                    currentEntries.all { checkedUuids.contains(it.first.getUUIDString()) }
                if (allChecked) {
                    checkedUuids.clear()
                } else {
                    for (entry in currentEntries) checkedUuids.add(entry.first.getUUIDString())
                }
                for ((uuid, cb) in rowChecks) {
                    cb.isChecked = checkedUuids.contains(uuid)
                }
                updateDeleteButton()
            }

            updateDeleteButton()
        }

        chip2Fails.setOnClickListener { filterMode = MODE_2_FAILS; render() }
        chip3Fails.setOnClickListener { filterMode = MODE_3_FAILS; render() }
        chip2Nets.setOnClickListener { filterMode = MODE_2_NETS; render() }
        closeBtn.setOnClickListener { dialog.dismiss() }
        cancelBtn.setOnClickListener { dialog.dismiss() }

        deleteBtn.setOnClickListener {
            val targets = currentEntries.map { it.first }
                .filter { checkedUuids.contains(it.getUUIDString()) }
            if (targets.isEmpty()) return@setOnClickListener
            MinabDialogs.showConfirm(
                context = context,
                title = context.getString(R.string.minab_dead_servers_title),
                message = context.getString(R.string.minab_dead_delete_confirm, targets.size),
                positiveText = context.getString(R.string.minab_delete_short),
                isDanger = true
            ) {
                val pm = ProfileManager.getInstance(context)
                val removedUuids = mutableListOf<String>()
                for (p in targets) {
                    removedUuids.add(p.getUUIDString())
                    pm.removeProfile(context, p)
                }
                MinabServerStats.removeStatsForUuids(context, removedUuids)
                Toast.makeText(
                    context,
                    context.getString(R.string.minab_dead_deleted_toast, targets.size),
                    Toast.LENGTH_SHORT
                ).show()
                dialog.dismiss()
                onDeleted()
                MinabProfileBus.notifyChanged()
            }
        }

        render()
        dialog.show()
    }
}
