/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * MinabMiniWidgetProvider: 1×1 Mini Power Orb home-screen widget.
 */

package de.blinkt.openvpn.minab

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

class MinabMiniWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        MinabWidgetProvider.updateMiniWidgets(
            context,
            appWidgetManager,
            appWidgetIds,
            MinabWidgetProvider.currentSnapshot(context)
        )
    }
}
