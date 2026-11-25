/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * MinabUpdateChecker: checks GitHub releases (including pre-releases) for a
 * newer version, caches tag + direct release page URL + APK asset download URL
 * for 24 h (force=true bypasses cache), and provides direct open helpers for
 * both the APK asset download and the specific release's GitHub assets page.
 */

package de.blinkt.openvpn.minab;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

import de.blinkt.openvpn.BuildConfig;

public final class MinabUpdateChecker {
    public static final String REPO_URL = "https://github.com/meiyou313/Minab-Children-OpenVPN";
    public static final String RELEASES_URL = REPO_URL + "/releases";
    private static final String API_URL =
            "https://api.github.com/repos/meiyou313/Minab-Children-OpenVPN/releases?per_page=1";

    private static final String PREFS_NAME = "MinabUpdateCheck";
    private static final String KEY_LATEST_TAG = "latest_tag";
    private static final String KEY_RELEASE_URL = "release_url";
    private static final String KEY_ASSET_URL = "asset_url";
    private static final String KEY_CHECKED_AT = "checked_at";
    private static final long CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000; // 24 h
    private static final int HTTP_TIMEOUT_MS = 10000;

    public interface Callback {
        void onResult(String latestTag, boolean updateAvailable);
    }

    private static final class ReleaseMeta {
        final String tag;
        final String releaseUrl;
        final String apkAssetUrl;

        ReleaseMeta(String tag, String releaseUrl, String apkAssetUrl) {
            this.tag = tag;
            this.releaseUrl = releaseUrl;
            this.apkAssetUrl = apkAssetUrl;
        }
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

    /** Open any URL safely in the user's browser. */
    public static void openUrl(Context context, String url) {
        if (context == null || url == null || url.isEmpty()) return;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    /** Open the general GitHub releases list. */
    public static void openReleases(Context context) {
        openUrl(context, RELEASES_URL);
    }

    /** URL pointing directly to the latest release page (with its GitHub Assets). */
    public static String getLatestReleaseUrl(Context context) {
        if (context == null) return RELEASES_URL;
        SharedPreferences p = prefs(context.getApplicationContext());
        String releaseUrl = p.getString(KEY_RELEASE_URL, null);
        if (releaseUrl != null && !releaseUrl.isEmpty()) return releaseUrl;
        String tag = p.getString(KEY_LATEST_TAG, null);
        if (tag != null && !tag.isEmpty()) {
            return RELEASES_URL + "/tag/" + Uri.encode(tag);
        }
        return RELEASES_URL;
    }

    /** Direct APK asset download URL if available, else the latest release tag page. */
    public static String getLatestAssetUrl(Context context) {
        if (context == null) return RELEASES_URL;
        SharedPreferences p = prefs(context.getApplicationContext());
        String assetUrl = p.getString(KEY_ASSET_URL, null);
        if (assetUrl != null && !assetUrl.isEmpty()) return assetUrl;
        return getLatestReleaseUrl(context);
    }

    /** Open the specific latest release page on GitHub (where Assets live). */
    public static void openLatestRelease(Context context) {
        openUrl(context, getLatestReleaseUrl(context));
    }

    /** Open the direct latest APK asset URL (or release tag page fallback). */
    public static void openLatestApkDownload(Context context) {
        openUrl(context, getLatestAssetUrl(context));
    }

    /**
     * Check for a newer release. With force=false a result cached less than
     * 24 h ago is returned without network. Callback always runs on main thread.
     */
    public static void check(final Context context, final boolean force, final Callback callback) {
        final Context appContext = context.getApplicationContext();
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            if (!force) {
                SharedPreferences p = prefs(appContext);
                long checkedAt = p.getLong(KEY_CHECKED_AT, 0);
                if (System.currentTimeMillis() - checkedAt < CHECK_INTERVAL_MS) {
                    final String cached = p.getString(KEY_LATEST_TAG, null);
                    mainHandler.post(() -> callback.onResult(
                            cached, cached != null && isNewer(BuildConfig.VERSION_NAME, cached)));
                    return;
                }
            }
            ReleaseMeta meta = null;
            try {
                meta = fetchLatestApi();
            } catch (Exception ignored) {
            }
            if (meta == null) {
                try {
                    meta = fetchLatestWebPage();
                } catch (Exception ignored) {
                }
            }
            String latestTag;
            if (meta != null && meta.tag != null) {
                latestTag = meta.tag;
                SharedPreferences.Editor ed = prefs(appContext).edit()
                        .putString(KEY_LATEST_TAG, meta.tag)
                        .putLong(KEY_CHECKED_AT, System.currentTimeMillis());
                if (meta.releaseUrl != null && !meta.releaseUrl.isEmpty()) {
                    ed.putString(KEY_RELEASE_URL, meta.releaseUrl);
                } else {
                    ed.putString(KEY_RELEASE_URL, RELEASES_URL + "/tag/" + Uri.encode(meta.tag));
                }
                if (meta.apkAssetUrl != null && !meta.apkAssetUrl.isEmpty()) {
                    ed.putString(KEY_ASSET_URL, meta.apkAssetUrl);
                } else {
                    ed.remove(KEY_ASSET_URL);
                }
                ed.apply();
            } else {
                latestTag = force ? null : prefs(appContext).getString(KEY_LATEST_TAG, null);
            }
            final String found = latestTag;
            mainHandler.post(() -> callback.onResult(
                    found, found != null && isNewer(BuildConfig.VERSION_NAME, found)));
        }).start();
    }

    private static ReleaseMeta fetchLatestApi() throws Exception {
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
            JSONArray releases = new JSONArray(body.toString());
            if (releases.length() == 0) return null;
            JSONObject first = releases.getJSONObject(0);
            String tag = first.optString("tag_name", null);
            if (tag == null || tag.isEmpty()) return null;
            String htmlUrl = first.optString("html_url", null);
            String apkUrl = null;
            JSONArray assets = first.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.optJSONObject(i);
                    if (asset == null) continue;
                    String name = asset.optString("name", "");
                    String dl = asset.optString("browser_download_url", "");
                    if (!dl.isEmpty() && name.toLowerCase(java.util.Locale.US).endsWith(".apk")) {
                        apkUrl = dl;
                        break;
                    }
                }
            }
            if (htmlUrl == null || htmlUrl.isEmpty()) {
                htmlUrl = RELEASES_URL + "/tag/" + Uri.encode(tag);
            }
            return new ReleaseMeta(tag, htmlUrl, apkUrl);
        } finally {
            conn.disconnect();
        }
    }

    private static ReleaseMeta fetchLatestWebPage() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(RELEASES_URL).openConnection();
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
                        String releaseUrl = RELEASES_URL + "/tag/" + Uri.encode(tag);
                        return new ReleaseMeta(tag, releaseUrl, null);
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
