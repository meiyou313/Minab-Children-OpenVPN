/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * MinabProfileBus: keeps Home and Configs synchronized in real time whenever
 * profiles, categories, test results, favorites, or sort modes change, and
 * provides shared sort, smart group-preserving deduplication, and failed-server helpers.
 */

package de.blinkt.openvpn.minab

import android.content.Context
import android.os.Handler
import android.os.Looper
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VpnStatus
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

object MinabProfileBus {
    const val SORT_NAME = 0
    const val SORT_PING = 1
    const val SORT_REAL = 2
    const val SORT_LRU = 3
    const val SORT_COUNTRY = 4

    private const val PREFS_NAME = "MinabPrefs"
    private const val KEY_SORT_MODE = "minab_sort_mode_v2"
    private const val KEY_LAST_IMPORTED = "minab_last_imported_uuids"

    fun interface Listener {
        fun onProfilesUpdated()
    }

    data class SmartDedupPlan(
        val keepers: List<VpnProfile>,
        val toRemove: List<VpnProfile>,
        val mergedGroupsByKeeperUuid: Map<String, Set<String>>,
        val extraFavoriteKeeperUuids: Set<String>
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun notifyChanged() {
        val dispatch = Runnable {
            for (listener in listeners) {
                try {
                    listener.onProfilesUpdated()
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

    /** Shared sort mode across Home and Configs; defaults to Real Test (Best / Recommended). */
    fun getSortMode(context: Context): Int {
        return try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_SORT_MODE, SORT_REAL)
        } catch (_: Exception) {
            SORT_REAL
        }
    }

    fun setSortMode(context: Context, mode: Int) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putInt(KEY_SORT_MODE, mode).apply()
        } catch (_: Exception) {
        }
        notifyChanged()
    }

    /** Remember UUIDs from the latest import batch so Bulk Import can scope credentials safely. */
    fun setLastImportedUuids(context: Context, uuids: Collection<String>) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putStringSet(KEY_LAST_IMPORTED, uuids.toSet()).apply()
        } catch (_: Exception) {
        }
    }

    fun getLastImportedUuids(context: Context): Set<String> {
        return try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getStringSet(KEY_LAST_IMPORTED, emptySet())?.toSet() ?: emptySet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    /**
     * Plan smart deduplication: groups profiles by host:port:proto, picks the best
     * keeper, and plans merging ALL non-Uncategorized groups & favorites from removed
     * duplicates onto the keeper so manually curated servers are NEVER lost.
     */
    fun planSmartDeduplication(
        context: Context,
        profiles: Collection<VpnProfile>,
        favorites: Set<String>
    ): SmartDedupPlan {
        val connectedUuid = try {
            if (VpnStatus.isVPNActive()) VpnStatus.getLastConnectedVPNProfile() else null
        } catch (_: Exception) {
            null
        }
        val byEndpoint = LinkedHashMap<String, MutableList<VpnProfile>>()
        for (p in profiles) {
            val host = p.getPrimaryRemoteHost()?.trim()?.lowercase(Locale.US) ?: ""
            if (host.isEmpty()) continue
            val port = p.getPrimaryRemotePort()
            val proto = if (p.mUseUdp) "u" else "t"
            val key = "$host:$port:$proto"
            byEndpoint.getOrPut(key) { mutableListOf() }.add(p)
        }

        val keepers = mutableListOf<VpnProfile>()
        val toRemove = mutableListOf<VpnProfile>()
        val mergedGroups = HashMap<String, MutableSet<String>>()
        val extraFavs = HashSet<String>()

        for (group in byEndpoint.values) {
            if (group.size <= 1) continue
            val sorted = group.sortedWith(
                compareByDescending<VpnProfile> { it.getUUIDString() == connectedUuid }
                    .thenByDescending { favorites.contains(it.getUUIDString()) }
                    .thenByDescending { p ->
                        p.getAllGroups().any { CategoryStore.isManualCategory(context, it) }
                    }
                    .thenBy { if (it.mLastRealMs > 0) it.mLastRealMs else Long.MAX_VALUE }
                    .thenBy { if (it.mLastPingMs > 0) it.mLastPingMs else Long.MAX_VALUE }
                    .thenByDescending { it.mLastUsed }
                    .thenBy { it.mName ?: "" }
            )
            val keeper = sorted[0]
            keepers.add(keeper)
            val combinedGroups = linkedSetOf<String>()
            combinedGroups.addAll(keeper.getAllGroups())
            for (i in 1 until sorted.size) {
                val dup = sorted[i]
                toRemove.add(dup)
                combinedGroups.addAll(dup.getAllGroups())
                if (favorites.contains(dup.getUUIDString())) {
                    extraFavs.add(keeper.getUUIDString())
                }
            }
            if (combinedGroups.size > 1) {
                combinedGroups.remove(CategoryStore.DEFAULT_CATEGORY)
            }
            mergedGroups[keeper.getUUIDString()] = combinedGroups
        }
        return SmartDedupPlan(keepers, toRemove, mergedGroups, extraFavs)
    }

    /**
     * Find duplicate configs sharing identical remote host + port + protocol (udp/tcp).
     */
    fun findDuplicates(profiles: Collection<VpnProfile>, favorites: Set<String>): List<VpnProfile> {
        val connectedUuid = try {
            if (VpnStatus.isVPNActive()) VpnStatus.getLastConnectedVPNProfile() else null
        } catch (_: Exception) {
            null
        }
        val byEndpoint = LinkedHashMap<String, MutableList<VpnProfile>>()
        for (p in profiles) {
            val host = p.getPrimaryRemoteHost()?.trim()?.lowercase(Locale.US) ?: ""
            if (host.isEmpty()) continue
            val port = p.getPrimaryRemotePort()
            val proto = if (p.mUseUdp) "u" else "t"
            val key = "$host:$port:$proto"
            byEndpoint.getOrPut(key) { mutableListOf() }.add(p)
        }
        val toRemove = mutableListOf<VpnProfile>()
        for (group in byEndpoint.values) {
            if (group.size <= 1) continue
            val sorted = group.sortedWith(
                compareByDescending<VpnProfile> { it.getUUIDString() == connectedUuid }
                    .thenByDescending { favorites.contains(it.getUUIDString()) }
                    .thenBy { if (it.mLastRealMs > 0) it.mLastRealMs else Long.MAX_VALUE }
                    .thenBy { if (it.mLastPingMs > 0) it.mLastPingMs else Long.MAX_VALUE }
                    .thenByDescending { it.mLastUsed }
                    .thenBy { it.mName ?: "" }
            )
            for (i in 1 until sorted.size) {
                toRemove.add(sorted[i])
            }
        }
        return toRemove
    }

    /** Execute smart deduplication: merges groups, stars, credentials & best test data onto keepers. */
    fun executeSmartDeduplication(context: Context, targets: Collection<VpnProfile>): Int {
        val favorites = MinabFavorites.getAll(context)
        val plan = planSmartDeduplication(context, targets, favorites)
        if (plan.toRemove.isEmpty()) return 0

        val pm = ProfileManager.getInstance(context)
        val byEndpoint = HashMap<String, MutableList<VpnProfile>>()
        for (p in targets) {
            val host = p.getPrimaryRemoteHost()?.trim()?.lowercase(Locale.US) ?: ""
            if (host.isEmpty()) continue
            val key = "$host:${p.getPrimaryRemotePort()}:${if (p.mUseUdp) "u" else "t"}"
            byEndpoint.getOrPut(key) { mutableListOf() }.add(p)
        }

        for (keeper in plan.keepers) {
            val merged = plan.mergedGroupsByKeeperUuid[keeper.getUUIDString()]
            if (merged != null) {
                keeper.setAllGroups(merged)
            }
            val host = keeper.getPrimaryRemoteHost()?.trim()?.lowercase(Locale.US) ?: ""
            val key = "$host:${keeper.getPrimaryRemotePort()}:${if (keeper.mUseUdp) "u" else "t"}"
            val family = byEndpoint[key].orEmpty()
            for (dup in family) {
                if (dup === keeper) continue
                if (keeper.mUsername.isNullOrEmpty() && !dup.mUsername.isNullOrEmpty()) {
                    keeper.mUsername = dup.mUsername
                }
                if (keeper.mPassword.isNullOrEmpty() && !dup.mPassword.isNullOrEmpty()) {
                    keeper.mPassword = dup.mPassword
                }
                if (keeper.mLastRealMs <= 0 && dup.mLastRealMs > 0) {
                    keeper.mLastRealMs = dup.mLastRealMs
                    keeper.mLastRealTime = dup.mLastRealTime
                    keeper.mLastRealResult = dup.mLastRealResult
                }
                if (keeper.mLastPingMs <= 0 && dup.mLastPingMs > 0) {
                    keeper.mLastPingMs = dup.mLastPingMs
                    keeper.mLastPingTime = dup.mLastPingTime
                    keeper.mLastPingResult = dup.mLastPingResult
                }
            }
            ProfileManager.saveProfile(context, keeper)
        }

        if (plan.extraFavoriteKeeperUuids.isNotEmpty()) {
            val updatedFavs = favorites.toMutableSet()
            updatedFavs.addAll(plan.extraFavoriteKeeperUuids)
            for (r in plan.toRemove) updatedFavs.remove(r.getUUIDString())
            MinabFavorites.setAll(context, updatedFavs)
        }

        val removedUuids = mutableListOf<String>()
        for (dup in plan.toRemove) {
            removedUuids.add(dup.getUUIDString())
            pm.removeProfile(context, dup)
        }
        MinabServerStats.removeStatsForUuids(context, removedUuids)
        return plan.toRemove.size
    }

    /** Return ONLY servers that were genuinely tested and failed/blocked (protects untested servers). */
    fun findFailedProfiles(profiles: Collection<VpnProfile>): List<VpnProfile> {
        val connectedUuid = try {
            if (VpnStatus.isVPNActive()) VpnStatus.getLastConnectedVPNProfile() else null
        } catch (_: Exception) {
            null
        }
        return profiles.filter { p ->
            p.getUUIDString() != connectedUuid && MinabVpnControl.isFailed(p)
        }
    }

    /** Return servers that have never been tested yet. */
    fun findUntestedProfiles(profiles: Collection<VpnProfile>): List<VpnProfile> {
        val connectedUuid = try {
            if (VpnStatus.isVPNActive()) VpnStatus.getLastConnectedVPNProfile() else null
        } catch (_: Exception) {
            null
        }
        return profiles.filter { p ->
            p.getUUIDString() != connectedUuid && !MinabVpnControl.isTested(p)
        }
    }

    /** Return servers that are unhealthy/failed (excludes connected VPN). */
    fun findInactiveProfiles(profiles: Collection<VpnProfile>): List<VpnProfile> {
        return findFailedProfiles(profiles)
    }

    /** Unified comparator used on both Home and Configs (favorites-first optional). */
    fun sortProfiles(
        profiles: List<VpnProfile>,
        sortMode: Int,
        favorites: Set<String>,
        favoritesFirst: Boolean
    ): List<VpnProfile> {
        val baseComparator: Comparator<VpnProfile> = when (sortMode) {
            SORT_PING -> compareBy<VpnProfile> {
                if (it.mLastPingMs > 0) it.mLastPingMs else Long.MAX_VALUE
            }.thenBy { it.mName ?: "" }

            SORT_LRU -> compareByDescending<VpnProfile> { it.mLastUsed }
                .thenBy { it.mName ?: "" }

            SORT_COUNTRY -> compareBy<VpnProfile> {
                CategoryStore.normalise(it.getCategory())
            }.thenBy { if (it.mLastRealMs > 0) it.mLastRealMs else Long.MAX_VALUE }
                .thenBy { if (it.mLastPingMs > 0) it.mLastPingMs else Long.MAX_VALUE }
                .thenBy { it.mName ?: "" }

            SORT_NAME -> compareBy<VpnProfile> {
                (it.mName ?: "").lowercase(Locale.US)
            }

            // SORT_REAL (Default & Recommended)
            else -> compareBy<VpnProfile> {
                if (it.mLastRealMs > 0) it.mLastRealMs else Long.MAX_VALUE
            }.thenBy {
                if (it.mLastPingMs > 0) it.mLastPingMs else Long.MAX_VALUE
            }.thenBy { it.mName ?: "" }
        }
        return if (favoritesFirst) {
            profiles.sortedWith(
                compareByDescending<VpnProfile> { favorites.contains(it.getUUIDString()) }
                    .then(baseComparator)
            )
        } else {
            profiles.sortedWith(baseComparator)
        }
    }
}
