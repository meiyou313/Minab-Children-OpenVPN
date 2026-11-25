/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * MinabLargeWidgetProvider: 4×2 Rich Status, Live Speed & Best-Server widget.
 */

package de.blinkt.openvpn.minab

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

class MinabLargeWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        MinabWidgetProvider.updateLargeWidgets(
            context,
            appWidgetManager,
            appWidgetIds,
            MinabWidgetProvider.currentSnapshot(context)
        )
    }
}
