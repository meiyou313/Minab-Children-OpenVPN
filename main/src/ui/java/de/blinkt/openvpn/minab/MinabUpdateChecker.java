/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * MinabUpdateChecker: lightweight check for a newer Minab release
 * on GitHub (public releases API, no auth). It never blocks the UI:
 * the HTTP call runs on a background thread and the callback is
 * posted to the main thread. The result is cached for 24 hours so a
 * launch check does not hit the network every time; a manual check
 * (force=true) always goes to the network. Nothing is uploaded and
 * no personal data is sent — the request only reads the latest
 * public release tag of meiyou313/Minab-Children-OpenVPN.
 *
 * Minab Beta v0.9.1 — why the check failed on the user's phone:
 * api.github.com was unreachable from their connection (the check
 * reported "Could not check for updates" even though the phone was
 * online). Two changes: the timeouts are 10 s now (5 s was tight
 * on a slow mobile link), and when the API call fails the checker
 * falls back to the /releases/latest page on github.com itself —
 * a 302 redirect whose Location header carries the latest tag.
 * github.com and api.github.com are different hosts, so one can
 * work while the other does not. The failure message is honest
 * now ("Could not reach GitHub…"), and the last cached tag is
 * still used when both routes fail.
 *
 * Minab Beta v0.9.6.1 — that 0.9.1 fix never actually worked, and
 * the check failed on the phone again in 0.9.6. The real cause was
 * found by testing both routes from outside the phone: EVERY Minab
 * release is a pre-release (prerelease: true), and GitHub's
 * "latest release" endpoint only returns FULL releases — so
 * api.github.com/.../releases/latest answers 404, always, for
 * this repo. And /releases/latest on github.com does NOT redirect
 * to a tag either: with no full release it 302s to the releases
 * LIST page (no "/tag/" in the Location), so the old fallback
 * returned null too. Both routes failed for everyone, on every
 * network — it was never the user's connection. The fix: the API
 * route now reads the releases LIST (per_page=1), which includes
 * pre-releases, and the web fallback reads the releases list
 * page's HTML and takes the first "/releases/tag/<tag>" link.
 *
 * Version comparison is numeric on the dotted parts ("0.4" vs
 * "0.10"); a "-beta" suffix is ignored for the comparison, so
 * 0.4-beta and 0.4 count as the same version. Four-part versions
 * compare correctly too (0.9.6.1 is newer than 0.9.6: a missing
 * part counts as 0).
 */

package de.blinkt.openvpn.minab;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

import de.blinkt.openvpn.BuildConfig;

public final class MinabUpdateChecker {
    /** Where the user downloads a newer version. */
    public static final String RELEASES_URL = "https://github.com/meiyou313/Minab-Children-OpenVPN/releases";
    /**
     * Minab Beta v0.9.6.1 — the releases LIST, not /releases/latest:
     * the "latest" endpoint only returns FULL releases and answers
     * 404 for this repo (every Minab release is a pre-release).
     * The list includes pre-releases, newest first.
     */
    private static final String API_URL = "https://api.github.com/repos/meiyou313/Minab-Children-OpenVPN/releases?per_page=1";

    private static final String PREFS_NAME = "MinabUpdateCheck";
    private static final String KEY_LATEST_TAG = "latest_tag";
    private static final String KEY_CHECKED_AT = "checked_at";
    private static final long CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000; // 24 h
    /** Minab Beta v0.9.1: 10 s (was 5 s) — a slow mobile link
        needs more time, and a slow check is not a failed check. */
    private static final int HTTP_TIMEOUT_MS = 10000;

    /** latestTag is null when the check failed (offline, API error). */
    public interface Callback {
        void onResult(String latestTag, boolean updateAvailable);
    }

    private MinabUpdateChecker() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Compare two version strings numerically. True when latest is newer. */
    public static boolean isNewer(String current, String latest) {
        if (current == null || latest == null) return false;
        String[] cur = normalise(current).split("\\.");
        String[] lat = normalise(latest).split("\\.");
        int len = Math.max(cur.length, lat.length);
        for (int i = 0; i < len; i++) {
            int c = i < cur.length ? parsePart(cur[i]) : 0;
            int l = i < lat.length ? parsePart(lat[i]) : 0;
            if (l > c) return true;
            if (l < c) return false;
        }
        return false;
    }

