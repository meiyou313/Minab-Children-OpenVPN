/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * MinabServerStats: persistent per-server test history across networks/ISPs.
 * Tracks cumulative tests, passes, and failed network fingerprints so servers
 * that consistently fail across multiple runs or networks can be reviewed and
 * removed safely (never touching untested servers or servers that ever passed).
 */

package de.blinkt.openvpn.minab

import android.content.Context
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.VpnStatus
import org.json.JSONArray
import org.json.JSONObject

object MinabServerStats {
    private const val PREFS = "MinabServerStatsPrefs"
    private const val KEY_STATS = "server_stats_json"
    private const val KEY_SEEDED = "seeded_from_history_v1"

    data class ServerStat(
        val uuid: String,
        var realTests: Int = 0,
        var realPasses: Int = 0,
        var pingTests: Int = 0,
        var pingPasses: Int = 0,
        val failedNetworks: MutableSet<String> = linkedSetOf(),
        var lastTestedMs: Long = 0L,
        var lastPassedMs: Long = 0L
    ) {
        val totalTests: Int get() = realTests + pingTests
        val totalPasses: Int get() = realPasses + pingPasses
    }

    @Synchronized
    fun ensureSeededFromHistory(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SEEDED, false)) return
        val map = readMapLocked(context)
        if (map.isEmpty()) {
            val runs = MinabTestHistory.getRuns(context).asReversed()
            for (run in runs) {
                val netFp = run.networkFingerprint.ifBlank { run.networkType.ifBlank { "Other" } }
                for (item in run.results) {
                    if (item.uuid.isBlank()) continue
                    val stat = map.getOrPut(item.uuid) { ServerStat(item.uuid) }
                    if (run.isRealTest) {
                        stat.realTests++
                        if (item.passed) stat.realPasses++
                    } else {
                        stat.pingTests++
                        if (item.passed) stat.pingPasses++
                    }
                    if (!item.passed) {
                        stat.failedNetworks.add(netFp)
                    } else if (run.timestampMs > stat.lastPassedMs) {
                        stat.lastPassedMs = run.timestampMs
                    }
                    if (run.timestampMs > stat.lastTestedMs) {
                        stat.lastTestedMs = run.timestampMs
                    }
                }
            }
            if (map.isNotEmpty()) {
                writeMapLocked(context, map)
            }
        }
        prefs.edit().putBoolean(KEY_SEEDED, true).apply()
    }

    @Synchronized
    fun recordTestRun(
        context: Context,
        isRealTest: Boolean,
        networkFingerprint: String,
        testedProfiles: List<VpnProfile>
    ) {
        if (testedProfiles.isEmpty()) return
        ensureSeededFromHistory(context)
        val map = readMapLocked(context)
        val netFp = networkFingerprint.ifBlank { "Other" }
        val now = System.currentTimeMillis()
        for (p in testedProfiles) {
            val uuid = p.getUUIDString()
            val stat = map.getOrPut(uuid) { ServerStat(uuid) }
            val passed = if (isRealTest) p.mLastRealMs > 0 else p.mLastPingMs > 0
            if (isRealTest) {
                stat.realTests++
                if (passed) stat.realPasses++
            } else {
                stat.pingTests++
                if (passed) stat.pingPasses++
            }
            if (passed) {
                stat.lastPassedMs = now
            } else {
                stat.failedNetworks.add(netFp)
            }
            stat.lastTestedMs = now
        }
        writeMapLocked(context, map)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SEEDED, true).apply()
    }

    @Synchronized
    fun getAllStats(context: Context): Map<String, ServerStat> {
        ensureSeededFromHistory(context)
        return readMapLocked(context)
    }

    /**
     * Find servers that were tested >= minFails times (and across >= minNetworks
     * distinct networks when minNetworks > 1), NEVER passed any test, and are
     * neither currently connected nor healthy.
     */
    fun findDeadServers(
        context: Context,
        profiles: Collection<VpnProfile>,
        minFails: Int = 2,
        minNetworks: Int = 1
    ): List<Pair<VpnProfile, ServerStat>> {
        val stats = getAllStats(context)
        val connectedUuid = try {
            if (VpnStatus.isVPNActive()) VpnStatus.getLastConnectedVPNProfile() else null
        } catch (_: Exception) {
            null
        }
        val out = mutableListOf<Pair<VpnProfile, ServerStat>>()
        for (p in profiles) {
            if (p.getUUIDString() == connectedUuid) continue
            if (MinabVpnControl.isHealthy(p)) continue
            val stat = stats[p.getUUIDString()] ?: continue
            if (stat.totalPasses == 0 &&
                stat.totalTests >= minFails &&
                (minNetworks <= 1 || stat.failedNetworks.size >= minNetworks)
            ) {
                out.add(p to stat)
            }
        }
        return out.sortedWith(
            compareByDescending<Pair<VpnProfile, ServerStat>> { it.second.totalTests }
                .thenByDescending { it.second.failedNetworks.size }
                .thenBy { it.first.mName ?: "" }
        )
    }

    @Synchronized
    fun removeStatsForUuids(context: Context, uuids: Collection<String>) {
        if (uuids.isEmpty()) return
        val map = readMapLocked(context)
        var changed = false
        for (u in uuids) {
            if (map.remove(u) != null) changed = true
        }
        if (changed) writeMapLocked(context, map)
    }

    private fun readMapLocked(context: Context): MutableMap<String, ServerStat> {
        val out = linkedMapOf<String, ServerStat>()
        try {
            val raw = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_STATS, null) ?: return out
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val uuid = obj.optString("uuid", "")
                if (uuid.isEmpty()) continue
                val nets = linkedSetOf<String>()
                val netArr = obj.optJSONArray("failedNetworks")
                if (netArr != null) {
                    for (j in 0 until netArr.length()) {
                        val n = netArr.optString(j, "")
                        if (n.isNotEmpty()) nets.add(n)
                    }
                }
                out[uuid] = ServerStat(
                    uuid = uuid,
                    realTests = obj.optInt("realTests", 0),
                    realPasses = obj.optInt("realPasses", 0),
                    pingTests = obj.optInt("pingTests", 0),
                    pingPasses = obj.optInt("pingPasses", 0),
                    failedNetworks = nets,
                    lastTestedMs = obj.optLong("lastTestedMs", 0L),
                    lastPassedMs = obj.optLong("lastPassedMs", 0L)
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun writeMapLocked(context: Context, map: Map<String, ServerStat>) {
        try {
            val arr = JSONArray()
            for (s in map.values) {
                val obj = JSONObject()
                obj.put("uuid", s.uuid)
                obj.put("realTests", s.realTests)
                obj.put("realPasses", s.realPasses)
                obj.put("pingTests", s.pingTests)
                obj.put("pingPasses", s.pingPasses)
                obj.put("lastTestedMs", s.lastTestedMs)
                obj.put("lastPassedMs", s.lastPassedMs)
                val netArr = JSONArray()
                for (n in s.failedNetworks) netArr.put(n)
                obj.put("failedNetworks", netArr)
                arr.put(obj)
            }
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_STATS, arr.toString()).apply()
        } catch (_: Exception) {
        }
    }
}
