/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * MinabDiagnosticsActivity: local-only Diagnostics & Debug Log Center.
 * Never uploads telemetry automatically; lets users inspect crashes, app
 * breadcrumbs, OpenVPN logs, and download/share a single sanitized .txt file.
 */

package de.blinkt.openvpn.activities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import de.blinkt.openvpn.BuildConfig
import de.blinkt.openvpn.R
import de.blinkt.openvpn.core.VpnStatus
import de.blinkt.openvpn.minab.MinabBackup
import de.blinkt.openvpn.minab.MinabCrashReporter
import de.blinkt.openvpn.minab.MinabDialogs
import de.blinkt.openvpn.minab.MinabProfileBus
import java.io.File
import java.util.concurrent.Executors

class MinabDiagnosticsActivity : BaseActivity() {

    private lateinit var tabOverview: TextView
    private lateinit var tabCrashes: TextView
    private lateinit var tabAppLog: TextView
    private lateinit var tabOvpnLog: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var contentView: TextView
    private lateinit var saveDocumentLauncher: ActivityResultLauncher<String>

    private var activeTab: Int = TAB_OVERVIEW
    private val ioExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = layoutInflater.inflate(R.layout.minab_diagnostics, null)
        setUpEdgeEdgeInsetsListener(root, R.id.minab_diag_root)
        setContentView(root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.minab_diag_title)
        supportActionBar?.elevation = 0f

        tabOverview = root.findViewById(R.id.minab_diag_tab_overview)
        tabCrashes = root.findViewById(R.id.minab_diag_tab_crashes)
        tabAppLog = root.findViewById(R.id.minab_diag_tab_applog)
        tabOvpnLog = root.findViewById(R.id.minab_diag_tab_ovpnlog)
        scrollView = root.findViewById(R.id.minab_diag_scroll)
        contentView = root.findViewById(R.id.minab_diag_content)

        saveDocumentLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("text/plain")
        ) { uri ->
            if (uri != null) {
                writeReportToUri(uri)
            }
        }

        root.findViewById<View>(R.id.minab_diag_github_card)?.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.minab_diag_github_url))))
            } catch (_: Exception) {
            }
        }

        tabOverview.setOnClickListener { selectTab(TAB_OVERVIEW) }
        tabCrashes.setOnClickListener { selectTab(TAB_CRASHES) }
        tabAppLog.setOnClickListener { selectTab(TAB_APPLOG) }
        tabOvpnLog.setOnClickListener { selectTab(TAB_OVPNLOG) }

        root.findViewById<Button>(R.id.minab_diag_btn_save)?.setOnClickListener {
            saveDocumentLauncher.launch(buildFileName())
        }
        root.findViewById<Button>(R.id.minab_diag_btn_share)?.setOnClickListener {
            shareTxtFile()
        }
        root.findViewById<Button>(R.id.minab_diag_btn_copy)?.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("Minab Diagnostics", contentView.text))
            Toast.makeText(this, R.string.minab_diag_copied, Toast.LENGTH_SHORT).show()
        }
        root.findViewById<Button>(R.id.minab_diag_btn_clear)?.setOnClickListener {
            MinabDialogs.showConfirm(
                context = this,
                title = getString(R.string.minab_crash_clear),
                message = getString(R.string.minab_diag_clear_confirm),
                positiveText = getString(R.string.minab_crash_clear),
                isDanger = true
            ) {
                MinabCrashReporter.clearAll(this)
                renderTabContent()
                MinabProfileBus.notifyChanged()
                Toast.makeText(this, R.string.minab_crash_cleared, Toast.LENGTH_SHORT).show()
            }
        }

        if (MinabCrashReporter.hasSavedCrash(this)) {
            selectTab(TAB_CRASHES)
        } else {
            selectTab(TAB_OVERVIEW)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun buildFileName(): String {
        val ts = MinabBackup.formatTimestampForFile(System.currentTimeMillis())
        return "Minab-DebugLog_v${BuildConfig.VERSION_NAME}_$ts.txt"
    }

    private fun selectTab(tab: Int) {
        activeTab = tab
        val vazir = ResourcesCompat.getFont(this, R.font.vazirmatn)
        val tealDark = ContextCompat.getColor(this, R.color.minab_teal_dark)
        val chipText = ContextCompat.getColor(this, R.color.minab_chip_text)

        val pairs = listOf(
            TAB_OVERVIEW to tabOverview,
            TAB_CRASHES to tabCrashes,
            TAB_APPLOG to tabAppLog,
            TAB_OVPNLOG to tabOvpnLog
        )
        for ((id, view) in pairs) {
            val active = id == tab
            view.setBackgroundResource(if (active) R.drawable.minab_chip_bg_selected else R.drawable.minab_chip_bg)
            view.setTextColor(if (active) tealDark else chipText)
            view.setTypeface(vazir, if (active) Typeface.BOLD else Typeface.NORMAL)
        }
        renderTabContent()
    }

    private fun renderTabContent() {
        val text = when (activeTab) {
            TAB_CRASHES -> MinabCrashReporter.getCrashText(this)
                .ifBlank { getString(R.string.minab_crash_report_empty) }
            TAB_APPLOG -> MinabCrashReporter.getBreadcrumbsText(this)
                .ifBlank { getString(R.string.minab_diag_empty_applog) }
            TAB_OVPNLOG -> {
                val entries = try {
                    VpnStatus.getlogbuffer()
                } catch (_: Exception) {
                    null
                }
                if (entries.isNullOrEmpty()) {
                    getString(R.string.minab_diag_empty_ovpnlog)
                } else {
                    entries.joinToString("\n") { it.getString(this) }
                }
            }
            else -> buildString {
                append(MinabCrashReporter.getSystemOverview(this@MinabDiagnosticsActivity))
                append("\n\n")
                append(getString(R.string.minab_diag_github_howto))
            }
        }
        contentView.text = text
        scrollView.post {
            if (activeTab == TAB_OVPNLOG || activeTab == TAB_APPLOG) {
                scrollView.fullScroll(View.FOCUS_DOWN)
            } else {
                scrollView.fullScroll(View.FOCUS_UP)
            }
        }
    }

    private fun writeReportToUri(uri: Uri) {
        val report = MinabCrashReporter.buildFullReport(this)
        val appCtx = applicationContext
        ioExecutor.execute {
            var ok = false
            try {
                appCtx.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(report.toByteArray(Charsets.UTF_8))
                }
                ok = true
            } catch (_: Exception) {
            }
            val success = ok
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                Toast.makeText(
                    this,
                    if (success) R.string.minab_diag_saved_toast else R.string.minab_diag_save_failed,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun shareTxtFile() {
        val report = MinabCrashReporter.buildFullReport(this)
        val fileName = buildFileName()
        val appCtx = applicationContext
        ioExecutor.execute {
            var outFile: File? = null
            try {
                val f = File(appCtx.cacheDir, fileName)
                f.writeText(report, Charsets.UTF_8)
                outFile = f
            } catch (_: Exception) {
            }
            val target = outFile
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (target == null) {
                    Toast.makeText(this, R.string.minab_diag_save_failed, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val uri = Uri.parse("content://$packageName.FileProvider/${target.name}")
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, fileName)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, getString(R.string.minab_diag_share_txt)))
            }
        }
    }

    companion object {
        private const val TAB_OVERVIEW = 0
        private const val TAB_CRASHES = 1
        private const val TAB_APPLOG = 2
        private const val TAB_OVPNLOG = 3
    }
}
