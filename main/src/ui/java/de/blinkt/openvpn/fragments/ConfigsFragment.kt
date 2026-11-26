/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * ConfigsFragment: the CONFIGS tab — virtualised RecyclerView hosting the
 * Configs header (view type 0) and recycled config rows (view type 1), with
 * the shared dedicated Test Progress panel and MinabTestCoordinator support.
 */

package de.blinkt.openvpn.fragments

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import de.blinkt.openvpn.LaunchVPN
import de.blinkt.openvpn.R
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.activities.VPNPreferences
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VpnStatus
import de.blinkt.openvpn.minab.AutoCategory
import de.blinkt.openvpn.minab.CategoryStore
import de.blinkt.openvpn.minab.FlagHelper
import de.blinkt.openvpn.minab.MinabBackup
import de.blinkt.openvpn.minab.MinabCategoryReorder
import de.blinkt.openvpn.minab.MinabConnectState
import de.blinkt.openvpn.minab.MinabFavorites
import de.blinkt.openvpn.minab.MinabProfileBus
import de.blinkt.openvpn.minab.MinabRealTestSettings
import de.blinkt.openvpn.minab.MinabRealTestSettingsPanel
import de.blinkt.openvpn.minab.MinabRedetectIpDialog
import de.blinkt.openvpn.minab.MinabServerCountry
import de.blinkt.openvpn.minab.MinabSortDialog
import de.blinkt.openvpn.minab.MinabTestCoordinator
import de.blinkt.openvpn.minab.MinabTestHistory
import de.blinkt.openvpn.minab.MinabTestHistoryDialog
import de.blinkt.openvpn.minab.MinabTestProgressPanel
import de.blinkt.openvpn.minab.MinabVpnControl
import de.blinkt.openvpn.minab.PingTester
import de.blinkt.openvpn.minab.RealTester
import de.blinkt.openvpn.minab.SharedCredentials
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import kotlin.math.min

class ConfigsFragment : Fragment(), VpnStatus.StateListener {
    private var selectedCategory: String? = null
    private var lastLevel: ConnectionStatus? = null
    private var lastObservedActive: Boolean = false
    private var lastObservedUuid: String? = null

    private var recyclerView: RecyclerView? = null
    private var headerView: View? = null
    private var emptyView: View? = null
    private var configsAdapter: ConfigsRecyclerAdapter? = null
    private var displayedProfiles: MutableList<VpnProfile> = mutableListOf()
    private var cachedFavorites: Set<String> = emptySet()

    private var chipsContainer: LinearLayout? = null
    private lateinit var backupLauncher: ActivityResultLauncher<String>

    private var searchQuery: String = ""
    private val searchDebounceHandler = Handler(Looper.getMainLooper())
    private val searchDebounceRunnable = Runnable { refreshList() }
    private var chipsSignature: String? = null
    private var lastListSignature: String? = null

    private val selectedUuids = mutableSetOf<String>()
    private var selectionRow: View? = null
    private var credentialsSmartButton: Button? = null
    private var selectAllButton: Button? = null
    private var sortButton: Button? = null
    private var searchEdit: EditText? = null
    private var searchClearBtn: ImageButton? = null
    private var shareButton: Button? = null
    private var shareSelectedButton: Button? = null

    // --- Server tests (Ping test & Real test) ---
    private var pingTester: PingTester? = null
    private var pingDoneCount: Int = 0
    private var pingTotalCount: Int = 0
    private var pingOkCount: Int = 0
    private var pingLatestName: String? = null

    private var testProgressPanel: MinabTestProgressPanel? = null
    private var realTestActive: Boolean = false
    private var realTester: RealTester? = null
    private var realTotalCount: Int = 0
    private var realTargetWorking: Int = 5
    private var realPingPhaseActive: Boolean = false
    private var realPingOkCount: Int = 0
    private var realTestSettingsPanel: MinabRealTestSettingsPanel? = null
    private var sortMode: Int = MinabProfileBus.SORT_REAL
    private var currentTestNetSnapshot: MinabTestHistory.NetworkSnapshot? = null
    private var realTestRunId = 0

    private val busListener = MinabProfileBus.Listener {
        if (isAdded) {
            refreshFromBus()
        }
    }

    private fun refreshFromBus() {
        if (!isAdded) return
        sortMode = MinabProfileBus.getSortMode(requireContext())
        updateSortButtonUi()
        realTestSettingsPanel?.refreshIfVisible()
        lastListSignature = null
        chipsSignature = null
        refreshList()
    }

    private val realCountdownHandler = Handler(Looper.getMainLooper())
    private var realCountdownSeconds: Int = 0
    private var realCountdownName: String? = null
    private var realCountdownDone: Int = 0
    private var realCountdownTotal: Int = 0
    private var realCountdownWorking: Int = 0

    private val listRefreshHandler = Handler(Looper.getMainLooper())
    private var listRefreshPending = false
    private val listRefreshRunnable = Runnable {
        listRefreshPending = false
        if (isAdded) configsAdapter?.submitList(displayedProfiles)
    }

    private fun requestListRefresh() {
        if (listRefreshPending) return
        listRefreshPending = true
        listRefreshHandler.postDelayed(listRefreshRunnable, 350)
    }

    private val realCountdownRunnable: Runnable = Runnable {
        if (realTestActive && realCountdownSeconds > 0) {
            realCountdownSeconds--
            updateRealCountdownLine()
            realCountdownHandler.postDelayed(realCountdownRunnable, 1000)
        }
    }

