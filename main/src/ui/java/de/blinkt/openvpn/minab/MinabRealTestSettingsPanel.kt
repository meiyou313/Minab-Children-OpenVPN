/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * MinabRealTestSettingsPanel (v0.9.6.2, fixes #1 and #6): the Real
 * test settings as an inline panel under the Real test button —
 * one shared helper for Home and Configs, so both tabs behave
 * exactly the same.
 *
 * Behaviour:
 *   - Tapping the gear toggles the panel. While the panel is open
 *     the gear is GREEN (@color/minab_connected); while it is
 *     closed the gear is BLUE (@color/minab_teal).
 *   - Every change saves at once (stepper tap, checkbox toggle,
 *     "Reset to recommended") — there is no OK button to forget.
 *     This matters: the 0.9.6.1 dialog only saved on OK, so a
 *     change lost by closing the dialog any other way looked like
 *     the "test unpinged" setting was being ignored (see
 *     RealTester, fix #3).
 *   - The panel reads the current values from
 *     MinabRealTestSettings every time it opens, so a change made
 *     on the other tab is picked up.
 *   - Changing the settings never touches a running real test:
 *     the runner reads them when a run starts, so edits apply to
 *     the next run.
 */

package de.blinkt.openvpn.minab

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import de.blinkt.openvpn.R

class MinabRealTestSettingsPanel(
    context: Context,
    private val gear: ImageButton,
    private val panel: View
) {
    private val appContext: Context = context.applicationContext
    private var open = false

    private var waitSeconds: Int = MinabRealTestSettings.getWaitSeconds(appContext)
    private var maxServers: Int = MinabRealTestSettings.getMaxServers(appContext)

    private val waitValue: TextView =
        panel.findViewById(R.id.minab_real_settings_wait_value)
    private val maxValue: TextView =
        panel.findViewById(R.id.minab_real_settings_max_value)
    private val unpingedCheck: CheckBox =
        panel.findViewById(R.id.minab_real_settings_unpinged_check)

    /** Saved here so refresh() can detach it while it sets the
        checkbox programmatically (a programmatic set must not
        save — only a real tap does). */
    private val unpingedListener =
        CompoundButton.OnCheckedChangeListener { _, checked ->
            save()
        }

    init {
        gear.setOnClickListener { toggle() }
        panel.findViewById<Button>(R.id.minab_real_settings_wait_minus)
            ?.setOnClickListener { stepWait(-1) }
        panel.findViewById<Button>(R.id.minab_real_settings_wait_plus)
            ?.setOnClickListener { stepWait(1) }
        panel.findViewById<Button>(R.id.minab_real_settings_max_minus)
            ?.setOnClickListener { stepMax(-1) }
        panel.findViewById<Button>(R.id.minab_real_settings_max_plus)
            ?.setOnClickListener { stepMax(1) }
        unpingedCheck.setOnCheckedChangeListener(unpingedListener)
        panel.findViewById<View>(R.id.minab_real_settings_reset)
            ?.setOnClickListener {
                MinabRealTestSettings.resetToRecommended(appContext)
                refresh()
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

    /** Re-read the saved settings into the panel's controls. */
    private fun refresh() {
        waitSeconds = MinabRealTestSettings.getWaitSeconds(appContext)
        maxServers = MinabRealTestSettings.getMaxServers(appContext)
        waitValue.text = appContext.getString(
            R.string.minab_real_settings_wait_value, waitSeconds
        )
        maxValue.text = maxServers.toString()
        unpingedCheck.setOnCheckedChangeListener(null)
        unpingedCheck.isChecked =
            MinabRealTestSettings.getTestUnpinged(appContext)
        unpingedCheck.setOnCheckedChangeListener(unpingedListener)
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
    }
}
