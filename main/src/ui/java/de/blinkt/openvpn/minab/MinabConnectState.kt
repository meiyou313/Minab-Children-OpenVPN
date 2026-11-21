/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * MinabConnectState (v0.9.6.5, fix #4): is this server the one
 * being connected to right now? The row of the connecting server
 * gets a green halo and a small spinner the moment it is tapped
 * (Home and Configs share this, so both tabs behave the same).
 * "Connecting" means: the tunnel is not active yet, this profile
 * is the one the connection was started for, and the state is one
 * of the connecting levels. Once connected, the row uses the
 * connected highlight instead (see the row binders).
 */

package de.blinkt.openvpn.minab

import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.VpnStatus

object MinabConnectState {
    fun isConnecting(profileUuid: String?, level: ConnectionStatus?): Boolean {
        if (profileUuid.isNullOrBlank()) return false
        if (VpnStatus.isVPNActive()) return false
        if (VpnStatus.getLastConnectedVPNProfile() != profileUuid) return false
        return level == ConnectionStatus.LEVEL_START ||
            level == ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET ||
            level == ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED ||
            level == ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT
    }
}
