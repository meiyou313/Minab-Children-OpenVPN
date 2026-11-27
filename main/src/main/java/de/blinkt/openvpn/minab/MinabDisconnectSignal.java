/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * MinabDisconnectSignal: bridges user/test-initiated disconnects across
 * main-package classes (DisconnectVPN, RealTester) and UI-layer failover
 * (MinabFailover) while also cancelling keepVPNAlive JobScheduler jobs.
 */

package de.blinkt.openvpn.minab;

import android.content.Context;

import de.blinkt.openvpn.core.keepVPNAlive;

public final class MinabDisconnectSignal {
    private static volatile Runnable userDisconnectListener = null;

    private MinabDisconnectSignal() {
    }

    public static void setListener(Runnable listener) {
        userDisconnectListener = listener;
    }

    public static void markUserDisconnect(Context context) {
        Runnable l = userDisconnectListener;
        if (l != null) {
            try {
                l.run();
            } catch (Exception ignored) {
            }
        }
        if (context != null) {
            try {
                keepVPNAlive.unscheduleKeepVPNAliveJobService(context.getApplicationContext());
            } catch (Exception ignored) {
            }
        }
    }
}
