/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * MinabCrashReporter (v0.9.5): the tab-switch crash survived
 * three rounds of fixes reasoned from code (0.9.2, 0.9.3, 0.9.4)
 * because no stack trace or logcat ever existed — every fix was a
 * guess. This ends the guessing: the app now records its own
 * crashes and the last steps before them, on the phone itself.
 *
 * - install(): a global UncaughtExceptionHandler (installed in
 *   ICSOpenVPNApplication.onCreate) that writes the full stack
 *   trace + thread + app version + Android version + timestamp to
 *   internal storage (minab-crash-report.txt), then chains to the
 *   previous default handler, so the app still closes exactly as
 *   before. No new permission, no network, no dependency.
 * - breadcrumb(): one timestamped line per tab selection and per
 *   fragment lifecycle event, kept to the last 50 lines
 *   (minab-crash-breadcrumbs.txt). This survives even an ANR or
 *   a kill, where no exception is thrown at all — the last lines
 *   show exactly where the app died.
 * - showPendingReportDialogIfAny(): on the next launch
 *   (MainActivity.onCreate), when a crash file exists — or when
 *   the breadcrumbs end mid-switch with no clean shutdown — a
 *   dialog shows the trace + the last steps, with Share (sends
 *   the full text) and Dismiss. Either choice deletes both files,
 *   so the dialog never nags twice. The trace may contain the
 *   user's own server names; it is shown only to them and shared
 *   only when THEY tap Share.
 */

package de.blinkt.openvpn.minab;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Looper;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.blinkt.openvpn.BuildConfig;
import de.blinkt.openvpn.R;
import de.blinkt.openvpn.activities.MinabDiagnosticsActivity;
import de.blinkt.openvpn.core.LogItem;
import de.blinkt.openvpn.core.ProfileManager;
import de.blinkt.openvpn.core.VpnStatus;

public final class MinabCrashReporter {
    private static final String CRASH_FILE = "minab-crash-report.txt";
    private static final String BREADCRUMB_FILE = "minab-crash-breadcrumbs.txt";
    /** Breadcrumbs kept: enough to see the last switch, small enough to write fast. */
    private static final int MAX_BREADCRUMBS = 50;
    /** How much of a long trace the dialog shows; the share always carries the full text. */
    private static final int MAX_DIALOG_CHARS = 6000;

    private static volatile boolean installed = false;
    private static Thread.UncaughtExceptionHandler previousHandler = null;

    /** In-memory ring buffer + single-thread background writer so tab-switch
        breadcrumbs never perform synchronous disk reads/writes on the UI thread.
        Crash reports and clean shutdowns flush synchronously. */
    private static final Object CRUMB_LOCK = new Object();
    private static List<String> cachedBreadcrumbs = null;
    private static final ExecutorService CRUMB_WRITER = Executors.newSingleThreadExecutor();

    private MinabCrashReporter() {
    }

