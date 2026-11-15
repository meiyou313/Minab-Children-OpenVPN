/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * MinabIpCheck (v0.9): after connecting, show the current public IP
 * and country. One small HTTPS request to ipinfo.io/json through the
 * tunnel (the VPN is the default route, so the request goes through
 * it with no special handling), 5 s timeout, on a worker thread — it
 * never blocks the UI. A failure returns null and the UI shows "—".
 * No speed test lives here on purpose: the user asked for the IP and
 * country only (v0.9 follow-up C).
 */

package de.blinkt.openvpn.minab;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;

public final class MinabIpCheck {
    // Minab Beta v0.9.3 — ipinfo.io can be blocked or fail in
    // Iran, and ipify returns no country at all: try ipinfo
    // first, then ip-api.com (also returns the country), then
    // ipify (IP only). The first endpoint that answers wins.
    private static final String[] IP_URLS = {
            "https://ipinfo.io/json",
            "https://ip-api.com/json",
            "https://api.ipify.org?format=json"
    };
    private static final int TIMEOUT_MS = 5000;

    /** IP + country code, or null when the check failed. */
    public static final class IpInfo {
        public final String ip;
        /** Two-letter country code (e.g. "DE"), may be empty. */
        public final String countryCode;

        public IpInfo(String ip, String countryCode) {
            this.ip = ip;
            this.countryCode = countryCode == null ? "" : countryCode;
        }
    }

    public interface Callback {
        void onResult(IpInfo info);
    }

    private MinabIpCheck() {
    }

    /** Runs off the UI thread; the callback always runs on the main thread. */
    public static void check(final Callback callback) {
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            IpInfo info = null;
            for (String url : IP_URLS) {
                info = fetch(url);
                if (info != null) break;
            }
            final IpInfo result = info;
            mainHandler.post(() -> callback.onResult(result));
        }).start();
    }

    private static IpInfo fetch(String urlString) {
        HttpsURLConnection conn = null;
        try {
            conn = (HttpsURLConnection) new URL(urlString).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "Minab");
            if (conn.getResponseCode() != HttpsURLConnection.HTTP_OK) return null;
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) body.append(line);
            reader.close();
            return parse(urlString, body.toString());
        } catch (Exception ignored) {
            // Offline / service down / tunnel not carrying traffic:
            // the next endpoint is tried; the caller shows "—".
            // Never fatal.
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** The three endpoints name their fields differently:
        ipinfo.io {"ip", "country"}, ip-api.com {"query",
        "countryCode", "status"}, ipify {"ip"} (no country). */
    private static IpInfo parse(String urlString, String body) {
        try {
            JSONObject json = new JSONObject(body);
            if (urlString.contains("ip-api.com")) {
                if (!"success".equals(json.optString("status", ""))) return null;
                String ip = json.optString("query", "");
                if (ip.isEmpty()) return null;
                return new IpInfo(ip, json.optString("countryCode", ""));
            }
            String ip = json.optString("ip", "");
            if (ip.isEmpty()) return null;
            return new IpInfo(ip, json.optString("country", ""));
        } catch (Exception ignored) {
            return null;
        }
    }
}
