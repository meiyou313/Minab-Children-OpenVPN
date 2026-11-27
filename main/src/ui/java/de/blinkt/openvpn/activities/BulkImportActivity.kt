/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * BulkImportActivity (v0.9.8): fast, non-freezing batch import of .ovpn/.conf/.zip
 * files & URLs, two-phase import + cancellable GeoIP detection with Skip button,
 * backup inspection preview, and modern MinabDialogs throughout.
 */

package de.blinkt.openvpn.activities

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import de.blinkt.openvpn.R
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.minab.AutoCategory
import de.blinkt.openvpn.minab.BulkImportHelper
import de.blinkt.openvpn.minab.CategoryStore
import de.blinkt.openvpn.minab.ImportSummaryStore
import de.blinkt.openvpn.minab.MinabBackup
import de.blinkt.openvpn.minab.MinabDialogs
import de.blinkt.openvpn.minab.MinabProfileBus
import de.blinkt.openvpn.minab.MinabServerCountry
import de.blinkt.openvpn.minab.SharedCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

class BulkImportActivity : BaseActivity() {
    companion object {
        private const val MAX_ZIP_BYTES = 64 * 1024 * 1024
    }

    private lateinit var usernameField: EditText
    private lateinit var passwordField: EditText
    private lateinit var applyAllBox: CheckBox
    private lateinit var autoCategoryBox: CheckBox
    private lateinit var categorySpinner: Spinner
    private lateinit var loginScopeSpinner: Spinner
    private lateinit var fillSavedLoginView: TextView
    private lateinit var statusView: TextView
    private lateinit var lastSummaryView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var importButton: Button
    private lateinit var zipButton: Button
    private lateinit var applyButton: Button
    private lateinit var urlField: EditText
    private lateinit var fetchButton: Button
    private lateinit var restoreButton: Button
    private lateinit var rootScroll: ScrollView
    private lateinit var resultCard: View
    private lateinit var resultIcon: ImageView
    private lateinit var resultTitle: TextView
    private lateinit var skipGeoIpButton: Button
    private lateinit var doneButton: Button
    private lateinit var footerBackButton: Button

    private var restoreSummary: String? = null
    private var selectedCategory: String? = null
    private var selectedCount: Int = 0
    private var lastImportedUuids: Set<String> = emptySet()
    private val geoCancelled = AtomicBoolean(false)