    private val testPauseHandler = Handler(Looper.getMainLooper())
    private val testPauseStop = Runnable {
        if (realTestActive) {
            cancelRealTest()
            try {
                Toast.makeText(requireContext(), R.string.minab_real_cancelled, Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sortMode = MinabProfileBus.getSortMode(requireContext())
        backupLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip")
        ) { uri: Uri? ->
            if (uri != null) exportBackup(uri)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.configs_fragment, container, false)
        try {
            val rv = root.findViewById<RecyclerView>(R.id.minab_configs_recycler)
            recyclerView = rv
            // CRITICAL: LayoutManager MUST be assigned BEFORE inflating any
            // child layout with rv as parent (RecyclerView.generateLayoutParams
            // throws IllegalStateException when mLayout is null).
            rv.layoutManager = LinearLayoutManager(requireContext())
            rv.setHasFixedSize(true)
            rv.itemAnimator = null
            rv.setItemViewCacheSize(12)

            val header = inflater.inflate(R.layout.configs_fragment_header, rv, false)
            headerView = header
            val empty = inflater.inflate(R.layout.configs_fragment_empty, rv, false)
            emptyView = empty

            chipsContainer = header.findViewById(R.id.minab_configs_chips)
            credentialsSmartButton = header.findViewById(R.id.minab_configs_credentials_smart)
            credentialsSmartButton?.setOnClickListener {
                when {
                    selectedUuids.isNotEmpty() -> showCredentialsForSelectionDialog(selectedProfiles())
                    selectedCategory != null && !CategoryStore.isPseudoTab(selectedCategory) ->
                        showCredentialsDialog(selectedCategory)
                    else -> showCredentialsDialog(null)
                }
            }
            header.findViewById<Button>(R.id.minab_configs_backup)?.setOnClickListener {
                val profiles = ProfileManager.getInstance(requireContext()).getProfiles()
                if (profiles.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.minab_backup_none, Toast.LENGTH_SHORT).show()
                } else {
                    backupLauncher.launch("minab-backup.ovpn.zip")
                }
            }
            shareButton = header.findViewById(R.id.minab_configs_share)
            shareButton?.setOnClickListener { shareCurrentScope() }
            selectAllButton = header.findViewById(R.id.minab_configs_select_all)
            selectAllButton?.setOnClickListener { toggleSelectAllDisplayed() }
            header.findViewById<Button>(R.id.minab_configs_redetect_ip)?.setOnClickListener {
                val all = ProfileManager.getInstance(requireContext()).getProfiles().toList()
                MinabRedetectIpDialog.show(requireContext(), all, selectedProfiles(), selectedCategory)
            }
            header.findViewById<Button>(R.id.minab_configs_dedup)?.setOnClickListener {
                showCleanupMenuDialog()
            }
            selectionRow = header.findViewById(R.id.minab_configs_selection_row)
            shareSelectedButton = header.findViewById(R.id.minab_configs_share_selected)
            header.findViewById<Button>(R.id.minab_configs_delete_selected)?.setOnClickListener {
                showDeleteSelectedDialog()
            }
            header.findViewById<Button>(R.id.minab_configs_copy_selected)?.setOnClickListener {
                showCopyMoveDialog(true)
            }
            header.findViewById<Button>(R.id.minab_configs_move_selected)?.setOnClickListener {
                showCopyMoveDialog(false)
            }
            shareSelectedButton?.setOnClickListener {
                shareProfiles(selectedProfiles())
            }
            header.findViewById<Button>(R.id.minab_configs_test_ping)?.setOnClickListener {
                startPingTest(visibleProfiles())
            }
            header.findViewById<Button>(R.id.minab_configs_test_real)?.setOnClickListener {
                startRealTest(visibleProfiles())
            }

            val realTestSettingsButton =
                header.findViewById<ImageButton>(R.id.minab_configs_btn_real_test_settings)
            val realTestSettingsPanelView =
                header.findViewById<View>(R.id.minab_real_settings_panel)
            if (realTestSettingsButton != null && realTestSettingsPanelView != null) {
                realTestSettingsPanel = MinabRealTestSettingsPanel(
                    requireContext(), realTestSettingsButton, realTestSettingsPanelView
                )
            }

            header.findViewById<ImageButton>(R.id.minab_configs_btn_test_history)?.setOnClickListener {
                MinabTestHistoryDialog.show(requireContext()) { chosen ->
                    startVPN(chosen)
                }
            }

            val testPanelRoot = header.findViewById<View>(R.id.minab_test_panel)
            if (testPanelRoot != null) {
                testProgressPanel = MinabTestProgressPanel(requireContext(), testPanelRoot) {
                    if (realTestActive) {
                        cancelRealTest()
                        Toast.makeText(requireContext(), R.string.minab_real_cancelled, Toast.LENGTH_SHORT).show()
                    } else if (pingTester != null) {
                        cancelPingTest()
                        Toast.makeText(requireContext(), R.string.minab_ping_cancelled, Toast.LENGTH_SHORT).show()
                    }
                }
            }

            sortButton = header.findViewById(R.id.minab_configs_btn_sort)
            sortButton?.setOnClickListener {
                MinabSortDialog.show(requireContext(), sortMode) { picked ->
                    sortMode = picked
                    updateSortButtonUi()
                    lastListSignature = null
                    refreshList()
                }
            }
            updateSortButtonUi()

            searchEdit = header.findViewById(R.id.minab_configs_search)
            searchClearBtn = header.findViewById(R.id.minab_configs_search_clear)
            searchClearBtn?.setOnClickListener {
                searchEdit?.setText("")
            }
            searchEdit?.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    searchQuery = s?.toString()?.trim() ?: ""
                    searchClearBtn?.visibility = if (searchQuery.isEmpty()) View.GONE else View.VISIBLE
                    searchDebounceHandler.removeCallbacks(searchDebounceRunnable)
                    searchDebounceHandler.postDelayed(searchDebounceRunnable, 180)
                }
            })

            MinabProfileBus.addListener(busListener)

            configsAdapter = ConfigsRecyclerAdapter(header, empty)
            rv.adapter = configsAdapter