    private static String normalise(String version) {
        String v = version.trim();
        if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        int dash = v.indexOf('-');
        if (dash >= 0) v = v.substring(0, dash);
        return v;
    }

    private static int parsePart(String part) {
        try {
            return Integer.parseInt(part);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Open the releases page in the browser. */
    public static void openReleases(Context context) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    /**
     * Check for a newer release. With force=false a result cached
     * less than 24 h ago is returned without network. The callback
     * always runs on the main thread.
     */
    public static void check(final Context context, final boolean force, final Callback callback) {
        final Context appContext = context.getApplicationContext();
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String latestTag = null;
            if (!force) {
                SharedPreferences p = prefs(appContext);
                long checkedAt = p.getLong(KEY_CHECKED_AT, 0);
                if (System.currentTimeMillis() - checkedAt < CHECK_INTERVAL_MS) {
                    latestTag = p.getString(KEY_LATEST_TAG, null);
                    final String cached = latestTag;
                    mainHandler.post(() -> callback.onResult(
                            cached, cached != null && isNewer(BuildConfig.VERSION_NAME, cached)));
                    return;
                }
            }
            try {
                latestTag = fetchLatestTagApi();
            } catch (Exception ignored) {
                // API unreachable: try the web fallback below.
            }
            if (latestTag == null) {
                // Minab Beta v0.9.6.1 — fallback: api.github.com is
                // unreachable from some networks while github.com
                // itself works. The releases list page (HTML) always
                // links the newest release first, pre-releases
                // included — the tag is in the first
                // "/releases/tag/<tag>" link. (The old fallback used
                // /releases/latest's 302 Location, which points at
                // the list page, not a tag, when there is no full
                // release — it could never work for this repo.)
                try {
                    latestTag = fetchLatestTagWebPage();
                } catch (Exception ignored) {
                    // Both routes failed: keep the cached tag, if any.
                }
            }
            if (latestTag == null) {
                latestTag = prefs(appContext).getString(KEY_LATEST_TAG, null);
            }
            if (latestTag != null) {
                prefs(appContext).edit()
                        .putString(KEY_LATEST_TAG, latestTag)
                        .putLong(KEY_CHECKED_AT, System.currentTimeMillis())
                        .apply();
            }
            final String found = latestTag;
            mainHandler.post(() -> callback.onResult(
                    found, found != null && isNewer(BuildConfig.VERSION_NAME, found)));
        }).start();
    }

    /**
     * The releases API list: [{"tag_name": "v0.9.6", ...}, ...],
     * newest first, pre-releases included. Null on any failure.
     * Minab Beta v0.9.6.1 — the old /releases/latest endpoint only
     * returns FULL releases and answered 404 for this repo, because
     * every Minab release is a pre-release.
     */
    private static String fetchLatestTagApi() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(API_URL).openConnection();
        try {
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "Minab");
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) body.append(line);
            reader.close();
            org.json.JSONArray releases = new JSONArray(body.toString());
            if (releases.length() == 0) return null;
            String tag = releases.getJSONObject(0).optString("tag_name", null);
            return (tag != null && !tag.isEmpty()) ? tag : null;
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Minab Beta v0.9.6.1 — the web fallback: fetch the releases
     * list page (redirects followed) and take the tag from the
     * first "/releases/tag/<tag>" link in the HTML. The page lists
     * releases newest first, pre-releases included. Null on any
     * failure (no tag link found, or the page could not be read).
     * The read stops as soon as the tag is found and never reads
     * more than ~512 KB of the page.
     */
    private static String fetchLatestTagWebPage() throws Exception {
        HttpURLConnection conn = (HttpURLConnection)
                new URL(RELEASES_URL).openConnection();
        try {
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "Minab");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line).append('\n');
                if (body.length() > 512 * 1024) break;
                int idx = body.indexOf("/releases/tag/");
                if (idx >= 0) {
                    int start = idx + "/releases/tag/".length();
                    int end = start;
                    while (end < body.length()) {
                        char c = body.charAt(end);
                        if (c == '"' || c == '\'' || c == '?' || c == '<' || c == ' ') break;
                        end++;
                    }
                    String tag = body.substring(start, end);
                    if (!tag.isEmpty()) {
                        reader.close();
                        return tag;
                    }
                }
            }
            reader.close();
            return null;
        } finally {
            conn.disconnect();
        }
    }
}
