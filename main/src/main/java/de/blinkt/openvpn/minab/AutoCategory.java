/*
 * Minab Children — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab Children contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * AutoCategory (v0.9.7.1): accurate batch IP geolocation with strict offline fallback.
 *   1. Resolve each profile's remote host to IP in parallel (or use literal IP directly).
 *   2. Query GeoIP in batches (get.geojs.io supports up to 25 IPs in 1 HTTPS call!)
 *      with multi-provider per-IP fallbacks (api.country.is, ipwho.is, ipinfo.io).
 *   3. If offline / rate-limited, offline fallback ONLY accepts flag emojis or full
 *      country names ("Germany", "Netherlands", "United States", etc.) — NEVER
 *      ambiguous 2-letter English words like "in", "no", "it", "at", "be", "my",
 *      so configs are never silently misclassified into wrong countries.
 */

package de.blinkt.openvpn.minab;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import de.blinkt.openvpn.VpnProfile;

public final class AutoCategory {
    private static final String GEO_CACHE_PREFS = "MinabGeoCache";
    private static final long CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000; // 30 days
    private static final int HTTP_TIMEOUT_MS = 4500;
    private static final int BATCH_SIZE = 25;

    public interface BatchProgressCallback {
        void onResult(VpnProfile profile, String countryCode, String countryName, int done, int total);
    }

    private AutoCategory() {
    }

    /**
     * Resolve and categorise a batch of profiles accurately by IP geolocation.
     * Returns map of profile UUID -> lower-case 2-letter ISO country code for
     * every profile whose country was resolved. Must be called from a background thread.
     */
    public static Map<String, String> detectCategoriesForProfiles(
            Context context,
            List<VpnProfile> profiles,
            boolean forceRefreshCache,
            BatchProgressCallback callback) {
        Map<String, String> detectedCodesByUuid = new HashMap<>();
        if (context == null || profiles == null || profiles.isEmpty()) return detectedCodesByUuid;

        final Context appCtx = context.getApplicationContext();
        final int total = profiles.size();

        // 1. Resolve profile hosts to IPs in parallel (chunks of 8)
        Map<String, String> uuidToIp = new HashMap<>();
        Set<String> uniqueIps = new LinkedHashSet<>();
        ExecutorService dnsPool = Executors.newFixedThreadPool(8);
        try {
            List<Future<String[]>> futures = new ArrayList<>();
            for (final VpnProfile p : profiles) {
                futures.add(dnsPool.submit((Callable<String[]>) () -> {
                    String host = p.getPrimaryRemoteHost();
                    String ip = TextUtils.isEmpty(host) ? null : resolveIp(host);
                    return new String[]{p.getUUIDString(), ip};
                }));
            }
            for (Future<String[]> f : futures) {
                try {
                    String[] pair = f.get();
                    if (pair[1] != null) {
                        uuidToIp.put(pair[0], pair[1]);
                        uniqueIps.add(pair[1]);
                    }
                } catch (Exception ignored) {
                }
            }
        } finally {
            dnsPool.shutdown();
        }

        // 2. Batch-query GeoIP for all resolved IPs
        Map<String, String> ipToCode = countryCodesForIps(
                appCtx, new ArrayList<>(uniqueIps), forceRefreshCache);

        // 3. Map each profile to its detected country (and strict offline fallback if needed)
        int done = 0;
        for (VpnProfile p : profiles) {
            done++;
            String uuid = p.getUUIDString();
            String ip = uuidToIp.get(uuid);
            String code = ip == null ? null : ipToCode.get(ip);
            String countryName = null;
            if (code != null) {
                countryName = CountryCodes.nameForCode(code);
                detectedCodesByUuid.put(uuid, code);
            } else if (!forceRefreshCache) {
                countryName = guessOffline(p.mName, null, p.getPrimaryRemoteHost());
            }
            if (callback != null) {
                callback.onResult(p, code, countryName, done, total);
            }
        }
        return detectedCodesByUuid;
    }

    /**
     * Batch IP -> 2-letter country code lookup using cache + GeoJS batch API
     * + individual multi-provider fallback. Background thread only.
     */
    public static Map<String, String> countryCodesForIps(
            Context context, List<String> ips, boolean forceRefresh) {
        Map<String, String> result = new HashMap<>();
        if (context == null || ips == null || ips.isEmpty()) return result;

        List<String> uncached = new ArrayList<>();
        for (String ip : ips) {
            if (TextUtils.isEmpty(ip)) continue;
            if (!forceRefresh) {
                String cached = cachedCountryCode(context, ip);
                if (cached != null) {
                    result.put(ip, cached);
                    continue;
                }
            }
            if (!uncached.contains(ip)) {
                uncached.add(ip);
            }
        }

        // Pass 1: Batch query via get.geojs.io (up to 25 IPs per request)
        List<String> remaining = new ArrayList<>();
        for (int i = 0; i < uncached.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, uncached.size());
            List<String> chunk = uncached.subList(i, end);
            Map<String, String> batch = queryGeoJsBatch(chunk);
            for (String ip : chunk) {
                String code = batch.get(ip);
                if (code != null) {
                    cacheCountryCode(context, ip, code);
                    result.put(ip, code);
                } else {
                    remaining.add(ip);
                }
            }
        }

