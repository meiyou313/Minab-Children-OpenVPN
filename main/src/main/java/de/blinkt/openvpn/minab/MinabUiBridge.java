/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * MinabUiBridge: flavor-safe bridge so core (main) classes like LaunchVPN
 * and MinabCrashReporter use MinabDialogs / MinabDiagnosticsActivity when
 * built in the `ui` flavor, while keeping `skeleton` builds self-contained.
 */

package de.blinkt.openvpn.minab;

import android.app.Activity;

public final class MinabUiBridge {

    public interface CredentialsCallback {
        void onResult(String username, String password, boolean saveAsDefault);
    }

    public interface TextCallback {
        void onText(String text);
    }

    public interface Handler {
        boolean showConfigError(Activity activity, String title, String message, Runnable onDismiss);

        boolean showPasswordPrompt(
                Activity activity,
                String title,
                String scopePrompt,
                String initUser,
                String initPass,
                CredentialsCallback onConfirm,
                Runnable onCancel
        );

        boolean showSecretPrompt(
                Activity activity,
                String title,
                String message,
                String hint,
                TextCallback onConfirm,
                Runnable onCancel
        );

        boolean openDiagnostics(Activity activity);

        boolean showPendingCrashDialog(
                Activity activity,
                String title,
                String message,
                boolean isDanger,
                Runnable onClear
        );

        default void setRealTestingUuid(String uuid) {}

        default void setDisconnectingUuid(String uuid, long minPulseMs) {}

        default void onPingServerStart(String uuid) {}

        default void onPingServerEnd(String uuid) {}
    }

    private static volatile Handler sHandler;
    private static volatile boolean sInitAttempted;

    private MinabUiBridge() {
    }

    public static void setHandler(Handler handler) {
        sHandler = handler;
    }

    private static Handler getHandler() {
        Handler h = sHandler;
        if (h == null && !sInitAttempted) {
            sInitAttempted = true;
            try {
                Class<?> impl = Class.forName("de.blinkt.openvpn.minab.MinabUiBridgeImpl");
                impl.getMethod("install").invoke(null);
                h = sHandler;
            } catch (Throwable ignored) {
            }
        }
        return h;
    }

    public static boolean showConfigError(
            Activity activity, String title, String message, Runnable onDismiss) {
        Handler h = getHandler();
        return h != null && h.showConfigError(activity, title, message, onDismiss);
    }

    public static boolean showPasswordPrompt(
            Activity activity,
            String title,
            String scopePrompt,
            String initUser,
            String initPass,
            CredentialsCallback onConfirm,
            Runnable onCancel) {
        Handler h = getHandler();
        return h != null && h.showPasswordPrompt(
                activity, title, scopePrompt, initUser, initPass, onConfirm, onCancel);
    }

    public static boolean showSecretPrompt(
            Activity activity,
            String title,
            String message,
            String hint,
            TextCallback onConfirm,
            Runnable onCancel) {
        Handler h = getHandler();
        return h != null && h.showSecretPrompt(activity, title, message, hint, onConfirm, onCancel);
    }

    public static boolean openDiagnostics(Activity activity) {
        Handler h = getHandler();
        return h != null && h.openDiagnostics(activity);
    }

    public static boolean showPendingCrashDialog(
            Activity activity, String title, String message, boolean isDanger, Runnable onClear) {
        Handler h = getHandler();
        return h != null && h.showPendingCrashDialog(activity, title, message, isDanger, onClear);
    }

    public static void setRealTestingUuid(String uuid) {
        Handler h = getHandler();
        if (h != null) h.setRealTestingUuid(uuid);
    }

    public static void setDisconnectingUuid(String uuid, long minPulseMs) {
        Handler h = getHandler();
        if (h != null) h.setDisconnectingUuid(uuid, minPulseMs);
    }

    public static void onPingServerStart(String uuid) {
        Handler h = getHandler();
        if (h != null) h.onPingServerStart(uuid);
    }

    public static void onPingServerEnd(String uuid) {
        Handler h = getHandler();
        if (h != null) h.onPingServerEnd(uuid);
    }
}
