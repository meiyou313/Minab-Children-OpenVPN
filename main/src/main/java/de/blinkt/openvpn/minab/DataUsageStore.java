/*
 * Minab Beta v0.7 — "Data used" store.
 *
 * Records real measured VPN traffic (download + upload) per day and
 * serves it back as daily / weekly / monthly buckets for the bar
 * charts on the Graph tab. Numbers are never invented: they come
 * only from the byte-count deltas the VPN service already receives
 * (OpenVPNService.updateByteCount, fed by VpnStatus.updateByteCount
 * from the management interface). The deltas are clamped to >= 0
 * there (TrafficHistory.LastDiff), so a reconnect — which resets
 * the counters to 0 — adds nothing instead of going negative.
 *
 * Storage is one private SharedPreferences file ("MinabDataUsage"),
 * one long per day ("day_yyyy-MM-dd" -> bytes). It survives app
 * restarts and updates; keys older than 400 days are pruned on read.
 */

package de.blinkt.openvpn.minab;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class DataUsageStore {

    private static final String PREFS = "MinabDataUsage";
    private static final String KEY_PREFIX = "day_";
    /** Keep a bit more than a year, so the monthly view never runs dry. */
    private static final int KEEP_DAYS = 400;

    private static final Object LOCK = new Object();

    /**
     * One bar of a chart: its label, the measured bytes, whether it
     * is the period we are in right now (today / this week / this
     * month), and the full date or range shown when the bar is
     * tapped (fix #7 in v0.9.6.1: "Sat 14 Nov 2026",
     * "9 Nov – 15 Nov 2026", "November 2026").
     */
    public static final class Bucket {
        public final String label;
        public final long bytes;
        public final boolean current;
        public final String detail;

        public Bucket(String label, long bytes) {
            this(label, bytes, false, label);
        }

        public Bucket(String label, long bytes, boolean current, String detail) {
            this.label = label;
            this.bytes = bytes;
            this.current = current;
            this.detail = detail;
        }
    }

    private DataUsageStore() {
    }

    /**
     * Minab Beta v0.9.1 — the writer and the reader live in different
     * processes. record() runs in OpenVPNService, which lives in the
     * ":openvpn" process (AndroidManifest), while the Graph tab reads
     * in the UI process. A plain MODE_PRIVATE SharedPreferences
     * instance is cached per process and never re-reads the file, so
     * the UI process kept showing the values from when the app
     * started — the live bar never grew and the totals looked wrong
     * against reality, even though the live line charts (fed by the
     * AIDL byte-count callback, not by this store) were fine.
     * MODE_MULTI_PROCESS forces a fresh read from the file on every
     * get, so the UI always sees the latest recorded bytes. It is
     * deprecated for general use, but it is exactly the supported
     * tool for a file written by one process and read by another.
     */
    private static SharedPreferences openPrefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS);
    }

    /**
     * Add one interval's traffic to today's total. Called from
     * OpenVPNService.updateByteCount on every byte-count update
     * (every ~2 s while connected). Safe to call from any thread.
     */
    public static void record(Context context, long diffIn, long diffOut) {
        long total = diffIn + diffOut;
        if (total <= 0) return;
        synchronized (LOCK) {
            SharedPreferences prefs = openPrefs(context);
            String key = dayKey(Calendar.getInstance());
            long cur = prefs.getLong(key, 0L);
            prefs.edit().putLong(key, cur + total).apply();
        }
    }

    /**
     * Minab Beta v0.9.1 — 50 bars: the past 46 days, today, and the
     * next 3 days as empty bars, oldest first. The 3 future bars are
     * always 0; they just round the strip out to 50, as the user
     * asked. Days with no data are 0, never invented.
     */
    public static List<Bucket> getDaily(Context context) {
        Map<String, Long> days = getDayMap(context);
        List<Bucket> out = new ArrayList<>(50);
        SimpleDateFormat labelFmt = new SimpleDateFormat("EEE d", Locale.US);
        SimpleDateFormat detailFmt = new SimpleDateFormat("EEE d MMM yyyy", Locale.US);
        Calendar today = Calendar.getInstance();
        for (int i = 46; i >= -3; i--) {
            Calendar c = (Calendar) today.clone();
            c.add(Calendar.DAY_OF_YEAR, -i);
            out.add(new Bucket(
                    labelFmt.format(c.getTime()),
                    days.getOrDefault(dayKey(c), 0L),
                    i == 0,
                    detailFmt.format(c.getTime())));
        }
        return out;
    }

    /**
     * Minab Beta v0.9.6.1 (fix #2) — last 12 calendar weeks (Monday
     * start), oldest first, so the Weekly chart shows 6 bars on
     * screen and the earlier 6 by swiping left (was 8).
     */
    public static List<Bucket> getWeekly(Context context) {
        Map<String, Long> days = getDayMap(context);
        List<Bucket> out = new ArrayList<>(12);
        SimpleDateFormat labelFmt = new SimpleDateFormat("d MMM", Locale.US);
        SimpleDateFormat detailStartFmt = new SimpleDateFormat("d MMM", Locale.US);
        SimpleDateFormat detailEndFmt = new SimpleDateFormat("d MMM yyyy", Locale.US);
        for (int w = 11; w >= 0; w--) {
            Calendar monday = Calendar.getInstance();
            monday.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);
            monday.add(Calendar.WEEK_OF_YEAR, -w);
            Calendar sunday = (Calendar) monday.clone();
            sunday.add(Calendar.DAY_OF_YEAR, 6);
            long sum = 0;
            for (int d = 0; d < 7; d++) {
                Calendar c = (Calendar) monday.clone();
                c.add(Calendar.DAY_OF_YEAR, d);
                sum += days.getOrDefault(dayKey(c), 0L);
            }
            out.add(new Bucket(
                    labelFmt.format(monday.getTime()),
                    sum,
                    w == 0,
                    detailStartFmt.format(monday.getTime())
                            + " – " + detailEndFmt.format(sunday.getTime())));
        }
        return out;
    }

    /**
     * Minab Beta v0.9.6.1 (fix #2) — last 12 calendar months, oldest
     * first, so the Monthly chart shows 6 bars on screen and the
     * earlier 6 by swiping left (was 6 total).
     */
    public static List<Bucket> getMonthly(Context context) {
        Map<String, Long> days = getDayMap(context);
        List<Bucket> out = new ArrayList<>(12);
        SimpleDateFormat labelFmt = new SimpleDateFormat("MMM", Locale.US);
        SimpleDateFormat detailFmt = new SimpleDateFormat("MMMM yyyy", Locale.US);
        int thisYear = Calendar.getInstance().get(Calendar.YEAR);
        for (int m = 11; m >= 0; m--) {
            Calendar first = Calendar.getInstance();
            first.set(Calendar.DAY_OF_MONTH, 1);
            first.add(Calendar.MONTH, -m);
            int year = first.get(Calendar.YEAR);
            int month = first.get(Calendar.MONTH);
            int maxDay = first.getActualMaximum(Calendar.DAY_OF_MONTH);
            long sum = 0;
            for (int d = 1; d <= maxDay; d++) {
                Calendar c = (Calendar) first.clone();
                c.set(Calendar.DAY_OF_MONTH, d);
                sum += days.getOrDefault(dayKey(c), 0L);
            }
            String label = labelFmt.format(first.getTime());
            if (year != thisYear) label += " '" + (year % 100);
            out.add(new Bucket(
                    label,
                    sum,
                    m == 0,
                    detailFmt.format(first.getTime())));
        }
        return out;
    }

    /** Sum of a bucket list, for the "Total: …" line. */
    public static long totalOf(List<Bucket> buckets) {
        long sum = 0;
        for (Bucket b : buckets) sum += b.bytes;
        return sum;
    }

    /** Human-readable byte count: "0 B", "12 KB", "345.6 MB", "1.23 GB". */
    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb);
        double gb = mb / 1024.0;
        return String.format(Locale.US, "%.2f GB", gb);
    }

    private static String dayKey(Calendar cal) {
        int y = cal.get(Calendar.YEAR);
        int m = cal.get(Calendar.MONTH) + 1;
        int d = cal.get(Calendar.DAY_OF_MONTH);
        StringBuilder sb = new StringBuilder(14);
        sb.append(KEY_PREFIX).append(y).append('-');
        if (m < 10) sb.append('0');
        sb.append(m).append('-');
        if (d < 10) sb.append('0');
        sb.append(d);
        return sb.toString();
    }

    /** All stored day totals, with keys older than KEEP_DAYS pruned. */
    private static Map<String, Long> getDayMap(Context context) {
        SharedPreferences prefs = openPrefs(context);
        Map<String, ?> all = prefs.getAll();
        Map<String, Long> days = new HashMap<>();
        Calendar cutoff = Calendar.getInstance();
        cutoff.add(Calendar.DAY_OF_YEAR, -KEEP_DAYS);
        String cutoffKey = dayKey(cutoff);
        SharedPreferences.Editor prune = null;
        for (Map.Entry<String, ?> e : all.entrySet()) {
            String key = e.getKey();
            if (key == null || !key.startsWith(KEY_PREFIX)) continue;
            if (!(e.getValue() instanceof Number)) continue;
            if (key.compareTo(cutoffKey) < 0) {
                if (prune == null) prune = prefs.edit();
                prune.remove(key);
                continue;
            }
            days.put(key, ((Number) e.getValue()).longValue());
        }
        if (prune != null) prune.apply();
        return days;
    }
}