    private val pickMultiple = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> onFilesPicked(uris) }

    private val pickZip = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onZipPicked(uri) }

    private val pickRestore = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onRestorePicked(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.bulk_import)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        rootScroll = findViewById(R.id.minab_bulk_root)
        resultCard = findViewById(R.id.minab_bulk_result_card)
        resultIcon = findViewById(R.id.minab_bulk_result_icon)
        resultTitle = findViewById(R.id.minab_bulk_result_title)
        skipGeoIpButton = findViewById(R.id.minab_bulk_skip_geoip)
        doneButton = findViewById(R.id.minab_bulk_done_btn)
        footerBackButton = findViewById(R.id.minab_bulk_footer_back)
        usernameField = findViewById(R.id.minab_bulk_username)
        passwordField = findViewById(R.id.minab_bulk_password)
        applyAllBox = findViewById(R.id.minab_bulk_apply_all)
        autoCategoryBox = findViewById(R.id.minab_bulk_auto_category)
        categorySpinner = findViewById(R.id.minab_bulk_category)
        loginScopeSpinner = findViewById(R.id.minab_bulk_login_scope)
        fillSavedLoginView = findViewById(R.id.minab_bulk_fill_saved)
        statusView = findViewById(R.id.minab_bulk_status)
        lastSummaryView = findViewById(R.id.minab_bulk_last_summary)
        progressBar = findViewById(R.id.minab_bulk_progress)
        importButton = findViewById(R.id.minab_bulk_pick)
        zipButton = findViewById(R.id.minab_bulk_pick_zip)
        applyButton = findViewById(R.id.minab_bulk_apply_credentials)
        urlField = findViewById(R.id.minab_bulk_url)
        fetchButton = findViewById(R.id.minab_bulk_fetch_url)
        restoreButton = findViewById(R.id.minab_bulk_restore)

        doneButton.setOnClickListener { finish() }
        footerBackButton.setOnClickListener { finish() }
        skipGeoIpButton.setOnClickListener {
            geoCancelled.set(true)
            skipGeoIpButton.isEnabled = false
        }

        val root = findViewById<View>(R.id.minab_bulk_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = bars.top }
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bars.bottom)
            insets
        }

        lastImportedUuids = MinabProfileBus.getLastImportedUuids(this)
        updateFillSavedPill()
        setupLoginScopeSpinner()

        ImportSummaryStore.formatLastSummary(this)?.let { summary ->
            lastSummaryView.text = getString(R.string.minab_last_import, summary)
        }

        refreshCategorySpinner(null)
        categorySpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (pos == 0) {
                    showNewCategoryDialog { name -> refreshCategorySpinner(name) }
                    return
                }
                selectedCategory = if (pos == 1) null else categorySpinnerItems()[pos]
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })

        importButton.setOnClickListener {
            pickMultiple.launch(arrayOf("application/x-openvpn-profile", "application/octet-stream", "*/*"))
        }
        zipButton.setOnClickListener {
            pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
        }
        applyButton.setOnClickListener { applySharedCredentials() }
        fetchButton.setOnClickListener { onFetchUrlClicked() }
        restoreButton.setOnClickListener {
            pickRestore.launch(arrayOf("application/zip", "*/*"))
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        geoCancelled.set(true)
        if (::passwordField.isInitialized) passwordField.setText("")
        super.onDestroy()
    }

    private fun updateFillSavedPill() {
        if (!::fillSavedLoginView.isInitialized) return
        if (SharedCredentials.hasCredentials(this)) {
            val savedUser = SharedCredentials.getUsername(this)
            fillSavedLoginView.visibility = View.VISIBLE
            fillSavedLoginView.text = getString(R.string.minab_fill_saved_login, savedUser)
            fillSavedLoginView.setOnClickListener {
                usernameField.setText(SharedCredentials.getUsername(this))
                passwordField.setText(SharedCredentials.getPassword(this))
            }
        } else {
            fillSavedLoginView.visibility = View.GONE
        }
    }

    private fun setupLoginScopeSpinner() {
        val items = listOf(
            getString(R.string.minab_bulk_login_scope_last),
            getString(R.string.minab_bulk_login_scope_group),
            getString(R.string.minab_bulk_login_scope_pick),
            getString(R.string.minab_bulk_login_scope_all)
        )
        val adapter = ArrayAdapter(this, R.layout.minab_spinner_item, items)
        adapter.setDropDownViewResource(R.layout.minab_spinner_dropdown_item)
        loginScopeSpinner.adapter = adapter
        loginScopeSpinner.setSelection(0)
    }

    private fun applySharedCredentials() {
        val username = usernameField.text.toString()
        val password = passwordField.text.toString()
        if (username.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, R.string.minab_credentials_empty, Toast.LENGTH_SHORT).show()
            return
        }
        when (loginScopeSpinner.selectedItemPosition) {
            0 -> {
                val ids = if (lastImportedUuids.isNotEmpty()) {
                    lastImportedUuids
                } else {
                    MinabProfileBus.getLastImportedUuids(this)
                }
                val all = ProfileManager.getInstance(this).getProfiles()
                val targets = all.filter { ids.contains(it.getUUIDString()) }
                if (targets.isEmpty()) {
                    Toast.makeText(this, R.string.minab_bulk_no_last_import, Toast.LENGTH_SHORT).show()
                    return
                }
                executeCredentialApply(username, password) {
                    SharedCredentials.applyToProfiles(this, targets, username, password)
                }
            }
            1 -> showGroupScopePicker { chosenGroup ->
                executeCredentialApply(username, password) {
                    SharedCredentials.applyToCategory(this, chosenGroup, username, password)
                }
            }
            2 -> showManualConfigPicker { chosenProfiles ->
                executeCredentialApply(username, password) {
                    SharedCredentials.applyToProfiles(this, chosenProfiles, username, password)
                }
            }
            else -> executeCredentialApply(username, password) {
                SharedCredentials.applyToAllProfiles(this, username, password)
            }
        }
    }

    private fun showGroupScopePicker(onPicked: (String) -> Unit) {
        val groups = CategoryStore.getOrderedCategories(this)
        if (groups.isEmpty()) {
            Toast.makeText(this, R.string.minab_no_profiles_yet, Toast.LENGTH_SHORT).show()
            return
        }
        MinabDialogs.showSingleChoice(
            context = this,
            title = getString(R.string.minab_bulk_login_scope_group),
            options = groups,
            iconRes = R.drawable.ic_baseline_folder_24
        ) { which ->
            onPicked(groups[which])
        }
    }

    private fun showManualConfigPicker(onPicked: (List<VpnProfile>) -> Unit) {
        val all = ProfileManager.getInstance(this).getProfiles().toList()
        if (all.isEmpty()) {
            Toast.makeText(this, R.string.minab_no_profiles_yet, Toast.LENGTH_SHORT).show()
            return
        }
        val options = all.map {
            MinabDialogs.MultiChoiceOption(
                label = it.mName ?: "",
                sublabel = "[${it.getGroupsDisplayText()}]",
                checked = false
            )
        }
        MinabDialogs.showMultiChoice(
            context = this,
            title = getString(R.string.minab_bulk_pick_configs_title),
            options = options,
            positiveText = getString(R.string.minab_apply_credentials),
            showSelectAll = true
        ) { selectedIndices, _ ->
            val chosen = selectedIndices.map { all[it] }
            if (chosen.isNotEmpty()) onPicked(chosen)
        }
    }

    private fun executeCredentialApply(
        username: String,
        password: String,
        worker: () -> Int
    ) {
        applyButton.isEnabled = false
        val saveDefault = applyAllBox.isChecked
        lifecycleScope.launch(Dispatchers.IO) {
            if (saveDefault) {
                SharedCredentials.save(this@BulkImportActivity, username, password)
            }
            val count = worker()
            withContext(Dispatchers.Main) {
                applyButton.isEnabled = true
                updateFillSavedPill()
                MinabProfileBus.notifyChanged()
                val message = if (count > 0) {
                    getString(R.string.minab_credentials_applied, count)
                } else {
                    getString(R.string.minab_credentials_none_applied)
                }
                showResultCard(
                    success = count > 0,
                    title = getString(R.string.minab_bulk_done_title),
                    detail = message
                )
                Toast.makeText(this@BulkImportActivity, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun options(batchUsername: String?, batchPassword: String?): BulkImportHelper.ImportOptions {
        val o = BulkImportHelper.ImportOptions()
        o.deferSaveList = true
        if (!batchUsername.isNullOrEmpty() && !batchPassword.isNullOrEmpty()) {
            o.username = batchUsername
            o.password = batchPassword
            o.applyCredentialsToAll = applyAllBox.isChecked
            if (o.applyCredentialsToAll) {
                SharedCredentials.save(this, batchUsername, batchPassword)
                updateFillSavedPill()
            }
        }
        o.category = selectedCategory
        return o
    }

    private fun categorySpinnerItems(): List<String> {
        val groups = CategoryStore.getCategories(this)
            .filter { it != CategoryStore.DEFAULT_CATEGORY }
        return listOf(
            getString(R.string.minab_import_create_group),
            getString(R.string.minab_import_category_all)
        ) + groups
    }

    private fun refreshCategorySpinner(selectCategory: String?) {
        val items = categorySpinnerItems()
        val adapter = ArrayAdapter(this, R.layout.minab_spinner_item, items)
        adapter.setDropDownViewResource(R.layout.minab_spinner_dropdown_item)
        categorySpinner.adapter = adapter
        selectedCategory = selectCategory
        val pos = when {
            selectCategory == null -> 1
            else -> items.indexOf(selectCategory).takeIf { it >= 0 } ?: 1
        }
        categorySpinner.setSelection(pos)
    }

    private fun showNewCategoryDialog(onCreated: (String) -> Unit) {
        MinabDialogs.showTextInput(
            context = this,
            title = getString(R.string.minab_new_category),
            hint = getString(R.string.minab_group_name_hint),
            onCancel = { refreshCategorySpinner(selectedCategory) }
        ) { raw ->
            val name = CategoryStore.normalise(raw)
            CategoryStore.addManualCategory(this, name)
            onCreated(name)
        }
    }

    private fun onFilesPicked(uris: List<Uri>) {
        if (uris.isEmpty()) {
            Toast.makeText(this, R.string.minab_no_files_picked, Toast.LENGTH_SHORT).show()
            return
        }
        maybeAskCredentialsThen { user, pass ->
            startFilesImport(uris, user, pass)
        }
    }

    private fun maybeAskCredentialsThen(onProceed: (String?, String?) -> Unit) {
        val typedUser = usernameField.text.toString()
        val typedPass = passwordField.text.toString()
        if (typedUser.isNotBlank() && typedPass.isNotBlank()) {
            onProceed(typedUser, typedPass)
        } else {
            MinabDialogs.showCredentialsDialog(
                context = this,
                title = getString(R.string.minab_bulk_credentials_title),
                scopeSummary = getString(R.string.minab_bulk_credentials_scope_new),
                isAllScopeWarning = false,
                showSaveDefaultOption = true,
                initialSaveDefault = applyAllBox.isChecked,
                positiveText = getString(R.string.minab_bulk_credentials_import),
                negativeText = getString(R.string.minab_bulk_credentials_skip),
                onConfirm = { user, pass, saveDefault ->
                    applyAllBox.isChecked = saveDefault
                    onProceed(user.takeIf { it.isNotBlank() }, pass.takeIf { it.isNotBlank() })
                },
                onCancel = { onProceed(null, null) }
            )
        }
    }

    private fun startFilesImport(uris: List<Uri>, batchUser: String?, batchPass: String?) {
        selectedCount = uris.size
        runImport(batchUser, batchPass) { result, opts ->
            var done = 0
            for (uri in uris) {
                val name = displayName(uri)
                var stream: InputStream? = null
                try {
                    stream = contentResolver.openInputStream(uri)
                    if (stream != null) {
                        if (isZipName(name)) {
                            val bytes = readAllBytes(stream, MAX_ZIP_BYTES)
                            if (bytes == null) {
                                result.failed++
                                result.failedNames.add(name ?: uri.toString())
                            } else {
                                importZipBytes(bytes, opts, result)
                            }
                        } else {
                            BulkImportHelper.importOne(this, stream, name, opts, result)
                        }
                    } else {
                        result.failed++
                        result.failedNames.add(name ?: uri.toString())
                    }
                } catch (e: Exception) {
                    result.failed++
                    result.failedNames.add(name ?: uri.toString())
                } finally {
                    try { stream?.close() } catch (_: Exception) { }
                }
                done++
                withContext(Dispatchers.Main) { updateProgress(done, uris.size) }
                if (result.imported + result.failed >= BulkImportHelper.MAX_FILES_PER_IMPORT) break
            }
            result
        }
    }

    private fun onZipPicked(uri: Uri) {
        maybeAskCredentialsThen { batchUser, batchPass ->
            selectedCount = 0
            runImport(batchUser, batchPass) { result, opts ->
                var stream: InputStream? = null
                try {
                    stream = contentResolver.openInputStream(uri)
                    if (stream == null) {
                        result.failed++
                        result.failedNames.add(displayName(uri) ?: uri.toString())
                    } else {
                        val bytes = readAllBytes(stream, MAX_ZIP_BYTES)
                        if (bytes == null) {
                            result.failed++
                            result.failedNames.add(displayName(uri) ?: uri.toString())
                        } else {
                            val totalInZip = BulkImportHelper.countZipOvpnEntries(bytes).coerceAtLeast(1)
                            selectedCount = totalInZip
                            importZipBytes(bytes, opts, result) { processed ->
                                runOnUiThread {
                                    updateProgress(processed, totalInZip)
                                }
                            }
                        }
                    }
                } finally {
                    try { stream?.close() } catch (_: Exception) { }
                }
                result
            }
        }
    }

    private fun updateProgress(done: Int, total: Int) {
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = false
        progressBar.max = total.coerceAtLeast(1)
        progressBar.progress = done.coerceAtMost(total)
        statusView.text = getString(R.string.minab_import_progress, done, total)
    }

    private fun runImport(
        batchUser: String?,
        batchPass: String?,
        work: suspend (BulkImportHelper.ImportResult, BulkImportHelper.ImportOptions) -> BulkImportHelper.ImportResult
    ) {
        geoCancelled.set(false)
        setBusy(true)
        resultCard.visibility = View.VISIBLE
        resultCard.setBackgroundResource(R.drawable.minab_card_bg)
        resultIcon.visibility = View.GONE
        skipGeoIpButton.visibility = View.GONE
        skipGeoIpButton.isEnabled = true
        doneButton.visibility = View.GONE
        resultTitle.text = getString(R.string.minab_importing)
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = false
        progressBar.max = 100
        progressBar.progress = 0
        statusView.text = getString(R.string.minab_importing)
        rootScroll.post { rootScroll.smoothScrollTo(0, 0) }
        val opts = options(batchUser, batchPass)
        val autoCategorise = autoCategoryBox.isChecked
        lifecycleScope.launch(Dispatchers.IO) {
            val result = BulkImportHelper.ImportResult()
            try {
                work(result, opts)
            } catch (e: Exception) {
                result.failed++
                result.failedNames.add(e.message ?: "import error")
            }

            // Commit imported profile list ONCE at end of Phase 1 so navigating Home
            // immediately sees 100% consistent profiles (no half-imported disappearing rows).
            try {
                ProfileManager.getInstance(this@BulkImportActivity)
                    .saveProfileList(this@BulkImportActivity)
            } catch (_: Exception) {
            }

            if (result.importedProfiles.isNotEmpty()) {
                val uuids = result.importedProfiles.map { it.getUUIDString() }.toSet()
                lastImportedUuids = uuids
                MinabProfileBus.setLastImportedUuids(this@BulkImportActivity, uuids)
                withContext(Dispatchers.Main) {
                    setResult(Activity.RESULT_OK)
                    MinabProfileBus.notifyChanged()
                }
            }

            // Phase 2: Cancellable Batch HTTPS GeoIP auto-detection with Skip button
            if (autoCategorise && result.importedProfiles.isNotEmpty() && !geoCancelled.get()) {
                withContext(Dispatchers.Main) {
                    skipGeoIpButton.visibility = View.VISIBLE
                    progressBar.isIndeterminate = true
                    statusView.text = getString(
                        R.string.minab_import_categorising, 0, result.importedProfiles.size
                    )
                }
                try {
                    AutoCategory.detectCategoriesForProfiles(
                        this@BulkImportActivity,
                        result.importedProfiles,
                        false,
                        geoCancelled
                    ) { profile, code, category, done, total ->
                        if (!code.isNullOrBlank()) {
                            MinabServerCountry.put(
                                this@BulkImportActivity, profile.getUUIDString(), code
                            )
                        }
                        if (opts.category == null && !category.isNullOrBlank()) {
                            try {
                                profile.setCategory(category)
                                CategoryStore.addCategory(this@BulkImportActivity, category)
                                ProfileManager.saveProfile(this@BulkImportActivity, profile)
                            } catch (_: Exception) {
                            }
                        }
                        runOnUiThread {
                            progressBar.isIndeterminate = false
                            progressBar.max = total
                            progressBar.progress = done
                            statusView.text = getString(
                                R.string.minab_import_categorising, done, total
                            )
                        }
                    }
                    ProfileManager.getInstance(this@BulkImportActivity)
                        .saveProfileList(this@BulkImportActivity)
                } catch (_: Exception) {
                }
            }

            withContext(Dispatchers.Main) {
                setBusy(false)
                progressBar.visibility = View.GONE
                skipGeoIpButton.visibility = View.GONE
                val restored = restoreSummary
                restoreSummary = null
                val summary = restored ?: getString(
                    R.string.minab_bulk_done_detail,
                    result.imported, result.skippedDuplicates, result.failed
                )
                val extra = if (result.failedNames.isNotEmpty()) {
                    "\n" + result.failedNames.take(6).joinToString("\n")
                } else ""
                val isSuccess = result.imported > 0 || restored != null
                showResultCard(
                    success = isSuccess,
                    title = getString(
                        if (isSuccess) R.string.minab_bulk_done_title
                        else R.string.minab_bulk_done_partial
                    ),
                    detail = summary + extra
                )
                ImportSummaryStore.save(
                    this@BulkImportActivity, selectedCount,
                    result.imported, result.skippedDuplicates,
                    result.failed, result.failedNames
                )
                ImportSummaryStore.formatLastSummary(this@BulkImportActivity)?.let { stored ->
                    lastSummaryView.text = getString(R.string.minab_last_import, stored)
                }
                if (result.imported > 0 || restored != null) {
                    setResult(Activity.RESULT_OK)
                }
                refreshCategorySpinner(selectedCategory)
                MinabProfileBus.notifyChanged()
            }
        }
    }

    private fun showResultCard(success: Boolean, title: String, detail: String) {
        resultCard.visibility = View.VISIBLE
        resultCard.setBackgroundResource(
            if (success) R.drawable.minab_bulk_success_bg else R.drawable.minab_card_bg
        )
        resultIcon.visibility = if (success) View.VISIBLE else View.GONE
        resultTitle.text = title
        statusView.text = detail
        doneButton.visibility = View.VISIBLE
        rootScroll.post { rootScroll.smoothScrollTo(0, 0) }
    }

    private fun setBusy(busy: Boolean) {
        importButton.isEnabled = !busy
        zipButton.isEnabled = !busy
        applyButton.isEnabled = !busy
        fetchButton.isEnabled = !busy
        restoreButton.isEnabled = !busy
    }

    private fun importZipBytes(
        bytes: ByteArray,
        opts: BulkImportHelper.ImportOptions,
        result: BulkImportHelper.ImportResult,
        onProgress: ((Int) -> Unit)? = null
    ) {
        if (MinabBackup.hasMetadata(bytes)) {
            val restore = MinabBackup.restoreBackup(this, bytes)
            result.imported += restore.imported
            result.skippedDuplicates += restore.skippedDuplicates
            result.failed += restore.failed
            if (restore.error != null) result.failedNames.add(restore.error!!)
            if (restore.wasBackup) {
                restoreSummary = getString(R.string.minab_restore_done, restore.profilesRestored)
            }
            return
        }
        val listener = if (onProgress == null) null else
            BulkImportHelper.ProgressListener { processed -> onProgress(processed) }
        val zipResult = BulkImportHelper.importZip(this, ByteArrayInputStream(bytes), opts, listener)
        result.imported += zipResult.imported
        result.skippedDuplicates += zipResult.skippedDuplicates
        result.failed += zipResult.failed
        result.failedNames.addAll(zipResult.failedNames)
        result.importedProfiles.addAll(zipResult.importedProfiles)
    }

    private fun readAllBytes(stream: InputStream, maxBytes: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = stream.read(buf)
            if (n == -1) break
            total += n
            if (total > maxBytes) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun onFetchUrlClicked() {
        val url = urlField.text.toString().trim()
        if (url.isEmpty() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            Toast.makeText(this, R.string.minab_link_failed, Toast.LENGTH_SHORT).show()
            return
        }
        maybeAskCredentialsThen { batchUser, batchPass ->
            selectedCount = 0
            runImport(batchUser, batchPass) { result, opts ->
                val bytes = downloadUrl(url)
                if (bytes == null) {
                    result.failed++
                    result.failedNames.add(url)
                } else if (isZipBytes(bytes)) {
                    importZipBytes(bytes, opts, result)
                } else {
                    BulkImportHelper.importOne(this, ByteArrayInputStream(bytes), urlName(url), opts, result)
                }
                result
            }
        }
    }

    private fun downloadUrl(url: String): ByteArray? {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Minab")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            return readAllBytes(conn.inputStream, MAX_ZIP_BYTES)
        } catch (_: Exception) {
            return null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) { }
        }
    }

    private fun isZipBytes(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()

    private fun urlName(url: String): String {
        val last = url.substringAfterLast('/').substringBefore('?').substringBefore('#')
        return if (last.isEmpty()) "link.ovpn" else last
    }

    private fun onRestorePicked(uri: Uri) {
        setBusy(true)
        lifecycleScope.launch(Dispatchers.IO) {
            val bytes = try {
                contentResolver.openInputStream(uri)?.use { readAllBytes(it, MAX_ZIP_BYTES) }
            } catch (_: Exception) {
                null
            }
            if (bytes == null || !MinabBackup.hasMetadata(bytes)) {
                val msg = getString(
                    if (bytes == null) R.string.minab_restore_failed
                    else R.string.minab_restore_no_metadata
                )
                withContext(Dispatchers.Main) {
                    setBusy(false)
                    showResultCard(false, getString(R.string.minab_bulk_done_partial), msg)
                }
                return@launch
            }
            val preview = MinabBackup.inspectBackup(bytes)
            withContext(Dispatchers.Main) {
                setBusy(false)
                val datePart = preview.exportedAtFormatted.ifEmpty { "—" }
                val msg = getString(
                    R.string.minab_restore_preview_msg,
                    preview.scopeLabel,
                    preview.profileCount,
                    datePart
                )
                MinabDialogs.showConfirm(
                    context = this@BulkImportActivity,
                    title = getString(R.string.minab_restore_preview_title),
                    message = msg,
                    positiveText = getString(R.string.minab_restore_backup)
                ) {
                    executeRestoreBytes(bytes)
                }
            }
        }
    }

    private fun executeRestoreBytes(bytes: ByteArray) {
        setBusy(true)
        resultCard.visibility = View.VISIBLE
        resultCard.setBackgroundResource(R.drawable.minab_card_bg)
        resultIcon.visibility = View.GONE
        skipGeoIpButton.visibility = View.GONE
        doneButton.visibility = View.GONE
        resultTitle.text = getString(R.string.minab_importing)
        statusView.text = getString(R.string.minab_importing)
        lifecycleScope.launch(Dispatchers.IO) {
            var message: String
            var ok = false
            try {
                val restore = MinabBackup.restoreBackup(this@BulkImportActivity, bytes)
                ok = restore.error == null
                message = if (restore.error != null) getString(R.string.minab_restore_failed)
                    else getString(R.string.minab_restore_done, restore.profilesRestored)
                if (restore.imported > 0) setResult(Activity.RESULT_OK)
            } catch (_: Exception) {
                message = getString(R.string.minab_restore_failed)
            }
            withContext(Dispatchers.Main) {
                setBusy(false)
                showResultCard(
                    success = ok,
                    title = getString(
                        if (ok) R.string.minab_bulk_done_title
                        else R.string.minab_bulk_done_partial
                    ),
                    detail = message
                )
                updateFillSavedPill()
                refreshCategorySpinner(selectedCategory)
                MinabProfileBus.notifyChanged()
            }
        }
    }

    private fun displayName(uri: Uri): String? {
        var cursor: android.database.Cursor? = null
        try {
            cursor = contentResolver.query(uri, null, null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx != -1) return cursor.getString(idx)
            }
        } catch (_: Exception) {
        } finally {
            try { cursor?.close() } catch (_: Exception) { }
        }
        return uri.lastPathSegment
    }

    private fun isZipName(name: String?): Boolean {
        return name != null && name.lowercase().endsWith(".zip")
    }
}
