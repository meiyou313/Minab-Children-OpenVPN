/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * MinabWidgetProvider (v0.9.7): powers all three home-screen widgets
 * (2×1 Compact Toggle, 1×1 Mini Power Orb, and 4×2 Status & Speed Dashboard).
 * All AppWidgetManager IPC runs on a single background executor, and live
 * speed pushes are throttled so the main thread is never stalled.
 */

package de.blinkt.openvpn.minab

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import de.blinkt.openvpn.R
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.activities.MainActivity
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VpnStatus
import java.util.concurrent.Executors

class MinabWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        updateCompactWidgets(context, appWidgetManager, appWidgetIds, currentSnapshot(context))
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TOGGLE -> {
                toggleConnection(context)
                updateAllWidgets(context)
                return
            }
            ACTION_CONNECT_BEST -> {
                connectBestServer(context)
                updateAllWidgets(context)
                return
            }
        }
        super.onReceive(context, intent)
    }

    companion object {
        const val ACTION_TOGGLE = "de.blinkt.openvpn.minab.WIDGET_TOGGLE"
        const val ACTION_CONNECT_BEST = "de.blinkt.openvpn.minab.WIDGET_CONNECT_BEST"

        private val widgetExecutor = Executors.newSingleThreadExecutor()

        @Volatile
        private var lastDownSpeed: String = "↓ 0 B/s"

        @Volatile
        private var lastUpSpeed: String = "↑ 0 B/s"

        @Volatile
        private var lastSpeedPushMs: Long = 0L

        internal enum class WidgetState { IDLE, CONNECTING, CONNECTED }

        internal data class WidgetSnapshot(
            val state: WidgetState,
            val serverName: String?,
            val serverSub: String,
            val flagRes: Int,
            val downSpeed: String,
            val upSpeed: String
        )

        internal fun toggleConnection(context: Context) {
            try {
                if (VpnStatus.isVPNActive()) {
                    MinabVpnControl.disconnect(context)
                } else {
                    val appContext = context.applicationContext
                    val target = MinabVpnControl.lastUsedProfile(appContext)
                        ?: MinabVpnControl.bestProfile(appContext)
                    if (target != null) {
                        MinabVpnControl.connect(appContext, target)
                    } else {
                        openMainApp(appContext)
                    }
                }
            } catch (_: Exception) {
            }
        }

        internal fun connectBestServer(context: Context) {
            try {
                val appContext = context.applicationContext
                val best = MinabVpnControl.bestProfile(appContext)
                    ?: MinabVpnControl.lastUsedProfile(appContext)
                if (best != null) {
                    if (VpnStatus.isVPNActive()) MinabFailover.noteUserDisconnect()
                    MinabVpnControl.connect(appContext, best)
                } else {
                    openMainApp(appContext)
                }
            } catch (_: Exception) {
            }
        }

        private fun openMainApp(context: Context) {
            try {
                val intent = Intent(context, MainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivity(intent)
            } catch (_: Exception) {
            }
        }

        /** Refresh all Minab widgets (Compact, Mini, Large) off the UI thread. */
        fun updateAllWidgets(context: Context) {
            val appCtx = context.applicationContext
            try {
                widgetExecutor.execute {
                    try {
                        val manager = AppWidgetManager.getInstance(appCtx)
                        val compactIds = manager.getAppWidgetIds(
                            ComponentName(appCtx, MinabWidgetProvider::class.java)
                        )
                        val miniIds = manager.getAppWidgetIds(
                            ComponentName(appCtx, MinabMiniWidgetProvider::class.java)
                        )
                        val largeIds = manager.getAppWidgetIds(
                            ComponentName(appCtx, MinabLargeWidgetProvider::class.java)
                        )
                        if (compactIds.isEmpty() && miniIds.isEmpty() && largeIds.isEmpty()) return@execute
                        val snap = currentSnapshot(appCtx)
                        if (compactIds.isNotEmpty()) updateCompactWidgets(appCtx, manager, compactIds, snap)
                        if (miniIds.isNotEmpty()) updateMiniWidgets(appCtx, manager, miniIds, snap)
                        if (largeIds.isNotEmpty()) updateLargeWidgets(appCtx, manager, largeIds, snap)
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
        }

        /** Throttled live speed update for the 4×2 dashboard widget. */
        fun updateLiveSpeed(context: Context, downText: String, upText: String) {
            lastDownSpeed = downText
            lastUpSpeed = upText
            val now = SystemClock.uptimeMillis()
            if (now - lastSpeedPushMs < 3000L) return
            lastSpeedPushMs = now
            val appCtx = context.applicationContext
            try {
                widgetExecutor.execute {
                    try {
                        val manager = AppWidgetManager.getInstance(appCtx)
                        val largeIds = manager.getAppWidgetIds(
                            ComponentName(appCtx, MinabLargeWidgetProvider::class.java)
                        )
                        if (largeIds.isEmpty()) return@execute
                        val snap = currentSnapshot(appCtx)
                        updateLargeWidgets(appCtx, manager, largeIds, snap)
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
        }

        internal fun currentSnapshot(context: Context): WidgetSnapshot {
            val active = try {
                VpnStatus.isVPNActive()
            } catch (_: Exception) {
                false
            }
            val level = try {
                VpnStatus.getLastLevel()
            } catch (_: Exception) {
                ConnectionStatus.LEVEL_NOTCONNECTED
            }
            val state = when {
                active && level == ConnectionStatus.LEVEL_CONNECTED -> WidgetState.CONNECTED
                active || level == ConnectionStatus.LEVEL_START ||
                    level == ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET ||
                    level == ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED -> WidgetState.CONNECTING
                else -> WidgetState.IDLE
            }
            val profile: VpnProfile? = try {
                if (state != WidgetState.IDLE) {
                    val uuid = VpnStatus.getLastConnectedVPNProfile()
                    uuid?.let { ProfileManager.getIfPresent(context, it) }
                        ?: MinabVpnControl.lastUsedProfile(context)
                } else {
                    MinabVpnControl.lastUsedProfile(context)
                }
            } catch (_: Exception) {
                null
            }
            val flagRes = if (profile != null) {
                try {
                    val res = FlagHelper.flagResForProfile(profile, context)
                    if (res == R.drawable.minab_flag_globe) 0 else res
                } catch (_: Exception) {
                    0
                }
            } else 0

            val pingPart = when {
                profile == null -> ""
                profile.mLastRealMs > 0 -> "${profile.mLastRealMs} ms"
                profile.mLastPingMs > 0 -> "${profile.mLastPingMs} ms"
                else -> ""
            }
            val sub = when (state) {
                WidgetState.CONNECTED -> if (pingPart.isNotEmpty())
                    context.getString(R.string.minab_status_connected) + " · " + pingPart
                else
                    context.getString(R.string.minab_status_connected)
                WidgetState.CONNECTING -> context.getString(R.string.minab_status_connecting)
                WidgetState.IDLE -> if (profile != null)
                    context.getString(R.string.minab_tap_to_connect)
                else
                    context.getString(R.string.minab_widget_no_server)
            }
            val down = if (state == WidgetState.CONNECTED) lastDownSpeed else "↓ 0 B/s"
            val up = if (state == WidgetState.CONNECTED) lastUpSpeed else "↑ 0 B/s"
            return WidgetSnapshot(
                state = state,
                serverName = profile?.mName,
                serverSub = sub,
                flagRes = flagRes,
                downSpeed = down,
                upSpeed = up
            )
        }

        private fun cardBgFor(state: WidgetState): Int = when (state) {
            WidgetState.CONNECTED -> R.drawable.minab_widget_card_connected
            WidgetState.CONNECTING -> R.drawable.minab_widget_card_connecting
            WidgetState.IDLE -> R.drawable.minab_widget_card_idle
        }

        private fun orbBgFor(state: WidgetState): Int = when (state) {
            WidgetState.CONNECTED -> R.drawable.minab_widget_orb_connected
            WidgetState.CONNECTING -> R.drawable.minab_widget_orb_connecting
            WidgetState.IDLE -> R.drawable.minab_widget_orb_idle
        }

        private fun pillBgFor(state: WidgetState): Int = when (state) {
            WidgetState.CONNECTED -> R.drawable.minab_widget_state_pill_connected
            WidgetState.CONNECTING -> R.drawable.minab_widget_state_pill_connecting
            WidgetState.IDLE -> R.drawable.minab_widget_state_pill_idle
        }

        private fun togglePendingIntent(context: Context, requestCode: Int): PendingIntent {
            val intent = Intent(context, MinabWidgetProvider::class.java)
            intent.action = ACTION_TOGGLE
            return PendingIntent.getBroadcast(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun bestPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, MinabWidgetProvider::class.java)
            intent.action = ACTION_CONNECT_BEST
            return PendingIntent.getBroadcast(
                context, 202, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun openAppPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(
                context, 203, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        internal fun updateCompactWidgets(
            context: Context,
            manager: AppWidgetManager,
            appWidgetIds: IntArray,
            snap: WidgetSnapshot
        ) {
            val stateLabel = when (snap.state) {
                WidgetState.CONNECTED -> context.getString(R.string.minab_status_connected)
                WidgetState.CONNECTING -> context.getString(R.string.minab_status_connecting)
                WidgetState.IDLE -> context.getString(R.string.minab_tap_to_connect)
            }
            val serverLabel = snap.serverName ?: context.getString(R.string.minab_widget_ready)
            val togglePi = togglePendingIntent(context, 101)
            val openAppPi = openAppPendingIntent(context)

            for (id in appWidgetIds) {
                val views = RemoteViews(context.packageName, R.layout.minab_widget)
                views.setInt(R.id.minab_widget_root, "setBackgroundResource", cardBgFor(snap.state))
                views.setInt(R.id.minab_widget_orb, "setBackgroundResource", orbBgFor(snap.state))
                views.setTextViewText(R.id.minab_widget_state, stateLabel)
                views.setTextViewText(R.id.minab_widget_server, serverLabel)
                if (snap.flagRes != 0) {
                    views.setImageViewResource(R.id.minab_widget_flag, snap.flagRes)
                    views.setViewVisibility(R.id.minab_widget_flag, View.VISIBLE)
                } else {
                    views.setViewVisibility(R.id.minab_widget_flag, View.GONE)
                }
                views.setOnClickPendingIntent(R.id.minab_widget_root, togglePi)
                views.setOnClickPendingIntent(R.id.minab_widget_logo, openAppPi)
                try {
                    manager.updateAppWidget(id, views)
                } catch (_: Exception) {
                }
            }
        }

        internal fun updateMiniWidgets(
            context: Context,
            manager: AppWidgetManager,
            appWidgetIds: IntArray,
            snap: WidgetSnapshot
        ) {
            val label = when (snap.state) {
                WidgetState.CONNECTED -> context.getString(R.string.minab_status_connected)
                WidgetState.CONNECTING -> "…"
                WidgetState.IDLE -> "Minab"
            }
            val labelColor = when (snap.state) {
                WidgetState.CONNECTED -> Color.parseColor("#34D399")
                WidgetState.CONNECTING -> Color.parseColor("#FBBF24")
                WidgetState.IDLE -> Color.parseColor("#E2E8F0")
            }
            val togglePi = togglePendingIntent(context, 102)
            for (id in appWidgetIds) {
                val views = RemoteViews(context.packageName, R.layout.minab_widget_mini)
                views.setInt(R.id.minab_widget_mini_root, "setBackgroundResource", cardBgFor(snap.state))
                views.setInt(R.id.minab_widget_mini_orb, "setBackgroundResource", orbBgFor(snap.state))
                views.setTextViewText(R.id.minab_widget_mini_label, label)
                views.setTextColor(R.id.minab_widget_mini_label, labelColor)
                views.setOnClickPendingIntent(R.id.minab_widget_mini_root, togglePi)
                try {
                    manager.updateAppWidget(id, views)
                } catch (_: Exception) {
                }
            }
        }

        internal fun updateLargeWidgets(
            context: Context,
            manager: AppWidgetManager,
            appWidgetIds: IntArray,
            snap: WidgetSnapshot
        ) {
            val pillLabel = when (snap.state) {
                WidgetState.CONNECTED -> context.getString(R.string.minab_status_connected)
                WidgetState.CONNECTING -> context.getString(R.string.minab_status_connecting)
                WidgetState.IDLE -> context.getString(R.string.minab_status_disconnected)
            }
            val pillTextColor = when (snap.state) {
                WidgetState.CONNECTED -> Color.parseColor("#34D399")
                WidgetState.CONNECTING -> Color.parseColor("#FBBF24")
                WidgetState.IDLE -> Color.parseColor("#E2E8F0")
            }
            val serverTitle = snap.serverName ?: context.getString(R.string.minab_widget_ready)
            val togglePi = togglePendingIntent(context, 103)
            val bestPi = bestPendingIntent(context)
            val openAppPi = openAppPendingIntent(context)

            for (id in appWidgetIds) {
                val views = RemoteViews(context.packageName, R.layout.minab_widget_large)
                views.setInt(R.id.minab_widget_large_root, "setBackgroundResource", cardBgFor(snap.state))
                views.setInt(R.id.minab_widget_large_orb, "setBackgroundResource", orbBgFor(snap.state))
                views.setInt(R.id.minab_widget_large_pill, "setBackgroundResource", pillBgFor(snap.state))
                views.setTextViewText(R.id.minab_widget_large_pill, pillLabel)
                views.setTextColor(R.id.minab_widget_large_pill, pillTextColor)
                views.setTextViewText(R.id.minab_widget_large_server, serverTitle)
                views.setTextViewText(R.id.minab_widget_large_sub, snap.serverSub)
                views.setTextViewText(R.id.minab_widget_large_down, snap.downSpeed)
                views.setTextViewText(R.id.minab_widget_large_up, snap.upSpeed)
                if (snap.flagRes != 0) {
                    views.setImageViewResource(R.id.minab_widget_large_flag, snap.flagRes)
                    views.setViewVisibility(R.id.minab_widget_large_flag, View.VISIBLE)
                } else {
                    views.setViewVisibility(R.id.minab_widget_large_flag, View.GONE)
                }
                views.setOnClickPendingIntent(R.id.minab_widget_large_header, openAppPi)
                views.setOnClickPendingIntent(R.id.minab_widget_large_server_box, openAppPi)
                views.setOnClickPendingIntent(R.id.minab_widget_large_orb, togglePi)
                views.setOnClickPendingIntent(R.id.minab_widget_large_best, bestPi)
                try {
                    manager.updateAppWidget(id, views)
                } catch (_: Exception) {
                }
            }
        }
    }
}
