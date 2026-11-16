/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * MinabRealTestSettings (v0.9.6.1, fix #11; v0.9.6.2, fix #1):
 * the Real test has a settings gear next to its button on Home
 * and on Configs. Three options, each with the app's recommended
 * value shown next to it in an inline panel under the button —
 * v0.9.6.2 replaced the 0.9.6.1 dialog with the panel, because
 * the dialog was ugly AND its changes only saved on OK (closing
 * it any other way silently lost them — see RealTester for why
 * that looked like the "test unpinged" setting being ignored).
 * The panel saves every change at once:
 *   - wait seconds per server (5–30, recommended 12 — the old
 *     Home wait; Configs used to wait 20 s, now both use this);
 *   - how many servers must PASS the real test before the run
 *     stops (3–30, recommended 5 since v0.9.6.2, was 10 — the
 *     number counts successes, not candidates: the run keeps
 *     going through the ranked list until that many pass, it does
 *     not stop after testing that many);
 *   - whether servers that did NOT answer the ping are tested too
 *     (recommended: no since v0.9.6.2, was yes — they can still
 *     work, e.g. UDP servers with ICMP blocked, so the user can
 *     turn it back on).
 * The settings live in one SharedPreferences file and apply to
 * the real test wherever it is started (Home or Configs).
 * applyCandidateFilter() runs on the ranked list AFTER the ping
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

    /** Recommended number of ping-answering servers to test.
        Minab Beta v0.9.6.2 (fix #1): 5, was 10. */
    public static final int RECOMMENDED_MAX_SERVERS = 5;
    public static final int MIN_MAX_SERVERS = 3;
    public static final int MAX_MAX_SERVERS = 30;

    /** Recommended: also test servers that did not answer ping.
        Minab Beta v0.9.6.2 (fix #1): no, was yes. */
    public static final boolean RECOMMENDED_TEST_UNPINGED = false;

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
     * Filter a ranked list (ping-OK first by ms, then never-tested,
     * then Failed — see RealTester.rankProfilesForRealTest) to what
     * the settings allow. Minab Beta v0.9.6.2 (fix #4): the number
     * setting does NOT cap this list — it counts SUCCESSES (the
     * runner stops after that many servers pass), so every
     * ping-answering server stays a candidate and the run keeps
     * going past the first N when they fail. The 0.9.6.1 cap
     * ("test only the N lowest-ping servers") stopped the run even
     * when all N failed. Servers that did not answer ping are in
     * only when testUnpinged is on. A 0 ms value is not a
     * measurement (see VpnProfile's migration guard), so ping-OK
     * needs mLastPingMs > 0. Returns a new list; the input is
     * untouched.
     */
    public static List<VpnProfile> applyCandidateFilter(
            List<VpnProfile> ranked, Context context) {
        boolean testUnpinged = getTestUnpinged(context);
        List<VpnProfile> out = new ArrayList<>();
        for (VpnProfile p : ranked) {
            boolean pingOk = p.mLastPingMs > 0;
            if (pingOk || testUnpinged) {
                out.add(p);
            }
        }
        return out;
    }
}
