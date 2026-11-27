/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * MinabRealTestSettingsPanel (v0.9.7.1): inline Real Test Settings panel shared
 * across Home and Configs, including target reachability URL selection (10 presets
 * + Custom URL), 20s wait default, 10 servers default, and bordered Reset button.
 */

package de.blinkt.openvpn.minab

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import de.blinkt.openvpn.R

class MinabRealTestSettingsPanel(
    private val uiContext: Context,
    private val gear: ImageButton,
    private val panel: View
) {
    private val appContext: Context = uiContext.applicationContext
    private var open = false

    private var waitSeconds: Int = MinabRealTestSettings.getWaitSeconds(appContext)
    private var maxServers: Int = MinabRealTestSettings.getMaxServers(appContext)

    private val waitValue: TextView =
        panel.findViewById(R.id.minab_real_settings_wait_value)
    private val maxValue: TextView =
        panel.findViewById(R.id.minab_real_settings_max_value)
    private val urlButton: TextView? =
        panel.findViewById(R.id.minab_real_settings_url_btn)
    private val unpingedCheck: CheckBox =
        panel.findViewById(R.id.minab_real_settings_unpinged_check)

    private val unpingedListener =
        CompoundButton.OnCheckedChangeListener { _, _ ->
            save()
        }

    init {
        gear.setOnClickListener { toggle() }
        panel.findViewById<ImageButton>(R.id.minab_real_settings_wait_minus)
            ?.setOnClickListener { stepWait(-1) }
        panel.findViewById<ImageButton>(R.id.minab_real_settings_wait_plus)
            ?.setOnClickListener { stepWait(1) }
        panel.findViewById<ImageButton>(R.id.minab_real_settings_max_minus)
            ?.setOnClickListener { stepMax(-1) }
        panel.findViewById<ImageButton>(R.id.minab_real_settings_max_plus)
            ?.setOnClickListener { stepMax(1) }
        urlButton?.setOnClickListener { showTargetUrlDialog() }
        unpingedCheck.setOnCheckedChangeListener(unpingedListener)
        panel.findViewById<View>(R.id.minab_real_settings_reset)
            ?.setOnClickListener {
                MinabRealTestSettings.resetToRecommended(appContext)
                refresh()
                MinabProfileBus.notifyChanged()
                Toast.makeText(
                    appContext, R.string.minab_real_settings_reset,
                    Toast.LENGTH_SHORT
                ).show()
            }
        refresh()
        applyState()
    }

    fun toggle() {
        open = !open
        applyState()
    }

    fun refreshIfVisible() {
        if (open) refresh()
    }

    private fun applyState() {
        panel.visibility = if (open) View.VISIBLE else View.GONE
        gear.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                appContext,
                if (open) R.color.minab_connected else R.color.minab_teal
            )
        )
        if (open) refresh()
    }

    private fun refresh() {
        waitSeconds = MinabRealTestSettings.getWaitSeconds(appContext)
        maxServers = MinabRealTestSettings.getMaxServers(appContext)
        waitValue.text = appContext.getString(
            R.string.minab_real_settings_wait_value, waitSeconds
        )
        maxValue.text = maxServers.toString()
        urlButton?.text = MinabRealTestSettings.getShortHostLabel(appContext)
        unpingedCheck.setOnCheckedChangeListener(null)
        unpingedCheck.isChecked =
            MinabRealTestSettings.getTestUnpinged(appContext)
        unpingedCheck.setOnCheckedChangeListener(unpingedListener)
    }

    private fun showTargetUrlDialog() {
        val currentUrl = MinabRealTestSettings.getTestUrl(appContext)
        val presetUrls = MinabRealTestSettings.PRESET_URLS
        val presetLabels = MinabRealTestSettings.PRESET_LABELS
        val items = presetLabels.toList() + uiContext.getString(R.string.minab_real_url_custom)
        val checkedIdx = presetUrls.indexOfFirst { it.equals(currentUrl, ignoreCase = true) }
            .let { if (it >= 0) it else presetLabels.size }

        MinabDialogs.showSingleChoice(
            context = uiContext,
            title = uiContext.getString(R.string.minab_real_settings_url_dialog_title),
            options = items,
            selectedIndex = checkedIdx
        ) { which ->
            if (which < presetUrls.size) {
                MinabRealTestSettings.setTestUrl(appContext, presetUrls[which])
                refresh()
                MinabProfileBus.notifyChanged()
            } else {
                showCustomUrlInput(currentUrl)
            }
        }
    }

    private fun showCustomUrlInput(currentUrl: String) {
        MinabDialogs.showTextInput(
            context = uiContext,
            title = uiContext.getString(R.string.minab_real_url_custom),
            initialText = currentUrl,
            hint = "https://api.telegram.org/"
        ) { raw ->
            MinabRealTestSettings.setTestUrl(appContext, raw)
            refresh()
            MinabProfileBus.notifyChanged()
        }
    }

    private fun stepWait(delta: Int) {
        val next = (waitSeconds + delta).coerceIn(
            MinabRealTestSettings.MIN_WAIT_SECONDS,
            MinabRealTestSettings.MAX_WAIT_SECONDS
        )
        if (next == waitSeconds) return
        waitSeconds = next
        waitValue.text = appContext.getString(
            R.string.minab_real_settings_wait_value, waitSeconds
        )
        save()
    }

    private fun stepMax(delta: Int) {
        val next = (maxServers + delta).coerceIn(
            MinabRealTestSettings.MIN_MAX_SERVERS,
            MinabRealTestSettings.MAX_MAX_SERVERS
        )
        if (next == maxServers) return
        maxServers = next
        maxValue.text = maxServers.toString()
        save()
    }

    private fun save() {
        MinabRealTestSettings.save(
            appContext, waitSeconds, maxServers, unpingedCheck.isChecked
        )
        MinabProfileBus.notifyChanged()
    }
}
