/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * MinabServerCountry (v0.9.6.5, fix #2): the country detected from
 * a server's IP after a ping test, stored per profile UUID so the
 * row flag survives restarts. It is only ever written when the
 * config name and host carry no country at all — the name scan in
 * FlagHelper stays the first word, and the group (category) is only
 * a fallback after it. The IP -> country lookup itself is
 * AutoCategory.countryCodeForIp (same 30-day cache and ip-api.com
 * endpoint as the auto-categoriser). Never throws.
 */

package de.blinkt.openvpn.minab

import android.content.Context
import java.util.Locale

object MinabServerCountry {
    private const val PREFS = "MinabServerCountry"

    /** The stored 2-letter country code for this profile, or null. */
    fun get(context: Context, profileUuid: String?): String? {
        if (profileUuid.isNullOrBlank()) return null
        return try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(profileUuid, null)
        } catch (e: Exception) {
            null
        }
    }

    fun put(context: Context, profileUuid: String?, code: String?) {
        if (profileUuid.isNullOrBlank() || code.isNullOrBlank()) return
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(profileUuid, code.lowercase(Locale.US))
                .apply()
        } catch (e: Exception) {
        }
    }
}