    /** Install the global crash handler. Safe to call twice; chains to the previous handler. */
    public static synchronized void install(Context appContext) {
        if (installed) return;
        installed = true;
        final Context ctx = appContext.getApplicationContext();
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                writeCrashReport(ctx, thread, throwable);
            } catch (Exception ignored) {
                // Never let the reporter itself mask the real crash.
            }
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        });
    }

    private static void writeCrashReport(Context ctx, Thread thread, Throwable throwable) {
        flushBreadcrumbsSync(ctx);
        StringBuilder sb = new StringBuilder();
        sb.append("Minab crash report\n");
        sb.append("time: ").append(nowString()).append('\n');
        sb.append("app: ").append(BuildConfig.VERSION_NAME)
                .append(" (versionCode ").append(BuildConfig.VERSION_CODE).append(")\n");
        sb.append("android: ").append(Build.VERSION.RELEASE)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("thread: ").append(thread.getName()).append('\n');
        sb.append("exception: ").append(throwable.toString()).append('\n');
        StringWriter sw = new StringWriter();
        throwable.printStackTrace(new PrintWriter(sw));
        sb.append(sw.toString());
        writePrivateFile(ctx, CRASH_FILE, sb.toString());
    }

    /** Record one timestamped event (e.g. "TAB selected 2 Graph") without blocking
        the UI thread with disk I/O during tab animations. Updates the in-memory
        ring buffer immediately and flushes asynchronously on a single-thread
        writer. Clean shutdown / backgrounding events flush immediately as well. */
    public static void breadcrumb(Context ctx, String event) {
        if (ctx == null || event == null) return;
        final Context appCtx = ctx.getApplicationContext();
        final String line = nowString() + " " + event;
        final String snapshot;
        synchronized (CRUMB_LOCK) {
            if (cachedBreadcrumbs == null) {
                cachedBreadcrumbs = new ArrayList<>();
            }
            cachedBreadcrumbs.add(line);
            while (cachedBreadcrumbs.size() > MAX_BREADCRUMBS) {
                cachedBreadcrumbs.remove(0);
            }
            snapshot = TextUtils.join("\n", cachedBreadcrumbs) + "\n";
        }
        try {
            CRUMB_WRITER.execute(() -> {
                try {
                    writePrivateFile(appCtx, BREADCRUMB_FILE, snapshot);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    private static void flushBreadcrumbsSync(Context ctx) {
        final String snapshot;
        synchronized (CRUMB_LOCK) {
            if (cachedBreadcrumbs == null || cachedBreadcrumbs.isEmpty()) return;
            snapshot = TextUtils.join("\n", cachedBreadcrumbs) + "\n";
        }
        try {
            writePrivateFile(ctx.getApplicationContext(), BREADCRUMB_FILE, snapshot);
        } catch (Exception ignored) {
        }
    }

    /** True when a crash report is waiting, or when the last session ended mid-switch with no
        clean shutdown marker (an ANR / kill leaves no exception, so the breadcrumbs are the only
        trace). A normal backgrounding writes "APP backgrounded" and a normal exit writes
        "APP closed cleanly" as the last line, so neither shows the dialog. */
    public static boolean hasPendingReport(Context ctx) {
        if (ctx == null) return false;
        try {
            if (ctx.getFileStreamPath(CRASH_FILE).exists()) return true;
            List<String> lines = readLines(ctx, BREADCRUMB_FILE);
            if (lines.isEmpty()) return false;
            String last = lines.get(lines.size() - 1);
            if (last.endsWith("APP backgrounded") || last.endsWith("APP closed cleanly")) {
                return false;
            }
            for (String line : lines) {
                if (line.contains(" TAB ") || line.contains(" FRAG ")) return true;
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static String getCrashText(Context ctx) {
        if (ctx == null) return "";
        return readPrivateFile(ctx.getApplicationContext(), CRASH_FILE);
    }

    public static String getBreadcrumbsText(Context ctx) {
        if (ctx == null) return "";
        return readPrivateFile(ctx.getApplicationContext(), BREADCRUMB_FILE);
    }

    public static String getSystemOverview(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("Minab OpenVPN v").append(BuildConfig.VERSION_NAME)
                .append(" (code ").append(BuildConfig.VERSION_CODE).append(")\n");
        sb.append("Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("ABIs: ").append(TextUtils.join(", ", Build.SUPPORTED_ABIS)).append('\n');
        try {
            int count = ProfileManager.getInstance(ctx).getProfiles().size();
            sb.append("Profiles loaded: ").append(count).append('\n');
        } catch (Exception ignored) {
        }
        sb.append("Saved crash trace: ").append(hasSavedCrash(ctx) ? "Yes" : "No").append('\n');
        sb.append("Telemetry: Disabled (all logs remain strictly local until you export/share them)");
        return sb.toString();
    }

    public static String buildFullReport(Context ctx) {
        Context app = ctx.getApplicationContext();
        StringBuilder sb = new StringBuilder();
        sb.append("=== MINAB DIAGNOSTICS REPORT ===\n");
        sb.append("Generated: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append('\n');
        sb.append(getSystemOverview(app)).append("\n\n");

        sb.append("=== CRASH TRACE ===\n");
        String crash = getCrashText(app);
        sb.append(TextUtils.isEmpty(crash) ? "(None)\n" : crash).append("\n\n");

        sb.append("=== APP BREADCRUMBS ===\n");
        String crumbs = getBreadcrumbsText(app);
        sb.append(TextUtils.isEmpty(crumbs) ? "(Empty)\n" : crumbs).append("\n\n");

        sb.append("=== OPENVPN CONNECTION LOG (RECENT) ===\n");
        try {
            LogItem[] items = VpnStatus.getlogbuffer();
            if (items == null || items.length == 0) {
                sb.append("(Empty)\n");
            } else {
                int start = Math.max(0, items.length - 150);
                for (int i = start; i < items.length; i++) {
                    sb.append(items[i].getString(app)).append('\n');
                }
            }
        } catch (Exception e) {
            sb.append("(Unavailable)\n");
        }
        return sb.toString();
    }

    public static void clearAll(Context ctx) {
        if (ctx != null) clearReports(ctx.getApplicationContext());
    }

    private static void clearReports(Context ctx) {
        synchronized (CRUMB_LOCK) {
            if (cachedBreadcrumbs != null) cachedBreadcrumbs.clear();
        }
        try {
            ctx.deleteFile(CRASH_FILE);
            ctx.deleteFile(BREADCRUMB_FILE);
        } catch (Exception ignored) {
        }
    }

    /** True when a real uncaught crash trace is saved on disk. */
    public static boolean hasSavedCrash(Context ctx) {
        if (ctx == null) return false;
        try {
            return ctx.getFileStreamPath(CRASH_FILE).exists();
        } catch (Exception ignored) {
            return false;
        }
    }

    /** On-demand debug & crash report viewer opened from Settings (Debug section). */
    public static void showReportDialog(final Activity activity, final int iconResId) {
        if (activity == null || activity.isFinishing()) return;
        try {
            activity.startActivity(new Intent(activity, MinabDiagnosticsActivity.class));
        } catch (Exception ignored) {
        }
    }

    /** Show the pending-report dialog after an unexpected termination or crash. */
    public static void showPendingReportDialogIfAny(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        if (Looper.myLooper() != Looper.getMainLooper()) return;
        final Context ctx = activity.getApplicationContext();
        if (!hasPendingReport(ctx)) return;
        try {
            String crash = getCrashText(ctx);
            String preview = TextUtils.isEmpty(crash)
                    ? activity.getString(R.string.minab_diag_pending_unexpected_msg)
                    : activity.getString(R.string.minab_diag_pending_crash_msg);
            MinabDialogs.showConfirmJava(
                    activity,
                    activity.getString(R.string.minab_diag_title),
                    preview,
                    activity.getString(R.string.minab_diag_open_center),
                    activity.getString(R.string.minab_test_dismiss_btn),
                    !TextUtils.isEmpty(crash),
                    true,
                    () -> {
                        try {
                            activity.startActivity(new Intent(activity, MinabDiagnosticsActivity.class));
                        } catch (Exception ignored) {
                        }
                    },
                    () -> clearReports(ctx)
            );
        } catch (Exception ignored) {
            try {
                clearReports(ctx);
            } catch (Exception ignored2) {
            }
        }
    }

    private static String nowString() {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
    }

    private static void writePrivateFile(Context ctx, String name, String content) {
        FileOutputStream out = null;
        try {
            out = ctx.openFileOutput(name, Context.MODE_PRIVATE);
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception ignored) {
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static String readPrivateFile(Context ctx, String name) {
        try {
            List<String> lines = readLines(ctx, name);
            if (lines.isEmpty()) return "";
            return TextUtils.join("\n", lines);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static List<String> readLines(Context ctx, String name) {
        List<String> lines = new ArrayList<>();
        FileInputStream in = null;
        BufferedReader reader = null;
        try {
            in = ctx.openFileInput(name);
            reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isEmpty()) lines.add(line);
            }
        } catch (Exception ignored) {
        } finally {
            try {
                if (reader != null) reader.close();
            } catch (Exception ignored) {
            }
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
        }
        return lines;
    }
}
