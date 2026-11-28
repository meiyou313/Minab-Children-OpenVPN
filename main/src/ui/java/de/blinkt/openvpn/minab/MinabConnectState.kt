/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * MinabConnectState (v0.9.8.1): zero-lag row halo and connection-transition state.
 * Tracks optimistic pending-connect, disconnecting transitions (with settlement pulse),
 * single Real Test target, and concurrent Ping Test targets, notifying listeners
 * synchronously on the main thread when touched from UI actions.
 */

package de.blinkt.openvpn.minab

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.VpnStatus
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object MinabConnectState {

    enum class RowVisualState {
        IDLE,
        TESTING,
        CONNECTING,
        CONNECTED,
        DISCONNECTING
    }

    fun interface Listener {
        fun onConnectStateChanged(affectedUuids: Set<String>)
    }

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile
    private var pendingConnectUuid: String? = null

    @Volatile
    private var pendingConnectSetAtMs: Long = 0L

    @Volatile
    private var pendingConnectUntilMs: Long = 0L

    @Volatile
    private var sawActiveSincePending: Boolean = false

    @Volatile
    private var disconnectingActive: Boolean = false

    @Volatile
    private var disconnectingUuid: String? = null

    @Volatile
    private var disconnectingMinUntilMs: Long = 0L

    @Volatile
    private var disconnectingMaxUntilMs: Long = 0L

    @Volatile
    private var realTestingUuid: String? = null

    private val pingTestingUuids: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val expiryCheckRunnable = Runnable { checkExpiryAndNotify() }

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun markPendingConnect(profileUuid: String?, durationMs: Long = 15_000L) {
        if (profileUuid.isNullOrBlank()) return
        val affected = LinkedHashSet<String>()
        synchronized(lock) {
            val now = SystemClock.uptimeMillis()
            pendingConnectUuid?.let { affected.add(it) }
            disconnectingUuid?.let { affected.add(it) }
            disconnectingActive = false
            disconnectingUuid = null
            disconnectingMinUntilMs = 0L
            disconnectingMaxUntilMs = 0L
            pendingConnectUuid = profileUuid
            pendingConnectSetAtMs = now
            pendingConnectUntilMs = now + durationMs.coerceAtLeast(1000L)
            sawActiveSincePending = false
            affected.add(profileUuid)
            scheduleExpiryCheckLocked(durationMs.coerceAtLeast(1000L) + 30L)
        }
        notifyListeners(affected)
    }

    fun clearPendingConnect() {
        val affected = LinkedHashSet<String>()
        synchronized(lock) {
            pendingConnectUuid?.let { affected.add(it) }
            pendingConnectUuid = null
            pendingConnectSetAtMs = 0L
            pendingConnectUntilMs = 0L
            sawActiveSincePending = false
            rescheduleNextExpiryLocked()
        }
        if (affected.isNotEmpty()) {
            notifyListeners(affected)
        }
    }

    fun markDisconnecting(
        profileUuid: String?,
        minPulseMs: Long = 550L,
        maxDurationMs: Long = 6000L
    ) {
        val resolvedTarget = profileUuid?.takeIf { it.isNotBlank() }
            ?: safeLastConnectedUuid()
        val affected = LinkedHashSet<String>()
        synchronized(lock) {
            val now = SystemClock.uptimeMillis()
            pendingConnectUuid?.let { affected.add(it) }
            disconnectingUuid?.let { affected.add(it) }
            pendingConnectUuid = null
            pendingConnectSetAtMs = 0L
            pendingConnectUntilMs = 0L
            sawActiveSincePending = false
            disconnectingActive = true
            disconnectingUuid = resolvedTarget
            disconnectingMinUntilMs = now + minPulseMs.coerceAtLeast(0L)
            disconnectingMaxUntilMs = now + maxDurationMs.coerceAtLeast(minPulseMs.coerceAtLeast(300L))
            resolvedTarget?.let { affected.add(it) }
            val nextDelay = if (minPulseMs > 0L) minPulseMs + 25L else maxDurationMs + 25L
            scheduleExpiryCheckLocked(nextDelay)
        }
        notifyListeners(affected)
    }

    fun clearDisconnecting() {
        val affected = LinkedHashSet<String>()
        synchronized(lock) {
            val hadState = disconnectingActive || disconnectingUuid != null
            disconnectingUuid?.let { affected.add(it) }
            disconnectingActive = false
            disconnectingUuid = null
            disconnectingMinUntilMs = 0L
            disconnectingMaxUntilMs = 0L
            rescheduleNextExpiryLocked()
            if (!hadState && affected.isEmpty()) return
        }
        notifyListeners(affected)
    }

    fun setRealTestingUuid(profileUuid: String?) {
        val normalized = profileUuid?.takeIf { it.isNotBlank() }
        val affected = LinkedHashSet<String>()
        synchronized(lock) {
            val prev = realTestingUuid
            if (prev == normalized && (!disconnectingActive || safeIsVpnActive())) return
            prev?.let { affected.add(it) }
            normalized?.let { affected.add(it) }
            realTestingUuid = normalized
            if (disconnectingActive && !safeIsVpnActive()) {
                disconnectingUuid?.let { affected.add(it) }
                disconnectingActive = false
                disconnectingUuid = null
                disconnectingMinUntilMs = 0L
                disconnectingMaxUntilMs = 0L
            }
            rescheduleNextExpiryLocked()
        }
        if (affected.isNotEmpty()) {
            notifyListeners(affected)
        }
    }

    fun onPingServerStart(profileUuid: String?) {
        if (profileUuid.isNullOrBlank()) return
        if (pingTestingUuids.add(profileUuid)) {
            notifyListeners(setOf(profileUuid))
        }
    }

    fun onPingServerEnd(profileUuid: String?) {
        if (profileUuid.isNullOrBlank()) return
        if (pingTestingUuids.remove(profileUuid)) {
            notifyListeners(setOf(profileUuid))
        }
    }

    fun onVpnStateUpdated(
        level: ConnectionStatus?,
        activeNow: Boolean,
        activeUuid: String?
    ) {
        val affected = LinkedHashSet<String>()
        var changed = false
        synchronized(lock) {
            val now = SystemClock.uptimeMillis()
            if (pendingConnectUuid != null && activeNow) {
                sawActiveSincePending = true
            }
            if (level == ConnectionStatus.LEVEL_CONNECTED && !isDisconnectingLocked(now, activeNow)) {
                if (pendingConnectUuid != null) {
                    pendingConnectUuid?.let { affected.add(it) }
                    pendingConnectUuid = null
                    pendingConnectSetAtMs = 0L
                    pendingConnectUntilMs = 0L
                    sawActiveSincePending = false
                    changed = true
                }
            }
            if (!activeNow ||
                level == ConnectionStatus.LEVEL_NOTCONNECTED ||
                level == ConnectionStatus.LEVEL_AUTH_FAILED
            ) {
                if (disconnectingActive) {
                    if (now >= disconnectingMinUntilMs) {
                        disconnectingUuid?.let { affected.add(it) }
                        disconnectingActive = false
                        disconnectingUuid = null
                        disconnectingMinUntilMs = 0L
                        disconnectingMaxUntilMs = 0L
                        changed = true
                    } else {
                        scheduleExpiryCheckLocked((disconnectingMinUntilMs - now).coerceAtLeast(16L))
                    }
                }
                if (pendingConnectUuid != null &&
                    (sawActiveSincePending ||
                        level == ConnectionStatus.LEVEL_AUTH_FAILED ||
                        now > pendingConnectSetAtMs + 1500L)
                ) {
                    pendingConnectUuid?.let { affected.add(it) }
                    pendingConnectUuid = null
                    pendingConnectSetAtMs = 0L
                    pendingConnectUntilMs = 0L
                    sawActiveSincePending = false
                    changed = true
                }
            } else if (disconnectingActive && activeUuid != null && disconnectingUuid == null) {
                disconnectingUuid = activeUuid
                affected.add(activeUuid)
                changed = true
            }
            if (changed) {
                rescheduleNextExpiryLocked()
            }
        }
        if (changed) {
            notifyListeners(affected)
        }
    }

    fun isDisconnectingNow(): Boolean {
        val activeNow = safeIsVpnActive()
        synchronized(lock) {
            return isDisconnectingLocked(SystemClock.uptimeMillis(), activeNow)
        }
    }

    fun isPendingConnectNow(): Boolean {
        synchronized(lock) {
            val uuid = pendingConnectUuid ?: return false
            if (SystemClock.uptimeMillis() < pendingConnectUntilMs && uuid.isNotBlank()) {
                return true
            }
            pendingConnectUuid = null
            pendingConnectSetAtMs = 0L
            pendingConnectUntilMs = 0L
            sawActiveSincePending = false
            return false
        }
    }

    fun getPendingConnectUuid(): String? {
        synchronized(lock) {
            val uuid = pendingConnectUuid ?: return null
            if (SystemClock.uptimeMillis() < pendingConnectUntilMs) return uuid
            pendingConnectUuid = null
            pendingConnectSetAtMs = 0L
            pendingConnectUntilMs = 0L
            sawActiveSincePending = false
            return null
        }
    }

    fun getDisconnectingUuid(): String? {
        val activeNow = safeIsVpnActive()
        synchronized(lock) {
            val now = SystemClock.uptimeMillis()
            return if (isDisconnectingLocked(now, activeNow)) disconnectingUuid else null
        }
    }

    fun isConnecting(profileUuid: String?, level: ConnectionStatus?): Boolean {
        return getRowState(profileUuid, level) == RowVisualState.CONNECTING
    }

    fun getRowState(profileUuid: String?, level: ConnectionStatus? = null): RowVisualState {
        if (profileUuid.isNullOrBlank()) return RowVisualState.IDLE
        val active = safeIsVpnActive()
        val activeUuid = safeLastConnectedUuid()
        val effectiveLevel = level ?: safeLastLevel()
        val now = SystemClock.uptimeMillis()

        val disc: Boolean
        val discUuid: String?
        val pendingUuid: String?
        val pendingValid: Boolean
        val realUuid: String?
        synchronized(lock) {
            disc = isDisconnectingLocked(now, active)
            discUuid = if (disc) disconnectingUuid else null
            pendingUuid = pendingConnectUuid
            pendingValid = pendingUuid != null && now < pendingConnectUntilMs
            if (pendingUuid != null && !pendingValid) {
                pendingConnectUuid = null
                pendingConnectSetAtMs = 0L
                pendingConnectUntilMs = 0L
            }
            realUuid = realTestingUuid
        }

        if (disc && (profileUuid == discUuid || (discUuid == null && profileUuid == activeUuid))) {
            return RowVisualState.DISCONNECTING
        }
        if (!disc && active && effectiveLevel == ConnectionStatus.LEVEL_CONNECTED &&
            !activeUuid.isNullOrBlank() && activeUuid == profileUuid
        ) {
            return RowVisualState.CONNECTED
        }
        if (profileUuid == realUuid || pingTestingUuids.contains(profileUuid)) {
            return RowVisualState.TESTING
        }
        if (!disc && ((pendingValid && pendingUuid == profileUuid) ||
                (active && activeUuid == profileUuid && isConnectingLevel(effectiveLevel)))
        ) {
            return RowVisualState.CONNECTING
        }
        return RowVisualState.IDLE
    }

    private fun isConnectingLevel(level: ConnectionStatus): Boolean {
        return level == ConnectionStatus.LEVEL_START ||
            level == ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET ||
            level == ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED ||
            level == ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT
    }

    private fun isDisconnectingLocked(now: Long, activeNow: Boolean): Boolean {
        if (!disconnectingActive) return false
        if (now >= disconnectingMaxUntilMs || (!activeNow && now >= disconnectingMinUntilMs)) {
            disconnectingActive = false
            disconnectingUuid = null
            disconnectingMinUntilMs = 0L
            disconnectingMaxUntilMs = 0L
            return false
        }
        return true
    }

    private fun scheduleExpiryCheckLocked(delayMs: Long) {
        mainHandler.removeCallbacks(expiryCheckRunnable)
        mainHandler.postDelayed(expiryCheckRunnable, delayMs.coerceAtLeast(16L))
    }

    private fun rescheduleNextExpiryLocked() {
        mainHandler.removeCallbacks(expiryCheckRunnable)
        val now = SystemClock.uptimeMillis()
        var nextAt = Long.MAX_VALUE
        if (disconnectingActive) {
            val minT = disconnectingMinUntilMs
            val maxT = disconnectingMaxUntilMs
            if (minT > now && minT < nextAt) nextAt = minT + 20L
            if (maxT > now && maxT < nextAt) nextAt = maxT + 20L
        }
        if (pendingConnectUuid != null && pendingConnectUntilMs > now) {
            val pendT = pendingConnectUntilMs + 20L
            if (pendT < nextAt) nextAt = pendT
        }
        if (nextAt < Long.MAX_VALUE) {
            mainHandler.postDelayed(expiryCheckRunnable, (nextAt - now).coerceAtLeast(16L))
        }
    }

    private fun checkExpiryAndNotify() {
        val activeNow = safeIsVpnActive()
        val affected = LinkedHashSet<String>()
        synchronized(lock) {
            val now = SystemClock.uptimeMillis()
            if (disconnectingActive) {
                if (now >= disconnectingMaxUntilMs || (!activeNow && now >= disconnectingMinUntilMs)) {
                    disconnectingUuid?.let { affected.add(it) }
                    disconnectingActive = false
                    disconnectingUuid = null
                    disconnectingMinUntilMs = 0L
                    disconnectingMaxUntilMs = 0L
                }
            }
            if (pendingConnectUuid != null && now >= pendingConnectUntilMs) {
                pendingConnectUuid?.let { affected.add(it) }
                pendingConnectUuid = null
                pendingConnectSetAtMs = 0L
                pendingConnectUntilMs = 0L
            }
            rescheduleNextExpiryLocked()
        }
        if (affected.isNotEmpty()) {
            notifyListeners(affected)
        }
    }

    private fun notifyListeners(affectedUuids: Set<String>) {
        if (listeners.isEmpty()) return
        val snapshot = HashSet(affectedUuids)
        val dispatch = Runnable {
            for (listener in listeners) {
                try {
                    listener.onConnectStateChanged(snapshot)
                } catch (_: Exception) {
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            dispatch.run()
        } else {
            mainHandler.post(dispatch)
        }
    }

    private fun safeIsVpnActive(): Boolean = try {
        VpnStatus.isVPNActive()
    } catch (_: Exception) {
        false
    }

    private fun safeLastConnectedUuid(): String? = try {
        VpnStatus.getLastConnectedVPNProfile()
    } catch (_: Exception) {
        null
    }

    private fun safeLastLevel(): ConnectionStatus = try {
        VpnStatus.getLastLevel() ?: ConnectionStatus.LEVEL_NOTCONNECTED
    } catch (_: Exception) {
        ConnectionStatus.LEVEL_NOTCONNECTED
    }
}