        // Pass 2: Parallel individual fallbacks for any IPs missed by batch
        if (!remaining.isEmpty()) {
            ExecutorService pool = Executors.newFixedThreadPool(Math.min(4, remaining.size()));
            try {
                List<Future<String[]>> futures = new ArrayList<>();
                for (final String ip : remaining) {
                    futures.add(pool.submit((Callable<String[]>) () ->
                            new String[]{ip, lookupCountryCodeSingle(ip)}));
                }
                for (Future<String[]> f : futures) {
                    try {
                        String[] pair = f.get();
                        if (pair[1] != null) {
                            cacheCountryCode(context, pair[0], pair[1]);
                            result.put(pair[0], pair[1]);
                        }
                    } catch (Exception ignored) {
                    }
                }
            } finally {
                pool.shutdown();
            }
        }

        return result;
    }

    /** Single-profile country guess (IP first, strict offline fallback). */
    public static String guessCategory(Context context, String profileName,
                                       String fileName, String remoteHost) {
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
        return guessOffline(profileName, fileName, remoteHost);
    }

    public static String countryCodeForIp(Context context, String ip) {
        if (context == null || TextUtils.isEmpty(ip)) return null;
        try {
            String cached = cachedCountryCode(context, ip);
            if (cached != null) return cached;
            String fetched = lookupCountryCodeSingle(ip);
            if (fetched != null) {
                cacheCountryCode(context, ip, fetched);
                return fetched;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Strict offline heuristic: ONLY matches flag emojis or explicit full country
     * names (e.g. "Germany", "Netherlands", "United States"). Never matches raw
     * 2-letter words like "in", "no", "it", "at", "be", "my" to prevent wrong
     * country grouping.
     */
    public static String guessOffline(String profileName, String fileName, String remoteHost) {
        String code = flagEmojiCode(profileName);
        if (code == null) code = flagEmojiCode(fileName);
        if (code == null) code = fullCountryCode(profileName);
        if (code == null) code = fullCountryCode(fileName);
        if (code == null) code = fullCountryCode(remoteHost);
        return code == null ? null : CountryCodes.nameForCode(code);
    }

    private static String flagEmojiCode(String text) {
        if (TextUtils.isEmpty(text)) return null;
        int i = 0;
        while (i < text.length()) {
            int cp = Character.codePointAt(text, i);
            if (cp >= 0x1F1E6 && cp <= 0x1F1FF) {
                int j = i + Character.charCount(cp);
                if (j < text.length()) {
                    int cp2 = Character.codePointAt(text, j);
                    if (cp2 >= 0x1F1E6 && cp2 <= 0x1F1FF) {
                        char a = (char) ('a' + (cp - 0x1F1E6));
                        char b = (char) ('a' + (cp2 - 0x1F1E6));
                        String code = "" + a + b;
                        if (CountryCodes.isKnownCode(code)) return code;
                    }
                }
            }
            i += Character.charCount(cp);
        }
        return null;
    }

    private static String fullCountryCode(String text) {
        if (TextUtils.isEmpty(text)) return null;
        String lower = " " + text.toLowerCase(Locale.US).replaceAll("[^a-z]+", " ") + " ";
        String bestCode = null;
        int bestLen = 0;
        for (String code : CountryCodes.knownCodes()) {
            String name = CountryCodes.nameForCode(code);
            if (name == null || name.length() <= 3) continue; // skip short acronyms like UAE unless matched cleanly
            String lowerName = name.toLowerCase(Locale.US);
            if (lowerName.length() > bestLen && lower.contains(" " + lowerName + " ")) {
                bestCode = code;
                bestLen = lowerName.length();
            }
        }
        return bestCode;
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

    private static Map<String, String> queryGeoJsBatch(List<String> ips) {
        if (ips.isEmpty()) return Collections.emptyMap();
        HttpURLConnection conn = null;
        Map<String, String> out = new HashMap<>();
        try {
            StringBuilder urlBuilder = new StringBuilder("https://get.geojs.io/v1/ip/country.json?ip=");
            for (int i = 0; i < ips.size(); i++) {
                if (i > 0) urlBuilder.append(',');
                urlBuilder.append(ips.get(i));
            }
            conn = (HttpURLConnection) new URL(urlBuilder.toString()).openConnection();
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "Minab");
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return out;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            String body = sb.toString().trim();
            if (body.startsWith("[")) {
                JSONArray arr = new JSONArray(body);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.optJSONObject(i);
                    parseGeoJsEntry(obj, out);
                }
            } else if (body.startsWith("{")) {
                parseGeoJsEntry(new JSONObject(body), out);
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return out;
    }

    private static void parseGeoJsEntry(JSONObject obj, Map<String, String> out) {
        if (obj == null) return;
        String ip = obj.optString("ip", "").trim();
        String code = obj.optString("country", "").trim().toLowerCase(Locale.US);
        if (!ip.isEmpty() && CountryCodes.isKnownCode(code)) {
            out.put(ip, code);
        }
    }

    private static String lookupCountryCodeSingle(String ip) {
        String code = queryJsonField("https://api.country.is/" + ip, null, "country");
        if (code != null) return code;

        code = queryJsonField("https://ipwho.is/" + ip + "?fields=success,country_code",
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
