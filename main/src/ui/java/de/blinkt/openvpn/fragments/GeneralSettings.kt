/*
 * Copyright (c) 2012-2016 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */
package de.blinkt.openvpn.fragments

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import de.blinkt.openvpn.BuildConfig
import de.blinkt.openvpn.R
import de.blinkt.openvpn.activities.MainActivity
import de.blinkt.openvpn.activities.MinabAllowedAppsActivity
import de.blinkt.openvpn.activities.OpenSSLSpeed
import de.blinkt.openvpn.api.ConfirmDialog.ANONYMOUS_PACKAGE
import de.blinkt.openvpn.api.ExternalAppDatabase
import de.blinkt.openvpn.core.Preferences
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.minab.FlagHelper
import de.blinkt.openvpn.minab.MinabAllowedApps
import de.blinkt.openvpn.minab.MinabCrashReporter
import de.blinkt.openvpn.minab.MinabProfileBus
import de.blinkt.openvpn.minab.MinabUiPrefs
import de.blinkt.openvpn.minab.MinabUpdateChecker

class GeneralSettings : Fragment() {
    private lateinit var mPermReceiver: ActivityResultLauncher<String>
    private lateinit var mExtapp: ExternalAppDatabase
    private var minabLatestTag: String? = null
    private var suppressSwitchCallbacks: Boolean = false
    private var advancedExpanded: Boolean = false

    // Appearance
    private var themeSystemBtn: LinearLayout? = null
    private var themeLightBtn: LinearLayout? = null
    private var themeDarkBtn: LinearLayout? = null
    private var themeSystemIcon: ImageView? = null
    private var themeLightIcon: ImageView? = null
    private var themeDarkIcon: ImageView? = null
    private var themeSystemText: TextView? = null
    private var themeLightText: TextView? = null
    private var themeDarkText: TextView? = null
    private var languageValueView: TextView? = null

    // Summaries & containers
    private var perAppSummaryView: TextView? = null
    private var defaultVpnSummaryView: TextView? = null
    private var ovpn3SummaryView: TextView? = null
    private var clearApiSummaryView: TextView? = null
    private var clearApiRow: View? = null
    private var proxyRow: View? = null
    private var ovpn3Row: View? = null
    private var localNetContainer: View? = null
    private var advancedBody: View? = null
    private var advancedChevron: ImageView? = null
    private var updateSummaryView: TextView? = null
    private var crashSummaryView: TextView? = null

