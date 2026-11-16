/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * MinabRealTestSettings (v0.9.6.1, fix #11): the Real test has a
 * settings gear next to its button on Home. Three options, each
 * with the app's recommended value shown next to it in the
 * dialog:
 *   - wait seconds per server (5–30, recommended 12 — the old
 *     Home wait; Configs used to wait 20 s, now both use this);
 *   - how many servers that answered the ping to test before
 *     stopping (3–30, recommended 10);
 *   - whether servers that did NOT answer the ping are tested too
 *     (recommended: yes — they can still work, e.g. UDP servers
 *     with ICMP blocked, so they used to be tested last).
 * The settings live in one SharedPreferences file and apply to
 * the real test wherever it is started (Home or Configs).
 * applyCandidateLimit() runs on the ranked list AFTER the ping
 * phase (RealTester.run), so a server whose ping result is only
 * refreshed by that phase is judged on the fresh result.
 */

package de.blinkt.openvpn.minab;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

import de.blinkt.openvpn.VpnProfile;

public final class MinabRealTestSettings {

    private static final String PREFS = "MinabRealTest";
    private static final String KEY_WAIT_SECONDS = "wait_seconds";
    private static final String KEY_MAX_SERVERS = "max_servers";
    private static final String KEY_TEST_UNPINGED = "test_unpinged";

    /** Recommended wait per server (seconds). */
    public static final int RECOMMENDED_WAIT_SECONDS = 12;
    public static final int MIN_WAIT_SECONDS = 5;
    public static final int MAX_WAIT_SECONDS = 30;

    /** Recommended number of ping-answering servers to test. */
    public static final int RECOMMENDED_MAX_SERVERS = 10;
    public static final int MIN_MAX_SERVERS = 3;
    public static final int MAX_MAX_SERVERS = 30;

    /** Recommended: also test servers that did not answer ping. */
    public static final boolean RECOMMENDED_TEST_UNPINGED = true;

    private MinabRealTestSettings() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static int getWaitSeconds(Context context) {
        int v = prefs(context).getInt(KEY_WAIT_SECONDS, RECOMMENDED_WAIT_SECONDS);
        if (v < MIN_WAIT_SECONDS) return MIN_WAIT_SECONDS;
        if (v > MAX_WAIT_SECONDS) return MAX_WAIT_SECONDS;
        return v;
    }

    public static long getWaitTimeoutMs(Context context) {
        return getWaitSeconds(context) * 1000L;
    }

    public static int getMaxServers(Context context) {
        int v = prefs(context).getInt(KEY_MAX_SERVERS, RECOMMENDED_MAX_SERVERS);
        if (v < MIN_MAX_SERVERS) return MIN_MAX_SERVERS;
        if (v > MAX_MAX_SERVERS) return MAX_MAX_SERVERS;
        return v;
    }

    public static boolean getTestUnpinged(Context context) {
        return prefs(context).getBoolean(KEY_TEST_UNPINGED, RECOMMENDED_TEST_UNPINGED);
    }

    public static void save(Context context, int waitSeconds, int maxServers,
                            boolean testUnpinged) {
        if (waitSeconds < MIN_WAIT_SECONDS) waitSeconds = MIN_WAIT_SECONDS;
        if (waitSeconds > MAX_WAIT_SECONDS) waitSeconds = MAX_WAIT_SECONDS;
        if (maxServers < MIN_MAX_SERVERS) maxServers = MIN_MAX_SERVERS;
        if (maxServers > MAX_MAX_SERVERS) maxServers = MAX_MAX_SERVERS;
        prefs(context).edit()
                .putInt(KEY_WAIT_SECONDS, waitSeconds)
                .putInt(KEY_MAX_SERVERS, maxServers)
                .putBoolean(KEY_TEST_UNPINGED, testUnpinged)
                .apply();
    }

    public static void resetToRecommended(Context context) {
        save(context, RECOMMENDED_WAIT_SECONDS, RECOMMENDED_MAX_SERVERS,
                RECOMMENDED_TEST_UNPINGED);
    }

    /**
     * Limit a ranked list (ping-OK first by ms, then never-tested,
     * then Failed — see RealTester.rankProfilesForRealTest) to what
     * the settings allow: at most maxServers ping-answering
     * servers, then — only when testUnpinged is on — the rest.
     * Returns a new list; the input is untouched.
     */
    public static List<VpnProfile> applyCandidateLimit(
            List<VpnProfile> ranked, Context context) {
        int maxServers = getMaxServers(context);
        boolean testUnpinged = getTestUnpinged(context);
        List<VpnProfile> out = new ArrayList<>();
        int pingOkTaken = 0;
        for (VpnProfile p : ranked) {
            boolean pingOk = p.mLastPingMs >= 0;
            if (pingOk) {
                if (pingOkTaken < maxServers) {
                    out.add(p);
                    pingOkTaken++;
                }
            } else if (testUnpinged) {
                out.add(p);
            }
        }
        return out;
    }
}
