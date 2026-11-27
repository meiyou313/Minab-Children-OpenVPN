/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * MinabSortDialog: minimal, card-styled ranking picker shared across Home & Configs.
 */

package de.blinkt.openvpn.minab

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import de.blinkt.openvpn.R

object MinabSortDialog {

    fun shortLabel(context: Context, mode: Int): String = when (mode) {
        MinabProfileBus.SORT_PING -> context.getString(R.string.minab_sort_short_ping)
        MinabProfileBus.SORT_COUNTRY -> context.getString(R.string.minab_sort_short_country)
        MinabProfileBus.SORT_NAME -> context.getString(R.string.minab_sort_short_name)
        MinabProfileBus.SORT_LRU -> context.getString(R.string.minab_sort_short_lru)
        else -> context.getString(R.string.minab_sort_short_real)
    }

    fun show(context: Context, currentMode: Int, onPicked: (Int) -> Unit) {
        val view = LayoutInflater.from(context).inflate(R.layout.minab_sort_dialog, null)
        val dialog = AlertDialog.Builder(context)
            .setView(view)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        view.findViewById<ImageButton>(R.id.minab_sort_close)?.setOnClickListener { dialog.dismiss() }

        val options = listOf(
            Triple(MinabProfileBus.SORT_REAL, R.id.minab_sort_opt_real, R.id.minab_sort_check_real),
            Triple(MinabProfileBus.SORT_PING, R.id.minab_sort_opt_ping, R.id.minab_sort_check_ping),
            Triple(MinabProfileBus.SORT_COUNTRY, R.id.minab_sort_opt_country, R.id.minab_sort_check_country),
            Triple(MinabProfileBus.SORT_NAME, R.id.minab_sort_opt_name, R.id.minab_sort_check_name),
            Triple(MinabProfileBus.SORT_LRU, R.id.minab_sort_opt_lru, R.id.minab_sort_check_lru)
        )

        for ((mode, rowId, checkId) in options) {
            val row = view.findViewById<View>(rowId)
            val check = view.findViewById<ImageView>(checkId)
            val active = mode == currentMode
            row?.setBackgroundResource(
                if (active) R.drawable.minab_chip_bg_selected else R.drawable.minab_chip_bg
            )
            check?.visibility = if (active) View.VISIBLE else View.GONE
            row?.setOnClickListener {
                dialog.dismiss()
                MinabProfileBus.setSortMode(context, mode)
                onPicked(mode)
            }
        }
        dialog.show()
    }
}
