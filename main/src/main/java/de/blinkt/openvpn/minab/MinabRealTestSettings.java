/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * MinabRealTestSettings (v0.9.7.1):
 *   - Wait per server: 5–45 s, recommended 20 s
 *   - Healthy servers to find: 3–30, recommended 10
 *   - Test unpinged servers: recommended off
 *   - Target URL to test reachability through tunnel (10 curated presets +
 *     custom URL; recommended: https://api.telegram.org/).
 */

package de.blinkt.openvpn.minab;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

import de.blinkt.openvpn.VpnProfile;

public final class MinabRealTestSettings {

    private static final String PREFS = "MinabRealTest";
    private static final String KEY_WAIT_SECONDS = "wait_seconds";
    private static final String KEY_MAX_SERVERS = "max_servers";
    private static final String KEY_TEST_UNPINGED = "test_unpinged";
    private static final String KEY_TEST_URL = "test_url";
    private static final String KEY_DEFAULTS_V2 = "defaults_v0971_migrated";

    /** Recommended wait per server (seconds). */
    public static final int RECOMMENDED_WAIT_SECONDS = 20;
    public static final int MIN_WAIT_SECONDS = 5;
    public static final int MAX_WAIT_SECONDS = 45;

    /** Recommended number of healthy servers to find before stopping. */
    public static final int RECOMMENDED_MAX_SERVERS = 10;
    public static final int MIN_MAX_SERVERS = 3;
    public static final int MAX_MAX_SERVERS = 30;

    /** Recommended: also test servers that did not answer ping. */
    public static final boolean RECOMMENDED_TEST_UNPINGED = false;

    /** Recommended Real-Test reachability endpoint (fast, lightweight, filtered-sensitive). */
    public static final String RECOMMENDED_TEST_URL = "https://api.telegram.org/";

    /** 10 curated endpoints users can pick from in Real Test Settings (+ Custom URL). */
    public static final String[] PRESET_URLS = {
            "https://api.telegram.org/",
            "https://web.telegram.org/",
            "https://www.google.com/generate_204",
            "https://www.youtube.com/generate_204",
            "https://www.instagram.com/",
            "https://x.com/",
            "https://api.github.com/",
            "https://www.cloudflare.com/cdn-cgi/trace",
            "https://cp.cloudflare.com/generate_204",
            "https://1.1.1.1/"
    };

    public static final String[] PRESET_LABELS = {
            "api.telegram.org (Telegram API)",
            "web.telegram.org (Telegram Web)",
            "google.com (Google 204)",
            "youtube.com (YouTube)",
            "instagram.com (Instagram)",
            "x.com (X / Twitter)",
            "api.github.com (GitHub API)",
            "cloudflare.com (Trace)",
            "cp.cloudflare.com (Cloudflare 204)",
            "1.1.1.1 (Cloudflare DNS)"
    };

    private MinabRealTestSettings() {
    }

    private static SharedPreferences prefs(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean(KEY_DEFAULTS_V2, false)) {
            SharedPreferences.Editor ed = p.edit().putBoolean(KEY_DEFAULTS_V2, true);
            // Upgrade old v0.9.6.x defaults cleanly to 20s / 10 servers / api.telegram.org
            if (!p.contains(KEY_WAIT_SECONDS) || p.getInt(KEY_WAIT_SECONDS, 12) == 12) {
                ed.putInt(KEY_WAIT_SECONDS, RECOMMENDED_WAIT_SECONDS);
            }
            if (!p.contains(KEY_MAX_SERVERS) || p.getInt(KEY_MAX_SERVERS, 5) == 5) {
                ed.putInt(KEY_MAX_SERVERS, RECOMMENDED_MAX_SERVERS);
            }
            if (!p.contains(KEY_TEST_URL)) {
                ed.putString(KEY_TEST_URL, RECOMMENDED_TEST_URL);
            }
            ed.apply();
        }
        return p;
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

    public static String getTestUrl(Context context) {
        String raw = prefs(context).getString(KEY_TEST_URL, RECOMMENDED_TEST_URL);
        if (TextUtils.isEmpty(raw)) return RECOMMENDED_TEST_URL;
        String trimmed = raw.trim();
        if (!trimmed.startsWith("https://") && !trimmed.startsWith("http://")) {
            return RECOMMENDED_TEST_URL;
        }
        return trimmed;
    }

    public static void setTestUrl(Context context, String url) {
        String clean = normaliseUrl(url);
        prefs(context).edit()
                .putString(KEY_TEST_URL, clean)
                .apply();
    }

    public static String normaliseUrl(String url) {
        if (TextUtils.isEmpty(url)) return RECOMMENDED_TEST_URL;
        String trimmed = url.trim();
        if (!trimmed.contains("://")) {
            trimmed = "https://" + trimmed;
        }
        if (!trimmed.startsWith("https://") && !trimmed.startsWith("http://")) {
            return RECOMMENDED_TEST_URL;
        }
        return trimmed;
    }

    public static String getShortHostLabel(Context context) {
        String url = getTestUrl(context);
        try {
            String host = new java.net.URL(url).getHost();
            return TextUtils.isEmpty(host) ? url : host;
        } catch (Exception ignored) {
            return url;
        }
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
        setTestUrl(context, RECOMMENDED_TEST_URL);
    }

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
