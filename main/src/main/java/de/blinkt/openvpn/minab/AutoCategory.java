/*
 * Minab Children — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab Children contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * AutoCategory: IP-based automatic country categorisation for imported VPN
 * profiles, with an offline hostname/filename fallback when offline or DNS
 * fails. Uses HTTPS GeoIP endpoints with 30-day SharedPreferences caching.
 */

package de.blinkt.openvpn.minab;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AutoCategory {
    private static final String GEO_CACHE_PREFS = "MinabGeoCache";
    private static final long CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000; // 30 days
    private static final int HTTP_TIMEOUT_MS = 3500;

    private static final Pattern CODE_TOKEN =
            Pattern.compile("(?:^|[^a-z0-9])([a-z]{2})(?:[^a-z]|$)");
    private static final Pattern TLD =
            Pattern.compile("\\.([a-z]{2})$");

    private AutoCategory() {
    }

    /**
     * IP-first country category detection for a profile.
     * 1. Resolve remoteHost -> IP -> 30-day cache or HTTPS GeoIP lookup.
     * 2. Fall back to offline country-code heuristic in profileName / fileName / host.
     * Safe to call from a background thread only. Never throws.
     */
    public static String guessCategory(Context context, String profileName,
                                       String fileName, String remoteHost) {
        // 1. IP-based country lookup first (most accurate for real server location).
        if (context != null && !TextUtils.isEmpty(remoteHost)) {
            try {
                String ip = resolveIp(remoteHost);
                if (ip != null) {
                    String code = countryCodeForIp(context, ip);
                    if (code != null) {
                        String name = CountryCodes.nameForCode(code);
                        if (name != null) return name;
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // 2. Offline heuristic fallback when offline, DNS failed, or rate-limited.
        return guessOffline(profileName, fileName, remoteHost);
    }

    /**
     * Resolve country code (lower-case 2-letter ISO) for an IP address,
     * using the 30-day cache and HTTPS GeoIP fallback chain.
     * Background thread only.
     */
    public static String countryCodeForIp(Context context, String ip) {
        if (context == null || TextUtils.isEmpty(ip)) return null;
        try {
            String cached = cachedCountryCode(context, ip);
            if (cached != null) return cached;
            String fetched = lookupCountryCode(ip);
            if (fetched != null) {
                cacheCountryCode(context, ip, fetched);
                return fetched;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Offline-only heuristic, usable without network or Context. */
    public static String guessOffline(String profileName, String fileName, String remoteHost) {
        String code = codeFromText(profileName);
        if (code == null) code = codeFromText(fileName);
        if (code == null) code = codeFromText(remoteHost);
        if (code == null) code = tldCode(remoteHost);
        return code == null ? null : CountryCodes.nameForCode(code);
    }

    private static String codeFromText(String text) {
        if (TextUtils.isEmpty(text)) return null;
        String lower = text.toLowerCase(Locale.US);
        Matcher m = CODE_TOKEN.matcher(lower);
        while (m.find()) {
            String candidate = m.group(1);
            if (CountryCodes.isKnownCode(candidate)) return candidate;
        }
        return null;
    }

    private static String tldCode(String host) {
        if (TextUtils.isEmpty(host)) return null;
        Matcher m = TLD.matcher(host.toLowerCase(Locale.US).trim());
        if (m.find() && CountryCodes.isKnownCode(m.group(1))) return m.group(1);
        return null;
    }

    private static String resolveIp(String host) {
        try {
            String clean = host.trim();
            if (clean.matches("^[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+$") || clean.contains(":")) {
                return clean;
            }
            InetAddress addr = InetAddress.getByName(clean);
            return addr == null ? null : addr.getHostAddress();
        } catch (Exception e) {
            return null;
        }
    }

    private static String cachedCountryCode(Context context, String ip) {
        SharedPreferences prefs = context.getSharedPreferences(GEO_CACHE_PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(ip, null);
        if (raw == null) return null;
        String[] parts = raw.split("\\|", 2);
        if (parts.length != 2) return null;
        try {
            long expiry = Long.parseLong(parts[1]);
            if (System.currentTimeMillis() > expiry) return null;
        } catch (NumberFormatException e) {
            return null;
        }
        return parts[0];
    }

    private static void cacheCountryCode(Context context, String ip, String code) {
        long expiry = System.currentTimeMillis() + CACHE_TTL_MS;
        context.getSharedPreferences(GEO_CACHE_PREFS, Context.MODE_PRIVATE)
                .edit().putString(ip, code + "|" + expiry).apply();
    }

    /**
     * HTTPS GeoIP lookup chain (no API key, free public endpoints):
     *   1. ipwho.is (JSON: {"success":true,"country_code":"DE"})
     *   2. get.geojs.io (JSON: {"country":"DE"})
     *   3. ipinfo.io (plain text: "DE")
     */
    private static String lookupCountryCode(String ip) {
        String code = queryJsonField("https://ipwho.is/" + ip + "?fields=success,country_code",
                "success", "country_code");
        if (code != null) return code;

        code = queryJsonField("https://get.geojs.io/v1/ip/country/" + ip + ".json",
                null, "country");
        if (code != null) return code;

        return queryPlainCode("https://ipinfo.io/" + ip + "/country");
    }

    private static String queryJsonField(String urlStr, String boolGateKey, String codeKey) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "Minab");
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            JSONObject json = new JSONObject(sb.toString());
            if (boolGateKey != null && !json.optBoolean(boolGateKey, false)) return null;
            String code = json.optString(codeKey, null);
            if (TextUtils.isEmpty(code)) return null;
            code = code.trim().toLowerCase(Locale.US);
            return CountryCodes.isKnownCode(code) ? code : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String queryPlainCode(String urlStr) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Minab");
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line = r.readLine();
                if (TextUtils.isEmpty(line)) return null;
                String code = line.trim().toLowerCase(Locale.US);
                return CountryCodes.isKnownCode(code) ? code : null;
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
