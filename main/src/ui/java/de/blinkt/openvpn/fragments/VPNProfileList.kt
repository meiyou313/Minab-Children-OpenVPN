/*
 * Copyright (c) 2012-2019 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */
package de.blinkt.openvpn.fragments

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.Html
import android.text.SpannableString
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import de.blinkt.openvpn.LaunchVPN
import de.blinkt.openvpn.R
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.activities.BaseActivity
import de.blinkt.openvpn.activities.BulkImportActivity
import de.blinkt.openvpn.activities.ConfigConverter
import de.blinkt.openvpn.activities.DisconnectVPN
import de.blinkt.openvpn.activities.FileSelect
import de.blinkt.openvpn.activities.VPNPreferences
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.OpenVPNManagement
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.PasswordDialogFragment.Companion.newInstance
import de.blinkt.openvpn.core.Preferences
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VpnStatus
import de.blinkt.openvpn.core.VpnStatus.ByteCountListener
import de.blinkt.openvpn.core.VpnStatus.StateListener
import de.blinkt.openvpn.fragments.ImportRemoteConfig.Companion.newInstance
import de.blinkt.openvpn.fragments.Utils.alwaysUseOldFileChooser
import de.blinkt.openvpn.fragments.Utils.getWarningText
import de.blinkt.openvpn.minab.AutoCategory
import de.blinkt.openvpn.minab.CategoryStore
import de.blinkt.openvpn.minab.DataUsageStore
import de.blinkt.openvpn.minab.FlagHelper
import de.blinkt.openvpn.minab.MinabCategoryReorder
import de.blinkt.openvpn.minab.MinabConnectOrbView
import de.blinkt.openvpn.minab.MinabConnectState
import de.blinkt.openvpn.minab.MinabDialogs
import de.blinkt.openvpn.minab.MinabFailover
import de.blinkt.openvpn.minab.MinabFavorites
import de.blinkt.openvpn.minab.MinabIpCheck
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
import de.blinkt.openvpn.minab.MinabUpdateChecker
import de.blinkt.openvpn.minab.MinabUpdateNotifier
import de.blinkt.openvpn.minab.MinabVpnControl
import de.blinkt.openvpn.minab.MinabWidgetProvider
import de.blinkt.openvpn.minab.PingTester
import de.blinkt.openvpn.minab.RealTester
import java.util.LinkedList
import java.util.Locale
import java.util.TreeSet
import java.util.concurrent.Executors
import kotlin.math.min

class VPNProfileList : Fragment(), View.OnClickListener, StateListener, ByteCountListener {
    protected var mEditProfile: VpnProfile? = null
    private var mLastStatusMessage: String? = null
    private var mLastIntent: Intent? = null
    private var defaultVPN: VpnProfile? = null
    private var mPermissionView: View? = null
    private lateinit var mPermReceiver: ActivityResultLauncher<String>

    // Single RecyclerView hosting header + virtualised server rows + empty state.
    private var recyclerView: RecyclerView? = null
    private var headerView: View? = null
    private var emptyView: View? = null
    private var homeAdapter: HomeRecyclerAdapter? = null
    private var displayedProfiles: MutableList<VpnProfile> = mutableListOf()
    private var cachedFavorites: Set<String> = emptySet()

    // --- Minab Children state ---
    /** Null = show all categories; otherwise only this category is listed. */
    private var selectedCategory: String? = null
    private var sortMode: Int = MinabProfileBus.SORT_REAL
    private var currentTestNetSnapshot: MinabTestHistory.NetworkSnapshot? = null

    @Volatile
    private var pingTester: PingTester? = null
    private var pingDoneCount: Int = 0
    private var pingTotalCount: Int = 0
    private var pingOkCount: Int = 0
    private var pingLatestName: String? = null

    // --- Minab Real test state ---
    @Volatile
    private var realTestActive: Boolean = false
    private var realTester: RealTester? = null
    private var realTestRunId = 0

    @Volatile
    private var realDoneCount: Int = 0
    @Volatile
    private var realTotalCount: Int = 0
    @Volatile
    private var realWorkingCount: Int = 0
    @Volatile
    private var realTargetWorking: Int = 5
    @Volatile
    private var realPingPhaseActive: Boolean = false
    @Volatile
    private var realPingOkCount: Int = 0
    @Volatile
    private var realTestCurrentName: String? = null

    private val realCountdownHandler = Handler(Looper.getMainLooper())
    private var realCountdownSeconds: Int = 0
    private var realCountdownName: String? = null
    private val realCountdownRunnable: Runnable = Runnable {
        if (realTestActive && realCountdownSeconds > 0) {
            realCountdownSeconds--
            updateRealStatus()
            realCountdownHandler.postDelayed(realCountdownRunnable, 1000)
        }
    }

    // Coalesced row-only refresh so live tests never hammer the UI thread.
    private val listRefreshHandler = Handler(Looper.getMainLooper())
    private var listRefreshPending = false
    private val listRefreshRunnable = Runnable {
        listRefreshPending = false
        if (isAdded) homeAdapter?.submitList(displayedProfiles)
    }

    private fun requestListRefresh() {
        if (listRefreshPending) return
        listRefreshPending = true
        listRefreshHandler.postDelayed(listRefreshRunnable, 350)
    }

    private val realTestPauseHandler = Handler(Looper.getMainLooper())
    private val realTestPauseStop = Runnable {
        if (realTestActive) {
            cancelRealTest()
            try {
                Toast.makeText(requireContext(), R.string.minab_real_cancelled, Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
            }
        }
    }

    // Dedicated test progress box + real test settings panel
    private var testProgressPanel: MinabTestProgressPanel? = null
    private var realTestSettingsPanel: MinabRealTestSettingsPanel? = null
    private var categoryChipsContainer: LinearLayout? = null

    // Dynamic animated Connect Orb + connect card + test-time lock/dim overlay
    private var orbView: MinabConnectOrbView? = null
    private var connectPanel: View? = null
    private var connectBlocker: View? = null
    private var bestServerButton: Button? = null
    private var bestButtonHighlighted: Boolean = false
    private var sortButton: Button? = null
    private var searchEdit: EditText? = null
    private var searchClearBtn: ImageButton? = null
    private var connStatusDot: View? = null
    private var connStatusView: TextView? = null
    private var connNameView: TextView? = null
    private var connIpView: TextView? = null
    private var connPingView: TextView? = null
    private var connIpFlagView: ImageView? = null
    private var lastConnectDimmed: Boolean? = null

    // Live connection speed, session duration & data bar (shown while connected)
    private var liveStatsBar: View? = null
    private var liveDownView: TextView? = null
    private var liveUpView: TextView? = null
    private var liveDurationView: TextView? = null
    private var liveTotalView: TextView? = null
    private var emptyTitleView: TextView? = null
    private var emptySubtitleView: TextView? = null
    private var emptyAddButton: Button? = null

    private val sessionTimerHandler = Handler(Looper.getMainLooper())
    private var sessionConnectedSinceMs: Long = 0L
    private var sessionTimerRunning: Boolean = false
    private val sessionTimerRunnable = object : Runnable {
        override fun run() {
            if (!isAdded || !sessionTimerRunning) return
            updateSessionDurationText()
            sessionTimerHandler.postDelayed(this, 1000L)
        }
    }

    // Optimistic connecting & disconnecting transition hints for responsive orb feedback
    private val orbTransitionHandler = Handler(Looper.getMainLooper())
    private var optimisticConnectingUntilMs: Long = 0L
    private var disconnectingUntilMs: Long = 0L
    private val orbTransitionRefreshRunnable = Runnable {
        if (isAdded) updateOrbState()
    }

    @Volatile
    private var connectedPingTesting: Boolean = false
    private var lastLevel: ConnectionStatus? = null
    private var lastObservedActive: Boolean = false
    private var lastObservedUuid: String? = null

    // Search debounce & caches
    private val searchDebounceHandler = Handler(Looper.getMainLooper())
    private val searchDebounceRunnable = Runnable { populateVpnList() }
    private var chipsSignature: String? = null
    private var lastListSignature: String? = null
    private var lastShortcutsSignature: String? = null
    private var updateBanner: TextView? = null
    private var searchQuery: String = ""
    private var ipCheckKey: String? = null
    private var ipCheckKeyConnected: Boolean = false

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
        populateVpnList()
    }

    private val testStateListener = MinabTestCoordinator.Listener {
        if (isAdded) {
            updateOrbState()
        }
    }

    private fun isAnyTestRunning(): Boolean =
        pingTester != null || realTestActive || MinabTestCoordinator.isAnyTestRunning()

