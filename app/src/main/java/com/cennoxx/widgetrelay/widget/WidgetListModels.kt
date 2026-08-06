package com.cennoxx.widgetrelay.widget

import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Build
import kotlin.math.ceil
import kotlin.math.max

/**
 * Home screen cells (columns x rows) this widget occupies by default.
 * Prefers the exact spans declared by the app (API 31+); otherwise derives
 * them from minWidth/minHeight, which are in px and must be converted to dp
 * before applying the launcher cell formula (n * 70dp - 30dp).
 */
fun AppWidgetProviderInfo.getSpans(context: Context): Pair<Int, Int> {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        targetCellWidth > 1 && targetCellHeight > 1
    ) {
        return targetCellWidth.coerceAtMost(WidgetGrid.MAX_SPAN_X) to
            targetCellHeight.coerceAtMost(WidgetGrid.MAX_SPAN_Y)
    }
    val density = context.resources.displayMetrics.density
    val widthDp = minWidth / density
    val heightDp = minHeight / density
    val spanX = max(1, ceil((widthDp + 30) / 70.0).toInt()).coerceAtMost(WidgetGrid.MAX_SPAN_X)
    val spanY = max(1, ceil((heightDp + 30) / 70.0).toInt()).coerceAtMost(WidgetGrid.MAX_SPAN_Y)
    return spanX to spanY
}

data class WidgetEntry(
    val label: String,
    val description: String?,
    val sizeText: String,
    val preview: Drawable?,
    val info: AppWidgetProviderInfo
)

data class AppWithWidgets(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val widgets: List<WidgetEntry>
)
