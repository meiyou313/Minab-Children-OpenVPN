/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * MinabTestHistory: saves recent Ping/Real test snapshots paired with network
 * transport (Wi-Fi / Mobile), optional ISP name & user-defined network label
 * (without requesting any Location/GPS permission), timestamp, and working servers.
 * Users can later inspect past test runs (e.g. "Home Wi-Fi" vs "University"),
 * connect directly to a working server from that run, or re-apply its results.
 */

package de.blinkt.openvpn.minab

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import de.blinkt.openvpn.R
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ProfileManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

object MinabTestHistory {
    private const val PREFS = "MinabTestHistoryPrefs"
    private const val KEY_RUNS = "test_runs_json"
    private const val KEY_LABELS = "network_labels_json"
    private const val MAX_RUNS = 30
    private val IO_EXECUTOR = Executors.newSingleThreadExecutor()

    data class NetworkSnapshot(
        val type: String,          // "Wi-Fi", "Mobile", "Other"
        val isp: String,           // e.g. "Shatel", "Irancell", or ""
        val fingerprint: String,   // e.g. "Wi-Fi|Shatel"
        val displayLabel: String   // custom user label if saved, else "Wi-Fi · Shatel"
    )

    data class ServerResultItem(
        val uuid: String,
        val name: String,
        val category: String,
        val ms: Long,
        val passed: Boolean
    )

    data class TestRunRecord(
        val id: String,
        val timestampMs: Long,
        val isRealTest: Boolean,
        val targetHost: String,
        val networkType: String,
        val networkFingerprint: String,
        val networkLabel: String,
        val testedCount: Int,
        val workingCount: Int,
        val results: List<ServerResultItem>
    )

    /** Fast network transport + ISP capture with ZERO Location permission.
        Should be called from a background thread or via async wrapper. */
    fun captureNetworkSnapshotSync(context: Context): NetworkSnapshot {
        val type = detectNetworkType(context)
        val isp = fetchIspQuietly()
        val fingerprint = if (isp.isNotEmpty()) "$type|$isp" else type
        val savedLabel = getSavedNetworkLabels(context)[fingerprint]
        val defaultLabel = if (isp.isNotEmpty()) "$type · $isp" else type
        return NetworkSnapshot(
            type = type,
            isp = isp,
            fingerprint = fingerprint,
            displayLabel = if (!savedLabel.isNullOrBlank()) savedLabel else defaultLabel
        )
    }

    fun captureNetworkAsync(context: Context, onReady: (NetworkSnapshot) -> Unit) {
        val appCtx = context.applicationContext
        IO_EXECUTOR.execute {
            val snap = try {
                captureNetworkSnapshotSync(appCtx)
            } catch (_: Exception) {
                NetworkSnapshot("Other", "", "Other", "Other")
            }
            onReady(snap)
        }
    }