            // Populate immediately while created offscreen so the first frame
            // during a Home -> Configs tab slide already shows populated rows.
            lastListSignature = null
            chipsSignature = null
            refreshList()
        } catch (e: Exception) {
            android.util.Log.e("ConfigsFragment", "Minab: onCreateView failed", e)
        }
        return root
    }

    override fun onResume() {
        super.onResume()
        VpnStatus.addStateListener(this)
        testPauseHandler.removeCallbacks(testPauseStop)
        sortMode = MinabProfileBus.getSortMode(requireContext())
        updateSortButtonUi()
        realTestSettingsPanel?.refreshIfVisible()
        lastListSignature = null
        chipsSignature = null
        refreshList()
    }

    override fun updateState(
        state: String?,
        logmessage: String?,
        localizedResId: Int,
        level: ConnectionStatus?,
        intent: Intent?
    ) {
        val activeNow = VpnStatus.isVPNActive()
        val uuidNow = VpnStatus.getLastConnectedVPNProfile()
        if (level == lastLevel && activeNow == lastObservedActive && uuidNow == lastObservedUuid) return
        lastLevel = level
        lastObservedActive = activeNow
        lastObservedUuid = uuidNow
        if (level == ConnectionStatus.LEVEL_CONNECTED ||
            level == ConnectionStatus.LEVEL_NOTCONNECTED
        ) {
            MinabConnectState.clearPendingConnect()
        }
        activity?.runOnUiThread {
            if (isAdded) configsAdapter?.notifyRowsUpdated()
        }
    }

    override fun setConnectedVPN(uuid: String?) {
    }

    override fun onPause() {
        super.onPause()
        VpnStatus.removeStateListener(this)
        searchDebounceHandler.removeCallbacks(searchDebounceRunnable)
        if (realTestActive) testPauseHandler.postDelayed(testPauseStop, 8000)
    }

    override fun onDestroyView() {
        MinabProfileBus.removeListener(busListener)
        listRefreshHandler.removeCallbacks(listRefreshRunnable)
        listRefreshPending = false
        recyclerView?.adapter = null
        recyclerView = null
        headerView = null
        emptyView = null
        configsAdapter = null
        chipsContainer = null
        selectionRow = null
        credentialsSmartButton = null
        selectAllButton = null
        sortButton = null
        searchEdit = null
        searchClearBtn = null
        shareButton = null
        shareSelectedButton = null
        testProgressPanel = null
        realTestSettingsPanel = null
        lastListSignature = null
        chipsSignature = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        testPauseHandler.removeCallbacks(testPauseStop)
        searchDebounceHandler.removeCallbacks(searchDebounceRunnable)
        cancelPingTest()
        cancelRealTest()
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun refreshList() {
        if (!isAdded || configsAdapter == null) return
        try {
            refreshListInner()
        } catch (_: Exception) {
        }
    }

    private fun listSignature(sorted: Collection<VpnProfile>, favorites: Set<String>): String {
        val sb = StringBuilder(sorted.size * 48)
        sb.append(selectedCategory ?: "all").append('|')
            .append(searchQuery).append('|')
            .append(sortMode).append('|')
            .append(selectedUuids.sorted().joinToString(",")).append('|')
            .append(sorted.size)
        for (p in sorted) {
            sb.append('|').append(p.getUUIDString())
                .append(',').append(p.mVersion)
                .append(',').append(p.mLastPingMs)
                .append(',').append(p.mLastPingResult)
                .append(',').append(p.mLastRealMs)
                .append(',').append(p.mLastRealResult)
                .append(',').append(favorites.contains(p.getUUIDString()))
                .append(',').append(p.mName ?: "")
                .append(',').append(p.getCategory() ?: "")
        }
        return sb.toString()
    }

    private fun setSortMode(mode: Int) {
        sortMode = mode
        if (isAdded) {
            MinabProfileBus.setSortMode(requireContext(), mode)
            updateSortButtonUi()
        }
    }

    private fun updateSortButtonUi() {
        val ctx = context ?: return
        sortButton?.text = MinabSortDialog.shortLabel(ctx, sortMode)
    }

    private fun resortConfigsAdapter() {
        if (displayedProfiles.isEmpty()) return
        val favorites = MinabFavorites.getAll(requireContext())
        cachedFavorites = favorites
        displayedProfiles = MinabProfileBus.sortProfiles(
            displayedProfiles, sortMode, favorites, true
        ).toMutableList()
        lastListSignature = null
        requestListRefresh()
    }

    private fun refreshListInner() {
        val context = requireContext()
        val pm = ProfileManager.getInstance(context)
        val all = pm.getProfiles().toList()
        selectedUuids.retainAll(all.map { it.getUUIDString() }.toSet())
        val favorites = MinabFavorites.getAll(context)
        val filtered = when {
            selectedCategory == null -> all
            selectedCategory == CategoryStore.FAVORITES_TAB ->
                all.filter { favorites.contains(it.getUUIDString()) }
            selectedCategory == CategoryStore.ACTIVE_TAB ->
                all.filter { MinabVpnControl.isHealthy(it) }
            selectedCategory == CategoryStore.INACTIVE_TAB ->
                all.filter { !MinabVpnControl.isHealthy(it) }
            else -> all.filter { it.getCategory() == selectedCategory }
        }
        val query = searchQuery.lowercase()
        val searched = if (query.isEmpty()) filtered else filtered.filter { profile ->
            val name = profile.mName?.lowercase() ?: ""
            val category = profile.getCategory()?.lowercase() ?: ""
            name.contains(query) || category.contains(query)
        }
        val sorted: List<VpnProfile> = MinabProfileBus.sortProfiles(searched, sortMode, favorites, true)

        val signature = listSignature(sorted, favorites)
        if (signature == lastListSignature) {
            rebuildChips(all)
            updateSelectionBar()
            return
        }
        lastListSignature = signature
        cachedFavorites = favorites
        displayedProfiles = sorted.toMutableList()
        configsAdapter?.submitList(displayedProfiles)
        rebuildChips(all)
        updateSelectionBar()
    }

    private fun setConfigBadge(badge: TextView, ms: Long, result: String): Boolean {
        if (ms > 0) {
            badge.visibility = View.VISIBLE
            badge.text = result.ifEmpty { "$ms ms" }
            badge.setBackgroundResource(
                when {
                    ms < 100 -> R.drawable.minab_ping_badge_fast
                    ms < 300 -> R.drawable.minab_ping_badge_slow
                    else -> R.drawable.minab_ping_badge_fail
                }
            )
            return true
        } else if (result.isNotEmpty() && ms != 0L) {
            badge.visibility = View.VISIBLE
            badge.text = result
            badge.setBackgroundResource(R.drawable.minab_ping_badge_fail)
            return true
        } else {
            badge.visibility = View.INVISIBLE
            return false
        }
    }

    private fun editVPN(profile: VpnProfile) {
        val intent = Intent(requireContext(), VPNPreferences::class.java)
            .putExtra(
                requireContext().packageName + ".profileUUID",
                profile.getUUID().toString()
            )
        startActivity(intent)
    }

    // --- Selection (delete / copy / move / share) ---

    private fun selectedProfiles(): List<VpnProfile> {
        val all = ProfileManager.getInstance(requireContext()).getProfiles().toList()
        return all.filter { selectedUuids.contains(it.getUUIDString()) }
    }

    private fun toggleSelection(profile: VpnProfile) {
        val uuid = profile.getUUIDString()
        if (!selectedUuids.remove(uuid)) selectedUuids.add(uuid)
        lastListSignature = null
        configsAdapter?.notifyRowsUpdated()
        updateSelectionBar()
    }

    private fun clearSelection() {
        if (selectedUuids.isEmpty()) return
        selectedUuids.clear()
        lastListSignature = null
        configsAdapter?.notifyRowsUpdated()
        updateSelectionBar()
    }

    private fun toggleSelectAllDisplayed() {
        if (displayedProfiles.isEmpty()) return
        val allSelected = displayedProfiles.all { selectedUuids.contains(it.getUUIDString()) }
        if (allSelected) {
            for (p in displayedProfiles) selectedUuids.remove(p.getUUIDString())
        } else {
            for (p in displayedProfiles) selectedUuids.add(p.getUUIDString())
        }
        lastListSignature = null
        configsAdapter?.notifyRowsUpdated()
        updateSelectionBar()
    }

    private fun updateSelectionBar() {
        if (!isAdded) return
        val count = selectedUuids.size
        selectionRow?.visibility = if (count > 0) View.VISIBLE else View.GONE
        if (count > 0) {
            shareSelectedButton?.text = getString(R.string.minab_share_selected_short, count)
        }
        shareButton?.text = when {
            count > 0 -> getString(R.string.minab_share_selected_short, count)
            selectedCategory != null -> getString(R.string.minab_share_group)
            else -> getString(R.string.minab_share_all)
        }
        val allShownSelected = displayedProfiles.isNotEmpty() &&
            displayedProfiles.all { selectedUuids.contains(it.getUUIDString()) }
        selectAllButton?.setText(
            if (allShownSelected) R.string.minab_deselect_all else R.string.minab_select_all
        )
        credentialsSmartButton?.text = when {
            count > 0 -> getString(R.string.minab_credentials_for_selected, count)
            selectedCategory != null && !CategoryStore.isPseudoTab(selectedCategory) ->
                getString(R.string.minab_credentials_for_scope, selectedCategory!!)
            else -> getString(R.string.minab_credentials_for_all)
        }
    }

    private fun showCleanupMenuDialog() {
        val ctx = requireContext()
        val pm = ProfileManager.getInstance(ctx)
        val all = pm.getProfiles().toList()
        val dups = MinabProfileBus.findDuplicates(all, MinabFavorites.getAll(ctx))
        val inactive = MinabProfileBus.findInactiveProfiles(all)
        val items = arrayOf(
            "${getString(R.string.minab_remove_duplicates)} (${dups.size})",
            "${getString(R.string.minab_remove_inactive)} (${inactive.size})"
        )
        AlertDialog.Builder(ctx)
            .setTitle(R.string.minab_tools_menu_title)
            .setItems(items) { _, which ->
                if (which == 0) {
                    if (dups.isEmpty()) {
                        Toast.makeText(ctx, R.string.minab_no_duplicates, Toast.LENGTH_SHORT).show()
                    } else {
                        AlertDialog.Builder(ctx)
                            .setTitle(R.string.minab_remove_duplicates)
                            .setMessage(getString(R.string.minab_remove_duplicates_confirm, dups.size))
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                for (p in dups) pm.removeProfile(ctx, p)
                                Toast.makeText(ctx, getString(R.string.minab_duplicates_removed, dups.size), Toast.LENGTH_SHORT).show()
                                lastListSignature = null
                                chipsSignature = null
                                refreshList()
                                MinabProfileBus.notifyChanged()
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                } else {
                    if (inactive.isEmpty()) {
                        Toast.makeText(ctx, R.string.minab_no_inactive_to_remove, Toast.LENGTH_SHORT).show()
                    } else {
                        AlertDialog.Builder(ctx)
                            .setTitle(R.string.minab_remove_inactive)
                            .setMessage(getString(R.string.minab_remove_inactive_confirm, inactive.size))
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                for (p in inactive) pm.removeProfile(ctx, p)
                                Toast.makeText(ctx, getString(R.string.minab_inactive_removed, inactive.size), Toast.LENGTH_SHORT).show()
                                lastListSignature = null
                                chipsSignature = null
                                refreshList()
                                MinabProfileBus.notifyChanged()
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDeleteSelectedDialog() {
        val count = selectedUuids.size
        if (count == 0) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.minab_delete_selected)
            .setMessage(getString(R.string.minab_delete_selected_confirm, count))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val pm = ProfileManager.getInstance(requireContext())
                val context = requireContext()
                for (profile in selectedProfiles()) {
                    pm.removeProfile(context, profile)
                }
                val deleted = count
                clearSelection()
                Toast.makeText(
                    context,
                    getString(R.string.minab_configs_deleted, deleted),
                    Toast.LENGTH_SHORT
                ).show()
                chipsSignature = null
                refreshList()
                MinabProfileBus.notifyChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCopyMoveDialog(isCopy: Boolean) {
        val count = selectedUuids.size
        if (count == 0) return
        val categories = CategoryStore.getOrderedCategories(requireContext())
        val labels = (categories + getString(R.string.minab_new_category)).toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.minab_copy_move_title)
            .setItems(labels) { _, which ->
                if (which < categories.size) {
                    copyMoveSelected(categories[which], isCopy)
                } else {
                    showNewCategoryDialog { name -> copyMoveSelected(name, isCopy) }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun copyMoveSelected(target: String, isCopy: Boolean) {
        val context = requireContext()
        val pm = ProfileManager.getInstance(context)
        CategoryStore.addCategory(context, target)
        var done = 0
        for (profile in selectedProfiles()) {
            if (isCopy) {
                val copy = profile.copy(profile.mName) ?: continue
                copy.setCategory(target)
                pm.addProfile(copy)
                ProfileManager.saveProfile(context, copy)
                pm.saveProfileList(context)
                done++
            } else {
                profile.setCategory(target)
                ProfileManager.saveProfile(context, profile)
                done++
            }
        }
        val moved = done
        clearSelection()
        Toast.makeText(
            context,
            getString(
                if (isCopy) R.string.minab_copied else R.string.minab_moved,
                moved, target
            ),
            Toast.LENGTH_SHORT
        ).show()
        chipsSignature = null
        refreshList()
        MinabProfileBus.notifyChanged()
    }

    // --- Share configs as zip ---

    private fun shareCurrentScope() {
        val context = requireContext()
        val profiles = if (selectedUuids.isNotEmpty()) selectedProfiles() else visibleProfiles()
        if (profiles.isEmpty()) {
            Toast.makeText(context, R.string.minab_backup_none, Toast.LENGTH_SHORT).show()
            return
        }
        shareProfiles(profiles)
    }

    private fun shareProfiles(profiles: List<VpnProfile>) {
        if (profiles.isEmpty()) return
        val context = requireContext()
        Toast.makeText(context, R.string.minab_share_creating, Toast.LENGTH_SHORT).show()
        val appCtx = context.applicationContext
        BACKGROUND_EXECUTOR.execute {
            var count = 0
            var file: File? = null
            try {
                file = File(appCtx.cacheDir, "minab-share-" + System.currentTimeMillis() + ".zip")
                FileOutputStream(file).use { out ->
                    count = writeProfilesZip(appCtx, profiles, out)
                }
                if (count == 0) file = null
            } catch (_: Exception) {
                file = null
            }
            val outFile = file
            val doneCount = count
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (outFile == null || doneCount == 0) {
                    Toast.makeText(
                        requireContext(),
                        R.string.minab_share_failed,
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    val uri = Uri.parse(
                        "content://" + requireContext().packageName +
                            ".FileProvider/" + outFile.name
                    )
                    val intent = Intent(Intent.ACTION_SEND)
                    intent.type = "application/zip"
                    intent.putExtra(Intent.EXTRA_STREAM, uri)
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(
                        Intent.createChooser(intent, getString(R.string.minab_share_zip))
                    )
                    clearSelection()
                }
            }
        }
    }

    private fun writeProfilesZip(
        context: Context,
        profiles: List<VpnProfile>,
        out: OutputStream
    ): Int = MinabBackup.writeProfilesZip(context, profiles, out)

    private fun rebuildChips(allProfiles: Collection<VpnProfile> = ProfileManager.getInstance(requireContext()).getProfiles()) {
        val container = chipsContainer ?: return
        val context = requireContext()
        val ordered = CategoryStore.getDisplayCategories(context, allProfiles)
        if (selectedCategory != null &&
            !CategoryStore.isPseudoTab(selectedCategory) &&
            !ordered.contains(selectedCategory)
        ) {
            selectedCategory = null
        }
        val favorites = MinabFavorites.getAll(context)
        val allCount = allProfiles.size
        val favCount = allProfiles.count { favorites.contains(it.getUUIDString()) }
        val activeCount = allProfiles.count { MinabVpnControl.isHealthy(it) }
        val inactiveCount = (allCount - activeCount).coerceAtLeast(0)
        val groupCounts = HashMap<String, Int>()
        for (p in allProfiles) {
            val cat = CategoryStore.normalise(p.getCategory())
            groupCounts[cat] = (groupCounts[cat] ?: 0) + 1
        }
        val signature = (selectedCategory ?: "all") + "|" +
            "$allCount,$favCount,$activeCount,$inactiveCount|" +
            ordered.joinToString(",") { "$it:${groupCounts[it] ?: 0}" }
        if (signature == chipsSignature && container.childCount > 0) return
        chipsSignature = signature
        container.removeAllViews()
        addFavoritesTab(container, favCount)
        addSimpleChip(container, null, allCount)
        addStatusFilterTab(
            container, CategoryStore.ACTIVE_TAB, R.string.minab_active_tab, R.color.minab_connected, activeCount
        )
        addStatusFilterTab(
            container, CategoryStore.INACTIVE_TAB, R.string.minab_inactive_tab, R.color.minab_ping_fail, inactiveCount
        )
        for (category in ordered) {
            if (category == CategoryStore.DEFAULT_CATEGORY) continue
            addSimpleChip(container, category, groupCounts[category] ?: 0)
        }
        if (ordered.contains(CategoryStore.DEFAULT_CATEGORY)) {
            addSimpleChip(container, CategoryStore.DEFAULT_CATEGORY, groupCounts[CategoryStore.DEFAULT_CATEGORY] ?: 0)
        }

        val manage = TextView(context).apply {
            text = getString(R.string.minab_manage_categories)
            textSize = 11f
            setTextColor(ContextCompat.getColor(context, R.color.minab_teal))
            setBackgroundResource(R.drawable.minab_chip_bg)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
            setOnClickListener { showManageCategoriesDialog() }
        }
        container.addView(manage)

        val add = TextView(context).apply {
            text = "+ " + getString(R.string.minab_new_group)
            textSize = 11f
            setTextColor(ContextCompat.getColor(context, R.color.minab_teal))
            setBackgroundResource(R.drawable.minab_chip_bg)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
            setOnClickListener { showNewCategoryDialog { refreshList() } }
        }
        container.addView(add)

        if (ordered.size > 1) {
            val reorder = ImageView(context).apply {
                setImageResource(R.drawable.ic_edit)
                setColorFilter(ContextCompat.getColor(context, R.color.minab_teal))
                setBackgroundResource(R.drawable.minab_chip_bg)
                setPadding(dp(8), dp(5), dp(8), dp(5))
                contentDescription = getString(R.string.minab_reorder_groups)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(6) }
                setOnClickListener {
                    MinabCategoryReorder.show(context) {
                        chipsSignature = null
                        rebuildChips()
                        refreshList()
                        MinabProfileBus.notifyChanged()
                    }
                }
            }
            container.addView(reorder)
        }

        val peek = View(context)
        peek.layoutParams = LinearLayout.LayoutParams(dp(20), dp(1))
        container.addView(peek)
    }

    private fun addSimpleChip(container: LinearLayout, category: String?, count: Int) {
        val context = requireContext()
        val label = "${category ?: getString(R.string.minab_all_tab)} ($count)"
        val selected = category == selectedCategory
        val chip = TextView(context).apply {
            text = label
            textSize = 11f
            setTextColor(
                ContextCompat.getColor(
                    context,
                    if (selected) R.color.minab_teal_dark else R.color.minab_chip_text
                )
            )
            setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            setBackgroundResource(
                if (selected) R.drawable.minab_chip_bg_selected else R.drawable.minab_chip_bg
            )
            setPadding(dp(10), dp(5), dp(10), dp(5))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
            setOnClickListener {
                selectedCategory = category
                refreshList()
            }
        }
        container.addView(chip)
    }

    private fun addStatusFilterTab(
        container: LinearLayout,
        tabKey: String,
        labelRes: Int,
        dotColorRes: Int,
        count: Int
    ) {
        val context = requireContext()
        val selected = selectedCategory == tabKey
        val tab = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(
                if (selected) R.drawable.minab_chip_bg_selected else R.drawable.minab_chip_bg
            )
            setPadding(dp(10), dp(5), dp(10), dp(5))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
        }
        val dot = View(context).apply {
            setBackgroundResource(R.drawable.minab_status_dot)
            ViewCompat.setBackgroundTintList(
                this,
                ColorStateList.valueOf(ContextCompat.getColor(context, dotColorRes))
            )
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(5) }
        }
        tab.addView(dot)
        val label = TextView(context).apply {
            text = "${getString(labelRes)} ($count)"
            textSize = 11f
            setTextColor(
                ContextCompat.getColor(
                    context,
                    if (selected) R.color.minab_teal_dark else R.color.minab_chip_text
                )
            )
            setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
        tab.addView(label)
        tab.setOnClickListener {
            selectedCategory = tabKey
            refreshList()
        }
        container.addView(tab)
    }

    private fun addFavoritesTab(container: LinearLayout, count: Int) {
        val context = requireContext()
        val selected = selectedCategory == CategoryStore.FAVORITES_TAB
        val tab = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(
                if (selected) R.drawable.minab_chip_bg_selected else R.drawable.minab_chip_bg
            )
            setPadding(dp(10), dp(5), dp(10), dp(5))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
        }
        val star = ImageView(context).apply {
            setImageResource(R.drawable.minab_star_on)
            layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(4) }
        }
        tab.addView(star)
        val label = TextView(context).apply {
            text = "${getString(R.string.minab_favorites_tab)} ($count)"
            textSize = 11f
            setTextColor(
                ContextCompat.getColor(
                    context,
                    if (selected) R.color.minab_teal_dark else R.color.minab_chip_text
                )
            )
            setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
        tab.addView(label)
        tab.setOnClickListener {
            selectedCategory = CategoryStore.FAVORITES_TAB
            refreshList()
            if (MinabFavorites.getAll(context).isEmpty()) {
                Toast.makeText(context, R.string.minab_no_favorites_yet, Toast.LENGTH_SHORT).show()
            }
        }
        container.addView(tab)
    }

    private fun showManageCategoriesDialog() {
        val categories = CategoryStore.getOrderedCategories(requireContext())
            .filter { it != CategoryStore.DEFAULT_CATEGORY }
        val builder = AlertDialog.Builder(requireContext())
            .setTitle(R.string.minab_manage_categories)
            .setPositiveButton(R.string.minab_redetect_btn) { _, _ ->
                val all = ProfileManager.getInstance(requireContext()).getProfiles().toList()
                MinabRedetectIpDialog.show(requireContext(), all, selectedProfiles(), selectedCategory)
            }
            .setNeutralButton(R.string.minab_new_category) { _, _ ->
                showNewCategoryDialog {
                    chipsSignature = null
                    refreshList()
                    MinabProfileBus.notifyChanged()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (categories.isNotEmpty()) {
            builder.setItems(categories.toTypedArray()) { _, which ->
                val oldName = categories[which]
                AlertDialog.Builder(requireContext())
                    .setTitle(oldName)
                    .setItems(
                        arrayOf(
                            getString(R.string.minab_rename_category),
                            getString(R.string.minab_delete_category)
                        )
                    ) { _, action ->
                        if (action == 0) {
                            val entry = EditText(requireContext())
                            entry.setText(oldName)
                            AlertDialog.Builder(requireContext())
                                .setTitle(R.string.minab_rename_category)
                                .setView(entry)
                                .setPositiveButton(android.R.string.ok) { _, _ ->
                                    CategoryStore.renameCategory(
                                        requireContext(), oldName,
                                        entry.text.toString()
                                    )
                                    if (selectedCategory == oldName) {
                                        selectedCategory = CategoryStore.normalise(entry.text.toString())
                                    }
                                    chipsSignature = null
                                    lastListSignature = null
                                    refreshList()
                                    MinabProfileBus.notifyChanged()
                                }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                        } else {
                            CategoryStore.deleteCategory(requireContext(), oldName)
                            if (selectedCategory == oldName) selectedCategory = null
                            chipsSignature = null
                            lastListSignature = null
                            refreshList()
                            MinabProfileBus.notifyChanged()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        builder.show()
    }

    private fun onConfigRowClick(profile: VpnProfile) {
        if (selectedUuids.isNotEmpty()) {
            toggleSelection(profile)
        } else {
            showConfigActionsDialog(profile)
        }
    }

    private fun showConfigActionsDialog(profile: VpnProfile) {
        val actions = arrayOf(
            getString(R.string.minab_rename_config),
            getString(R.string.minab_change_credentials),
            getString(R.string.minab_assign_category),
            getString(R.string.minab_ping_one),
            getString(R.string.minab_real_test_one),
            getString(R.string.minab_redetect_btn),
            getString(R.string.minab_share_config),
            getString(R.string.minab_delete_config)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.minab_config_actions, profile.mName))
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> showRenameDialog(profile)
                    1 -> showProfileCredentialsDialog(profile)
                    2 -> showAssignCategoryDialog(profile)
                    3 -> pingOne(profile)
                    4 -> startRealTest(listOf(profile))
                    5 -> MinabRedetectIpDialog.show(
                        requireContext(),
                        ProfileManager.getInstance(requireContext()).getProfiles().toList(),
                        listOf(profile),
                        selectedCategory
                    )
                    6 -> shareProfiles(listOf(profile))
                    7 -> showDeleteDialog(profile)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRenameDialog(profile: VpnProfile) {
        val entry = EditText(requireContext())
        entry.setSingleLine()
        entry.setText(profile.mName)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.minab_rename_config)
            .setView(entry)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = entry.text.toString().trim()
                if (name.isNotEmpty()) {
                    profile.mName = name
                    ProfileManager.saveProfile(requireContext(), profile)
                    Toast.makeText(requireContext(), R.string.minab_config_renamed, Toast.LENGTH_SHORT).show()
                    refreshList()
                    MinabProfileBus.notifyChanged()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showProfileCredentialsDialog(profile: VpnProfile) {
        if (!profile.isUserPWAuth()) {
            Toast.makeText(requireContext(), R.string.minab_credentials_none_applied, Toast.LENGTH_SHORT).show()
            return
        }
        showCredentialsInputDialog { username, password ->
            profile.mUsername = username
            profile.mPassword = password
            ProfileManager.saveProfile(requireContext(), profile)
            Toast.makeText(
                requireContext(),
                getString(R.string.minab_credentials_applied, 1),
                Toast.LENGTH_SHORT
            ).show()
            refreshList()
            MinabProfileBus.notifyChanged()
        }
    }

    private fun showCredentialsForSelectionDialog(targets: List<VpnProfile>) {
        if (targets.isEmpty()) return
        val title = getString(R.string.minab_credentials_for_selected, targets.size)
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(R.string.minab_shared_credentials_hint)
            .setPositiveButton(R.string.minab_apply_credentials) { _, _ ->
                showCredentialsInputDialog { username, password ->
                    SharedCredentials.save(requireContext(), username, password)
                    val updated = SharedCredentials.applyToProfiles(
                        requireContext(), targets, username, password
                    )
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.minab_credentials_applied, updated),
                        Toast.LENGTH_LONG
                    ).show()
                    refreshList()
                    MinabProfileBus.notifyChanged()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCredentialsDialog(group: String?) {
        val effectiveGroup = if (group == null || CategoryStore.isPseudoTab(group)) null else group
        val title = if (effectiveGroup == null) getString(R.string.minab_credentials_for_all)
        else getString(R.string.minab_credentials_for_scope, effectiveGroup)
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(R.string.minab_shared_credentials_hint)
            .setPositiveButton(R.string.minab_apply_credentials) { _, _ ->
                showCredentialsInputDialog { username, password ->
                    SharedCredentials.save(requireContext(), username, password)
                    val updated = if (effectiveGroup == null) {
                        SharedCredentials.applyToAllProfiles(requireContext(), username, password)
                    } else {
                        SharedCredentials.applyToCategory(requireContext(), effectiveGroup, username, password)
                    }
                    Toast.makeText(
                        requireContext(),
                        if (effectiveGroup == null) getString(R.string.minab_credentials_applied, updated)
                        else getString(R.string.minab_credentials_group_applied, updated),
                        Toast.LENGTH_LONG
                    ).show()
                    refreshList()
                    MinabProfileBus.notifyChanged()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCredentialsInputDialog(onApply: (String, String) -> Unit) {
        val context = requireContext()
        val layout = LinearLayout(context)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(dp(20), dp(8), dp(20), dp(8))
        val userField = EditText(context)
        userField.hint = getString(R.string.minab_username)
        userField.setText(SharedCredentials.getUsername(context))
        val passField = EditText(context)
        passField.hint = getString(R.string.minab_password)
        passField.inputType =
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        passField.setText(SharedCredentials.getPassword(context))
        layout.addView(userField)
        layout.addView(passField)
        AlertDialog.Builder(context)
            .setTitle(R.string.minab_change_credentials)
            .setView(layout)
            .setPositiveButton(R.string.minab_apply_credentials) { _, _ ->
                onApply(userField.text.toString(), passField.text.toString())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAssignCategoryDialog(profile: VpnProfile) {
        val categories = CategoryStore.getOrderedCategories(requireContext())
        val labels = (categories + getString(R.string.minab_new_category)).toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.minab_assign_category))
            .setItems(labels) { _, which ->
                if (which < categories.size) {
                    assignCategory(profile, categories[which])
                } else {
                    showNewCategoryDialog { name -> assignCategory(profile, name) }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showNewCategoryDialog(onCreated: (String) -> Unit) {
        val entry = EditText(requireContext())
        entry.setSingleLine()
        entry.hint = getString(R.string.minab_group_name_hint)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.minab_new_group)
            .setView(entry)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = CategoryStore.normalise(entry.text.toString())
                CategoryStore.addManualCategory(requireContext(), name)
                onCreated(name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun assignCategory(profile: VpnProfile, category: String) {
        profile.setCategory(category)
        CategoryStore.addCategory(requireContext(), category)
        ProfileManager.saveProfile(requireContext(), profile)
        chipsSignature = null
        lastListSignature = null
        refreshList()
        MinabProfileBus.notifyChanged()
    }

    private fun showDeleteDialog(profile: VpnProfile) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.minab_delete_config)
            .setMessage(getString(R.string.minab_delete_config_confirm, profile.mName))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                ProfileManager.getInstance(requireContext()).removeProfile(requireContext(), profile)
                Toast.makeText(requireContext(), R.string.minab_config_deleted, Toast.LENGTH_SHORT).show()
                chipsSignature = null
                lastListSignature = null
                refreshList()
                MinabProfileBus.notifyChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pingOne(profile: VpnProfile) {
        val tester = PingTester()
        tester.testAll(listOf(profile), object : PingTester.PingListener {
            override fun onResult(result: PingTester.Result) {
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    result.profile.mLastPingMs =
                        if (result.status == PingTester.Status.OK) result.ms else -1
                    result.profile.mLastPingTime = result.timestampMs
                    result.profile.mLastPingResult = result.displayText()
                    val profileToSave = result.profile
                    val saveContext = requireContext().applicationContext
                    BACKGROUND_EXECUTOR.execute {
                        try {
                            ProfileManager.saveProfile(saveContext, profileToSave)
                        } catch (_: Exception) {
                        }
                    }
                    requestListRefresh()
                }
            }

            override fun onFinished(cancelled: Boolean) {
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    Toast.makeText(requireContext(), R.string.minab_ping_done, Toast.LENGTH_SHORT).show()
                    MinabProfileBus.notifyChanged()
                }
            }
        })
    }

    // --- Two server tests ---

    private fun visibleProfiles(): List<VpnProfile> {
        val all = ProfileManager.getInstance(requireContext()).getProfiles().toList()
        val wanted = selectedCategory
        return when {
            wanted == null -> all
            wanted == CategoryStore.FAVORITES_TAB -> {
                val favorites = MinabFavorites.getAll(requireContext())
                all.filter { favorites.contains(it.getUUIDString()) }
            }
            wanted == CategoryStore.ACTIVE_TAB -> all.filter { MinabVpnControl.isHealthy(it) }
            wanted == CategoryStore.INACTIVE_TAB -> all.filter { !MinabVpnControl.isHealthy(it) }
            else -> all.filter { it.getCategory() == wanted }
        }
    }

    private fun clearTestResultsAsync(profiles: List<VpnProfile>) {
        for (profile in profiles) {
            profile.mLastPingMs = -1
            profile.mLastPingTime = 0
            profile.mLastPingResult = ""
            profile.mLastRealMs = -1
            profile.mLastRealTime = 0
            profile.mLastRealResult = ""
        }
        val appCtx = requireContext().applicationContext
        val snapshot = profiles.toList()
        BACKGROUND_EXECUTOR.execute {
            for (profile in snapshot) {
                try {
                    ProfileManager.saveProfile(appCtx, profile)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun startPingTest(profiles: List<VpnProfile>) {
        if (profiles.isEmpty()) {
            Toast.makeText(requireContext(), R.string.minab_no_profiles_in_category, Toast.LENGTH_SHORT).show()
            return
        }
        cancelRealTest()
        cancelPingTest()
        RealTester.cancelCurrent()
        currentTestNetSnapshot = null
        MinabTestHistory.captureNetworkAsync(requireContext()) { snap -> currentTestNetSnapshot = snap }
        clearTestResultsAsync(profiles)
        lastListSignature = null
        refreshList()
        if (VpnStatus.isVPNActive()) {
            Toast.makeText(requireContext(), R.string.minab_ping_while_vpn, Toast.LENGTH_LONG).show()
        }
        val tester = PingTester()
        pingTester = tester
        pingDoneCount = 0
        pingTotalCount = profiles.size
        pingOkCount = 0
        pingLatestName = null
        MinabTestCoordinator.setRunning(MinabTestCoordinator.Source.CONFIGS_PING, true)
        updateTestPingStatus()
        tester.testAll(profiles, object : PingTester.PingListener {
            override fun onResult(result: PingTester.Result) {
                activity?.runOnUiThread {
                    if (!isAdded || pingTester !== tester) return@runOnUiThread
                    val ok = result.status == PingTester.Status.OK
                    result.profile.mLastPingMs = if (ok) result.ms else -1
                    result.profile.mLastPingTime = result.timestampMs
                    result.profile.mLastPingResult = result.displayText()
                    val profileToSave = result.profile
                    val saveContext = requireContext().applicationContext
                    BACKGROUND_EXECUTOR.execute {
                        try {
                            ProfileManager.saveProfile(saveContext, profileToSave)
                        } catch (_: Exception) {
                        }
                    }
                    pingDoneCount++
                    if (ok) pingOkCount++
                    pingLatestName = result.profile.mName
                    updateTestPingStatus()
                    if (sortMode == MinabProfileBus.SORT_PING) resortConfigsAdapter()
                    else requestListRefresh()
                    maybeDetectCountryFromIp(result)
                }
            }

            override fun onFinished(cancelled: Boolean) {
                activity?.runOnUiThread {
                    if (pingTester === tester) {
                        pingTester = null
                    }
                    MinabTestCoordinator.setRunning(MinabTestCoordinator.Source.CONFIGS_PING, false)
                    if (!isAdded) return@runOnUiThread
                    if (cancelled) {
                        Toast.makeText(requireContext(), R.string.minab_ping_cancelled, Toast.LENGTH_SHORT).show()
                        testProgressPanel?.hide()
                        MinabProfileBus.notifyChanged()
                    } else {
                        MinabTestHistory.recordRunFromProfiles(
                            requireContext(),
                            false,
                            "TCP/Ping",
                            currentTestNetSnapshot,
                            profiles,
                            pingDoneCount,
                            pingOkCount
                        )
                        setSortMode(MinabProfileBus.SORT_PING)
                        lastListSignature = null
                        chipsSignature = null
                        refreshList()
                        Toast.makeText(requireContext(), R.string.minab_ping_done, Toast.LENGTH_SHORT).show()
                        testProgressPanel?.showFinished(
                            isReal = false,
                            summaryText = getString(R.string.minab_sort_by_ping),
                            tested = pingDoneCount,
                            total = pingTotalCount,
                            found = pingOkCount
                        )
                        MinabProfileBus.notifyChanged()
                    }
                }
            }
        })
    }

    private fun maybeDetectCountryFromIp(result: PingTester.Result) {
        val ip = result.resolvedIp ?: return
        val profile = result.profile
        val ctx = context?.applicationContext ?: return
        if (MinabServerCountry.get(ctx, profile.getUUIDString()) != null) return
        if (FlagHelper.flagResForProfile(profile) != R.drawable.minab_flag_globe) return
        BACKGROUND_EXECUTOR.execute {
            try {
                val code = AutoCategory.countryCodeForIp(ctx, ip) ?: return@execute
                MinabServerCountry.put(ctx, profile.getUUIDString(), code)
                activity?.runOnUiThread {
                    if (isAdded) {
                        requestListRefresh()
                        MinabProfileBus.notifyChanged()
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun stopRealCountdown() {
        realCountdownHandler.removeCallbacks(realCountdownRunnable)
        realCountdownSeconds = 0
        realCountdownName = null
    }

    private fun updateRealCountdownLine() {
        if (!realTestActive) return
        val waitSeconds = try {
            MinabRealTestSettings.getWaitSeconds(requireContext())
        } catch (_: Exception) {
            12
        }
        val serversAfterCurrent = (realCountdownTotal - realCountdownDone - 1).coerceAtLeast(0)
        val totalSecondsLeft = realCountdownSeconds + serversAfterCurrent * waitSeconds
        testProgressPanel?.showRealProgress(
            done = realCountdownDone,
            total = realCountdownTotal,
            working = realCountdownWorking,
            targetWorking = realTargetWorking,
            currentName = realCountdownName,
            totalSecondsLeft = totalSecondsLeft
        )
    }

    private fun updateTestPingStatus() {
        val panel = testProgressPanel ?: return
        if (pingTester == null) {
            panel.hide()
            return
        }
        panel.showPingProgress(pingDoneCount, pingTotalCount, pingOkCount, pingLatestName)
    }

    private fun cancelPingTest() {
        pingTester?.cancel()
        pingTester = null
        MinabTestCoordinator.setRunning(MinabTestCoordinator.Source.CONFIGS_PING, false)
        if (!realTestActive) testProgressPanel?.hide()
    }

    private fun startRealTest(profiles: List<VpnProfile>) {
        if (profiles.isEmpty()) {
            Toast.makeText(requireContext(), R.string.minab_no_profiles_in_category, Toast.LENGTH_SHORT).show()
            return
        }
        cancelPingTest()
        cancelRealTest()
        realTotalCount = 0
        realCountdownDone = 0
        realCountdownTotal = 0
        realCountdownWorking = 0
        realPingPhaseActive = true
        realPingOkCount = 0
        realTargetWorking = MinabRealTestSettings.getMaxServers(requireContext())
        currentTestNetSnapshot = null
        MinabTestHistory.captureNetworkAsync(requireContext()) { snap -> currentTestNetSnapshot = snap }
        realTestRunId++
        val runId = realTestRunId

        RealTester.cancelCurrent()
        clearTestResultsAsync(profiles)
        lastListSignature = null
        refreshList()

        val tester = RealTester(
            requireContext().applicationContext,
            MinabRealTestSettings.getWaitTimeoutMs(requireContext()),
            realTargetWorking,
            object : RealTester.Listener {
                override fun onPingProgress(done: Int, total: Int) {
                    if (!isAdded || runId != realTestRunId) return
                    realPingPhaseActive = true
                    pingDoneCount = done
                    pingTotalCount = total
                    testProgressPanel?.showRealPingPhase(done, total, realPingOkCount)
                }

                override fun onRealProgress(
                    done: Int, total: Int, currentName: String?, working: Int
                ) {
                    if (!isAdded || runId != realTestRunId) return
                    realPingPhaseActive = false
                    realTotalCount = total
                    realCountdownDone = done
                    realCountdownTotal = total
                    realCountdownWorking = working
                    if (currentName == null) {
                        stopRealCountdown()
                    } else if (currentName != realCountdownName) {
                        realCountdownName = currentName
                        realCountdownSeconds = MinabRealTestSettings.getWaitSeconds(requireContext())
                        realCountdownHandler.removeCallbacks(realCountdownRunnable)
                        realCountdownHandler.postDelayed(realCountdownRunnable, 1000)
                    }
                    updateRealCountdownLine()
                }

                override fun onProfileResult(profile: VpnProfile) {
                    if (!isAdded || runId != realTestRunId) return
                    if (realPingPhaseActive && profile.mLastPingMs > 0) {
                        realPingOkCount++
                        testProgressPanel?.showRealPingPhase(pingDoneCount, pingTotalCount, realPingOkCount)
                    }
                    if (sortMode == MinabProfileBus.SORT_REAL) resortConfigsAdapter()
                    else requestListRefresh()
                }

                override fun onFinished(cancelled: Boolean, working: Int) {
                    if (runId != realTestRunId) return
                    realTester = null
                    realTestActive = false
                    realPingPhaseActive = false
                    stopRealCountdown()
                    MinabTestCoordinator.setRunning(MinabTestCoordinator.Source.CONFIGS_REAL, false)
                    if (!isAdded) return
                    if (cancelled) {
                        testProgressPanel?.hide()
                        MinabProfileBus.notifyChanged()
                        return
                    }
                    if (realTotalCount == 0) {
                        testProgressPanel?.hide()
                        Toast.makeText(requireContext(), R.string.minab_real_none_to_test, Toast.LENGTH_LONG).show()
                        MinabProfileBus.notifyChanged()
                        return
                    }
                    MinabTestHistory.recordRunFromProfiles(
                        requireContext(),
                        true,
                        MinabRealTestSettings.getShortHostLabel(requireContext()),
                        currentTestNetSnapshot,
                        profiles,
                        realCountdownDone,
                        working
                    )
                    setSortMode(MinabProfileBus.SORT_REAL)
                    lastListSignature = null
                    chipsSignature = null
                    refreshList()
                    Toast.makeText(requireContext(), R.string.minab_real_done, Toast.LENGTH_SHORT).show()
                    testProgressPanel?.showFinished(
                        isReal = true,
                        summaryText = getString(R.string.minab_sort_by_real),
                        tested = realCountdownDone,
                        total = realTotalCount,
                        found = working
                    )
                    MinabProfileBus.notifyChanged()
                }

                override fun onConnectRequested(profile: VpnProfile) {
                    if (!isAdded || runId != realTestRunId) return
                    startVPN(profile)
                }
            }
        )
        if (!tester.start(profiles)) {
            Toast.makeText(requireContext(), R.string.minab_real_already_running, Toast.LENGTH_SHORT).show()
            return
        }
        realTester = tester
        realTestActive = true
        MinabTestCoordinator.setRunning(MinabTestCoordinator.Source.CONFIGS_REAL, true)
        testProgressPanel?.showRealPingPhase(0, profiles.size, 0)
    }

    private fun cancelRealTest() {
        realTestRunId++
        realTester?.cancel()
        realTester = null
        realTestActive = false
        realPingPhaseActive = false
        stopRealCountdown()
        MinabTestCoordinator.setRunning(MinabTestCoordinator.Source.CONFIGS_REAL, false)
        if (pingTester == null) testProgressPanel?.hide()
    }

    private fun startVPN(profile: VpnProfile) {
        MinabConnectState.markPendingConnect(profile.getUUIDString())
        configsAdapter?.notifyRowsUpdated()
        ProfileManager.saveProfile(requireContext(), profile)
        val intent = Intent(requireContext(), LaunchVPN::class.java)
        intent.putExtra(LaunchVPN.EXTRA_KEY, profile.getUUID().toString())
        intent.putExtra(OpenVPNService.EXTRA_START_REASON, "configs real test")
        intent.putExtra(LaunchVPN.EXTRA_HIDELOG, true)
        intent.action = Intent.ACTION_MAIN
        startActivity(intent)
    }

    private fun exportBackup(uri: Uri) {
        val context = requireContext().applicationContext
        BACKGROUND_EXECUTOR.execute {
            var count = 0
            var ok = false
            try {
                val profiles = ProfileManager.getInstance(context).getProfiles().toList()
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    count = MinabBackup.writeFullBackup(context, profiles, out)
                }
                ok = count > 0
            } catch (_: Exception) {
            }
            val doneCount = count
            val success = ok
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                Toast.makeText(
                    requireContext(),
                    if (success) getString(R.string.minab_backup_done, doneCount)
                    else getString(R.string.minab_backup_failed),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // --- Virtualised RecyclerView Adapter ---

    private class StaticViewHolder(view: View) : RecyclerView.ViewHolder(view)

    private class ConfigRowViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cardWrapper: View = view.findViewById(R.id.minab_config_card_wrapper)
        val rowContent: View = view.findViewById(R.id.minab_config_row_content)
        val indexText: TextView = view.findViewById(R.id.minab_config_index)
        val name: TextView = view.findViewById(R.id.minab_config_name)
        val subtitle: TextView = view.findViewById(R.id.minab_config_subtitle)
        val flag: ImageView = view.findViewById(R.id.minab_config_flag)
        val connecting: ProgressBar = view.findViewById(R.id.minab_config_connecting_spinner)
        val badges: View = view.findViewById(R.id.minab_config_badges)
        val pingBadge: TextView = view.findViewById(R.id.minab_config_ping_badge)
        val realBadge: TextView = view.findViewById(R.id.minab_config_real_badge)
        val star: ImageView = view.findViewById(R.id.minab_config_star)
        val edit: ImageView = view.findViewById(R.id.minab_config_edit)
        val checkBox: CheckBox = view.findViewById(R.id.minab_config_checkbox)
    }

    private inner class ConfigsRecyclerAdapter(
        private val header: View,
        private val empty: View
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var items: List<VpnProfile> = emptyList()
        private val selectableItemBgResId: Int = run {
            val tv = TypedValue()
            if (requireContext().theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
                tv.resourceId
            } else 0
        }

        override fun getItemCount(): Int {
            return if (items.isEmpty()) 2 else 1 + items.size
        }

        override fun getItemViewType(position: Int): Int {
            if (position == 0) return VIEW_TYPE_HEADER
            return if (items.isEmpty()) VIEW_TYPE_EMPTY else VIEW_TYPE_ITEM
        }

        fun submitList(newItems: List<VpnProfile>) {
            val oldEmpty = items.isEmpty()
            val oldCount = items.size
            val snapshot = newItems.toList()
            val newEmpty = snapshot.isEmpty()
            val newCount = snapshot.size
            items = snapshot

            val oldTailCount = if (oldEmpty) 1 else oldCount
            val newTailCount = if (newEmpty) 1 else newCount

            if (oldEmpty != newEmpty) {
                notifyItemRangeRemoved(1, oldTailCount)
                notifyItemRangeInserted(1, newTailCount)
            } else if (!newEmpty) {
                val common = min(oldTailCount, newTailCount)
                if (common > 0) notifyItemRangeChanged(1, common)
                if (newTailCount > oldTailCount) {
                    notifyItemRangeInserted(1 + oldTailCount, newTailCount - oldTailCount)
                } else if (oldTailCount > newTailCount) {
                    notifyItemRangeRemoved(1 + newTailCount, oldTailCount - newTailCount)
                }
            }
        }

        fun notifyRowsUpdated() {
            if (items.isNotEmpty()) {
                notifyItemRangeChanged(1, items.size)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return when (viewType) {
                VIEW_TYPE_HEADER -> {
                    (header.parent as? ViewGroup)?.removeView(header)
                    StaticViewHolder(header).apply { setIsRecyclable(false) }
                }
                VIEW_TYPE_EMPTY -> {
                    (empty.parent as? ViewGroup)?.removeView(empty)
                    StaticViewHolder(empty).apply { setIsRecyclable(false) }
                }
                else -> {
                    val v = LayoutInflater.from(parent.context)
                        .inflate(R.layout.minab_config_row, parent, false)
                    ConfigRowViewHolder(v)
                }
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder !is ConfigRowViewHolder) return
            val index = position - 1
            if (index < 0 || index >= items.size) return
            try {
                bindConfigRow(holder, items[index], index, items.size)
            } catch (e: Exception) {
                android.util.Log.e("ConfigsFragment", "Minab: row bind failed at $index", e)
            }
        }

        private fun bindConfigRow(
            holder: ConfigRowViewHolder,
            profile: VpnProfile,
            index: Int,
            total: Int
        ) {
            val ctx = requireContext()

            holder.cardWrapper.setBackgroundResource(
                when {
                    total <= 1 -> R.drawable.minab_row_card_single
                    index == 0 -> R.drawable.minab_row_card_top
                    index == total - 1 -> R.drawable.minab_row_card_bottom
                    else -> R.drawable.minab_row_card_middle
                }
            )

            holder.indexText.text = (index + 1).toString()
            holder.name.text = profile.mName ?: ""
            holder.subtitle.text = "[" + profile.getCategory() + "]"
            holder.flag.setImageResource(FlagHelper.flagResForProfile(profile, ctx))
            holder.flag.visibility = View.VISIBLE

            val activeUuid = VpnStatus.getLastConnectedVPNProfile()
            val connected = VpnStatus.isVPNActive() &&
                lastLevel == ConnectionStatus.LEVEL_CONNECTED &&
                profile.getUUIDString() == activeUuid
            val connecting = !connected && MinabConnectState.isConnecting(profile.getUUIDString(), lastLevel)
            if (connecting) {
                holder.rowContent.setBackgroundResource(R.drawable.minab_row_halo_connecting)
                holder.connecting.visibility = View.VISIBLE
            } else if (connected) {
                holder.rowContent.setBackgroundResource(R.drawable.minab_row_halo_connected)
                holder.connecting.visibility = View.GONE
            } else {
                if (selectableItemBgResId != 0) {
                    holder.rowContent.setBackgroundResource(selectableItemBgResId)
                } else {
                    holder.rowContent.setBackgroundColor(Color.TRANSPARENT)
                }
                holder.connecting.visibility = View.GONE
            }

            val pingShown = setConfigBadge(
                holder.pingBadge, profile.mLastPingMs, profile.mLastPingResult
            )
            val realShown = setConfigBadge(
                holder.realBadge, profile.mLastRealMs, profile.mLastRealResult
            )
            holder.badges.visibility = if (pingShown || realShown) View.VISIBLE else View.GONE

            val isFavorite = cachedFavorites.contains(profile.getUUIDString())
            holder.star.setImageResource(
                if (isFavorite) R.drawable.minab_star_on else R.drawable.minab_star_off
            )
            holder.star.setOnClickListener {
                MinabFavorites.toggle(ctx, profile.getUUIDString())
                lastListSignature = null
                chipsSignature = null
                refreshList()
                MinabProfileBus.notifyChanged()
            }

            holder.edit.setOnClickListener { editVPN(profile) }

            val checkBox = holder.checkBox
            checkBox.setOnCheckedChangeListener(null)
            checkBox.isChecked = selectedUuids.contains(profile.getUUIDString())
            checkBox.setOnCheckedChangeListener { _, _ -> toggleSelection(profile) }

            holder.rowContent.setOnClickListener { onConfigRowClick(profile) }
            holder.rowContent.setOnLongClickListener {
                toggleSelection(profile)
                true
            }
        }
    }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_ITEM = 1
        private const val VIEW_TYPE_EMPTY = 2

        private val BACKGROUND_EXECUTOR = Executors.newSingleThreadExecutor()
    }
}
