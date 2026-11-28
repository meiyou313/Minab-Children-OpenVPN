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

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import androidx.core.widget.TextViewCompat
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
import de.blinkt.openvpn.minab.MinabDeadServersDialog
import de.blinkt.openvpn.minab.MinabDialogs
import de.blinkt.openvpn.minab.MinabFavorites
import de.blinkt.openvpn.minab.MinabProfileBus
import de.blinkt.openvpn.minab.MinabRealTestSettings
import de.blinkt.openvpn.minab.MinabRealTestSettingsPanel
import de.blinkt.openvpn.minab.MinabRedetectIpDialog
import de.blinkt.openvpn.minab.MinabServerCountry
import de.blinkt.openvpn.minab.MinabServerStats
import de.blinkt.openvpn.minab.MinabSortDialog
import de.blinkt.openvpn.minab.MinabStaleResults
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
    private var cachedStalePing: Set<String> = emptySet()
    private var cachedStaleReal: Set<String> = emptySet()

    private var chipsContainer: LinearLayout? = null
    private lateinit var backupLauncher: ActivityResultLauncher<String>

    private var searchQuery: String = ""
    private val searchDebounceHandler = Handler(Looper.getMainLooper())
    private val searchDebounceRunnable = Runnable { refreshList() }
    private var chipsSignature: String? = null
    private var lastListSignature: String? = null

    private val selectedUuids = mutableSetOf<String>()
    private var selectionRow: View? = null
    private var selectionCountView: TextView? = null
    private var credentialsSmartButton: Button? = null
    private var backupButton: Button? = null
    private var selectAllButton: Button? = null
    private var pingButton: Button? = null
    private var realButton: Button? = null
    private var sortButton: Button? = null
    private var searchEdit: EditText? = null
    private var searchClearBtn: ImageButton? = null
    private var shareButton: Button? = null
    private var pendingBackupProfiles: List<VpnProfile>? = null
    private var pendingBackupScopeType: String = MinabBackup.SCOPE_ALL
    private var pendingBackupScopeLabel: String = "All"

    // --- Server tests (Ping test & Real test) ---
    private var pingTester: PingTester? = null
    private var pingDoneCount: Int = 0
    private var pingTotalCount: Int = 0
    private var pingOkCount: Int = 0
    private var pingLatestName: String? = null
    private var pingRunStartMs: Long = 0L

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

    private var activePingTargets: List<VpnProfile> = emptyList()
    private var activePingSnapshots: Map<String, MinabStaleResults.ResultSnapshot> = emptyMap()
    private var activeRealTargets: List<VpnProfile> = emptyList()
    private var activeRealSnapshots: Map<String, MinabStaleResults.ResultSnapshot> = emptyMap()
    private var activeRealClearedPing: Boolean = false
    private var activeRealStartMs: Long = 0L

    private val busListener = MinabProfileBus.Listener {
        if (isAdded) {
            refreshFromBus()
        }
    }

    private val connectStateListener = MinabConnectState.Listener { affected ->
        if (isAdded) {
            if (affected.isEmpty()) {
                configsAdapter?.notifyRowsUpdated()
            } else {
                configsAdapter?.notifyUuidsChanged(affected)
            }
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sortMode = MinabProfileBus.getSortMode(requireContext())
        backupLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip")
        ) { uri: Uri? ->
            if (uri != null) exportBackup(uri) else pendingBackupProfiles = null
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
                val targets = activeTargetProfiles()
                if (targets.isEmpty()) {
                    Toast.makeText(
                        requireContext(),
                        R.string.minab_no_profiles_in_category,
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                val count = selectedUuids.size
                val isAllScope = count == 0 && selectedCategory == null
                val scopeSummary = when {
                    count > 0 -> getString(R.string.minab_credentials_scope_selected, count)
                    selectedCategory != null -> getString(R.string.minab_credentials_scope_group, activeScopeLabel(), targets.size)
                    else -> getString(R.string.minab_credentials_scope_all_warn, targets.size)
                }
                val title = getString(R.string.minab_configs_login_short)
                showUnifiedCredentialsDialog(
                    title = title,
                    scopeSummary = scopeSummary,
                    isAllScopeWarning = isAllScope,
                    targets = targets,
                    showSaveDefaultOption = true
                )
            }
            backupButton = header.findViewById(R.id.minab_configs_backup)
            backupButton?.setOnClickListener { onBackupClicked() }
            shareButton = header.findViewById(R.id.minab_configs_share)
            shareButton?.setOnClickListener { shareCurrentScope() }
            selectAllButton = header.findViewById(R.id.minab_configs_select_all)
            selectAllButton?.setOnClickListener { toggleSelectAllDisplayed() }
            selectionRow = header.findViewById(R.id.minab_configs_selection_row)
            selectionCountView = header.findViewById(R.id.minab_configs_selection_count)
            header.findViewById<View>(R.id.minab_configs_clear_selection)?.setOnClickListener {
                clearSelection()
            }
            header.findViewById<Button>(R.id.minab_configs_delete_selected)?.setOnClickListener {
                showDeleteSelectedDialog()
            }
            header.findViewById<Button>(R.id.minab_configs_copy_selected)?.setOnClickListener {
                showCopyMoveDialog(true)
            }
            header.findViewById<Button>(R.id.minab_configs_move_selected)?.setOnClickListener {
                showCopyMoveDialog(false)
            }
            pingButton = header.findViewById(R.id.minab_configs_test_ping)
            pingButton?.setOnClickListener { startPingTest(activeTargetProfiles()) }
            realButton = header.findViewById(R.id.minab_configs_test_real)
            realButton?.setOnClickListener { startRealTest(activeTargetProfiles()) }

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
        lastLevel = VpnStatus.getLastLevel()
        lastObservedActive = VpnStatus.isVPNActive()
        lastObservedUuid = VpnStatus.getLastConnectedVPNProfile()
        VpnStatus.addStateListener(this)
        MinabConnectState.addListener(connectStateListener)
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
        val prevUuid = lastObservedUuid
        lastLevel = level
        lastObservedActive = activeNow
        lastObservedUuid = uuidNow
        MinabConnectState.onVpnStateUpdated(level, activeNow, uuidNow)
        activity?.runOnUiThread {
            if (!isAdded) return@runOnUiThread
            val affected = HashSet<String>()
            if (!prevUuid.isNullOrBlank()) affected.add(prevUuid)
            if (!uuidNow.isNullOrBlank()) affected.add(uuidNow)
            if (affected.isNotEmpty()) {
                configsAdapter?.notifyUuidsChanged(affected)
            } else {
                configsAdapter?.notifyRowsUpdated()
            }
        }
    }

    override fun setConnectedVPN(uuid: String?) {
        val act = activity ?: return
        act.runOnUiThread {
            if (!isAdded) return@runOnUiThread
            if (uuid != lastObservedUuid) {
                val prev = lastObservedUuid
                lastObservedUuid = uuid
                val affected = HashSet<String>()
                if (!prev.isNullOrBlank()) affected.add(prev)
                if (!uuid.isNullOrBlank()) affected.add(uuid)
                if (affected.isNotEmpty()) {
                    configsAdapter?.notifyUuidsChanged(affected)
                } else {
                    configsAdapter?.notifyRowsUpdated()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        MinabConnectState.removeListener(connectStateListener)
        VpnStatus.removeStateListener(this)
        searchDebounceHandler.removeCallbacks(searchDebounceRunnable)
    }

    override fun onDestroyView() {
        MinabProfileBus.removeListener(busListener)
        MinabConnectState.removeListener(connectStateListener)
        listRefreshHandler.removeCallbacks(listRefreshRunnable)
        listRefreshPending = false
        recyclerView?.adapter = null
        recyclerView = null
        headerView = null
        emptyView = null
        configsAdapter = null
        chipsContainer = null
        selectionRow = null
        selectionCountView = null
        credentialsSmartButton = null
        backupButton = null
        selectAllButton = null
        pingButton = null
        realButton = null
        sortButton = null
        searchEdit = null
        searchClearBtn = null
        shareButton = null
        testProgressPanel = null
        realTestSettingsPanel = null
        lastListSignature = null
        chipsSignature = null
        super.onDestroyView()
    }

    override fun onDestroy() {
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

    private fun listSignature(
        sorted: Collection<VpnProfile>,
        favorites: Set<String>,
        stalePing: Set<String>,
        staleReal: Set<String>
    ): String {
        val sb = StringBuilder(sorted.size * 52)
        sb.append(selectedCategory ?: "all").append('|')
            .append(searchQuery).append('|')
            .append(sortMode).append('|')
            .append(selectedUuids.sorted().joinToString(",")).append('|')
            .append(sorted.size)
        for (p in sorted) {
            val uuid = p.getUUIDString()
            sb.append('|').append(uuid)
                .append(',').append(p.mVersion)
                .append(',').append(p.mLastPingMs)
                .append(',').append(p.mLastPingResult)
                .append(',').append(stalePing.contains(uuid))
                .append(',').append(p.mLastRealMs)
                .append(',').append(p.mLastRealResult)
                .append(',').append(staleReal.contains(uuid))
                .append(',').append(favorites.contains(uuid))
                .append(',').append(p.mName ?: "")
                .append(',').append(p.getGroupsDisplayText())
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
        val ctx = requireContext()
        val favorites = MinabFavorites.getAll(ctx)
        val stalePing = MinabStaleResults.getPingStaleSet(ctx)
        val staleReal = MinabStaleResults.getRealStaleSet(ctx)
        cachedFavorites = favorites
        cachedStalePing = stalePing
        cachedStaleReal = staleReal
        displayedProfiles = MinabProfileBus.sortProfiles(
            displayedProfiles, sortMode, favorites, true, stalePing, staleReal
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
        val stalePing = MinabStaleResults.getPingStaleSet(context)
        val staleReal = MinabStaleResults.getRealStaleSet(context)
        val filtered = when {
            selectedCategory == null -> all
            selectedCategory == CategoryStore.FAVORITES_TAB ->
                all.filter { favorites.contains(it.getUUIDString()) }
            selectedCategory == CategoryStore.ACTIVE_TAB ->
                all.filter { MinabVpnControl.isHealthy(it) }
            selectedCategory == CategoryStore.INACTIVE_TAB ->
                all.filter { !MinabVpnControl.isHealthy(it) }
            else -> all.filter { it.belongsToGroup(selectedCategory) }
        }
        val query = searchQuery.lowercase()
        val searched = if (query.isEmpty()) filtered else filtered.filter { profile ->
            val name = profile.mName?.lowercase() ?: ""
            val category = profile.getGroupsDisplayText().lowercase()
            name.contains(query) || category.contains(query)
        }
        val sorted: List<VpnProfile> = MinabProfileBus.sortProfiles(
            searched, sortMode, favorites, true, stalePing, staleReal
        )

        val signature = listSignature(sorted, favorites, stalePing, staleReal)
        if (signature == lastListSignature) {
            rebuildChips(all)
            updateSelectionBar()
            return
        }
        lastListSignature = signature
        cachedFavorites = favorites
        cachedStalePing = stalePing
        cachedStaleReal = staleReal
        displayedProfiles = sorted.toMutableList()
        configsAdapter?.submitList(displayedProfiles)
        rebuildChips(all)
        updateSelectionBar()
    }

    private fun setConfigBadge(
        badge: TextView,
        ms: Long,
        result: String,
        isReal: Boolean,
        isStale: Boolean,
        ctx: Context
    ): Boolean {
        if (ms > 0) {
            badge.visibility = View.VISIBLE
            badge.text = result.ifEmpty { if (isReal) getString(R.string.minab_real_ms, ms) else "$ms ms" }
            badge.setCompoundDrawablesRelativeWithIntrinsicBounds(
                if (isReal) R.drawable.ic_minab_badge_real_ok else R.drawable.ic_minab_badge_ping,
                0, 0, 0
            )
            if (isStale) {
                applyStaleConfigBadgeStyle(badge, ctx)
            } else {
                val bgRes = if (isReal) {
                    when {
                        ms < 2000 -> R.drawable.minab_ping_badge_fast
                        ms < 4500 -> R.drawable.minab_ping_badge_slow
                        else -> R.drawable.minab_real_badge_ok
                    }
                } else {
                    when {
                        ms < 100 -> R.drawable.minab_ping_badge_fast
                        ms < 300 -> R.drawable.minab_ping_badge_slow
                        else -> R.drawable.minab_ping_badge_fail
                    }
                }
                applyFreshConfigBadgeStyle(badge, bgRes)
            }
            return true
        } else if (result.isNotEmpty() && ms != 0L) {
            badge.visibility = View.VISIBLE
            badge.text = result
            badge.setCompoundDrawablesRelativeWithIntrinsicBounds(
                if (isReal) R.drawable.ic_minab_badge_real_fail else R.drawable.ic_minab_badge_ping,
                0, 0, 0
            )
            if (isStale) {
                applyStaleConfigBadgeStyle(badge, ctx)
            } else {
                applyFreshConfigBadgeStyle(badge, R.drawable.minab_ping_badge_fail)
            }
            return true
        } else {
            badge.alpha = 1f
            badge.visibility = View.GONE
            return false
        }
    }

    private fun applyStaleConfigBadgeStyle(badge: TextView, ctx: Context) {
        val staleTextColor = ContextCompat.getColor(ctx, R.color.minab_badge_stale_text)
        badge.setBackgroundResource(R.drawable.minab_badge_stale)
        badge.setTextColor(staleTextColor)
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 7.2f)
        badge.typeface = Typeface.create(badge.typeface, Typeface.NORMAL)
        TextViewCompat.setCompoundDrawableTintList(badge, ColorStateList.valueOf(staleTextColor))
        badge.alpha = 0.88f
    }

    private fun applyFreshConfigBadgeStyle(badge: TextView, bgRes: Int) {
        badge.setBackgroundResource(bgRes)
        badge.setTextColor(Color.WHITE)
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f)
        badge.typeface = Typeface.create(badge.typeface, Typeface.BOLD)
        TextViewCompat.setCompoundDrawableTintList(badge, ColorStateList.valueOf(Color.WHITE))
        badge.alpha = 1f
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

    private fun activeTargetProfiles(): List<VpnProfile> =
        if (selectedUuids.isNotEmpty()) selectedProfiles() else visibleProfiles()

    private fun activeScopeLabel(): String {
        val count = selectedUuids.size
        if (count > 0) return getString(R.string.minab_configs_selected_count, count)
        val cat = selectedCategory
        return when {
            cat == null -> getString(R.string.minab_all_tab)
            cat == CategoryStore.FAVORITES_TAB -> getString(R.string.minab_favorites_tab)
            cat == CategoryStore.ACTIVE_TAB -> getString(R.string.minab_active_tab)
            cat == CategoryStore.INACTIVE_TAB -> getString(R.string.minab_inactive_tab)
            else -> cat
        }
    }

    private fun updateSelectionBar() {
        if (!isAdded) return
        val count = selectedUuids.size
        selectionRow?.visibility = if (count > 0) View.VISIBLE else View.GONE
        if (count > 0) {
            selectionCountView?.text = getString(R.string.minab_configs_selected_count, count)
            credentialsSmartButton?.text = "${getString(R.string.minab_configs_login_short)} ($count)"
            backupButton?.text = "${getString(R.string.minab_configs_backup_short)} ($count)"
            shareButton?.text = "${getString(R.string.minab_configs_share_short)} ($count)"
            pingButton?.text = "${getString(R.string.minab_test_ping)} ($count)"
            realButton?.text = "${getString(R.string.minab_real_test)} ($count)"
        } else {
            credentialsSmartButton?.setText(R.string.minab_configs_login_short)
            backupButton?.setText(R.string.minab_configs_backup_short)
            shareButton?.setText(R.string.minab_configs_share_short)
            pingButton?.setText(R.string.minab_test_ping)
            realButton?.setText(R.string.minab_real_test)
        }
        val allShownSelected = displayedProfiles.isNotEmpty() &&
            displayedProfiles.all { selectedUuids.contains(it.getUUIDString()) }
        selectAllButton?.setText(
            if (allShownSelected) R.string.minab_deselect_all else R.string.minab_select_all
        )
    }

    private fun onBackupClicked() {
        val ctx = requireContext()
        val all = ProfileManager.getInstance(ctx).getProfiles().toList()
        if (all.isEmpty()) {
            Toast.makeText(ctx, R.string.minab_backup_none, Toast.LENGTH_SHORT).show()
            return
        }
        val selected = if (selectedUuids.isNotEmpty()) selectedProfiles() else emptyList()
        val visible = visibleProfiles()
        if (selected.isEmpty() && selectedCategory == null) {
            pendingBackupProfiles = all
            pendingBackupScopeType = MinabBackup.SCOPE_ALL
            pendingBackupScopeLabel = "All"
            backupLauncher.launch(MinabBackup.buildBackupFileName(MinabBackup.SCOPE_ALL, "All", all.size))
            return
        }

        val items = ArrayList<MinabDialogs.ActionItem>()
        if (selected.isNotEmpty()) {
            items.add(
                MinabDialogs.ActionItem(
                    title = getString(R.string.minab_configs_backup_scope_selected, selected.size),
                    iconRes = R.drawable.ic_minab_backup_small,
                    badgeText = selected.size.toString()
                ) {
                    pendingBackupProfiles = selected
                    pendingBackupScopeType = MinabBackup.SCOPE_SELECTED
                    pendingBackupScopeLabel = "Selected"
                    backupLauncher.launch(
                        MinabBackup.buildBackupFileName(MinabBackup.SCOPE_SELECTED, "Selected", selected.size)
                    )
                }
            )
        } else if (visible.isNotEmpty()) {
            val scopeLbl = activeScopeLabel()
            items.add(
                MinabDialogs.ActionItem(
                    title = getString(R.string.minab_configs_backup_scope_group, scopeLbl, visible.size),
                    iconRes = R.drawable.ic_minab_backup_small,
                    badgeText = visible.size.toString()
                ) {
                    pendingBackupProfiles = visible
                    pendingBackupScopeType = MinabBackup.SCOPE_GROUP
                    pendingBackupScopeLabel = scopeLbl
                    backupLauncher.launch(
                        MinabBackup.buildBackupFileName(MinabBackup.SCOPE_GROUP, scopeLbl, visible.size)
                    )
                }
            )
        }
        items.add(
            MinabDialogs.ActionItem(
                title = getString(R.string.minab_configs_backup_scope_all, all.size),
                iconRes = R.drawable.ic_minab_backup_small,
                badgeText = all.size.toString()
            ) {
                pendingBackupProfiles = all
                pendingBackupScopeType = MinabBackup.SCOPE_ALL
                pendingBackupScopeLabel = "All"
                backupLauncher.launch(
                    MinabBackup.buildBackupFileName(MinabBackup.SCOPE_ALL, "All", all.size)
                )
            }
        )
        MinabDialogs.showActionList(
            context = ctx,
            title = getString(R.string.minab_configs_backup_scope_title),
            iconRes = R.drawable.ic_minab_backup_small,
            items = items
        )
    }

    fun showSmartToolsMenu() {
        val ctx = context ?: return
        val all = ProfileManager.getInstance(ctx).getProfiles().toList()
        val uncategorizedCount = all.count {
            it.getAllGroups().singleOrNull() == CategoryStore.DEFAULT_CATEGORY
        }
        val emptyAutoCount = CategoryStore.getEmptyAutoCategories(ctx, all).size
        val emptyManualCount = CategoryStore.getEmptyManualCategories(ctx, all).size
        val totalEmptyCount = emptyAutoCount + emptyManualCount
        val dedupPlan = MinabProfileBus.planSmartDeduplication(ctx, all, MinabFavorites.getAll(ctx))
        val dupCount = dedupPlan.toRemove.size
        val deadCount = MinabServerStats.findDeadServers(ctx, all, minFails = 2, minNetworks = 1).size
        val failedCount = MinabProfileBus.findFailedProfiles(all).size

        val items = listOf(
            MinabDialogs.ActionItem(
                title = getString(R.string.minab_redetect_title),
                iconRes = R.drawable.ic_minab_globe_refresh,
                badgeText = uncategorizedCount.takeIf { it > 0 }?.toString(),
                hasAttentionDot = uncategorizedCount >= 3,
                subtitle = getString(R.string.minab_smart_redetect_sub)
            ) {
                MinabRedetectIpDialog.show(ctx, all, selectedProfiles(), selectedCategory)
            },
            MinabDialogs.ActionItem(
                title = getString(R.string.minab_clean_empty_title),
                iconRes = R.drawable.ic_baseline_folder_24,
                badgeText = totalEmptyCount.takeIf { it > 0 }?.toString(),
                hasAttentionDot = emptyAutoCount > 0,
                subtitle = getString(R.string.minab_smart_clean_empty_sub)
            ) {
                MinabDialogs.showCleanEmptyGroupsDialog(
                    context = ctx,
                    profilesProvider = { ProfileManager.getInstance(ctx).getProfiles().toList() }
                ) {
                    chipsSignature = null
                    lastListSignature = null
                    refreshList()
                }
            },
            MinabDialogs.ActionItem(
                title = getString(R.string.minab_remove_duplicates),
                iconRes = R.drawable.ic_minab_dedup,
                badgeText = dupCount.takeIf { it > 0 }?.toString(),
                hasAttentionDot = dupCount > 0,
                subtitle = getString(R.string.minab_smart_dedup_sub)
            ) {
                if (dupCount == 0) {
                    Toast.makeText(ctx, R.string.minab_no_duplicates, Toast.LENGTH_SHORT).show()
                } else {
                    MinabDialogs.showConfirm(
                        context = ctx,
                        title = getString(R.string.minab_remove_duplicates),
                        message = getString(R.string.minab_smart_dedup_confirm, dupCount),
                        positiveText = getString(R.string.minab_remove_duplicates)
                    ) {
                        val currentAll = ProfileManager.getInstance(ctx).getProfiles().toList()
                        val removed = MinabProfileBus.executeSmartDeduplication(ctx, currentAll)
                        clearSelection()
                        Toast.makeText(ctx, getString(R.string.minab_duplicates_removed, removed), Toast.LENGTH_SHORT).show()
                        chipsSignature = null
                        lastListSignature = null
                        refreshList()
                    }
                }
            },
            MinabDialogs.ActionItem(
                title = getString(R.string.minab_dead_servers_title),
                iconRes = R.drawable.ic_minab_dead_server,
                badgeText = deadCount.takeIf { it > 0 }?.toString(),
                hasAttentionDot = deadCount > 0,
                subtitle = getString(R.string.minab_smart_dead_sub)
            ) {
                MinabDeadServersDialog.show(ctx, all) {
                    clearSelection()
                    chipsSignature = null
                    lastListSignature = null
                    refreshList()
                }
            },
            MinabDialogs.ActionItem(
                title = getString(R.string.minab_remove_inactive),
                iconRes = R.drawable.ic_baseline_delete_outline_24,
                badgeText = failedCount.takeIf { it > 0 }?.toString(),
                isDanger = true,
                subtitle = getString(R.string.minab_smart_failed_sub)
            ) {
                MinabDialogs.showDeleteFailedDialog(ctx, all) {
                    clearSelection()
                    chipsSignature = null
                    lastListSignature = null
                    refreshList()
                }
            },
            MinabDialogs.ActionItem(
                title = getString(R.string.minab_manage_categories),
                iconRes = R.drawable.ic_baseline_folder_24
            ) {
                showManageCategoriesDialog()
            }
        )
        MinabDialogs.showActionList(
            context = ctx,
            title = getString(R.string.minab_smart_tools_title),
            iconRes = R.drawable.ic_minab_sparkle,
            items = items
        )
    }

    private fun showDeleteSelectedDialog() {
        val count = selectedUuids.size
        if (count == 0) return
        val ctx = requireContext()
        val pm = ProfileManager.getInstance(ctx)
        val targets = selectedProfiles()
        val currentGroup = selectedCategory?.takeIf { !CategoryStore.isPseudoTab(it) }

        fun deletePermanently() {
            val uuids = ArrayList<String>(targets.size)
            for (profile in targets) {
                uuids.add(profile.getUUIDString())
                pm.removeProfile(ctx, profile)
            }
            MinabServerStats.removeStatsForUuids(ctx, uuids)
            clearSelection()
            Toast.makeText(ctx, getString(R.string.minab_configs_deleted, targets.size), Toast.LENGTH_SHORT).show()
            chipsSignature = null
            lastListSignature = null
            refreshList()
            MinabProfileBus.notifyChanged()
        }

        if (currentGroup != null && currentGroup != CategoryStore.DEFAULT_CATEGORY) {
            MinabDialogs.showThreeWayConfirm(
                context = ctx,
                title = getString(R.string.minab_delete_selected),
                message = getString(R.string.minab_delete_selected_group_confirm, targets.size, currentGroup),
                positiveText = getString(R.string.minab_remove_from_group_short, currentGroup),
                neutralText = getString(R.string.minab_delete_permanently),
                isNeutralDanger = true,
                onPositive = {
                    for (profile in targets) {
                        profile.removeFromGroup(currentGroup)
                        ProfileManager.saveProfile(ctx, profile)
                    }
                    pm.saveProfileList(ctx)
                    clearSelection()
                    Toast.makeText(ctx, getString(R.string.minab_removed_from_group, targets.size, currentGroup), Toast.LENGTH_SHORT).show()
                    chipsSignature = null
                    lastListSignature = null
                    refreshList()
                    MinabProfileBus.notifyChanged()
                },
                onNeutral = { deletePermanently() }
            )
        } else {
            MinabDialogs.showConfirm(
                context = ctx,
                title = getString(R.string.minab_delete_selected),
                message = getString(R.string.minab_delete_selected_confirm, count),
                positiveText = getString(R.string.minab_delete_short),
                isDanger = true
            ) {
                deletePermanently()
            }
        }
    }

    private fun showCopyMoveDialog(isAddToGroup: Boolean) {
        val count = selectedUuids.size
        if (count == 0) return
        val ctx = requireContext()
        val categories = CategoryStore.getOrderedCategories(ctx)
            .filter { it != CategoryStore.DEFAULT_CATEGORY }
        val items = ArrayList<MinabDialogs.ActionItem>(categories.size + 1)
        for (cat in categories) {
            items.add(
                MinabDialogs.ActionItem(
                    title = cat,
                    iconRes = R.drawable.ic_baseline_folder_24
                ) {
                    copyMoveSelected(cat, isAddToGroup)
                }
            )
        }
        items.add(
            MinabDialogs.ActionItem(
                title = getString(R.string.minab_new_category),
                iconRes = R.drawable.ic_baseline_add_circle_outline_24
            ) {
                showNewCategoryDialog { name -> copyMoveSelected(name, isAddToGroup) }
            }
        )
        MinabDialogs.showActionList(
            context = ctx,
            title = getString(if (isAddToGroup) R.string.minab_add_to_group_title else R.string.minab_move_to_group_title),
            iconRes = R.drawable.ic_baseline_folder_24,
            items = items
        )
    }

    private fun copyMoveSelected(target: String, isAddToGroup: Boolean) {
        val context = requireContext()
        val pm = ProfileManager.getInstance(context)
        CategoryStore.addCategory(context, target)
        val currentGroup = selectedCategory?.takeIf { !CategoryStore.isPseudoTab(it) }
        var done = 0
        for (profile in selectedProfiles()) {
            if (isAddToGroup) {
                profile.addToGroup(target)
                ProfileManager.saveProfile(context, profile)
                done++
            } else {
                if (currentGroup != null) {
                    profile.moveGroup(currentGroup, target)
                } else {
                    profile.setCategory(target)
                }
                ProfileManager.saveProfile(context, profile)
                done++
            }
        }
        if (done > 0) pm.saveProfileList(context)
        clearSelection()
        Toast.makeText(
            context,
            getString(
                if (isAddToGroup) R.string.minab_added_to_group else R.string.minab_moved,
                done, target
            ),
            Toast.LENGTH_SHORT
        ).show()
        chipsSignature = null
        lastListSignature = null
        refreshList()
        MinabProfileBus.notifyChanged()
    }

    // --- Share configs (.ovpn or .zip) ---

    private fun shareCurrentScope() {
        val context = requireContext()
        val profiles = if (selectedUuids.isNotEmpty()) selectedProfiles() else visibleProfiles()
        if (profiles.isEmpty()) {
            Toast.makeText(context, R.string.minab_backup_none, Toast.LENGTH_SHORT).show()
            return
        }
        val (scopeType, scopeLabel) = when {
            selectedUuids.isNotEmpty() -> MinabBackup.SCOPE_SELECTED to "Selected"
            selectedCategory != null -> MinabBackup.SCOPE_GROUP to activeScopeLabel()
            else -> MinabBackup.SCOPE_ALL to "All"
        }
        shareProfiles(profiles, scopeType, scopeLabel)
    }

    private fun shareProfiles(
        profiles: List<VpnProfile>,
        scopeType: String = MinabBackup.SCOPE_SELECTED,
        scopeLabel: String? = null
    ) {
        if (profiles.isEmpty()) return
        val context = requireContext()
        Toast.makeText(context, R.string.minab_share_creating, Toast.LENGTH_SHORT).show()
        val appCtx = context.applicationContext
        val singleMode = profiles.size == 1
        BACKGROUND_EXECUTOR.execute {
            var count = 0
            var file: File? = null
            try {
                if (singleMode) {
                    val single = profiles[0]
                    val out = File(appCtx.cacheDir, MinabBackup.buildSingleOvpnFileName(single))
                    out.writeText(single.getExportConfigFile(), Charsets.UTF_8)
                    file = out
                    count = 1
                } else {
                    val out = File(
                        appCtx.cacheDir,
                        MinabBackup.buildShareZipFileName(scopeType, scopeLabel, profiles.size)
                    )
                    FileOutputStream(out).use { stream ->
                        count = MinabBackup.writeProfilesZip(appCtx, profiles, stream, scopeType, scopeLabel)
                    }
                    file = if (count > 0) out else null
                }
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
                    intent.type = if (singleMode) "application/x-openvpn-profile" else "application/zip"
                    intent.putExtra(Intent.EXTRA_STREAM, uri)
                    intent.putExtra(Intent.EXTRA_SUBJECT, outFile.name)
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(
                        Intent.createChooser(
                            intent,
                            getString(if (singleMode) R.string.minab_share_config else R.string.minab_share_zip)
                        )
                    )
                    clearSelection()
                }
            }
        }
    }

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
            for (grp in p.getAllGroups()) {
                val cat = CategoryStore.normalise(grp)
                groupCounts[cat] = (groupCounts[cat] ?: 0) + 1
            }
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
        val ctx = requireContext()
        MinabDialogs.showManageCategories(
            context = ctx,
            profilesProvider = { ProfileManager.getInstance(ctx).getProfiles().toList() },
            onRedetect = {
                val all = ProfileManager.getInstance(ctx).getProfiles().toList()
                MinabRedetectIpDialog.show(ctx, all, selectedProfiles(), selectedCategory)
            },
            onCategoryCreated = {
                chipsSignature = null
                refreshList()
                MinabProfileBus.notifyChanged()
            },
            onCategoryRenamed = { oldName, newName ->
                if (selectedCategory == oldName) {
                    selectedCategory = newName
                }
                chipsSignature = null
                lastListSignature = null
                refreshList()
                MinabProfileBus.notifyChanged()
            },
            onCategoryDeleted = { oldName ->
                if (selectedCategory == oldName) selectedCategory = null
                chipsSignature = null
                lastListSignature = null
                refreshList()
                MinabProfileBus.notifyChanged()
            },
            onCleanEmpty = {
                MinabDialogs.showCleanEmptyGroupsDialog(
                    context = ctx,
                    profilesProvider = { ProfileManager.getInstance(ctx).getProfiles().toList() }
                ) {
                    chipsSignature = null
                    lastListSignature = null
                    refreshList()
                }
            }
        )
    }

    private fun onConfigRowClick(profile: VpnProfile) {
        if (selectedUuids.isNotEmpty()) {
            toggleSelection(profile)
        } else {
            showConfigActionsDialog(profile)
        }
    }

    private fun showConfigActionsDialog(profile: VpnProfile) {
        val ctx = requireContext()
        val currentGroup = selectedCategory?.takeIf {
            !CategoryStore.isPseudoTab(it) && it != CategoryStore.DEFAULT_CATEGORY
        }
        val deleteTitle = if (currentGroup != null) {
            getString(R.string.minab_remove_or_delete)
        } else {
            getString(R.string.minab_delete_config)
        }
        val items = listOf(
            MinabDialogs.ActionItem(
                title = getString(R.string.minab_connect_server),
                iconRes = R.drawable.ic_minab_power
            ) { startVPN(profile) },
            MinabDialogs.ActionItem(
                title = getString(R.string.minab_rename_config),
                iconRes = R.drawable.ic_edit
            ) { showRenameDialog(profile) },
            MinabDialogs.ActionItem(
                title = getString(R.string.minab_change_credentials),
                iconRes = R.drawable.ic_minab_key_small
            ) { showProfileCredentialsDialog(profile) },
            MinabDialogs.ActionItem(
                title = getString(R.string.minab_manage_groups_for_server),
                iconRes = R.drawable.ic_baseline_folder_24,
                badgeText = profile.getAllGroups().size.toString()
            ) { showAssignCategoryDialog(profile) },
            MinabDialogs.ActionItem(
                title = getString(R.string.minab_ping_one),
                iconRes = R.drawable.ic_minab_ping_small
            ) { pingOne(profile) },
            MinabDialogs.ActionItem(
                title = getString(R.string.minab_real_test_one),
                iconRes = R.drawable.ic_minab_real_test_small
            ) { startRealTest(listOf(profile)) },
            MinabDialogs.ActionItem(
                title = getString(R.string.minab_redetect_btn),
                iconRes = R.drawable.ic_minab_globe_refresh
            ) {
                MinabRedetectIpDialog.show(
                    ctx,
                    ProfileManager.getInstance(ctx).getProfiles().toList(),
                    listOf(profile),
                    selectedCategory
                )
            },
            MinabDialogs.ActionItem(
                title = getString(R.string.minab_share_config),
                iconRes = R.drawable.ic_minab_share_small
            ) { shareProfiles(listOf(profile)) },
            MinabDialogs.ActionItem(
                title = deleteTitle,
                iconRes = R.drawable.ic_baseline_delete_outline_24,
                isDanger = true
            ) { showDeleteDialog(profile) }
        )
        MinabDialogs.showActionList(
            context = ctx,
            title = profile.mName ?: "",
            iconRes = R.drawable.ic_minab_tune,
            subtitle = "[${profile.getGroupsDisplayText()}]",
            items = items
        )
    }

    private fun showRenameDialog(profile: VpnProfile) {
        val ctx = requireContext()
        MinabDialogs.showTextInput(
            context = ctx,
            title = getString(R.string.minab_rename_config),
            initialText = profile.mName ?: ""
        ) { raw ->
            val name = raw.trim()
            if (name.isNotEmpty()) {
                profile.mName = name
                ProfileManager.saveProfile(ctx, profile)
                Toast.makeText(ctx, R.string.minab_config_renamed, Toast.LENGTH_SHORT).show()
                refreshList()
                MinabProfileBus.notifyChanged()
            }
        }
    }

    private fun showProfileCredentialsDialog(profile: VpnProfile) {
        if (!profile.isUserPWAuth()) {
            Toast.makeText(requireContext(), R.string.minab_credentials_none_applied, Toast.LENGTH_SHORT).show()
            return
        }
        showUnifiedCredentialsDialog(
            title = getString(R.string.minab_change_credentials),
            scopeSummary = getString(R.string.minab_credentials_scope_single, profile.mName ?: ""),
            isAllScopeWarning = false,
            targets = listOf(profile),
            showSaveDefaultOption = false
        )
    }

    private fun showUnifiedCredentialsDialog(
        title: String,
        scopeSummary: String,
        isAllScopeWarning: Boolean,
        targets: List<VpnProfile>,
        showSaveDefaultOption: Boolean
    ) {
        val ctx = requireContext()
        val single = if (targets.size == 1) targets[0] else null
        MinabDialogs.showCredentialsDialog(
            context = ctx,
            title = title,
            scopeSummary = scopeSummary,
            isAllScopeWarning = isAllScopeWarning,
            showSaveDefaultOption = showSaveDefaultOption,
            initialSaveDefault = false,
            initialUsername = single?.mUsername ?: "",
            initialPassword = single?.mPassword ?: "",
            positiveText = getString(R.string.minab_apply_credentials),
            onConfirm = { user, pass, saveAsDefault ->
                if (saveAsDefault && (user.isNotEmpty() || pass.isNotEmpty())) {
                    SharedCredentials.save(ctx, user, pass)
                }
                val updated = SharedCredentials.applyToProfiles(ctx, targets, user, pass)
                Toast.makeText(
                    ctx,
                    getString(R.string.minab_credentials_applied, updated),
                    Toast.LENGTH_LONG
                ).show()
                refreshList()
                MinabProfileBus.notifyChanged()
            }
        )
    }

    private fun showAssignCategoryDialog(profile: VpnProfile) {
        val ctx = requireContext()
        val categories = CategoryStore.getOrderedCategories(ctx)
            .filter { it != CategoryStore.DEFAULT_CATEGORY }
        val options = ArrayList<MinabDialogs.MultiChoiceOption>(categories.size + 1)
        for (cat in categories) {
            options.add(
                MinabDialogs.MultiChoiceOption(
                    label = cat,
                    checked = profile.belongsToGroup(cat)
                )
            )
        }
        options.add(
            MinabDialogs.MultiChoiceOption(
                label = "+ ${getString(R.string.minab_new_category)}",
                sublabel = getString(R.string.minab_new_group_assign_hint),
                checked = false
            )
        )
        MinabDialogs.showMultiChoice(
            context = ctx,
            title = getString(R.string.minab_manage_groups_for_server),
            options = options,
            positiveText = getString(android.R.string.ok),
            showSelectAll = false
        ) { pickedIndices, _ ->
            val createNewIdx = categories.size
            val wantsNew = pickedIndices.contains(createNewIdx)
            val chosenGroups = pickedIndices
                .filter { it < categories.size }
                .map { categories[it] }
            profile.setAllGroups(chosenGroups)
            ProfileManager.saveProfile(ctx, profile)
            if (wantsNew) {
                showNewCategoryDialog { newGroup ->
                    profile.addToGroup(newGroup)
                    ProfileManager.saveProfile(ctx, profile)
                    chipsSignature = null
                    lastListSignature = null
                    refreshList()
                    MinabProfileBus.notifyChanged()
                }
            } else {
                chipsSignature = null
                lastListSignature = null
                refreshList()
                MinabProfileBus.notifyChanged()
            }
        }
    }

    private fun showNewCategoryDialog(onCreated: (String) -> Unit) {
        val ctx = requireContext()
        MinabDialogs.showTextInput(
            context = ctx,
            title = getString(R.string.minab_new_group),
            hint = getString(R.string.minab_group_name_hint)
        ) { raw ->
            val name = CategoryStore.normalise(raw)
            CategoryStore.addManualCategory(ctx, name)
            onCreated(name)
        }
    }

    private fun showDeleteDialog(profile: VpnProfile) {
        val ctx = requireContext()
        val pm = ProfileManager.getInstance(ctx)
        val currentGroup = selectedCategory?.takeIf {
            !CategoryStore.isPseudoTab(it) && it != CategoryStore.DEFAULT_CATEGORY
        }
        fun deletePermanently() {
            val uuid = profile.getUUIDString()
            pm.removeProfile(ctx, profile)
            MinabServerStats.removeStatsForUuids(ctx, listOf(uuid))
            selectedUuids.remove(uuid)
            Toast.makeText(ctx, R.string.minab_config_deleted, Toast.LENGTH_SHORT).show()
            chipsSignature = null
            lastListSignature = null
            refreshList()
            MinabProfileBus.notifyChanged()
        }
        if (currentGroup != null) {
            MinabDialogs.showThreeWayConfirm(
                context = ctx,
                title = profile.mName ?: getString(R.string.minab_delete_config),
                message = getString(R.string.minab_delete_single_group_confirm, profile.mName ?: "", currentGroup),
                positiveText = getString(R.string.minab_remove_from_group_short, currentGroup),
                neutralText = getString(R.string.minab_delete_permanently),
                isNeutralDanger = true,
                onPositive = {
                    profile.removeFromGroup(currentGroup)
                    ProfileManager.saveProfile(ctx, profile)
                    pm.saveProfileList(ctx)
                    Toast.makeText(
                        ctx,
                        getString(R.string.minab_removed_from_group, 1, currentGroup),
                        Toast.LENGTH_SHORT
                    ).show()
                    chipsSignature = null
                    lastListSignature = null
                    refreshList()
                    MinabProfileBus.notifyChanged()
                },
                onNeutral = { deletePermanently() }
            )
        } else {
            MinabDialogs.showConfirm(
                context = ctx,
                title = getString(R.string.minab_delete_config),
                message = getString(R.string.minab_delete_config_confirm, profile.mName ?: ""),
                positiveText = getString(R.string.minab_delete_short),
                isDanger = true
            ) {
                deletePermanently()
            }
        }
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
                    MinabStaleResults.clearPingStale(saveContext, profileToSave.getUUIDString())
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
            else -> all.filter { it.belongsToGroup(wanted) }
        }
    }

    private fun clearTargetsInMemoryAndSave(
        targets: List<VpnProfile>,
        clearPing: Boolean,
        clearReal: Boolean
    ) {
        if (targets.isEmpty() || (!clearPing && !clearReal)) return
        val appCtx = context?.applicationContext ?: return
        val uuids = ArrayList<String>(targets.size)
        for (profile in targets) {
            uuids.add(profile.getUUIDString())
            if (clearPing) {
                profile.mLastPingMs = 0L
                profile.mLastPingTime = 0L
                profile.mLastPingResult = ""
            }
            if (clearReal) {
                profile.mLastRealMs = 0L
                profile.mLastRealTime = 0L
                profile.mLastRealResult = ""
            }
        }
        if (clearPing) MinabStaleResults.clearPingStaleAll(appCtx, uuids)
        if (clearReal) MinabStaleResults.clearRealStaleAll(appCtx, uuids)
        val snapshot = targets.toList()
        BACKGROUND_EXECUTOR.execute {
            for (profile in snapshot) {
                try {
                    ProfileManager.saveProfile(appCtx, profile)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun restoreUntestedAfterRun(
        targets: List<VpnProfile>,
        snapshots: Map<String, MinabStaleResults.ResultSnapshot>,
        runStartMs: Long,
        clearedPing: Boolean,
        clearedReal: Boolean
    ) {
        if (targets.isEmpty() || snapshots.isEmpty() || (!clearedPing && !clearedReal)) return
        val appCtx = context?.applicationContext ?: return
        val restored = MinabStaleResults.restoreUntested(
            appCtx, targets, snapshots, runStartMs, clearedPing, clearedReal
        )
        if (restored.isNotEmpty()) {
            val saveList = restored.toList()
            BACKGROUND_EXECUTOR.execute {
                for (profile in saveList) {
                    try {
                        ProfileManager.saveProfile(appCtx, profile)
                    } catch (_: Exception) {
                    }
                }
            }
        }
        lastListSignature = null
        chipsSignature = null
        if (isAdded) {
            refreshList()
            MinabProfileBus.notifyChanged()
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
        pingRunStartMs = System.currentTimeMillis()
        activePingTargets = profiles.toList()
        activePingSnapshots = MinabStaleResults.captureSnapshots(activePingTargets)
        clearTargetsInMemoryAndSave(activePingTargets, clearPing = true, clearReal = false)
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
                    MinabStaleResults.clearPingStale(saveContext, profileToSave.getUUIDString())
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
                    val pTargets = activePingTargets
                    val pSnaps = activePingSnapshots
                    activePingTargets = emptyList()
                    activePingSnapshots = emptyMap()
                    if (!isAdded) {
                        restoreUntestedAfterRun(pTargets, pSnaps, pingRunStartMs, clearedPing = true, clearedReal = false)
                        return@runOnUiThread
                    }
                    if (cancelled) {
                        restoreUntestedAfterRun(pTargets, pSnaps, pingRunStartMs, clearedPing = true, clearedReal = false)
                        Toast.makeText(requireContext(), R.string.minab_ping_cancelled, Toast.LENGTH_SHORT).show()
                        testProgressPanel?.hide()
                        MinabProfileBus.notifyChanged()
                    } else {
                        val testedInRun = profiles.filter { it.mLastPingTime >= pingRunStartMs }
                        MinabTestHistory.recordRunFromProfiles(
                            requireContext(),
                            false,
                            "TCP/Ping",
                            currentTestNetSnapshot,
                            testedInRun,
                            pingDoneCount,
                            pingOkCount
                        )
                        restoreUntestedAfterRun(pTargets, pSnaps, pingRunStartMs, clearedPing = true, clearedReal = false)
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
        val pTargets = activePingTargets
        val pSnaps = activePingSnapshots
        activePingTargets = emptyList()
        activePingSnapshots = emptyMap()
        pingTester?.cancel()
        pingTester = null
        MinabTestCoordinator.setRunning(MinabTestCoordinator.Source.CONFIGS_PING, false)
        restoreUntestedAfterRun(pTargets, pSnaps, pingRunStartMs, clearedPing = true, clearedReal = false)
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
        val willRunPing = MinabStaleResults.willRealTestRunPingPhase(requireContext(), profiles)
        activeRealTargets = profiles.toList()
        activeRealSnapshots = MinabStaleResults.captureSnapshots(activeRealTargets)
        activeRealClearedPing = willRunPing
        activeRealStartMs = System.currentTimeMillis()
        clearTargetsInMemoryAndSave(activeRealTargets, clearPing = willRunPing, clearReal = true)
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
                    val appCtx = context?.applicationContext
                    if (appCtx != null) {
                        if (profile.mLastRealTime >= activeRealStartMs) {
                            MinabStaleResults.clearRealStale(appCtx, profile.getUUIDString())
                        }
                        if (profile.mLastPingTime >= activeRealStartMs) {
                            MinabStaleResults.clearPingStale(appCtx, profile.getUUIDString())
                        }
                    }
                    if (realPingPhaseActive && profile.mLastPingMs > 0) {
                        realPingOkCount++
                        testProgressPanel?.showRealPingPhase(pingDoneCount, pingTotalCount, realPingOkCount)
                    }
                    if (sortMode == MinabProfileBus.SORT_REAL) resortConfigsAdapter()
                    else requestListRefresh()
                }

                override fun onFinished(cancelled: Boolean, working: Int) {
                    if (runId != realTestRunId) return
                    val runStartMs = realTester?.getRunStartTimeMs() ?: activeRealStartMs
                    realTester = null
                    realTestActive = false
                    realPingPhaseActive = false
                    stopRealCountdown()
                    MinabTestCoordinator.setRunning(MinabTestCoordinator.Source.CONFIGS_REAL, false)
                    val rTargets = activeRealTargets
                    val rSnaps = activeRealSnapshots
                    val rClearedPing = activeRealClearedPing
                    activeRealTargets = emptyList()
                    activeRealSnapshots = emptyMap()
                    if (!isAdded) {
                        restoreUntestedAfterRun(rTargets, rSnaps, runStartMs, rClearedPing, clearedReal = true)
                        return
                    }
                    if (cancelled) {
                        testProgressPanel?.hide()
                        restoreUntestedAfterRun(rTargets, rSnaps, runStartMs, rClearedPing, clearedReal = true)
                        MinabProfileBus.notifyChanged()
                        return
                    }
                    if (realTotalCount == 0) {
                        testProgressPanel?.hide()
                        restoreUntestedAfterRun(rTargets, rSnaps, runStartMs, rClearedPing, clearedReal = true)
                        Toast.makeText(requireContext(), R.string.minab_real_none_to_test, Toast.LENGTH_LONG).show()
                        MinabProfileBus.notifyChanged()
                        return
                    }
                    val testedInRun = profiles.filter {
                        it.mLastRealTime >= runStartMs || it.mLastPingTime >= runStartMs
                    }
                    MinabTestHistory.recordRunFromProfiles(
                        requireContext(),
                        true,
                        MinabRealTestSettings.getShortHostLabel(requireContext()),
                        currentTestNetSnapshot,
                        testedInRun,
                        realCountdownDone,
                        working
                    )
                    restoreUntestedAfterRun(rTargets, rSnaps, runStartMs, rClearedPing, clearedReal = true)
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
            restoreUntestedAfterRun(
                activeRealTargets, activeRealSnapshots, activeRealStartMs, activeRealClearedPing, clearedReal = true
            )
            activeRealTargets = emptyList()
            activeRealSnapshots = emptyMap()
            return
        }
        realTester = tester
        realTestActive = true
        MinabTestCoordinator.setRunning(MinabTestCoordinator.Source.CONFIGS_REAL, true)
        testProgressPanel?.showRealPingPhase(0, profiles.size, 0)
    }

    private fun cancelRealTest() {
        val rTargets = activeRealTargets
        val rSnaps = activeRealSnapshots
        val rClearedPing = activeRealClearedPing
        val rStartMs = realTester?.getRunStartTimeMs() ?: activeRealStartMs
        activeRealTargets = emptyList()
        activeRealSnapshots = emptyMap()
        realTestRunId++
        realTester?.cancel()
        realTester = null
        realTestActive = false
        realPingPhaseActive = false
        stopRealCountdown()
        MinabTestCoordinator.setRunning(MinabTestCoordinator.Source.CONFIGS_REAL, false)
        restoreUntestedAfterRun(rTargets, rSnaps, rStartMs, rClearedPing, clearedReal = true)
        if (pingTester == null) testProgressPanel?.hide()
    }

    private fun startVPN(profile: VpnProfile) {
        MinabConnectState.markPendingConnect(profile.getUUIDString())
        ProfileManager.saveProfile(requireContext(), profile)
        val intent = Intent(requireContext(), LaunchVPN::class.java)
        intent.putExtra(LaunchVPN.EXTRA_KEY, profile.getUUID().toString())
        intent.putExtra(OpenVPNService.EXTRA_START_REASON, "configs real test")
        intent.putExtra(LaunchVPN.EXTRA_HIDELOG, true)
        intent.putExtra(OpenVPNService.EXTRA_CONNECT_UPTIME, SystemClock.uptimeMillis())
        intent.action = Intent.ACTION_MAIN
        startActivity(intent)
    }

    private fun exportBackup(uri: Uri) {
        val context = requireContext().applicationContext
        val targets = pendingBackupProfiles
        val scopeType = pendingBackupScopeType
        val scopeLabel = pendingBackupScopeLabel
        pendingBackupProfiles = null
        BACKGROUND_EXECUTOR.execute {
            var count = 0
            var ok = false
            try {
                val profiles = targets ?: ProfileManager.getInstance(context).getProfiles().toList()
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    count = MinabBackup.writeFullBackup(context, profiles, out, scopeType, scopeLabel)
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

        fun notifyUuidsChanged(uuids: Set<String>) {
            if (items.isEmpty()) return
            if (uuids.isEmpty()) {
                notifyRowsUpdated()
                return
            }
            for (i in items.indices) {
                if (uuids.contains(items[i].getUUIDString())) {
                    notifyItemChanged(i + 1)
                }
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
            holder.subtitle.text = "[" + profile.getGroupsDisplayText() + "]"
            holder.flag.setImageResource(FlagHelper.flagResForProfile(profile, ctx))
            holder.flag.visibility = View.VISIBLE

            val rowState = MinabConnectState.getRowState(
                profile.getUUIDString(),
                VpnStatus.getLastLevel()
            )
            when (rowState) {
                MinabConnectState.RowVisualState.DISCONNECTING -> {
                    holder.rowContent.setBackgroundResource(R.drawable.minab_row_halo_disconnecting)
                    holder.connecting.indeterminateTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(ctx, R.color.minab_orb_disconnecting_end)
                    )
                    holder.connecting.visibility = View.VISIBLE
                }
                MinabConnectState.RowVisualState.TESTING,
                MinabConnectState.RowVisualState.CONNECTING -> {
                    holder.rowContent.setBackgroundResource(R.drawable.minab_row_halo_connecting)
                    holder.connecting.indeterminateTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(ctx, R.color.minab_busy)
                    )
                    holder.connecting.visibility = View.VISIBLE
                }
                MinabConnectState.RowVisualState.CONNECTED -> {
                    holder.rowContent.setBackgroundResource(R.drawable.minab_row_halo_connected)
                    holder.connecting.visibility = View.GONE
                }
                MinabConnectState.RowVisualState.IDLE -> {
                    if (selectableItemBgResId != 0) {
                        holder.rowContent.setBackgroundResource(selectableItemBgResId)
                    } else {
                        holder.rowContent.setBackgroundColor(Color.TRANSPARENT)
                    }
                    holder.connecting.visibility = View.GONE
                }
            }

            val uuid = profile.getUUIDString()
            val pingShown = setConfigBadge(
                holder.pingBadge,
                profile.mLastPingMs,
                profile.mLastPingResult,
                isReal = false,
                isStale = cachedStalePing.contains(uuid),
                ctx = ctx
            )
            val realShown = setConfigBadge(
                holder.realBadge,
                profile.mLastRealMs,
                profile.mLastRealResult,
                isReal = true,
                isStale = cachedStaleReal.contains(uuid),
                ctx = ctx
            )
            if (!pingShown && !realShown) {
                holder.badges.visibility = View.GONE
            } else {
                holder.badges.visibility = View.VISIBLE
                holder.pingBadge.visibility = if (pingShown) View.VISIBLE else View.INVISIBLE
                holder.realBadge.visibility = if (realShown) View.VISIBLE else View.INVISIBLE
            }

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
                showConfigActionsDialog(profile)
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
