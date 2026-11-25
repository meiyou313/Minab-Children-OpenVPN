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
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.ScrollView;
import android.widget.TextView;

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

    /** The dialog text: the crash trace (capped for display) plus the last breadcrumbs. */
    private static String buildDialogText(Context ctx) {
        StringBuilder sb = new StringBuilder();
        String crash = readPrivateFile(ctx, CRASH_FILE);
        if (crash != null && !crash.isEmpty()) {
            sb.append("آخرین خرابی:\n").append(crash);
        } else {
            sb.append("برنامه بدون اینکه گزارشی بگذارد بسته شد.\n");
        }
        String crumbs = readPrivateFile(ctx, BREADCRUMB_FILE);
        if (crumbs != null && !crumbs.isEmpty()) {
            sb.append("\n\nآخرین رویدادها:\n").append(crumbs);
        }
        String text = sb.toString();
        if (text.length() > MAX_DIALOG_CHARS) {
            text = text.substring(0, MAX_DIALOG_CHARS) + "\n… (متن کامل در اشتراک‌گذاری)";
        }
        return text;
    }

    /** The share text: the FULL crash trace + the FULL breadcrumbs, never capped. */
    private static String buildShareText(Context ctx) {
        StringBuilder sb = new StringBuilder();
        String crash = readPrivateFile(ctx, CRASH_FILE);
        if (crash != null && !crash.isEmpty()) {
            sb.append(crash);
            if (!crash.endsWith("\n")) sb.append('\n');
        } else {
            sb.append("Minab closed without leaving a crash trace.\n");
        }
        String crumbs = readPrivateFile(ctx, BREADCRUMB_FILE);
        if (crumbs != null && !crumbs.isEmpty()) {
            sb.append("\nLast events before the close:\n").append(crumbs);
        }
        return sb.toString();
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
        if (Looper.myLooper() != Looper.getMainLooper()) return;
        final Context ctx = activity.getApplicationContext();
        try {
            final String crash = readPrivateFile(ctx, CRASH_FILE);
            final String crumbs = readPrivateFile(ctx, BREADCRUMB_FILE);
            final boolean hasCrash = crash != null && !crash.isEmpty();
            final boolean hasCrumbs = crumbs != null && !crumbs.isEmpty();

            final String displayText;
            final String shareText;
            if (!hasCrash && !hasCrumbs) {
                displayText = activity.getString(R.string.minab_crash_report_empty);
                shareText = "";
            } else {
                StringBuilder sb = new StringBuilder();
                if (hasCrash) {
                    sb.append(crash);
                    if (!crash.endsWith("\n")) sb.append('\n');
                }
                if (hasCrumbs) {
                    if (sb.length() > 0) sb.append("\n---\n");
                    sb.append(crumbs);
                }
                shareText = sb.toString();
                if (shareText.length() > MAX_DIALOG_CHARS) {
                    displayText = shareText.substring(0, MAX_DIALOG_CHARS) + "\n…";
                } else {
                    displayText = shareText;
                }
            }

            TextView textView = new TextView(activity);
            textView.setText(displayText);
            textView.setTextIsSelectable(true);
            textView.setTextSize(12f);
            int pad = (int) (16 * activity.getResources().getDisplayMetrics().density);
            textView.setPadding(pad, pad, pad, pad);
            ScrollView scroll = new ScrollView(activity);
            scroll.addView(textView);

            AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                    .setTitle(R.string.minab_crash_report_title)
                    .setView(scroll)
                    .setNegativeButton(android.R.string.ok, null);
            if (iconResId != 0) {
                builder.setIcon(iconResId);
            }
            if (hasCrash || hasCrumbs) {
                builder.setPositiveButton(R.string.minab_crash_share, (d, which) -> {
                    try {
                        Intent send = new Intent(Intent.ACTION_SEND);
                        send.setType("text/plain");
                        send.putExtra(Intent.EXTRA_SUBJECT,
                                "Minab crash report " + BuildConfig.VERSION_NAME);
                        send.putExtra(Intent.EXTRA_TEXT, shareText);
                        activity.startActivity(Intent.createChooser(
                                send, activity.getString(R.string.minab_crash_share)));
                    } catch (Exception ignored) {
                    }
                });
                builder.setNeutralButton(R.string.minab_crash_clear, (d, which) -> {
                    clearReports(ctx);
                    try {
                        android.widget.Toast.makeText(
                                activity, R.string.minab_crash_cleared,
                                android.widget.Toast.LENGTH_SHORT).show();
                    } catch (Exception ignored) {
                    }
                });
            }
            builder.show();
        } catch (Exception ignored) {
        }
    }

    /** Show the pending-report dialog, if any. Kept for backwards compatibility. */
    public static void showPendingReportDialogIfAny(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        if (Looper.myLooper() != Looper.getMainLooper()) return;
        final Context ctx = activity.getApplicationContext();
        if (!hasPendingReport(ctx)) return;
        try {
            final String dialogText = buildDialogText(ctx);
            final String shareText = buildShareText(ctx);
            TextView textView = new TextView(activity);
            textView.setText(dialogText);
            textView.setTextIsSelectable(true);
            int pad = (int) (16 * activity.getResources().getDisplayMetrics().density);
            textView.setPadding(pad, pad, pad, pad);
            ScrollView scroll = new ScrollView(activity);
            scroll.addView(textView);
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle("گزارش خرابی")
                    .setView(scroll)
                    .setPositiveButton("اشتراک‌گذاری", (d, which) -> {
                        clearReports(ctx);
                        try {
                            Intent send = new Intent(Intent.ACTION_SEND);
                            send.setType("text/plain");
                            send.putExtra(Intent.EXTRA_SUBJECT,
                                    "Minab crash report " + BuildConfig.VERSION_NAME);
                            send.putExtra(Intent.EXTRA_TEXT, shareText);
                            activity.startActivity(Intent.createChooser(send, "اشتراک‌گذاری"));
                        } catch (Exception ignored) {
                        }
                    })
                    .setNegativeButton("بستن", (d, which) -> clearReports(ctx))
                    .setOnCancelListener(d -> clearReports(ctx))
                    .create();
            dialog.show();
        } catch (Exception ignored) {
            // The report must never break the launch.
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
