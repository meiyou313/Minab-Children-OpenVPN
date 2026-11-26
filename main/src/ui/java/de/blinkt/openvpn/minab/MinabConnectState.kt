/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * MinabConnectState (v0.9.7.1): zero-lag connecting & connected row halo state.
 * Fixes the v0.9.6.5 bug where `VpnStatus.isVPNActive()` returned true during
 * connecting levels (suppressing the connecting halo until late), and adds an
 * instant optimistic pendingConnectUuid so the tapped server glows immediately.
 */

package de.blinkt.openvpn.minab

import android.os.SystemClock
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.VpnStatus

object MinabConnectState {
    @Volatile
    private var pendingConnectUuid: String? = null

    @Volatile
    private var pendingConnectUntilMs: Long = 0L

    fun markPendingConnect(profileUuid: String?, durationMs: Long = 4500L) {
        if (profileUuid.isNullOrBlank()) return
        pendingConnectUuid = profileUuid
        pendingConnectUntilMs = SystemClock.uptimeMillis() + durationMs
    }

    fun clearPendingConnect() {
        pendingConnectUuid = null
        pendingConnectUntilMs = 0L
    }

    fun isConnecting(profileUuid: String?, level: ConnectionStatus?): Boolean {
        if (profileUuid.isNullOrBlank()) return false
        val effectiveLevel = level ?: try {
            VpnStatus.getLastLevel()
        } catch (_: Exception) {
            ConnectionStatus.LEVEL_NOTCONNECTED
        }
        val activeUuid = try {
            VpnStatus.getLastConnectedVPNProfile()
        } catch (_: Exception) {
            null
        }
        if (effectiveLevel == ConnectionStatus.LEVEL_CONNECTED && activeUuid == profileUuid) {
            return false
        }
        if (pendingConnectUuid == profileUuid && SystemClock.uptimeMillis() < pendingConnectUntilMs) {
            return true
        }
        if (activeUuid != profileUuid) return false
        return effectiveLevel == ConnectionStatus.LEVEL_START ||
            effectiveLevel == ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET ||
            effectiveLevel == ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED ||
            effectiveLevel == ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT
    }
}