    // Switches
    private var switchFailover: SwitchCompat? = null
    private var switchNetchange: SwitchCompat? = null
    private var switchSpeedNotif: SwitchCompat? = null
    private var switchBoot: SwitchCompat? = null
    private var switchEncrypt: SwitchCompat? = null
    private var switchProxy: SwitchCompat? = null
    private var switchScreenoff: SwitchCompat? = null
    private var switchLog: SwitchCompat? = null
    private var switchOvpn3: SwitchCompat? = null
    private var switchLocalNet: SwitchCompat? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mPermReceiver = registerForActivityResult(RequestPermission()) {
            if (isAdded) updateLocalNetworkState()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.minab_settings_fragment, container, false)
        try {
            val ctx = requireContext()
            mExtapp = ExternalAppDatabase(ctx)
            bindViews(root)
            wireAppearance(ctx, root)
            wirePreferenceSwitches(ctx, root)
            wireActions(ctx, root)
            syncUiFromPrefs(ctx)
            refreshUpdateSummary(false)
        } catch (e: Exception) {
            android.util.Log.e("GeneralSettings", "Minab: onCreateView failed", e)
        }
        return root
    }

    private fun bindViews(root: View) {
        themeSystemBtn = root.findViewById(R.id.minab_settings_theme_system)
        themeLightBtn = root.findViewById(R.id.minab_settings_theme_light)
        themeDarkBtn = root.findViewById(R.id.minab_settings_theme_dark)
        themeSystemIcon = root.findViewById(R.id.minab_settings_theme_system_icon)
        themeLightIcon = root.findViewById(R.id.minab_settings_theme_light_icon)
        themeDarkIcon = root.findViewById(R.id.minab_settings_theme_dark_icon)
        themeSystemText = root.findViewById(R.id.minab_settings_theme_system_text)
        themeLightText = root.findViewById(R.id.minab_settings_theme_light_text)
        themeDarkText = root.findViewById(R.id.minab_settings_theme_dark_text)
        languageValueView = root.findViewById(R.id.minab_settings_language_value)

        perAppSummaryView = root.findViewById(R.id.minab_settings_per_app_summary)
        defaultVpnSummaryView = root.findViewById(R.id.minab_settings_default_vpn_summary)
        ovpn3SummaryView = root.findViewById(R.id.minab_settings_ovpn3_summary)
        clearApiSummaryView = root.findViewById(R.id.minab_settings_clearapi_summary)
        clearApiRow = root.findViewById(R.id.minab_settings_clearapi_row)
        proxyRow = root.findViewById(R.id.minab_settings_proxy_row)
        ovpn3Row = root.findViewById(R.id.minab_settings_ovpn3_row)
        localNetContainer = root.findViewById(R.id.minab_settings_local_net_container)
        advancedBody = root.findViewById(R.id.minab_settings_advanced_body)
        advancedChevron = root.findViewById(R.id.minab_settings_advanced_chevron)
        updateSummaryView = root.findViewById(R.id.minab_settings_update_summary)
        crashSummaryView = root.findViewById(R.id.minab_settings_crash_summary)

        switchFailover = root.findViewById(R.id.minab_switch_failover)
        switchNetchange = root.findViewById(R.id.minab_switch_netchange)
        switchSpeedNotif = root.findViewById(R.id.minab_switch_speed_notif)
        switchBoot = root.findViewById(R.id.minab_switch_boot)
        switchEncrypt = root.findViewById(R.id.minab_switch_encrypt)
        switchProxy = root.findViewById(R.id.minab_switch_proxy)
        switchScreenoff = root.findViewById(R.id.minab_switch_screenoff)
        switchLog = root.findViewById(R.id.minab_switch_log)
        switchOvpn3 = root.findViewById(R.id.minab_switch_ovpn3)
        switchLocalNet = root.findViewById(R.id.minab_switch_local_net)
    }

    private fun wireAppearance(ctx: Context, root: View) {
        themeSystemBtn?.setOnClickListener {
            MinabUiPrefs.setThemeMode(ctx, MinabUiPrefs.THEME_SYSTEM)
            applyThemeSegmentUi(ctx)
        }
        themeLightBtn?.setOnClickListener {
            MinabUiPrefs.setThemeMode(ctx, MinabUiPrefs.THEME_LIGHT)
            applyThemeSegmentUi(ctx)
        }
        themeDarkBtn?.setOnClickListener {
            MinabUiPrefs.setThemeMode(ctx, MinabUiPrefs.THEME_DARK)
            applyThemeSegmentUi(ctx)
        }
        root.findViewById<View>(R.id.minab_settings_language_row)?.setOnClickListener {
            showLanguagePickerDialog()
        }
    }

    private fun wirePreferenceSwitches(ctx: Context, root: View) {
        val prefs = Preferences.getDefaultSharedPreferences(ctx)
        bindBoolSwitch(root.findViewById(R.id.minab_settings_failover_row), switchFailover, prefs, "minab_failover")
        bindBoolSwitch(root.findViewById(R.id.minab_settings_netchange_row), switchNetchange, prefs, "netchangereconnect")
        bindBoolSwitch(root.findViewById(R.id.minab_settings_speed_notif_row), switchSpeedNotif, prefs, "minab_show_speed_notification")
        bindBoolSwitch(root.findViewById(R.id.minab_settings_encrypt_row), switchEncrypt, prefs, "preferencryption")
        bindBoolSwitch(proxyRow, switchProxy, prefs, "usesystemproxy")
        bindBoolSwitch(root.findViewById(R.id.minab_settings_screenoff_row), switchScreenoff, prefs, "screenoff")
        bindBoolSwitch(root.findViewById(R.id.minab_settings_log_row), switchLog, prefs, "showlogwindow")

        root.findViewById<View>(R.id.minab_settings_boot_row)?.setOnClickListener {
            switchBoot?.toggle()
        }
        switchBoot?.setOnCheckedChangeListener { _, isChecked ->
            if (suppressSwitchCallbacks) return@setOnCheckedChangeListener
            if (!mExtapp.checkAllowingModifyingRemoteControl(ctx)) {
                revertSwitch(switchBoot, !isChecked)
                return@setOnCheckedChangeListener
            }
            if (isChecked) {
                val vpn = ProfileManager.getAlwaysOnVPN(ctx)
                if (vpn == null) {
                    Toast.makeText(ctx, R.string.no_default_vpn_set, Toast.LENGTH_LONG).show()
                    revertSwitch(switchBoot, false)
                    return@setOnCheckedChangeListener
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    AlertDialog.Builder(ctx)
                        .setMessage(R.string.use_alwayson_vpn)
                        .setNeutralButton(R.string.open_vpn_settings) { _, _ ->
                            try {
                                startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                            } catch (_: ActivityNotFoundException) {
                                Toast.makeText(
                                    ctx,
                                    "Cannot launch VPN settings directly, please manually open the settings instead.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
            prefs.edit().putBoolean("restartvpnonboot", isChecked).apply()
        }

        ovpn3Row?.setOnClickListener {
            if (switchOvpn3?.isEnabled == true) switchOvpn3?.toggle()
        }
        switchOvpn3?.setOnCheckedChangeListener { _, isChecked ->
            if (suppressSwitchCallbacks) return@setOnCheckedChangeListener
            if (!BuildConfig.openvpn3) {
                revertSwitch(switchOvpn3, false)
                return@setOnCheckedChangeListener
            }
            if (!mExtapp.checkAllowingModifyingRemoteControl(ctx)) {
                revertSwitch(switchOvpn3, !isChecked)
                return@setOnCheckedChangeListener
            }
            prefs.edit().putBoolean("ovpn3", isChecked).apply()
            updateOvpn3Dependencies(isChecked)
        }

        root.findViewById<View>(R.id.minab_settings_local_net_row)?.setOnClickListener {
            if (switchLocalNet?.isEnabled == true) switchLocalNet?.toggle()
        }
        switchLocalNet?.setOnCheckedChangeListener { _, isChecked ->
            if (suppressSwitchCallbacks) return@setOnCheckedChangeListener
            if (isChecked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
                mPermReceiver.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
            } else {
                revertSwitch(switchLocalNet, false)
            }
        }
    }

    private fun bindBoolSwitch(
        row: View?,
        sw: SwitchCompat?,
        prefs: SharedPreferences,
        key: String
    ) {
        row?.setOnClickListener {
            if (sw?.isEnabled == true) sw.toggle()
        }
        sw?.setOnCheckedChangeListener { _, isChecked ->
            if (suppressSwitchCallbacks) return@setOnCheckedChangeListener
            val ctx = context ?: return@setOnCheckedChangeListener
            if (!mExtapp.checkAllowingModifyingRemoteControl(ctx)) {
                revertSwitch(sw, !isChecked)
                return@setOnCheckedChangeListener
            }
            prefs.edit().putBoolean(key, isChecked).apply()
        }
    }

    private fun revertSwitch(sw: SwitchCompat?, targetChecked: Boolean) {
        suppressSwitchCallbacks = true
        sw?.isChecked = targetChecked
        suppressSwitchCallbacks = false
    }

    private fun wireActions(ctx: Context, root: View) {
        root.findViewById<View>(R.id.minab_settings_per_app_row)?.setOnClickListener {
            if (!mExtapp.checkAllowingModifyingRemoteControl(ctx)) return@setOnClickListener
            startActivity(Intent(ctx, MinabAllowedAppsActivity::class.java))
        }
        root.findViewById<View>(R.id.minab_settings_default_vpn_row)?.setOnClickListener {
            if (!mExtapp.checkAllowingModifyingRemoteControl(ctx)) return@setOnClickListener
            showDefaultVpnPickerDialog()
        }
        root.findViewById<View>(R.id.minab_settings_advanced_header)?.setOnClickListener {
            advancedExpanded = !advancedExpanded
            advancedBody?.visibility = if (advancedExpanded) View.VISIBLE else View.GONE
            advancedChevron?.animate()?.rotation(if (advancedExpanded) 180f else 0f)
                ?.setDuration(180L)?.start()
        }
        root.findViewById<View>(R.id.minab_settings_ossl_row)?.setOnClickListener {
            if (!mExtapp.checkAllowingModifyingRemoteControl(ctx)) return@setOnClickListener
            startActivity(Intent(ctx, OpenSSLSpeed::class.java))
        }
        clearApiRow?.setOnClickListener {
            if (!mExtapp.checkAllowingModifyingRemoteControl(ctx)) return@setOnClickListener
            if (mExtapp.extAppList.isEmpty()) return@setOnClickListener
            AlertDialog.Builder(ctx)
                .setMessage(getString(R.string.clearappsdialog, getExtAppList(", ")))
                .setPositiveButton(R.string.clear) { _, _ ->
                    mExtapp.clearAllApiApps()
                    setClearApiSummary()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        root.findViewById<View>(R.id.minab_settings_update_row)?.setOnClickListener {
            val tag = minabLatestTag
            if (tag != null && MinabUpdateChecker.isNewer(BuildConfig.VERSION_NAME, tag)) {
                MinabUpdateChecker.openLatestRelease(ctx)
            } else {
                updateSummaryView?.text = getString(R.string.minab_update_checking)
                refreshUpdateSummary(true)
            }
        }
        root.findViewById<View>(R.id.minab_settings_faq_row)?.setOnClickListener {
            (activity as? MainActivity)?.showFaq()
        }
        root.findViewById<View>(R.id.minab_settings_crash_row)?.setOnClickListener {
            activity?.let { MinabCrashReporter.showReportDialog(it, R.drawable.ic_minab_ladybug) }
        }
    }

    override fun onResume() {
        super.onResume()
        val ctx = context ?: return
        try {
            syncUiFromPrefs(ctx)
        } catch (e: Exception) {
            android.util.Log.e("GeneralSettings", "Minab: onResume failed", e)
        }
    }

    override fun onDestroyView() {
        themeSystemBtn = null
        themeLightBtn = null
        themeDarkBtn = null
        themeSystemIcon = null
        themeLightIcon = null
        themeDarkIcon = null
        themeSystemText = null
        themeLightText = null
        themeDarkText = null
        languageValueView = null
        perAppSummaryView = null
        defaultVpnSummaryView = null
        ovpn3SummaryView = null
        clearApiSummaryView = null
        clearApiRow = null
        proxyRow = null
        ovpn3Row = null
        localNetContainer = null
        advancedBody = null
        advancedChevron = null
        updateSummaryView = null
        crashSummaryView = null
        switchFailover = null
        switchNetchange = null
        switchSpeedNotif = null
        switchBoot = null
        switchEncrypt = null
        switchProxy = null
        switchScreenoff = null
        switchLog = null
        switchOvpn3 = null
        switchLocalNet = null
        super.onDestroyView()
    }

    private fun syncUiFromPrefs(ctx: Context) {
        val prefs = Preferences.getDefaultSharedPreferences(ctx)
        suppressSwitchCallbacks = true
        try {
            switchFailover?.isChecked = prefs.getBoolean("minab_failover", true)
            switchNetchange?.isChecked = prefs.getBoolean("netchangereconnect", true)
            switchSpeedNotif?.isChecked = prefs.getBoolean("minab_show_speed_notification", true)
            switchEncrypt?.isChecked = prefs.getBoolean("preferencryption", true)
            switchProxy?.isChecked = prefs.getBoolean("usesystemproxy", true)
            switchScreenoff?.isChecked = prefs.getBoolean("screenoff", false)
            switchLog?.isChecked = prefs.getBoolean("showlogwindow", true)

            val ovpn3Supported = BuildConfig.openvpn3
            val ovpn3On = ovpn3Supported && prefs.getBoolean("ovpn3", false)
            switchOvpn3?.isEnabled = ovpn3Supported
            ovpn3Row?.isEnabled = ovpn3Supported
            ovpn3Row?.alpha = if (ovpn3Supported) 1f else 0.5f
            switchOvpn3?.isChecked = ovpn3On
            updateOvpn3Dependencies(ovpn3On)

            var bootOn = prefs.getBoolean("restartvpnonboot", false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val vpn = VpnService()
                    if (vpn.isAlwaysOn) bootOn = prefs.getBoolean("restartvpnonboot", false)
                } catch (_: Exception) {
                }
            }
            switchBoot?.isChecked = bootOn
        } finally {
            suppressSwitchCallbacks = false
        }

        applyThemeSegmentUi(ctx)
        refreshSummaries(ctx)
        updateLocalNetworkState()
        setClearApiSummary()
    }

    private fun updateOvpn3Dependencies(ovpn3Enabled: Boolean) {
        ovpn3SummaryView?.text = if (ovpn3Enabled) {
            "Use the C++ OpenVPN library (experimental)"
        } else {
            "Use OpenVPN 2.x"
        }
        val proxyEnabled = !ovpn3Enabled
        switchProxy?.isEnabled = proxyEnabled
        proxyRow?.isEnabled = proxyEnabled
        proxyRow?.alpha = if (proxyEnabled) 1f else 0.5f
    }

    private fun updateLocalNetworkState() {
        val ctx = context ?: return
        val container = localNetContainer
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) {
            container?.visibility = View.GONE
            return
        }
        container?.visibility = View.VISIBLE
        val granted = ctx.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) ==
            PackageManager.PERMISSION_GRANTED
        suppressSwitchCallbacks = true
        switchLocalNet?.isChecked = granted
        switchLocalNet?.isEnabled = !granted
        suppressSwitchCallbacks = false
    }

    private fun applyThemeSegmentUi(ctx: Context) {
        val mode = MinabUiPrefs.getThemeMode(ctx)
        val secondaryColor = ContextCompat.getColor(ctx, R.color.minab_text_secondary)
        val entries = listOf(
            Triple(MinabUiPrefs.THEME_SYSTEM, themeSystemBtn, Pair(themeSystemIcon, themeSystemText)),
            Triple(MinabUiPrefs.THEME_LIGHT, themeLightBtn, Pair(themeLightIcon, themeLightText)),
            Triple(MinabUiPrefs.THEME_DARK, themeDarkBtn, Pair(themeDarkIcon, themeDarkText))
        )
        for ((entryMode, btn, views) in entries) {
            val active = entryMode == mode
            val (icon, text) = views
            if (active) {
                btn?.setBackgroundResource(R.drawable.minab_btn_primary)
                icon?.imageTintList = ColorStateList.valueOf(Color.WHITE)
                text?.setTextColor(Color.WHITE)
            } else {
                btn?.background = null
                icon?.imageTintList = ColorStateList.valueOf(secondaryColor)
                text?.setTextColor(secondaryColor)
            }
        }
    }

    private fun refreshSummaries(ctx: Context) {
        languageValueView?.text = MinabUiPrefs.languageDisplayName(ctx)
        perAppSummaryView?.text = getString(
            if (MinabAllowedApps.getStoredAreDisallowed(ctx))
                R.string.minab_per_app_vpn_summary_bypass
            else
                R.string.minab_per_app_vpn_summary_only
        )
        val defaultVpn = ProfileManager.getAlwaysOnVPN(ctx)
        defaultVpnSummaryView?.text = if (defaultVpn == null) {
            getString(R.string.minab_settings_default_vpn_none)
        } else {
            getString(R.string.vpnselected, defaultVpn.name)
        }
        crashSummaryView?.text = getString(
            if (MinabCrashReporter.hasSavedCrash(ctx))
                R.string.minab_crash_report_summary_pending
            else
                R.string.minab_crash_report_summary_clean
        )
    }

    private fun showLanguagePickerDialog() {
        val ctx = context ?: return
        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.minab_picker_dialog, null)
        val dialog = AlertDialog.Builder(ctx).setView(dialogView).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<TextView>(R.id.minab_picker_title)?.setText(R.string.minab_language_title)
        val container = dialogView.findViewById<LinearLayout>(R.id.minab_picker_container)
        dialogView.findViewById<Button>(R.id.minab_picker_close)?.setOnClickListener { dialog.dismiss() }

        val currentTag = MinabUiPrefs.getLanguageTag(ctx)
        val inflater = LayoutInflater.from(ctx)
        for (opt in MinabUiPrefs.SUPPORTED_LANGUAGES) {
            val row = inflater.inflate(R.layout.minab_picker_option_row, container, false)
            val icon = row.findViewById<ImageView>(R.id.minab_picker_icon)
            val label = row.findViewById<TextView>(R.id.minab_picker_label)
            val check = row.findViewById<ImageView>(R.id.minab_picker_check)

            val flagRes = flagResForLanguageTag(opt.tag)
            icon?.setImageResource(flagRes)
            icon?.visibility = View.VISIBLE

            label?.text = if (opt.tag == MinabUiPrefs.LANG_SYSTEM) {
                getString(R.string.minab_lang_system)
            } else {
                opt.nativeLabel
            }

            val selected = opt.tag.equals(currentTag, ignoreCase = true)
            row.setBackgroundResource(
                if (selected) R.drawable.minab_chip_bg_selected else R.drawable.minab_chip_bg
            )
            label?.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            check?.visibility = if (selected) View.VISIBLE else View.GONE

            row.setOnClickListener {
                dialog.dismiss()
                MinabUiPrefs.setLanguageTag(ctx, opt.tag)
                refreshSummaries(ctx)
            }
            container?.addView(row)
        }
        if (MinabUiPrefs.SUPPORTED_LANGUAGES.size > 8) {
            dialogView.findViewById<View>(R.id.minab_picker_scroll)?.layoutParams?.height =
                (ctx.resources.displayMetrics.heightPixels * 0.58f).toInt()
        }
        dialog.show()
    }

    private fun flagResForLanguageTag(tag: String): Int = when (tag.lowercase()) {
        "fa" -> R.drawable.flag_ir
        "en" -> R.drawable.flag_gb
        "ar" -> R.drawable.flag_sa
        "tr" -> R.drawable.flag_tr
        "ru" -> R.drawable.flag_ru
        "de" -> R.drawable.flag_de
        "fr" -> R.drawable.flag_fr
        "zh-cn" -> R.drawable.flag_cn
        else -> R.drawable.minab_flag_globe
    }

    private fun showDefaultVpnPickerDialog() {
        val ctx = context ?: return
        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.minab_picker_dialog, null)
        val dialog = AlertDialog.Builder(ctx).setView(dialogView).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<TextView>(R.id.minab_picker_title)?.setText(R.string.defaultvpn)
        val container = dialogView.findViewById<LinearLayout>(R.id.minab_picker_container)
        dialogView.findViewById<Button>(R.id.minab_picker_close)?.setOnClickListener { dialog.dismiss() }

        val prefs = Preferences.getDefaultSharedPreferences(ctx)
        val currentUuid = prefs.getString("alwaysOnVpn", null)
        val inflater = LayoutInflater.from(ctx)

        val noneRow = inflater.inflate(R.layout.minab_picker_option_row, container, false)
        val noneLabel = noneRow.findViewById<TextView>(R.id.minab_picker_label)
        val noneCheck = noneRow.findViewById<ImageView>(R.id.minab_picker_check)
        val noneSelected = currentUuid.isNullOrEmpty()
        noneRow.setBackgroundResource(
            if (noneSelected) R.drawable.minab_chip_bg_selected else R.drawable.minab_chip_bg
        )
        noneLabel?.setText(R.string.minab_settings_none_option)
        noneLabel?.setTypeface(null, if (noneSelected) Typeface.BOLD else Typeface.NORMAL)
        noneCheck?.visibility = if (noneSelected) View.VISIBLE else View.GONE
        noneRow.setOnClickListener {
            dialog.dismiss()
            prefs.edit().remove("alwaysOnVpn").apply()
            refreshSummaries(ctx)
            MinabProfileBus.notifyChanged()
        }
        container?.addView(noneRow)

        val profiles = ProfileManager.getInstance(ctx).getProfiles()
            .sortedBy { (it.mName ?: "").lowercase() }
        for (profile in profiles) {
            val row = inflater.inflate(R.layout.minab_picker_option_row, container, false)
            val icon = row.findViewById<ImageView>(R.id.minab_picker_icon)
            val label = row.findViewById<TextView>(R.id.minab_picker_label)
            val sublabel = row.findViewById<TextView>(R.id.minab_picker_sublabel)
            val check = row.findViewById<ImageView>(R.id.minab_picker_check)

            icon?.setImageResource(FlagHelper.flagResForProfile(profile, ctx))
            icon?.visibility = View.VISIBLE
            label?.text = profile.mName ?: ""
            sublabel?.text = "[" + profile.getCategory() + "]"
            sublabel?.visibility = View.VISIBLE

            val selected = profile.getUUIDString() == currentUuid
            row.setBackgroundResource(
                if (selected) R.drawable.minab_chip_bg_selected else R.drawable.minab_chip_bg
            )
            label?.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            check?.visibility = if (selected) View.VISIBLE else View.GONE

            row.setOnClickListener {
                dialog.dismiss()
                prefs.edit().putString("alwaysOnVpn", profile.getUUIDString()).apply()
                refreshSummaries(ctx)
                MinabProfileBus.notifyChanged()
            }
            container?.addView(row)
        }
        if (profiles.size + 1 > 8) {
            dialogView.findViewById<View>(R.id.minab_picker_scroll)?.layoutParams?.height =
                (ctx.resources.displayMetrics.heightPixels * 0.58f).toInt()
        }
        dialog.show()
    }

    private fun setClearApiSummary() {
        val row = clearApiRow ?: return
        val summaryView = clearApiSummaryView ?: return
        if (mExtapp.extAppList.isEmpty()) {
            row.isEnabled = false
            row.alpha = 0.55f
            summaryView.setText(R.string.no_external_app_allowed)
        } else {
            row.isEnabled = true
            row.alpha = 1f
            summaryView.text = getString(R.string.allowed_apps, getExtAppList(", "))
        }
    }

    private fun getExtAppList(delim: String): String {
        val ctx = context ?: return ""
        val pm = ctx.packageManager
        val applist = StringBuilder()
        for (packagename in mExtapp.extAppList) {
            if (packagename == ANONYMOUS_PACKAGE) {
                applist.append("(Any app)")
            } else {
                try {
                    val app = pm.getApplicationInfo(packagename, 0)
                    if (applist.isNotEmpty()) applist.append(delim)
                    applist.append(app.loadLabel(pm))
                } catch (_: PackageManager.NameNotFoundException) {
                    mExtapp.removeApp(packagename)
                }
            }
        }
        return applist.toString()
    }

    private fun refreshUpdateSummary(force: Boolean) {
        val ctx = context ?: return
        MinabUpdateChecker.check(ctx, force) { latest, available ->
            if (!isAdded) return@check
            minabLatestTag = latest
            updateSummaryView?.text = when {
                latest == null -> getString(R.string.minab_update_failed)
                available -> getString(R.string.minab_update_available_short, latest)
                else -> getString(R.string.minab_update_none, BuildConfig.VERSION_NAME)
            }
        }
    }
}