    private fun detectNetworkType(context: Context): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return "Other"
            val net = cm.activeNetwork ?: return "Other"
            val caps = cm.getNetworkCapabilities(net) ?: return "Other"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "Other"
            }
        } catch (_: Exception) {
            "Other"
        }
    }

    private fun fetchIspQuietly(): String {
        val endpoints = arrayOf(
            "https://ipwho.is/?fields=connection",
            "https://ipinfo.io/json"
        )
        for (ep in endpoints) {
            var conn: HttpsURLConnection? = null
            try {
                conn = (URL(ep).openConnection() as HttpsURLConnection).apply {
                    connectTimeout = 2200
                    readTimeout = 2200
                    setRequestProperty("User-Agent", "Minab")
                }
                if (conn.responseCode != HttpsURLConnection.HTTP_OK) continue
                val body = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use {
                    it.readText()
                }
                val json = JSONObject(body)
                if (ep.contains("ipwho.is")) {
                    val isp = json.optJSONObject("connection")?.optString("isp", "")?.trim() ?: ""
                    if (isp.isNotEmpty()) return shortenIsp(isp)
                } else {
                    val org = json.optString("org", "").trim()
                    if (org.isNotEmpty()) {
                        val cleaned = org.replace(Regex("^AS\\d+\\s+"), "").trim()
                        if (cleaned.isNotEmpty()) return shortenIsp(cleaned)
                    }
                }
            } catch (_: Exception) {
            } finally {
                try {
                    conn?.disconnect()
                } catch (_: Exception) {
                }
            }
        }
        return ""
    }

    private fun shortenIsp(raw: String): String {
        val clean = raw.replace(",", " ").trim()
        return if (clean.length > 22) clean.substring(0, 22).trim() else clean
    }

    private fun getSavedNetworkLabels(context: Context): MutableMap<String, String> {
        val out = mutableMapOf<String, String>()
        try {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LABELS, null) ?: return out
            val json = JSONObject(raw)
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = json.optString(k, "")
                if (v.isNotEmpty()) out[k] = v
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun recordRunFromProfiles(
        context: Context,
        isRealTest: Boolean,
        targetHost: String,
        snapshot: NetworkSnapshot?,
        testedProfiles: List<VpnProfile>,
        testedCount: Int,
        workingCount: Int
    ) {
        val appCtx = context.applicationContext
        val net = snapshot ?: NetworkSnapshot(
            type = detectNetworkType(appCtx),
            isp = "",
            fingerprint = detectNetworkType(appCtx),
            displayLabel = detectNetworkType(appCtx)
        )
        val items = testedProfiles.map { p ->
            val ms = if (isRealTest) p.mLastRealMs else p.mLastPingMs
            ServerResultItem(
                uuid = p.getUUIDString(),
                name = p.mName ?: "",
                category = CategoryStore.normalise(p.getCategory()),
                ms = ms,
                passed = ms > 0
            )
        }.sortedWith(
            compareByDescending<ServerResultItem> { it.passed }
                .thenBy { if (it.ms > 0) it.ms else Long.MAX_VALUE }
                .thenBy { it.name }
        )
        val record = TestRunRecord(
            id = UUID.randomUUID().toString(),
            timestampMs = System.currentTimeMillis(),
            isRealTest = isRealTest,
            targetHost = targetHost,
            networkType = net.type,
            networkFingerprint = net.fingerprint,
            networkLabel = getSavedNetworkLabels(appCtx)[net.fingerprint] ?: net.displayLabel,
            testedCount = testedCount.coerceAtLeast(items.size),
            workingCount = workingCount,
            results = items
        )
        IO_EXECUTOR.execute {
            saveNewRun(appCtx, record)
        }
    }

    @Synchronized
    private fun saveNewRun(context: Context, record: TestRunRecord) {
        val runs = getRuns(context).toMutableList()
        runs.add(0, record)
        while (runs.size > MAX_RUNS) {
            runs.removeAt(runs.lastIndex)
        }
        writeRuns(context, runs)
    }

    @Synchronized
    fun getRuns(context: Context): List<TestRunRecord> {
        val out = mutableListOf<TestRunRecord>()
        try {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_RUNS, null) ?: return out
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val resArr = obj.optJSONArray("results") ?: JSONArray()
                val resList = mutableListOf<ServerResultItem>()
                for (j in 0 until resArr.length()) {
                    val rObj = resArr.optJSONObject(j) ?: continue
                    resList.add(
                        ServerResultItem(
                            uuid = rObj.optString("uuid", ""),
                            name = rObj.optString("name", ""),
                            category = rObj.optString("category", CategoryStore.DEFAULT_CATEGORY),
                            ms = rObj.optLong("ms", -1L),
                            passed = rObj.optBoolean("passed", false)
                        )
                    )
                }
                out.add(
                    TestRunRecord(
                        id = obj.optString("id", i.toString()),
                        timestampMs = obj.optLong("timestampMs", 0L),
                        isRealTest = obj.optBoolean("isRealTest", false),
                        targetHost = obj.optString("targetHost", ""),
                        networkType = obj.optString("networkType", "Other"),
                        networkFingerprint = obj.optString("networkFingerprint", "Other"),
                        networkLabel = obj.optString("networkLabel", "Other"),
                        testedCount = obj.optInt("testedCount", resList.size),
                        workingCount = obj.optInt("workingCount", 0),
                        results = resList
                    )
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    @Synchronized
    fun renameNetworkLabel(context: Context, runId: String, newLabel: String) {
        val clean = newLabel.trim()
        if (clean.isEmpty()) return
        val runs = getRuns(context).toMutableList()
        var fingerprint: String? = null
        for (i in runs.indices) {
            if (runs[i].id == runId) {
                fingerprint = runs[i].networkFingerprint
                runs[i] = runs[i].copy(networkLabel = clean)
            }
        }
        if (fingerprint != null) {
            val labels = getSavedNetworkLabels(context)
            labels[fingerprint] = clean
            val json = JSONObject()
            for ((k, v) in labels) json.put(k, v)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_LABELS, json.toString()).apply()
            for (i in runs.indices) {
                if (runs[i].networkFingerprint == fingerprint) {
                    runs[i] = runs[i].copy(networkLabel = clean)
                }
            }
        }
        writeRuns(context, runs)
    }

    @Synchronized
    fun deleteRun(context: Context, runId: String) {
        val runs = getRuns(context).filter { it.id != runId }
        writeRuns(context, runs)
    }

    @Synchronized
    fun clearAll(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_RUNS).apply()
    }

    private fun writeRuns(context: Context, runs: List<TestRunRecord>) {
        val arr = JSONArray()
        for (r in runs) {
            val obj = JSONObject()
            obj.put("id", r.id)
            obj.put("timestampMs", r.timestampMs)
            obj.put("isRealTest", r.isRealTest)
            obj.put("targetHost", r.targetHost)
            obj.put("networkType", r.networkType)
            obj.put("networkFingerprint", r.networkFingerprint)
            obj.put("networkLabel", r.networkLabel)
            obj.put("testedCount", r.testedCount)
            obj.put("workingCount", r.workingCount)
            val resArr = JSONArray()
            for (item in r.results) {
                val rObj = JSONObject()
                rObj.put("uuid", item.uuid)
                rObj.put("name", item.name)
                rObj.put("category", item.category)
                rObj.put("ms", item.ms)
                rObj.put("passed", item.passed)
                resArr.put(rObj)
            }
            obj.put("results", resArr)
            arr.put(obj)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_RUNS, arr.toString()).apply()
    }

    /** Apply a saved historical run's test measurements onto live profiles
        and notify both Home and Configs immediately. */
    fun applyRunToProfiles(context: Context, record: TestRunRecord): Int {
        val pm = ProfileManager.getInstance(context)
        val byUuid = record.results.associateBy { it.uuid }
        var updated = 0
        for (p in pm.getProfiles()) {
            val item = byUuid[p.getUUIDString()]
            if (item != null) {
                if (record.isRealTest) {
                    p.mLastRealMs = if (item.passed && item.ms > 0) item.ms else -1L
                    p.mLastRealTime = record.timestampMs
                    p.mLastRealResult = if (item.passed && item.ms > 0) "${item.ms} ms"
                    else context.getString(R.string.minab_real_blocked)
                } else {
                    p.mLastPingMs = if (item.passed && item.ms > 0) item.ms else -1L
                    p.mLastPingTime = record.timestampMs
                    p.mLastPingResult = if (item.passed && item.ms > 0) "${item.ms} ms"
                    else "Timeout"
                }
                updated++
            }
        }
        val appCtx = context.applicationContext
        val snapshot = pm.getProfiles().filter { byUuid.containsKey(it.getUUIDString()) }
        IO_EXECUTOR.execute {
            for (p in snapshot) {
                try {
                    ProfileManager.saveProfile(appCtx, p)
                } catch (_: Exception) {
                }
            }
        }
        MinabProfileBus.notifyChanged()
        return updated
    }
}