    override fun updateState(
        state: String?,
        logmessage: String?,
        localizedResId: Int,
        level: ConnectionStatus?,
        intent: Intent?
    ) {
        val activity = activity ?: return
        try {
            activity.runOnUiThread {
                try {
                    val prevLevel = lastLevel
                    val prevActive = lastObservedActive
                    val prevUuid = lastObservedUuid
                    val prevStatusMsg = mLastStatusMessage
                    val activeNow = VpnStatus.isVPNActive()
                    val uuidNow = VpnStatus.getLastConnectedVPNProfile()
                    val newStatusMsg = VpnStatus.getLastCleanLogMessage(activity)

                    lastLevel = level
                    lastObservedActive = activeNow
                    lastObservedUuid = uuidNow
                    mLastStatusMessage = newStatusMsg
                    mLastIntent = intent

                    if (activeNow || level == ConnectionStatus.LEVEL_CONNECTED ||
                        level == ConnectionStatus.LEVEL_START ||
                        level == ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET ||
                        level == ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED
                    ) {
                        optimisticConnectingUntilMs = 0L
                    }
                    // Subtle haptic cue when connection settles to connected or drops
                    if (prevLevel != ConnectionStatus.LEVEL_CONNECTED &&
                        level == ConnectionStatus.LEVEL_CONNECTED
                    ) {
                        recordSessionConnectedStart(activity)
                        performSubtleHaptic()
                    } else if (prevActive && !activeNow) {
                        clearSessionConnectedStart(activity)
                        if (!realTestActive) performSubtleHaptic()
                    }
                    // If a previously connected VPN just dropped on its own, show
                    // a brief disconnecting visual pulse before settling to idle.
                    if (prevActive && !activeNow && !realTestActive &&
                        SystemClock.uptimeMillis() >= disconnectingUntilMs
                    ) {
                        triggerDisconnectingTransition(550L)
                    }

                    val stateChanged = prevLevel != level || prevActive != activeNow || prevUuid != uuidNow
                    val statusMsgChanged = prevStatusMsg != newStatusMsg
                    if (level == ConnectionStatus.LEVEL_CONNECTED ||
                        level == ConnectionStatus.LEVEL_NOTCONNECTED
                    ) {
                        MinabConnectState.clearPendingConnect()
                    }
                    if (stateChanged) {
                        homeAdapter?.notifyRowsUpdated()
                        updateOrbState()
                        MinabWidgetProvider.updateAllWidgets(activity)
                    } else if (statusMsgChanged) {
                        requestListRefresh()
                    }
                    showUserRequestDialogIfNeeded(level, intent)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun triggerDisconnectingTransition(durationMs: Long) {
        optimisticConnectingUntilMs = 0L
        disconnectingUntilMs = SystemClock.uptimeMillis() + durationMs
        orbTransitionHandler.removeCallbacks(orbTransitionRefreshRunnable)
        orbTransitionHandler.postDelayed(orbTransitionRefreshRunnable, durationMs + 30L)
    }

    private fun triggerOptimisticConnecting() {
        disconnectingUntilMs = 0L
        optimisticConnectingUntilMs = SystemClock.uptimeMillis() + 2600L
        orbTransitionHandler.removeCallbacks(orbTransitionRefreshRunnable)
        orbTransitionHandler.postDelayed(orbTransitionRefreshRunnable, 2650L)
    }

    private fun showUserRequestDialogIfNeeded(level: ConnectionStatus?, intent: Intent?): Boolean {
        if (level == ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT) {
            if (intent != null && intent.getStringExtra(OpenVPNService.EXTRA_CHALLENGE_TXT) != null) {
                val pwInputFrag = newInstance(intent, false)
                pwInputFrag?.show(parentFragmentManager, "dialog")
                return true
            }
        }
        return false
    }

    override fun setConnectedVPN(uuid: String?) {
    }

    override fun updateByteCount(inBytes: Long, outBytes: Long, diffIn: Long, diffOut: Long) {
        val act = activity ?: return
        val interval = OpenVPNManagement.mBytecountInterval.coerceAtLeast(1)
        val downBps = (diffIn / interval).coerceAtLeast(0L)
        val upBps = (diffOut / interval).coerceAtLeast(0L)
        val total = (inBytes + outBytes).coerceAtLeast(0L)
        val res = act.resources
        val downText = "↓ " + OpenVPNService.humanReadableByteCount(downBps, true, res)
        val upText = "↑ " + OpenVPNService.humanReadableByteCount(upBps, true, res)
        val totalText = DataUsageStore.formatBytes(total)
        act.runOnUiThread {
            if (!isAdded) return@runOnUiThread
            liveDownView?.text = downText
            liveUpView?.text = upText
            liveTotalView?.text = totalText
        }
        MinabWidgetProvider.updateLiveSpeed(act, downText, upText)
    }

    private fun performSubtleHaptic() {
        try {
            val target = orbView ?: view
            target?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        } catch (_: Exception) {
        }
    }

    private fun recordSessionConnectedStart(ctx: Context) {
        val now = System.currentTimeMillis()
        sessionConnectedSinceMs = now
        try {
            ctx.getSharedPreferences("MinabPrefs", Context.MODE_PRIVATE)
                .edit().putLong("minab_connected_since_ms", now).apply()
        } catch (_: Exception) {
        }
    }

    private fun clearSessionConnectedStart(ctx: Context) {
        sessionConnectedSinceMs = 0L
        try {
            ctx.getSharedPreferences("MinabPrefs", Context.MODE_PRIVATE)
                .edit().remove("minab_connected_since_ms").apply()
        } catch (_: Exception) {
        }
    }

    private fun ensureSessionConnectedStart(ctx: Context) {
        if (sessionConnectedSinceMs > 0L) return
        val saved = try {
            ctx.getSharedPreferences("MinabPrefs", Context.MODE_PRIVATE)
                .getLong("minab_connected_since_ms", 0L)
        } catch (_: Exception) {
            0L
        }
        sessionConnectedSinceMs = if (saved > 0L) saved else System.currentTimeMillis()
    }

    private fun startSessionTimer() {
        val ctx = context ?: return
        ensureSessionConnectedStart(ctx)
        updateSessionDurationText()
        if (!sessionTimerRunning) {
            sessionTimerRunning = true
            sessionTimerHandler.removeCallbacks(sessionTimerRunnable)
            sessionTimerHandler.postDelayed(sessionTimerRunnable, 1000L)
        }
    }

    private fun stopSessionTimer() {
        sessionTimerRunning = false
        sessionTimerHandler.removeCallbacks(sessionTimerRunnable)
    }

    private fun updateSessionDurationText() {
        val view = liveDurationView ?: return
        val start = sessionConnectedSinceMs
        if (start <= 0L) {
            view.text = "00:00:00"
            return
        }
        val elapsedSec = ((System.currentTimeMillis() - start) / 1000L).coerceAtLeast(0L)
        val hours = elapsedSec / 3600L
        val mins = (elapsedSec % 3600L) / 60L
        val secs = elapsedSec % 60L
        view.text = String.format(Locale.US, "%02d:%02d:%02d", hours, mins, secs)
    }

    private fun startOrStopVPN(profile: VpnProfile) {
        if (isAnyTestRunning()) {
            Toast.makeText(requireContext(), R.string.minab_connect_locked_toast, Toast.LENGTH_SHORT).show()
            return
        }
        if (VpnStatus.isVPNActive() && profile.getUUIDString() == VpnStatus.getLastConnectedVPNProfile()) {
            if (lastLevel == ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT && mLastIntent != null) {
                startActivity(mLastIntent!!)
            } else {
                MinabConnectState.clearPendingConnect()
                triggerDisconnectingTransition(750L)
                updateOrbState()
                homeAdapter?.notifyRowsUpdated()
                MinabVpnControl.disconnect(requireContext())
            }
        } else {
            if (VpnStatus.isVPNActive()) MinabFailover.noteUserDisconnect()
            bestButtonHighlighted = false
            updateBestServerButtonHighlight()
            MinabConnectState.markPendingConnect(profile.getUUIDString())
            triggerOptimisticConnecting()
            updateOrbState()
            homeAdapter?.notifyRowsUpdated()
            startVPN(profile)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        sortMode = MinabProfileBus.getSortMode(requireContext())
        registerPermissionReceiver()
    }

    private fun registerPermissionReceiver() {
        mPermReceiver = registerForActivityResult(
            RequestPermission(),
            ActivityResultCallback { updateNotifBanner() }
        )
    }

    @RequiresApi(api = Build.VERSION_CODES.N_MR1)
    fun updateDynamicShortcuts() {
        val ctx = context ?: return
        val versionExtras = PersistableBundle()
        versionExtras.putInt("version", SHORTCUT_VERSION)

        val shortcutManager = ctx.getSystemService(ShortcutManager::class.java) ?: return
        if (shortcutManager.isRateLimitingActive) return

        val shortcuts = shortcutManager.dynamicShortcuts
        var maxvpn = shortcutManager.maxShortcutCountPerActivity - 1

        val disconnectShortcut = ShortcutInfo.Builder(ctx, "disconnectVPN")
            .setShortLabel("Disconnect")
            .setLongLabel("Disconnect VPN")
            .setIntent(
                Intent(ctx, DisconnectVPN::class.java).setAction(OpenVPNService.DISCONNECT_VPN)
            )
            .setIcon(Icon.createWithResource(ctx, R.drawable.ic_shortcut_cancel))
            .setExtras(versionExtras)
            .build()

        val newShortcuts = LinkedList<ShortcutInfo>()
        val updateShortcuts = LinkedList<ShortcutInfo>()
        val removeShortcuts = LinkedList<String>()
        val disableShortcuts = LinkedList<String>()
        var addDisconnect = true

        val sortedProfilesLRU = TreeSet<VpnProfile?>(VpnProfileLRUComparator())
        val profileManager = ProfileManager.getInstance(ctx)
        sortedProfilesLRU.addAll(profileManager.getProfiles())

        val lruProfiles = LinkedList<VpnProfile>()
        maxvpn = min(maxvpn, sortedProfilesLRU.size)
        for (i in 0 until maxvpn) {
            sortedProfilesLRU.pollFirst()?.let { lruProfiles.add(it) }
        }

        for (shortcut in shortcuts) {
            if (shortcut.id == "disconnectVPN") {
                addDisconnect = false
                if (shortcut.extras == null ||
                    shortcut.extras!!.getInt("version") != SHORTCUT_VERSION
                ) updateShortcuts.add(disconnectShortcut)
            } else {
                val p = ProfileManager.getIfPresent(ctx, shortcut.id)
                if (p == null || p.profileDeleted) {
                    if (shortcut.isEnabled) {
                        disableShortcuts.add(shortcut.id)
                        removeShortcuts.add(shortcut.id)
                    }
                    if (!shortcut.isPinned) removeShortcuts.add(shortcut.id)
                } else {
                    if (lruProfiles.contains(p)) lruProfiles.remove(p)
                    else removeShortcuts.add(p.getUUIDString())

                    if ((p.getName() != shortcut.shortLabel) || shortcut.extras == null ||
                        shortcut.extras!!.getInt("version") != SHORTCUT_VERSION
                    ) updateShortcuts.add(createShortcut(ctx, p))
                }
            }
        }
        if (addDisconnect) newShortcuts.add(disconnectShortcut)
        for (p in lruProfiles) newShortcuts.add(createShortcut(ctx, p))

        if (updateShortcuts.isNotEmpty()) shortcutManager.updateShortcuts(updateShortcuts)
        if (removeShortcuts.isNotEmpty()) shortcutManager.removeDynamicShortcuts(removeShortcuts)
        if (newShortcuts.isNotEmpty()) shortcutManager.addDynamicShortcuts(newShortcuts)
        if (disableShortcuts.isNotEmpty()) shortcutManager.disableShortcuts(
            disableShortcuts,
            "VpnProfile does not exist anymore."
        )
    }

    @RequiresApi(api = Build.VERSION_CODES.N_MR1)
    private fun createShortcut(ctx: Context, profile: VpnProfile): ShortcutInfo {
        val shortcutIntent = Intent(Intent.ACTION_MAIN)
        shortcutIntent.setClass(ctx, LaunchVPN::class.java)
        shortcutIntent.putExtra(LaunchVPN.EXTRA_KEY, profile.getUUID().toString())
        shortcutIntent.action = Intent.ACTION_MAIN
        shortcutIntent.putExtra(OpenVPNService.EXTRA_START_REASON, "shortcut")
        shortcutIntent.putExtra("EXTRA_HIDELOG", true)

        val versionExtras = PersistableBundle()
        versionExtras.putInt("version", SHORTCUT_VERSION)

        return ShortcutInfo.Builder(ctx, profile.getUUIDString())
            .setShortLabel(profile.getName())
            .setLongLabel(ctx.getString(R.string.qs_connect, profile.getName()))
            .setIcon(Icon.createWithResource(ctx, R.drawable.ic_shortcut_vpn_key))
            .setIntent(shortcutIntent)
            .setExtras(versionExtras)
            .build()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.vpn_profile_list, container, false)
        try {
            val rv = root.findViewById<RecyclerView>(R.id.minab_home_recycler)
            recyclerView = rv
            // CRITICAL: LayoutManager MUST be assigned BEFORE inflating any
            // child layout with rv as parent, because RecyclerView.generateLayoutParams
            // throws IllegalStateException("RecyclerView has no LayoutManager") when null.
            rv.layoutManager = LinearLayoutManager(requireContext())
            rv.setHasFixedSize(true)
            rv.itemAnimator = null
            rv.setItemViewCacheSize(12)

            val header = inflater.inflate(R.layout.vpn_profile_list_header, rv, false)
            headerView = header
            val empty = inflater.inflate(R.layout.vpn_profile_list_empty, rv, false)
            emptyView = empty
            emptyTitleView = empty.findViewById(R.id.minab_empty_title)
            emptySubtitleView = empty.findViewById(R.id.minab_empty_subtitle)
            emptyAddButton = empty.findViewById(R.id.minab_empty_add_btn)
            emptyAddButton?.setOnClickListener {
                startActivity(Intent(requireContext(), BulkImportActivity::class.java))
            }
            empty.findViewById<View>(R.id.minab_empty_icon_wrap)?.setOnClickListener {
                startActivity(Intent(requireContext(), BulkImportActivity::class.java))
            }

            header.findViewById<Button>(R.id.minab_btn_bulk_import)?.setOnClickListener {
                startActivity(Intent(requireContext(), BulkImportActivity::class.java))
            }
            header.findViewById<Button>(R.id.minab_btn_ping)?.setOnClickListener {
                startPingTest(visibleProfiles())
            }
            header.findViewById<Button>(R.id.minab_btn_real_test)?.setOnClickListener {
                startRealTest(visibleProfiles())
            }

            val realTestSettingsButton =
                header.findViewById<ImageButton>(R.id.minab_btn_real_test_settings)
            val realTestSettingsPanelView =
                header.findViewById<View>(R.id.minab_real_settings_panel)
            if (realTestSettingsButton != null && realTestSettingsPanelView != null) {
                realTestSettingsPanel = MinabRealTestSettingsPanel(
                    requireContext(), realTestSettingsButton, realTestSettingsPanelView
                )
            }

            header.findViewById<ImageButton>(R.id.minab_btn_test_history)?.setOnClickListener {
                MinabTestHistoryDialog.show(requireContext()) { chosen ->
                    startOrStopVPN(chosen)
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

            bestServerButton = header.findViewById(R.id.minab_btn_best_server)
            bestServerButton?.setOnClickListener { connectBestServer() }
            updateBestServerButtonHighlight()

            sortButton = header.findViewById(R.id.minab_btn_sort)
            sortButton?.setOnClickListener {
                MinabSortDialog.show(requireContext(), sortMode) { picked ->
                    sortMode = picked
                    updateSortButtonUi()
                    lastListSignature = null
                    populateVpnList()
                }
            }
            updateSortButtonUi()

            header.findViewById<ImageButton>(R.id.minab_btn_tools)?.setOnClickListener {
                showToolsDialog()
            }

            searchEdit = header.findViewById(R.id.minab_search)
            searchClearBtn = header.findViewById(R.id.minab_search_clear)
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

            connectPanel = header.findViewById(R.id.minab_connect_panel)
            connectBlocker = header.findViewById(R.id.minab_connect_blocker)
            connectBlocker?.setOnClickListener {
                Toast.makeText(requireContext(), R.string.minab_connect_locked_toast, Toast.LENGTH_SHORT).show()
            }
            orbView = header.findViewById(R.id.minab_power_orb)
            orbView?.setOnClickListener { toggleOrbConnection() }

            connStatusDot = header.findViewById(R.id.minab_conn_status_dot)
            connStatusView = header.findViewById(R.id.minab_conn_status)
            connNameView = header.findViewById(R.id.minab_conn_name)
            connIpView = header.findViewById(R.id.minab_conn_ip)
            connIpFlagView = header.findViewById(R.id.minab_conn_ip_flag)
            connPingView = header.findViewById(R.id.minab_conn_ping)
            connPingView?.setOnClickListener { retestConnectedPing() }

            liveStatsBar = header.findViewById(R.id.minab_live_stats_bar)
            liveDownView = header.findViewById(R.id.minab_live_down)
            liveUpView = header.findViewById(R.id.minab_live_up)
            liveDurationView = header.findViewById(R.id.minab_live_duration)
            liveTotalView = header.findViewById(R.id.minab_live_total)

            categoryChipsContainer = header.findViewById(R.id.minab_category_chips)
            updateBanner = header.findViewById(R.id.minab_update_banner)
            mPermissionView = header.findViewById(R.id.notification_permission)

            homeAdapter = HomeRecyclerAdapter(header, empty)
            rv.adapter = homeAdapter

            defaultVPN = try {
                ProfileManager.getAlwaysOnVPN(requireContext())
            } catch (_: Exception) {
                null
            }
            lastListSignature = null
            chipsSignature = null
            lastConnectDimmed = null
            populateVpnList()
            updateOrbState(animate = false)
            checkForUpdates()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !((activity as? BaseActivity)?.isAndroidTV == true)
            ) {
                checkForNotificationPermission(header)
            }
        } catch (e: Exception) {
            android.util.Log.e("VPNProfileList", "Minab: onCreateView failed", e)
        }

        return root
    }

    override fun onResume() {
        super.onResume()
        realTestPauseHandler.removeCallbacks(realTestPauseStop)
        orbView?.setAnimationsEnabled(true)
        MinabTestCoordinator.addListener(testStateListener)
        try {
            sortMode = MinabProfileBus.getSortMode(requireContext())
            updateSortButtonUi()
            realTestSettingsPanel?.refreshIfVisible()
            defaultVPN = ProfileManager.getAlwaysOnVPN(requireContext())
            lastListSignature = null
            chipsSignature = null
            populateVpnList()
            updateOrbState()
        } catch (e: Exception) {
            android.util.Log.e("VPNProfileList", "Minab: onResume populate failed", e)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            val shortcutSig = displayedProfiles.joinToString("|") { it.getUUIDString() + ":" + (it.mName ?: "") }
            if (shortcutSig != lastShortcutsSignature) {
                lastShortcutsSignature = shortcutSig
                BACKGROUND_EXECUTOR.execute {
                    try {
                        updateDynamicShortcuts()
                    } catch (_: Exception) {
                    }
                }
            }
        }
        VpnStatus.addStateListener(this)
        VpnStatus.addByteCountListener(this)
    }

    override fun onPause() {
        super.onPause()
        orbView?.setAnimationsEnabled(false)
        stopSessionTimer()
        MinabTestCoordinator.removeListener(testStateListener)
        VpnStatus.removeStateListener(this)
        VpnStatus.removeByteCountListener(this)
        searchDebounceHandler.removeCallbacks(searchDebounceRunnable)
        if (realTestActive) realTestPauseHandler.postDelayed(realTestPauseStop, 8000)
    }

    override fun onDestroyView() {
        MinabProfileBus.removeListener(busListener)
        stopSessionTimer()
        orbTransitionHandler.removeCallbacks(orbTransitionRefreshRunnable)
        listRefreshHandler.removeCallbacks(listRefreshRunnable)
        listRefreshPending = false
        recyclerView?.adapter = null
        recyclerView = null
        headerView = null
        emptyView = null
        emptyTitleView = null
        emptySubtitleView = null
        emptyAddButton = null
        homeAdapter = null
        orbView = null
        connectPanel = null
        connectBlocker = null
        bestServerButton = null
        sortButton = null
        searchEdit = null
        searchClearBtn = null
        connStatusDot = null
        connStatusView = null
        connNameView = null
        connIpView = null
        connPingView = null
        connIpFlagView = null
        liveStatsBar = null
        liveDownView = null
        liveUpView = null
        liveDurationView = null
        liveTotalView = null
        categoryChipsContainer = null
        testProgressPanel = null
        realTestSettingsPanel = null
        updateBanner = null
        mPermissionView = null
        lastListSignature = null
        chipsSignature = null
        lastConnectDimmed = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        cancelPingTest()
        searchDebounceHandler.removeCallbacks(searchDebounceRunnable)
        realTestPauseHandler.removeCallbacks(realTestPauseStop)
        cancelRealTest()
        super.onDestroy()
    }

    private fun checkForNotificationPermission(header: View) {
        val permView = header.findViewById<View>(R.id.notification_permission) ?: return
        mPermissionView = permView
        if (requireActivity().checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            permView.visibility = View.GONE
            return
        }
        val prefs = requireContext().getSharedPreferences("MinabPrefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("notif_perm_requested", false)) {
            prefs.edit().putBoolean("notif_perm_requested", true).apply()
            mPermReceiver.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        updateNotifBanner()
    }

    private fun updateNotifBanner() {
        val permView = mPermissionView ?: return
        if (!isAdded) return
        val granted =
            (requireActivity().checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        permView.visibility = if (granted) View.GONE else View.VISIBLE
        if (granted) return
        permView.setOnClickListener {
            if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                mPermReceiver.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                AlertDialog.Builder(requireContext())
                    .setMessage(R.string.minab_notification_open_settings)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + requireContext().packageName)
                        )
                        startActivity(intent)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun checkForUpdates() {
        val banner = updateBanner ?: return
        MinabUpdateChecker.check(requireContext(), false) { latestTag, available ->
            if (!isAdded) return@check
            if (available && latestTag != null) {
                banner.text = getString(R.string.minab_update_available, latestTag)
                banner.visibility = View.VISIBLE
                banner.setOnClickListener { MinabUpdateChecker.openLatestRelease(requireContext()) }
            } else {
                banner.visibility = View.GONE
            }
        }
        if (isAdded) MinabUpdateNotifier.maybeNotifyUpdate(requireContext())
    }

    private fun populateVpnList() {
        if (!isAdded || homeAdapter == null) return
        try {
            populateVpnListInner()
        } catch (_: Exception) {
        }
    }

    private fun listSignature(ordered: List<VpnProfile>, favorites: Set<String>): String {
        val defaultUuid = defaultVPN?.getUUIDString() ?: ""
        val sb = StringBuilder(ordered.size * 48)
        sb.append(selectedCategory ?: "all").append('|')
            .append(searchQuery).append('|').append(sortMode).append('|')
            .append(defaultUuid).append('|').append(ordered.size)
        for (p in ordered) {
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

    private fun populateVpnListInner() {
        val ctx = requireContext()
        val allvpn: Collection<VpnProfile> = this.pM.getProfiles()
        val favorites = MinabFavorites.getAll(ctx)
        val filtered = allvpn.filter { profile ->
            when {
                selectedCategory == null -> true
                selectedCategory == CategoryStore.FAVORITES_TAB ->
                    favorites.contains(profile.getUUIDString())
                selectedCategory == CategoryStore.ACTIVE_TAB ->
                    MinabVpnControl.isHealthy(profile)
                selectedCategory == CategoryStore.INACTIVE_TAB ->
                    !MinabVpnControl.isHealthy(profile)
                else -> profile.getCategory() == selectedCategory
            }
        }
        val query = searchQuery.lowercase()
        val searched = if (query.isEmpty()) filtered else filtered.filter { profile ->
            val name = profile.mName?.lowercase() ?: ""
            val category = profile.getCategory()?.lowercase() ?: ""
            name.contains(query) || category.contains(query)
        }
        val ordered = MinabProfileBus.sortProfiles(searched, sortMode, favorites, true)

        updateEmptyStateView(allvpn.isEmpty())

        val signature = listSignature(ordered, favorites)
        if (signature == lastListSignature) {
            rebuildCategoryChips(allvpn)
            return
        }
        lastListSignature = signature
        cachedFavorites = favorites
        displayedProfiles = ordered.toMutableList()
        homeAdapter?.submitList(displayedProfiles)
        rebuildCategoryChips(allvpn)
    }

    private fun updateEmptyStateView(noProfilesAtAll: Boolean) {
        if (noProfilesAtAll) {
            emptyTitleView?.setText(R.string.minab_empty_title)
            emptySubtitleView?.setText(R.string.minab_empty_subtitle)
            emptyAddButton?.visibility = View.VISIBLE
        } else {
            emptyTitleView?.setText(R.string.minab_empty_filter_title)
            emptySubtitleView?.setText(R.string.minab_empty_filter_subtitle)
            emptyAddButton?.visibility = View.GONE
        }
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

    private fun resortAdapter() {
        if (displayedProfiles.isEmpty()) return
        val favorites = MinabFavorites.getAll(requireContext())
        cachedFavorites = favorites
        displayedProfiles = MinabProfileBus.sortProfiles(
            displayedProfiles, sortMode, favorites, true
        ).toMutableList()
        lastListSignature = null
        requestListRefresh()
    }

    // --- Minab Children: categories ---

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun rebuildCategoryChips(profiles: Collection<VpnProfile> = pM.getProfiles()) {
        val container = categoryChipsContainer ?: return
        val context = requireContext()
        val ordered = CategoryStore.getDisplayCategories(context, profiles)
        if (selectedCategory != null &&
            !CategoryStore.isPseudoTab(selectedCategory) &&
            !ordered.contains(selectedCategory)
        ) {
            selectedCategory = null
        }
        val favorites = MinabFavorites.getAll(context)
        val allCount = profiles.size
        val favCount = profiles.count { favorites.contains(it.getUUIDString()) }
        val activeCount = profiles.count { MinabVpnControl.isHealthy(it) }
        val inactiveCount = (allCount - activeCount).coerceAtLeast(0)
        val groupCounts = HashMap<String, Int>()
        for (p in profiles) {
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
        addGroupTab(container, null, allCount)
        addStatusFilterTab(
            container, CategoryStore.ACTIVE_TAB, R.string.minab_active_tab, R.color.minab_connected, activeCount
        )
        addStatusFilterTab(
            container, CategoryStore.INACTIVE_TAB, R.string.minab_inactive_tab, R.color.minab_ping_fail, inactiveCount
        )
        for (category in ordered) {
            if (category == CategoryStore.DEFAULT_CATEGORY) continue
            addGroupTab(container, category, groupCounts[category] ?: 0)
        }
        if (ordered.contains(CategoryStore.DEFAULT_CATEGORY)) {
            addGroupTab(container, CategoryStore.DEFAULT_CATEGORY, groupCounts[CategoryStore.DEFAULT_CATEGORY] ?: 0)
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
            setOnClickListener { showNewCategoryDialog { populateVpnList() } }
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
                        rebuildCategoryChips()
                        populateVpnList()
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
            populateVpnList()
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
            populateVpnList()
            if (MinabFavorites.getAll(context).isEmpty()) {
                Toast.makeText(context, R.string.minab_no_favorites_yet, Toast.LENGTH_SHORT).show()
            }
        }
        container.addView(tab)
    }

    private fun addGroupTab(container: LinearLayout, category: String?, count: Int) {
        val context = requireContext()
        val selected = category == selectedCategory
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
        val base = category ?: getString(R.string.minab_all_tab)
        val label = TextView(context).apply {
            text = "$base ($count)"
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
            selectedCategory = category
            populateVpnList()
        }
        container.addView(tab)
    }

    private fun showCategoryFilterDialog() {
        val categories = mutableListOf<String?>(null)
        categories.addAll(CategoryStore.getOrderedCategories(requireContext()))
        val labels = categories.map { it ?: getString(R.string.minab_all_categories) }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.minab_filter_category)
            .setItems(labels) { _, which ->
                selectedCategory = categories[which]
                populateVpnList()
            }
            .setNeutralButton(R.string.minab_manage_categories) { _, _ ->
                showManageCategoriesDialog()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRowContextDialog(profile: VpnProfile) {
        val actions = arrayOf(
            getString(R.string.minab_assign_category),
            getString(R.string.minab_ping_one),
            getString(R.string.minab_real_test_one),
            getString(R.string.minab_redetect_btn)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(profile.getName())
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> showAssignCategoryDialog(profile)
                    1 -> startPingTest(listOf(profile))
                    2 -> startRealTest(listOf(profile))
                    3 -> MinabRedetectIpDialog.show(
                        requireContext(),
                        pM.getProfiles().toList(),
                        listOf(profile),
                        selectedCategory
                    )
                }
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
        val ctx = requireContext()
        MinabDialogs.showTextInput(
            context = ctx,
            title = getString(R.string.minab_new_category),
            hint = getString(R.string.minab_group_name_hint)
        ) { raw ->
            val name = CategoryStore.normalise(raw)
            CategoryStore.addManualCategory(ctx, name)
            onCreated(name)
        }
    }

    private fun assignCategory(profile: VpnProfile, category: String) {
        profile.setCategory(category)
        CategoryStore.addCategory(requireContext(), category)
        ProfileManager.saveProfile(requireContext(), profile)
        lastListSignature = null
        chipsSignature = null
        populateVpnList()
        MinabProfileBus.notifyChanged()
    }

    private fun showManageCategoriesDialog() {
        val ctx = requireContext()
        MinabDialogs.showManageCategories(
            context = ctx,
            profilesProvider = { pM.getProfiles().toList() },
            onRedetect = {
                MinabRedetectIpDialog.show(
                    ctx,
                    pM.getProfiles().toList(),
                    emptyList(),
                    selectedCategory
                )
            },
            onCategoryCreated = {
                chipsSignature = null
                populateVpnList()
                MinabProfileBus.notifyChanged()
            },
            onCategoryRenamed = { oldName, newName ->
                if (selectedCategory == oldName) {
                    selectedCategory = newName
                }
                chipsSignature = null
                lastListSignature = null
                populateVpnList()
                MinabProfileBus.notifyChanged()
            },
            onCategoryDeleted = { oldName ->
                if (selectedCategory == oldName) selectedCategory = null
                chipsSignature = null
                lastListSignature = null
                populateVpnList()
                MinabProfileBus.notifyChanged()
            }
        )
    }

    private fun showToolsDialog() {
        val ctx = requireContext()
        val all = pM.getProfiles().toList()
        val dupCount = MinabProfileBus.findDuplicates(all, MinabFavorites.getAll(ctx)).size
        val inactiveCount = MinabProfileBus.findInactiveProfiles(all).size
        val items = listOf(
            MinabDialogs.ActionItem(
                title = getString(R.string.minab_redetect_title),
                iconRes = R.drawable.ic_minab_globe_refresh
            ) {
                MinabRedetectIpDialog.show(
                    ctx,
                    pM.getProfiles().toList(),
                    emptyList(),
                    selectedCategory
                )
            },
            MinabDialogs.ActionItem(
                title = getString(R.string.minab_remove_duplicates),
                iconRes = R.drawable.ic_minab_dedup,
                badgeText = dupCount.takeIf { it > 0 }?.toString()
            ) {
                showRemoveDuplicatesDialog()
            },
            MinabDialogs.ActionItem(
                title = getString(R.string.minab_remove_inactive),
                iconRes = R.drawable.ic_baseline_delete_outline_24,
                badgeText = inactiveCount.takeIf { it > 0 }?.toString(),
                isDanger = true
            ) {
                showRemoveInactiveDialog()
            },
            MinabDialogs.ActionItem(
                title = getString(R.string.minab_history_title),
                iconRes = R.drawable.ic_minab_history
            ) {
                MinabTestHistoryDialog.show(ctx) { startOrStopVPN(it) }
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
            title = getString(R.string.minab_tools_menu_title),
            iconRes = R.drawable.ic_minab_tools,
            items = items
        )
    }

    private fun showRemoveDuplicatesDialog() {
        val ctx = requireContext()
        val all = pM.getProfiles().toList()
        val dups = MinabProfileBus.findDuplicates(all, MinabFavorites.getAll(ctx))
        if (dups.isEmpty()) {
            Toast.makeText(ctx, R.string.minab_no_duplicates, Toast.LENGTH_SHORT).show()
            return
        }
        MinabDialogs.showConfirm(
            context = ctx,
            title = getString(R.string.minab_remove_duplicates),
            message = getString(R.string.minab_remove_duplicates_confirm, dups.size),
            positiveText = getString(R.string.minab_remove_duplicates),
            isDanger = true
        ) {
            for (p in dups) {
                pM.removeProfile(ctx, p)
            }
            Toast.makeText(ctx, getString(R.string.minab_duplicates_removed, dups.size), Toast.LENGTH_SHORT).show()
            lastListSignature = null
            chipsSignature = null
            populateVpnList()
            MinabProfileBus.notifyChanged()
        }
    }

    private fun showRemoveInactiveDialog() {
        val ctx = requireContext()
        val inactive = MinabProfileBus.findInactiveProfiles(pM.getProfiles().toList())
        if (inactive.isEmpty()) {
            Toast.makeText(ctx, R.string.minab_no_inactive_to_remove, Toast.LENGTH_SHORT).show()
            return
        }
        MinabDialogs.showConfirm(
            context = ctx,
            title = getString(R.string.minab_remove_inactive),
            message = getString(R.string.minab_remove_inactive_confirm, inactive.size),
            positiveText = getString(R.string.minab_remove_inactive),
            isDanger = true
        ) {
            for (p in inactive) {
                pM.removeProfile(ctx, p)
            }
            Toast.makeText(ctx, getString(R.string.minab_inactive_removed, inactive.size), Toast.LENGTH_SHORT).show()
            lastListSignature = null
            chipsSignature = null
            populateVpnList()
            MinabProfileBus.notifyChanged()
        }
    }

    // --- Dynamic animated Connect Orb & connect card ---

    private fun orbTargetProfile(): VpnProfile? {
        val all = pM.getProfiles().toList()
        if (all.isEmpty()) return null
        if (VpnStatus.isVPNActive()) {
            val connected = VpnStatus.getLastConnectedVPNProfile()
            all.firstOrNull { it.getUUIDString() == connected }?.let { return it }
        }
        return all.maxByOrNull { it.mLastUsed } ?: all.firstOrNull()
    }

    private fun toggleOrbConnection() {
        performSubtleHaptic()
        if (isAnyTestRunning()) {
            Toast.makeText(requireContext(), R.string.minab_connect_locked_toast, Toast.LENGTH_SHORT).show()
            return
        }
        val target = orbTargetProfile()
        if (target == null) {
            Toast.makeText(requireContext(), R.string.minab_no_profiles_yet, Toast.LENGTH_LONG).show()
            startActivity(Intent(requireContext(), BulkImportActivity::class.java))
            return
        }
        startOrStopVPN(target)
    }

    /** Smoothly dims and disables the connect area while any test runs,
        and restores full interactivity when the test finishes or is cancelled. */
    private fun applyConnectSectionLock(locked: Boolean, animate: Boolean = true) {
        val panel = connectPanel ?: return
        val blocker = connectBlocker
        val bestBtn = bestServerButton
        val orb = orbView

        orb?.isEnabled = !locked
        bestBtn?.isEnabled = !locked

        val targetPanelAlpha = if (locked) 0.36f else 1f
        val targetBestAlpha = if (locked) 0.45f else 1f

        if (lastConnectDimmed == locked) {
            panel.alpha = targetPanelAlpha
            bestBtn?.alpha = targetBestAlpha
            blocker?.visibility = if (locked) View.VISIBLE else View.GONE
            return
        }
        lastConnectDimmed = locked

        if (animate) {
            panel.animate().alpha(targetPanelAlpha).setDuration(220L).start()
            bestBtn?.animate()?.alpha(targetBestAlpha)?.setDuration(220L)?.start()
            if (blocker != null) {
                if (locked) {
                    blocker.alpha = 0f
                    blocker.visibility = View.VISIBLE
                    blocker.animate().alpha(1f).setDuration(200L).start()
                } else {
                    blocker.animate().alpha(0f).setDuration(180L).withEndAction {
                        if (lastConnectDimmed == false) {
                            blocker.visibility = View.GONE
                        }
                    }.start()
                }
            }
        } else {
            panel.animate().cancel()
            panel.alpha = targetPanelAlpha
            bestBtn?.animate()?.cancel()
            bestBtn?.alpha = targetBestAlpha
            blocker?.animate()?.cancel()
            blocker?.alpha = if (locked) 1f else 0f
            blocker?.visibility = if (locked) View.VISIBLE else View.GONE
        }
    }

    private fun updateOrbState(animate: Boolean = true) {
        val orb = orbView ?: return
        if (!isAdded) return
        val statusView = connStatusView ?: return
        val ctx = requireContext()

        val anyTestRunning = isAnyTestRunning()
        applyConnectSectionLock(anyTestRunning, animate)

        if (anyTestRunning) {
            orb.setState(MinabConnectOrbView.State.DISABLED_TESTING, animate)
            connectPanel?.setBackgroundResource(R.drawable.minab_card_bg)
            liveStatsBar?.visibility = View.GONE
            stopSessionTimer()
            val busyColor = ContextCompat.getColor(ctx, R.color.minab_busy)
            setStatusDotColor(busyColor)
            statusView.text = getString(R.string.minab_status_testing)
            statusView.setTextColor(busyColor)
            if (realTestActive) {
                connNameView?.text = realTestCurrentName ?: getString(R.string.minab_real_test)
                val pingView = connPingView
                if (pingView != null) {
                    pingView.text = if (realTotalCount > 0) "$realDoneCount/$realTotalCount" else "—"
                    pingView.background = null
                    pingView.setTextColor(ContextCompat.getColor(ctx, R.color.minab_text_secondary))
                    pingView.setPadding(0, 0, 0, 0)
                }
            }
            return
        }

        val now = SystemClock.uptimeMillis()
        val active = VpnStatus.isVPNActive()
        val uuid = VpnStatus.getLastConnectedVPNProfile()
        val isDisconnecting = now < disconnectingUntilMs
        val isOptimisticConnecting = now < optimisticConnectingUntilMs
        val isConnected = active && lastLevel == ConnectionStatus.LEVEL_CONNECTED
        val isConnecting = !isDisconnecting && (
            (active && !isConnected) ||
                isOptimisticConnecting ||
                MinabConnectState.isConnecting(uuid, lastLevel)
            )

        val orbState = when {
            isDisconnecting -> MinabConnectOrbView.State.DISCONNECTING
            isConnected -> MinabConnectOrbView.State.CONNECTED
            isConnecting -> MinabConnectOrbView.State.CONNECTING
            else -> MinabConnectOrbView.State.DISCONNECTED
        }
        orb.setState(orbState, animate)

        connectPanel?.setBackgroundResource(
            when (orbState) {
                MinabConnectOrbView.State.CONNECTED -> R.drawable.minab_connect_card_connected
                MinabConnectOrbView.State.CONNECTING -> R.drawable.minab_connect_card_connecting
                MinabConnectOrbView.State.DISCONNECTING -> R.drawable.minab_connect_card_disconnecting
                else -> R.drawable.minab_card_bg
            }
        )

        val statusColor = when (orbState) {
            MinabConnectOrbView.State.CONNECTED -> ContextCompat.getColor(ctx, R.color.minab_connected)
            MinabConnectOrbView.State.CONNECTING -> ContextCompat.getColor(ctx, R.color.minab_busy)
            MinabConnectOrbView.State.DISCONNECTING -> ContextCompat.getColor(ctx, R.color.minab_orb_disconnecting_end)
            else -> ContextCompat.getColor(ctx, R.color.minab_text_secondary)
        }
        setStatusDotColor(statusColor)
        statusView.setTextColor(statusColor)
        statusView.text = when (orbState) {
            MinabConnectOrbView.State.CONNECTED -> getString(R.string.minab_status_connected)
            MinabConnectOrbView.State.CONNECTING -> getString(R.string.minab_status_connecting)
            MinabConnectOrbView.State.DISCONNECTING -> getString(R.string.minab_status_disconnecting)
            else -> getString(R.string.minab_status_disconnected)
        }

        val nameView = connNameView
        val pingView = connPingView
        val profile = if (active || isConnecting) uuid?.let {
            try {
                ProfileManager.getIfPresent(ctx, it)
            } catch (_: Exception) {
                null
            }
        } else null

        val shownProfile = profile ?: orbTargetProfile()
        nameView?.text = shownProfile?.mName ?: ""
        if (shownProfile != null && pingView != null) {
            setPanelPing(shownProfile, live = isConnected || isConnecting)
        } else {
            pingView?.text = "—"
            pingView?.background = null
        }

        if (isConnected) {
            liveStatsBar?.visibility = View.VISIBLE
            startSessionTimer()
        } else {
            liveStatsBar?.visibility = View.GONE
            stopSessionTimer()
        }

        updateIpInfo(uuid)
    }

    private fun setStatusDotColor(color: Int) {
        val dot = connStatusDot ?: return
        ViewCompat.setBackgroundTintList(dot, ColorStateList.valueOf(color))
    }

    private fun setPanelPing(profile: VpnProfile, live: Boolean) {
        val pingView = connPingView ?: return
        if (connectedPingTesting) return
        val label = connectedPingLabel(profile)
        pingView.text = label
        if (live && profile.mLastPingMs > 0) {
            pingView.setBackgroundResource(
                when {
                    profile.mLastPingMs < 100 -> R.drawable.minab_ping_badge_fast
                    profile.mLastPingMs < 300 -> R.drawable.minab_ping_badge_slow
                    else -> R.drawable.minab_ping_badge_fail
                }
            )
            pingView.setTextColor(Color.WHITE)
            pingView.setPadding(dp(8), dp(2), dp(8), dp(2))
        } else if (live && profile.mLastPingResult.isNotEmpty() && profile.mLastPingMs != 0L) {
            pingView.setBackgroundResource(R.drawable.minab_ping_badge_fail)
            pingView.setTextColor(Color.WHITE)
            pingView.setPadding(dp(8), dp(2), dp(8), dp(2))
        } else {
            pingView.background = null
            pingView.setTextColor(ContextCompat.getColor(requireContext(), R.color.minab_text_secondary))
            pingView.setPadding(0, 0, 0, 0)
        }
    }

    private fun connectBestServer() {
        performSubtleHaptic()
        if (isAnyTestRunning()) {
            Toast.makeText(requireContext(), R.string.minab_connect_locked_toast, Toast.LENGTH_SHORT).show()
            return
        }
        val best = MinabVpnControl.bestProfile(requireContext(), visibleProfiles())
        if (best == null) {
            Toast.makeText(requireContext(), R.string.minab_best_none, Toast.LENGTH_LONG).show()
            return
        }
        bestButtonHighlighted = false
        updateBestServerButtonHighlight()
        MinabConnectState.markPendingConnect(best.getUUIDString())
        triggerOptimisticConnecting()
        updateOrbState()
        homeAdapter?.notifyRowsUpdated()
        startVPN(best)
    }

    private fun updateBestServerButtonHighlight() {
        val btn = bestServerButton ?: return
        val ctx = context ?: return
        if (bestButtonHighlighted) {
            btn.setBackgroundResource(R.drawable.minab_btn_best_glow)
            btn.setTextColor(Color.WHITE)
            TextViewCompat.setCompoundDrawableTintList(btn, ColorStateList.valueOf(Color.WHITE))
            btn.animate().scaleX(1.03f).scaleY(1.03f).setDuration(220L).withEndAction {
                btn.animate().scaleX(1f).scaleY(1f).setDuration(220L).start()
            }.start()
        } else {
            btn.setBackgroundResource(R.drawable.minab_btn_outline)
            btn.setTextColor(ContextCompat.getColor(ctx, R.color.minab_text_primary))
            TextViewCompat.setCompoundDrawableTintList(
                btn,
                ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.minab_teal))
            )
        }
    }

    private fun updateIpInfo(uuid: String?) {
        if (connIpView == null) return
        if (VpnStatus.isVPNActive()) {
            val connected = lastLevel == ConnectionStatus.LEVEL_CONNECTED || lastLevel == null
            if (!connected) return
            val key = "conn:$uuid"
            if (ipCheckKey == key && ipCheckKeyConnected) return
            ipCheckKey = key
            ipCheckKeyConnected = true
            runIpCheck(key, uuid)
        } else {
            if (ipCheckKey == "own") return
            ipCheckKey = "own"
            ipCheckKeyConnected = false
            runIpCheck("own", null)
        }
    }

    private fun runIpCheck(key: String, uuid: String?) {
        MinabIpCheck.check { info ->
            if (!isAdded) return@check
            if (ipCheckKey != key) return@check
            if (uuid != null) {
                if (!VpnStatus.isVPNActive() || VpnStatus.getLastConnectedVPNProfile() != uuid) return@check
            } else {
                if (VpnStatus.isVPNActive()) return@check
            }
            connIpView?.text = info?.ip ?: "—"
            val flagView = connIpFlagView
            if (flagView != null) {
                val res = if (info == null) 0 else FlagHelper.flagResForCountryCode(info.countryCode)
                if (res != 0) {
                    try {
                        flagView.setImageResource(res)
                    } catch (_: Exception) {
                        flagView.visibility = View.GONE
                        return@check
                    }
                    flagView.visibility = View.VISIBLE
                } else {
                    flagView.visibility = View.GONE
                }
            }
        }
    }

    private fun connectedPingLabel(profile: VpnProfile): String {
        if (profile.mLastPingResult.isNotEmpty()) return profile.mLastPingResult
        if (profile.mLastPingMs > 0) return "${profile.mLastPingMs} ms"
        return "—"
    }

    private fun retestConnectedPing() {
        if (isAnyTestRunning() || connectedPingTesting) return
        val profile = if (VpnStatus.isVPNActive()) {
            val uuid = VpnStatus.getLastConnectedVPNProfile() ?: return
            try {
                ProfileManager.getIfPresent(requireContext(), uuid)
            } catch (_: Exception) {
                null
            } ?: return
        } else {
            orbTargetProfile() ?: return
        }
        val pingView = connPingView ?: return
        connectedPingTesting = true
        pingView.text = "…"
        pingView.background = null
        val appCtx = requireContext().applicationContext
        BACKGROUND_EXECUTOR.execute {
            val result = try {
                PingTester.testOne(profile)
            } catch (_: Exception) {
                null
            }
            if (result != null) {
                profile.mLastPingMs = if (result.status == PingTester.Status.OK) result.ms else -1
                profile.mLastPingTime = result.timestampMs
                profile.mLastPingResult = result.displayText()
                try {
                    ProfileManager.saveProfile(appCtx, profile)
                } catch (_: Exception) {
                }
            }
            activity?.runOnUiThread {
                connectedPingTesting = false
                if (!isAdded) return@runOnUiThread
                updateOrbState()
                homeAdapter?.notifyRowsUpdated()
            }
        }
    }

    // --- Minab Children: bulk ping ---

    private fun visibleProfiles(): List<VpnProfile> {
        val all = pM.getProfiles().toList()
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

    /** Clears ping and real results in memory immediately (zero UI stutter)
        and persists the cleared profiles off the UI thread. */
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
        cancelPingTest()
        cancelRealTest()
        RealTester.cancelCurrent()
        bestButtonHighlighted = false
        updateBestServerButtonHighlight()
        currentTestNetSnapshot = null
        MinabTestHistory.captureNetworkAsync(requireContext()) { snap -> currentTestNetSnapshot = snap }
        clearTestResultsAsync(profiles)
        lastListSignature = null
        populateVpnList()
        if (VpnStatus.isVPNActive()) {
            Toast.makeText(requireContext(), R.string.minab_ping_while_vpn, Toast.LENGTH_LONG).show()
        }
        runPingPhase(profiles) { cancelled ->
            if (!isAdded) return@runPingPhase
            MinabTestCoordinator.setRunning(MinabTestCoordinator.Source.HOME_PING, false)
            updateOrbState()
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
                populateVpnList()
                Toast.makeText(requireContext(), R.string.minab_ping_done, Toast.LENGTH_SHORT).show()
                testProgressPanel?.showFinished(
                    isReal = false,
                    summaryText = getString(R.string.minab_sort_by_ping),
                    tested = pingDoneCount,
                    total = pingTotalCount,
                    found = pingOkCount
                )
                bestButtonHighlighted = pingOkCount > 0
                updateBestServerButtonHighlight()
                MinabProfileBus.notifyChanged()
            }
        }
    }

    private fun stopRealCountdown() {
        realCountdownHandler.removeCallbacks(realCountdownRunnable)
        realCountdownSeconds = 0
        realCountdownName = null
    }

    private fun runPingPhase(profiles: List<VpnProfile>, onFinished: (Boolean) -> Unit) {
        val tester = PingTester()
        pingTester = tester
        pingDoneCount = 0
        pingTotalCount = profiles.size
        pingOkCount = 0
        pingLatestName = null
        MinabTestCoordinator.setRunning(MinabTestCoordinator.Source.HOME_PING, true)
        updateOrbState()
        updatePingStatus()
        tester.testAll(profiles, object : PingTester.PingListener {
            override fun onResult(result: PingTester.Result) {
                activity?.runOnUiThread {
                    if (!isAdded || pingTester !== tester) return@runOnUiThread
                    recordPingResult(result)
                }
            }

            override fun onFinished(cancelled: Boolean) {
                activity?.runOnUiThread {
                    if (pingTester === tester) {
                        pingTester = null
                    }
                    MinabTestCoordinator.setRunning(MinabTestCoordinator.Source.HOME_PING, false)
                    if (!isAdded) return@runOnUiThread
                    onFinished(cancelled)
                }
            }
        })
    }

    private fun recordPingResult(result: PingTester.Result) {
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
        updatePingStatus()
        if (sortMode == MinabProfileBus.SORT_PING) resortAdapter()
        else requestListRefresh()
        maybeDetectCountryFromIp(result)
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

    private fun updatePingStatus() {
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
        MinabTestCoordinator.setRunning(MinabTestCoordinator.Source.HOME_PING, false)
        if (!realTestActive) testProgressPanel?.hide()
        updateOrbState()
    }

    // --- Minab Real test ---

    private fun startRealTest(profiles: List<VpnProfile>) {
        if (profiles.isEmpty()) {
            Toast.makeText(requireContext(), R.string.minab_no_profiles_in_category, Toast.LENGTH_SHORT).show()
            return
        }
        cancelPingTest()
        cancelRealTest()
        realDoneCount = 0
        realTotalCount = 0
        realWorkingCount = 0
        realPingPhaseActive = true
        realPingOkCount = 0
        realTestCurrentName = null
        realTargetWorking = MinabRealTestSettings.getMaxServers(requireContext())
        bestButtonHighlighted = false
        updateBestServerButtonHighlight()
        currentTestNetSnapshot = null
        MinabTestHistory.captureNetworkAsync(requireContext()) { snap -> currentTestNetSnapshot = snap }

        RealTester.cancelCurrent()
        clearTestResultsAsync(profiles)
        lastListSignature = null
        populateVpnList()

        realTestRunId++
        val runId = realTestRunId
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
                    realDoneCount = done
                    realTotalCount = total
                    realWorkingCount = working
                    realTestCurrentName = currentName
                    if (currentName == null) {
                        stopRealCountdown()
                    } else if (currentName != realCountdownName) {
                        realCountdownName = currentName
                        realCountdownSeconds = MinabRealTestSettings.getWaitSeconds(requireContext())
                        realCountdownHandler.removeCallbacks(realCountdownRunnable)
                        realCountdownHandler.postDelayed(realCountdownRunnable, 1000)
                    }
                    updateRealStatus()
                    updateOrbState()
                }

                override fun onProfileResult(profile: VpnProfile) {
                    if (!isAdded || runId != realTestRunId) return
                    if (realPingPhaseActive && profile.mLastPingMs > 0) {
                        realPingOkCount++
                        testProgressPanel?.showRealPingPhase(pingDoneCount, pingTotalCount, realPingOkCount)
                    }
                    if (sortMode == MinabProfileBus.SORT_REAL) resortAdapter()
                    else requestListRefresh()
                }

                override fun onFinished(cancelled: Boolean, working: Int) {
                    if (runId != realTestRunId) return
                    realTester = null
                    realTestActive = false
                    realPingPhaseActive = false
                    stopRealCountdown()
                    MinabTestCoordinator.setRunning(MinabTestCoordinator.Source.HOME_REAL, false)
                    if (!isAdded) return
                    if (cancelled) {
                        testProgressPanel?.hide()
                        updateOrbState()
                        MinabProfileBus.notifyChanged()
                        return
                    }
                    if (realTotalCount == 0) {
                        testProgressPanel?.hide()
                        Toast.makeText(requireContext(), R.string.minab_real_none_to_test, Toast.LENGTH_LONG).show()
                        updateOrbState()
                        MinabProfileBus.notifyChanged()
                        return
                    }
                    MinabTestHistory.recordRunFromProfiles(
                        requireContext(),
                        true,
                        MinabRealTestSettings.getShortHostLabel(requireContext()),
                        currentTestNetSnapshot,
                        profiles,
                        realDoneCount,
                        working
                    )
                    setSortMode(MinabProfileBus.SORT_REAL)
                    lastListSignature = null
                    chipsSignature = null
                    populateVpnList()
                    Toast.makeText(requireContext(), R.string.minab_real_done, Toast.LENGTH_SHORT).show()
                    testProgressPanel?.showFinished(
                        isReal = true,
                        summaryText = getString(R.string.minab_sort_by_real),
                        tested = realDoneCount,
                        total = realTotalCount,
                        found = working
                    )
                    bestButtonHighlighted = working > 0
                    updateBestServerButtonHighlight()
                    updateOrbState()
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
        MinabTestCoordinator.setRunning(MinabTestCoordinator.Source.HOME_REAL, true)
        testProgressPanel?.showRealPingPhase(0, profiles.size, 0)
        updateOrbState()
    }

    private fun cancelRealTest() {
        realTestRunId++
        realTester?.cancel()
        realTester = null
        realTestActive = false
        realPingPhaseActive = false
        stopRealCountdown()
        MinabTestCoordinator.setRunning(MinabTestCoordinator.Source.HOME_REAL, false)
        if (pingTester == null) testProgressPanel?.hide()
        updateOrbState()
    }

    private fun updateRealStatus() {
        val panel = testProgressPanel ?: return
        if (!realTestActive) {
            panel.hide()
            return
        }
        val waitSeconds = try {
            MinabRealTestSettings.getWaitSeconds(requireContext())
        } catch (_: Exception) {
            12
        }
        val serversAfterCurrent = (realTotalCount - realDoneCount - 1).coerceAtLeast(0)
        val totalSecondsLeft = realCountdownSeconds + serversAfterCurrent * waitSeconds
        panel.showRealProgress(
            done = realDoneCount,
            total = realTotalCount,
            working = realWorkingCount,
            targetWorking = realTargetWorking,
            currentName = realTestCurrentName,
            totalSecondsLeft = totalSecondsLeft
        )
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        menu.add(0, MENU_CHANGE_SORTING, 0, R.string.change_sorting)
            .setIcon(R.drawable.ic_sort)
            .setAlphabeticShortcut('s')
            .setTitleCondensed(getString(R.string.sort))
            .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM)

        menu.add(0, MENU_IMPORT_AS, 0, R.string.import_from_as)
            .setIcon(R.drawable.ic_menu_import_download)
            .setAlphabeticShortcut('p')
            .setTitleCondensed("Import AS")
            .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM)

        menu.add(0, MENU_PING_ALL, 0, R.string.minab_ping_all)
            .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, MENU_PING_CATEGORY, 0, R.string.minab_ping_category)
            .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, MENU_FILTER_CATEGORY, 0, R.string.minab_filter_category)
            .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_CHANGE_SORTING -> changeSorting()
            MENU_IMPORT_AS -> startASProfileImport()
            MENU_PING_ALL -> {
                startPingTest(pM.getProfiles().toList())
                true
            }
            MENU_PING_CATEGORY -> {
                startPingTest(visibleProfiles())
                true
            }
            MENU_FILTER_CATEGORY -> {
                showCategoryFilterDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun startASProfileImport(): Boolean {
        val asImportFrag = newInstance(null)
        asImportFrag.show(parentFragmentManager, "dialog")
        return true
    }

    private fun changeSorting(): Boolean {
        val next = if (sortMode == MinabProfileBus.SORT_LRU) {
            Toast.makeText(activity, R.string.sorted_az, Toast.LENGTH_SHORT).show()
            MinabProfileBus.SORT_NAME
        } else {
            Toast.makeText(activity, R.string.sorted_lru, Toast.LENGTH_SHORT).show()
            MinabProfileBus.SORT_LRU
        }
        setSortMode(next)
        lastListSignature = null
        populateVpnList()
        return true
    }

    override fun onClick(v: View) {
        when (v.id) {
            R.id.fab_import -> startImportConfigFilePicker()
            R.id.fab_add -> onAddOrDuplicateProfile(null)
        }
    }

    private fun startImportConfigFilePicker(): Boolean {
        var startOldFileDialog = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && !alwaysUseOldFileChooser(activity)) {
            startOldFileDialog = !startFilePicker()
        }
        if (startOldFileDialog) startImportConfig()
        return true
    }

    private fun startFilePicker(): Boolean {
        val i = Utils.getFilePickerIntent(requireActivity(), Utils.FileType.OVPN_CONFIG)
        return if (i != null) {
            startActivityForResult(i, FILE_PICKER_RESULT_KITKAT)
            true
        } else false
    }

    private fun startImportConfig() {
        val intent = Intent(activity, FileSelect::class.java)
        intent.putExtra(FileSelect.NO_INLINE_SELECTION, true)
        intent.putExtra(FileSelect.WINDOW_TITLE, R.string.import_configuration_file)
        startActivityForResult(intent, SELECT_PROFILE)
    }

    private fun onAddOrDuplicateProfile(mCopyProfile: VpnProfile?) {
        val context: Context = activity ?: return
        val entry = EditText(context)
        entry.setSingleLine()
        entry.contentDescription = getString(R.string.name_of_the_vpn_profile)

        val dialog = AlertDialog.Builder(context)
        if (mCopyProfile == null) {
            dialog.setTitle(R.string.menu_add_profile)
        } else {
            dialog.setTitle(context.getString(R.string.duplicate_profile_title, mCopyProfile.mName))
            entry.setText(getString(R.string.copy_of_profile, mCopyProfile.mName))
        }

        dialog.setMessage(R.string.add_profile_name_prompt)
        dialog.setView(entry)
        dialog.setNeutralButton(R.string.menu_import_short) { _: DialogInterface?, _: Int ->
            startImportConfigFilePicker()
        }
        dialog.setPositiveButton(android.R.string.ok) { _: DialogInterface?, _: Int ->
            val name = entry.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(activity, R.string.minab_profile_name_required, Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            if (this.pM.getProfileByName(name) == null) {
                val profile: VpnProfile = if (mCopyProfile != null) {
                    mCopyProfile.copy(name).apply {
                        mProfileCreator = null
                        mUserEditable = true
                    }
                } else {
                    VpnProfile(name)
                }
                addProfile(profile)
                editVPN(profile)
            } else {
                Toast.makeText(activity, R.string.duplicate_profile_name, Toast.LENGTH_LONG).show()
            }
        }
        dialog.setNegativeButton(android.R.string.cancel, null)
        dialog.create().show()
    }

    private fun addProfile(profile: VpnProfile) {
        this.pM.addProfile(profile)
        this.pM.saveProfileList(activity)
        profile.addChangeLogEntry("empty profile added via main profile list")
        ProfileManager.saveProfile(activity, profile)
        lastListSignature = null
        chipsSignature = null
        populateVpnList()
        MinabProfileBus.notifyChanged()
    }

    private val pM: ProfileManager
        get() = ProfileManager.getInstance(requireContext())

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode == RESULT_VPN_DELETED) {
            lastListSignature = null
            chipsSignature = null
            populateVpnList()
            MinabProfileBus.notifyChanged()
        } else if (resultCode == RESULT_VPN_DUPLICATE && data != null) {
            val profileUUID = data.getStringExtra(VpnProfile.EXTRA_PROFILEUUID)
            val profile = ProfileManager.get(activity, profileUUID)
            if (profile != null) onAddOrDuplicateProfile(profile)
        }

        if (resultCode != Activity.RESULT_OK) return

        if (requestCode == EDIT_VPN_CONFIG) {
            val configuredVPN = data?.getStringExtra(VpnProfile.EXTRA_PROFILEUUID) ?: return
            val profile = ProfileManager.get(activity, configuredVPN) ?: return
            profile.addChangeLogEntry("Profile edited by user")
            ProfileManager.saveProfile(activity, profile)
            lastListSignature = null
            chipsSignature = null
            populateVpnList()
            MinabProfileBus.notifyChanged()
        } else if (requestCode == SELECT_PROFILE) {
            val fileData = data?.getStringExtra(FileSelect.RESULT_DATA) ?: return
            val uri = Uri.Builder().path(fileData).scheme("file").build()
            startConfigImport(uri)
        } else if (requestCode == IMPORT_PROFILE) {
            val profileUUID = data?.getStringExtra(VpnProfile.EXTRA_PROFILEUUID) ?: return
            val profile = ProfileManager.get(activity, profileUUID)
            lastListSignature = null
            chipsSignature = null
            populateVpnList()
            MinabProfileBus.notifyChanged()
            if (profile != null) {
                val appContext = requireContext().applicationContext
                BACKGROUND_EXECUTOR.execute {
                    try {
                        val guessed = AutoCategory.guessCategory(
                            appContext, profile.mName, null, profile.primaryRemoteHost
                        )
                        if (guessed != null) {
                            activity?.runOnUiThread {
                                if (!isAdded) return@runOnUiThread
                                profile.setCategory(guessed)
                                CategoryStore.addCategory(requireContext(), guessed)
                                ProfileManager.saveProfile(requireContext(), profile)
                                lastListSignature = null
                                chipsSignature = null
                                populateVpnList()
                                MinabProfileBus.notifyChanged()
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        } else if (requestCode == FILE_PICKER_RESULT_KITKAT) {
            val uri = data?.data ?: return
            startConfigImport(uri)
        }
    }

    private fun startConfigImport(uri: Uri?) {
        val startImport = Intent(activity, ConfigConverter::class.java)
        startImport.action = ConfigConverter.IMPORT_PROFILE
        startImport.data = uri
        startActivityForResult(startImport, IMPORT_PROFILE)
    }

    private fun editVPN(profile: VpnProfile) {
        mEditProfile = profile
        val vprefintent = Intent(requireActivity(), VPNPreferences::class.java)
            .putExtra(requireActivity().packageName + ".profileUUID", profile.getUUID().toString())
        startActivityForResult(vprefintent, EDIT_VPN_CONFIG)
    }

    private fun startVPN(profile: VpnProfile) {
        ProfileManager.saveProfile(activity, profile)
        val intent = Intent(activity, LaunchVPN::class.java)
        intent.putExtra(LaunchVPN.EXTRA_KEY, profile.getUUID().toString())
        intent.putExtra(OpenVPNService.EXTRA_START_REASON, "main profile list")
        intent.putExtra(LaunchVPN.EXTRA_HIDELOG, true)
        intent.action = Intent.ACTION_MAIN
        startActivity(intent)
    }

    // --- Comparators ---

    internal class VpnProfileRealComparator : Comparator<VpnProfile?> {
        override fun compare(lhs: VpnProfile?, rhs: VpnProfile?): Int {
            if (lhs === rhs) return 0
            if (lhs == null) return -1
            if (rhs == null) return 1
            val lReal = lhs.mLastRealMs > 0
            val rReal = rhs.mLastRealMs > 0
            if (lReal != rReal) return if (lReal) -1 else 1
            if (lReal) {
                if (lhs.mLastRealMs != rhs.mLastRealMs) return lhs.mLastRealMs.compareTo(rhs.mLastRealMs)
                return (lhs.mName ?: "").compareTo(rhs.mName ?: "")
            }
            val l = if (lhs.mLastPingMs > 0) lhs.mLastPingMs else Long.MAX_VALUE
            val r = if (rhs.mLastPingMs > 0) rhs.mLastPingMs else Long.MAX_VALUE
            if (l != r) return l.compareTo(r)
            return (lhs.mName ?: "").compareTo(rhs.mName ?: "")
        }
    }

    internal class VpnProfilePingComparator : Comparator<VpnProfile?> {
        override fun compare(lhs: VpnProfile?, rhs: VpnProfile?): Int {
            if (lhs === rhs) return 0
            if (lhs == null) return -1
            if (rhs == null) return 1
            val l = if (lhs.mLastPingMs > 0) lhs.mLastPingMs else Long.MAX_VALUE
            val r = if (rhs.mLastPingMs > 0) rhs.mLastPingMs else Long.MAX_VALUE
            if (l != r) return l.compareTo(r)
            return (lhs.mName ?: "").compareTo(rhs.mName ?: "")
        }
    }

    internal class VpnProfileNameComparator : Comparator<VpnProfile?> {
        override fun compare(lhs: VpnProfile?, rhs: VpnProfile?): Int {
            if (lhs === rhs) return 0
            if (lhs == null) return -1
            if (rhs == null) return 1
            if (lhs.mName == null) return -1
            if (rhs.mName == null) return 1
            return lhs.mName.compareTo(rhs.mName)
        }
    }

    internal class VpnProfileLRUComparator : Comparator<VpnProfile?> {
        var nameComparator: VpnProfileNameComparator = VpnProfileNameComparator()
        override fun compare(lhs: VpnProfile?, rhs: VpnProfile?): Int {
            if (lhs === rhs) return 0
            if (lhs == null) return -1
            if (rhs == null) return 1
            if (lhs.mLastUsed > rhs.mLastUsed) return -1
            if (lhs.mLastUsed < rhs.mLastUsed) return 1
            return nameComparator.compare(lhs, rhs)
        }
    }

    // --- Virtualised RecyclerView Adapter (Header + Server Rows + Empty State) ---

    private class StaticViewHolder(view: View) : RecyclerView.ViewHolder(view)

    private class ServerRowViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cardWrapper: View = view.findViewById(R.id.minab_row_card_wrapper)
        val rowContent: View = view.findViewById(R.id.minab_row_content)
        val rowIndex: TextView = view.findViewById(R.id.minab_row_index)
        val title: TextView = view.findViewById(R.id.vpn_item_title)
        val subtitle: TextView = view.findViewById(R.id.vpn_item_subtitle)
        val left: View = view.findViewById(R.id.vpn_list_item_left)
        val settings: View = view.findViewById(R.id.quickedit_settings)
        val flag: ImageView = view.findViewById(R.id.minab_flag)
        val connecting: ProgressBar = view.findViewById(R.id.minab_connecting_spinner)
        val badges: View = view.findViewById(R.id.minab_badges)
        val pingBadge: TextView = view.findViewById(R.id.minab_ping_badge)
        val realBadge: TextView = view.findViewById(R.id.minab_real_badge)
        val star: ImageView = view.findViewById(R.id.minab_favorite_star)
    }

    private inner class HomeRecyclerAdapter(
        private val header: View,
        private val empty: View
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var items: List<VpnProfile> = emptyList()

        override fun getItemCount(): Int {
            return if (items.isEmpty()) 2 else 1 + items.size
        }

        override fun getItemViewType(position: Int): Int {
            if (position == 0) return VIEW_TYPE_HEADER
            return if (items.isEmpty()) VIEW_TYPE_EMPTY else VIEW_TYPE_ITEM
        }

        /** Safely replaces the row snapshot and dispatches exact range updates
            strictly below the header (position >= 1), so RecyclerView item
            counts can never desync and the header view is never rebound. */
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
                        .inflate(R.layout.vpn_list_item, parent, false)
                    ServerRowViewHolder(v)
                }
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder !is ServerRowViewHolder) return
            val index = position - 1
            if (index < 0 || index >= items.size) return
            try {
                bindServerRow(holder, items[index], index, items.size)
            } catch (e: Exception) {
                android.util.Log.e("VPNProfileList", "Minab: row bind failed at $index", e)
            }
        }

        private fun bindServerRow(
            holder: ServerRowViewHolder,
            profile: VpnProfile,
            index: Int,
            total: Int
        ) {
            val ctx = requireContext()

            // Grouped card border & divider for seamless single-card appearance.
            holder.cardWrapper.setBackgroundResource(
                when {
                    total <= 1 -> R.drawable.minab_row_card_single
                    index == 0 -> R.drawable.minab_row_card_top
                    index == total - 1 -> R.drawable.minab_row_card_bottom
                    else -> R.drawable.minab_row_card_middle
                }
            )

            holder.rowIndex.text = (index + 1).toString()

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
                holder.rowContent.setBackgroundColor(Color.TRANSPARENT)
                holder.connecting.visibility = View.GONE
            }

            holder.title.text = profile.mName ?: ""
            holder.title.setTextColor(
                ContextCompat.getColor(
                    ctx,
                    if (connected) R.color.minab_connected else R.color.minab_text_primary
                )
            )

            holder.left.setOnClickListener { startOrStopVPN(profile) }
            holder.left.setOnLongClickListener {
                showRowContextDialog(profile)
                true
            }
            holder.settings.setOnClickListener { editVPN(profile) }

            val warningText = getWarningText(ctx, profile)
            if (profile === defaultVPN) {
                if (warningText.isNotEmpty()) warningText.append(" ")
                warningText.append(SpannableString("Default VPN"))
            }
            if (profile.getUUIDString() == activeUuid && !mLastStatusMessage.isNullOrEmpty()) {
                holder.subtitle.text = mLastStatusMessage
            } else {
                val minabInfo = "[" + profile.getCategory() + "]"
                if (warningText.isNotEmpty()) {
                    warningText.append(" — ").append(minabInfo)
                    holder.subtitle.text = warningText
                } else {
                    holder.subtitle.text = minabInfo
                }
            }
            holder.subtitle.visibility = View.VISIBLE

            holder.flag.setImageResource(FlagHelper.flagResForProfile(profile, ctx))
            holder.flag.visibility = View.VISIBLE

            val badge = holder.pingBadge
            val pingShown = if (profile.mLastPingMs > 0) {
                badge.text = profile.mLastPingResult.ifEmpty { "${profile.mLastPingMs} ms" }
                badge.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    R.drawable.ic_minab_badge_ping, 0, 0, 0
                )
                badge.setBackgroundResource(
                    when {
                        profile.mLastPingMs < 100 -> R.drawable.minab_ping_badge_fast
                        profile.mLastPingMs < 300 -> R.drawable.minab_ping_badge_slow
                        else -> R.drawable.minab_ping_badge_fail
                    }
                )
                true
            } else if (profile.mLastPingResult.isNotEmpty() && profile.mLastPingMs != 0L) {
                badge.text = profile.mLastPingResult
                badge.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    R.drawable.ic_minab_badge_ping, 0, 0, 0
                )
                badge.setBackgroundResource(R.drawable.minab_ping_badge_fail)
                true
            } else {
                false
            }

            val realBadge = holder.realBadge
            val realShown = if (profile.mLastRealMs > 0) {
                realBadge.text = profile.mLastRealResult.ifEmpty {
                    getString(R.string.minab_real_ms, profile.mLastRealMs)
                }
                realBadge.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    R.drawable.ic_minab_badge_real_ok, 0, 0, 0
                )
                realBadge.setBackgroundResource(
                    when {
                        profile.mLastRealMs < 2000 -> R.drawable.minab_ping_badge_fast
                        profile.mLastRealMs < 4500 -> R.drawable.minab_ping_badge_slow
                        else -> R.drawable.minab_real_badge_ok
                    }
                )
                true
            } else if (profile.mLastRealResult.isNotEmpty() && profile.mLastRealMs != 0L) {
                realBadge.text = profile.mLastRealResult
                realBadge.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    R.drawable.ic_minab_badge_real_fail, 0, 0, 0
                )
                realBadge.setBackgroundResource(R.drawable.minab_ping_badge_fail)
                true
            } else {
                false
            }

            holder.badges.visibility = if (pingShown || realShown) View.VISIBLE else View.GONE
            badge.visibility = if (pingShown) View.VISIBLE else View.GONE
            realBadge.visibility = if (realShown) View.VISIBLE else View.GONE

            val isFavorite = cachedFavorites.contains(profile.getUUIDString())
            holder.star.setImageResource(
                if (isFavorite) R.drawable.minab_star_on else R.drawable.minab_star_off
            )
            holder.star.setOnClickListener {
                MinabFavorites.toggle(ctx, profile.getUUIDString())
                lastListSignature = null
                chipsSignature = null
                populateVpnList()
                MinabProfileBus.notifyChanged()
            }
        }
    }

    internal inner class MiniImageGetter : Html.ImageGetter {
        override fun getDrawable(source: String?): Drawable? {
            var d: Drawable? = null
            if ("ic_menu_add" == source) {
                d = requireActivity().resources.getDrawable(R.drawable.ic_menu_add_grey, requireActivity().theme)
            } else if ("ic_menu_archive" == source) {
                d = requireActivity().resources.getDrawable(R.drawable.ic_menu_import_grey, requireActivity().theme)
            }
            if (d != null) {
                d.setBounds(0, 0, d.intrinsicWidth, d.intrinsicHeight)
                return d
            }
            return null
        }
    }

    companion object {
        val RESULT_VPN_DELETED: Int = Activity.RESULT_FIRST_USER
        val RESULT_VPN_DUPLICATE: Int = Activity.RESULT_FIRST_USER + 1

        const val SHORTCUT_VERSION: Int = 1
        private const val EDIT_VPN_CONFIG = 92
        private const val SELECT_PROFILE = 43
        private const val IMPORT_PROFILE = 231
        private const val FILE_PICKER_RESULT_KITKAT = 392
        private val MENU_CHANGE_SORTING = Menu.FIRST + 2
        private val MENU_IMPORT_AS = Menu.FIRST + 3
        private val MENU_PING_ALL = Menu.FIRST + 5
        private val MENU_PING_CATEGORY = Menu.FIRST + 6
        private val MENU_FILTER_CATEGORY = Menu.FIRST + 7

        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_ITEM = 1
        private const val VIEW_TYPE_EMPTY = 2

        private val BACKGROUND_EXECUTOR = Executors.newSingleThreadExecutor()
    }
}
