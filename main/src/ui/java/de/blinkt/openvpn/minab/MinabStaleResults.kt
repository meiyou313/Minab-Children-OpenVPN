/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * MinabStaleResults (v0.9.8.2): tracks which Ping / Real test badges were
 * restored from pre-test snapshots after a test was cancelled (or stopped
 * early at targetPasses) without being re-tested in that run. Those badges
 * render muted gray with a smaller font and sort cleanly below fresh results.
 */

package de.blinkt.openvpn.minab

import android.content.Context
import de.blinkt.openvpn.VpnProfile

object MinabStaleResults {
    private const val PREFS_NAME = "MinabPrefs"
    private const val KEY_STALE_PING = "minab_stale_ping_uuids"
    private const val KEY_STALE_REAL = "minab_stale_real_uuids"

    data class ResultSnapshot(
        val pingMs: Long,
        val pingTime: Long,
        val pingResult: String,
        val realMs: Long,
        val realTime: Long,
        val realResult: String,
        val hadPing: Boolean,
        val hadReal: Boolean
    )

    private val lock = Any()
    private var loaded = false
    private val stalePing = HashSet<String>()
    private val staleReal = HashSet<String>()

    private fun ensureLoadedLocked(context: Context) {
        if (loaded) return
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getStringSet(KEY_STALE_PING, null)?.let { stalePing.addAll(it) }
            prefs.getStringSet(KEY_STALE_REAL, null)?.let { staleReal.addAll(it) }
        } catch (_: Exception) {
        }
        loaded = true
    }

    private fun persistLocked(context: Context) {
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putStringSet(KEY_STALE_PING, HashSet(stalePing))
                .putStringSet(KEY_STALE_REAL, HashSet(staleReal))
                .apply()
        } catch (_: Exception) {
        }
    }

    fun captureSnapshots(profiles: Collection<VpnProfile>): Map<String, ResultSnapshot> {
        val map = HashMap<String, ResultSnapshot>(profiles.size)
        for (p in profiles) {
            val pingRes = p.mLastPingResult ?: ""
            val realRes = p.mLastRealResult ?: ""
            val hadPing = p.mLastPingMs > 0 || (pingRes.isNotEmpty() && p.mLastPingMs != 0L)
            val hadReal = p.mLastRealMs > 0 || (realRes.isNotEmpty() && p.mLastRealMs != 0L)
            map[p.getUUIDString()] = ResultSnapshot(
                pingMs = p.mLastPingMs,
                pingTime = p.mLastPingTime,
                pingResult = pingRes,
                realMs = p.mLastRealMs,
                realTime = p.mLastRealTime,
                realResult = realRes,
                hadPing = hadPing,
                hadReal = hadReal
            )
        }
        return map
    }

    /** Matches RealTester's decision on whether stage 1 parallel Ping runs. */
    fun willRealTestRunPingPhase(context: Context, profiles: List<VpnProfile>): Boolean {
        val testUnpinged = try {
            MinabRealTestSettings.getTestUnpinged(context)
        } catch (_: Exception) {
            false
        }
        if (!testUnpinged) return true
        val fiveMinAgo = System.currentTimeMillis() - 5 * 60 * 1000L
        for (p in profiles) {
            if (p.mLastPingTime <= 0 || p.mLastPingTime < fiveMinAgo || p.mLastPingResult.isNullOrEmpty()) {
                return true
            }
        }
        return false
    }

    fun getPingStaleSet(context: Context): Set<String> = synchronized(lock) {
        ensureLoadedLocked(context)
        HashSet(stalePing)
    }

    fun getRealStaleSet(context: Context): Set<String> = synchronized(lock) {
        ensureLoadedLocked(context)
        HashSet(staleReal)
    }

    fun clearPingStale(context: Context, uuid: String?) {
        if (uuid.isNullOrBlank()) return
        synchronized(lock) {
            ensureLoadedLocked(context)
            if (stalePing.remove(uuid)) persistLocked(context)
        }
    }

    fun clearRealStale(context: Context, uuid: String?) {
        if (uuid.isNullOrBlank()) return
        synchronized(lock) {
            ensureLoadedLocked(context)
            if (staleReal.remove(uuid)) persistLocked(context)
        }
    }

    fun clearPingStaleAll(context: Context, uuids: Collection<String>) {
        if (uuids.isEmpty()) return
        synchronized(lock) {
            ensureLoadedLocked(context)
            if (stalePing.removeAll(uuids.toSet())) persistLocked(context)
        }
    }

    fun clearRealStaleAll(context: Context, uuids: Collection<String>) {
        if (uuids.isEmpty()) return
        synchronized(lock) {
            ensureLoadedLocked(context)
            if (staleReal.removeAll(uuids.toSet())) persistLocked(context)
        }
    }

    fun markPingStale(context: Context, uuids: Collection<String>) {
        if (uuids.isEmpty()) return
        synchronized(lock) {
            ensureLoadedLocked(context)
            if (stalePing.addAll(uuids)) persistLocked(context)
        }
    }

    fun markRealStale(context: Context, uuids: Collection<String>) {
        if (uuids.isEmpty()) return
        synchronized(lock) {
            ensureLoadedLocked(context)
            if (staleReal.addAll(uuids)) persistLocked(context)
        }
    }

    fun removeForUuids(context: Context, uuids: Collection<String>) {
        if (uuids.isEmpty()) return
        val set = uuids.toSet()
        synchronized(lock) {
            ensureLoadedLocked(context)
            val c1 = stalePing.removeAll(set)
            val c2 = staleReal.removeAll(set)
            if (c1 || c2) persistLocked(context)
        }
    }

    /**
     * Restores previous results for profiles NOT reached during the run, and
     * marks those restored columns stale so they render muted gray & smaller.
     */
    fun restoreUntested(
        context: Context,
        profiles: List<VpnProfile>,
        snapshots: Map<String, ResultSnapshot>,
        runStartMs: Long,
        clearedPing: Boolean,
        clearedReal: Boolean
    ): List<VpnProfile> {
        if (profiles.isEmpty() || snapshots.isEmpty() || (!clearedPing && !clearedReal)) {
            return emptyList()
        }
        val restoredProfiles = ArrayList<VpnProfile>()
        val newStalePing = ArrayList<String>()
        val newStaleReal = ArrayList<String>()
        for (p in profiles) {
            val uuid = p.getUUIDString()
            val snap = snapshots[uuid] ?: continue
            var modified = false
            if (clearedPing && p.mLastPingTime < runStartMs && snap.hadPing) {
                p.mLastPingMs = snap.pingMs
                p.mLastPingTime = snap.pingTime
                p.mLastPingResult = snap.pingResult
                newStalePing.add(uuid)
                modified = true
            }
            if (clearedReal && p.mLastRealTime < runStartMs && snap.hadReal) {
                p.mLastRealMs = snap.realMs
                p.mLastRealTime = snap.realTime
                p.mLastRealResult = snap.realResult
                newStaleReal.add(uuid)
                modified = true
            }
            if (modified) {
                restoredProfiles.add(p)
            }
        }
        synchronized(lock) {
            ensureLoadedLocked(context)
            val changedPing = newStalePing.isNotEmpty() && stalePing.addAll(newStalePing)
            val changedReal = newStaleReal.isNotEmpty() && staleReal.addAll(newStaleReal)
            if (changedPing || changedReal) {
                persistLocked(context)
            }
        }
        return restoredProfiles
    }
}
