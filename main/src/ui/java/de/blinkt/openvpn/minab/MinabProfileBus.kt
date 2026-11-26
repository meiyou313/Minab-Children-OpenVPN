/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * MinabProfileBus: keeps Home and Configs synchronized in real time whenever
 * profiles, categories, test results, favorites, or sort modes change, and
 * provides shared sort, duplicate-detection, and failed-server helpers.
 */

package de.blinkt.openvpn.minab

import android.content.Context
import android.os.Handler
import android.os.Looper
import de.blinkt.openvpn.VpnProfile
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
     * Find duplicate configs sharing identical remote host + port + protocol (udp/tcp),
     * keeping the best copy per endpoint (connected > favorited > fastest real > fastest ping > LRU).
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

    /** Return servers that are unhealthy/inactive (never keeping the currently connected VPN). */
    fun findInactiveProfiles(profiles: Collection<VpnProfile>): List<VpnProfile> {
        val connectedUuid = try {
            if (VpnStatus.isVPNActive()) VpnStatus.getLastConnectedVPNProfile() else null
        } catch (_: Exception) {
            null
        }
        return profiles.filter { p ->
            p.getUUIDString() != connectedUuid && !MinabVpnControl.isHealthy(p)
        }
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
