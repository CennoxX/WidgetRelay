package com.cennoxx.widgetrelay.widget

import android.content.Context

/**
 * Launcher home screen geometry.
 *
 * Widgets are laid out in cells, and many providers pick a different
 * RemoteViews layout depending on the size they are given, so the config
 * preview and the plugin runtime have to agree on how big a "2 x 2 widget"
 * actually is. This approximates a typical launcher grid: [COLUMNS] columns
 * across the usable screen width, with cells [ROW_ASPECT] times as tall as
 * they are wide.
 */
object WidgetGrid {
    /** Widgets can be configured from 1 x 1 up to [MAX_SPAN] x [MAX_SPAN] cells. */
    const val MAX_SPAN = 5

    private const val COLUMNS = 5
    private const val ROW_ASPECT = 1.4f
    private const val SCREEN_MARGIN_DP = 48

    /** Size in pixels of a [spanX] x [spanY] widget. */
    fun sizePx(context: Context, spanX: Int, spanY: Int, availableWidthPx: Int = 0): Pair<Int, Int> {
        val density = context.resources.displayMetrics.density
        val width = availableWidthPx.takeIf { it > 0 }
            ?: (context.resources.displayMetrics.widthPixels - (SCREEN_MARGIN_DP * density).toInt())
        val columnWidth = width / COLUMNS
        val rowHeight = (columnWidth * ROW_ASPECT).toInt()
        return spanX * columnWidth to spanY * rowHeight
    }

    /** Size in dp of a [spanX] x [spanY] widget, as the AppWidget APIs expect it. */
    fun sizeDp(context: Context, spanX: Int, spanY: Int): Pair<Int, Int> {
        val density = context.resources.displayMetrics.density
        val (widthPx, heightPx) = sizePx(context, spanX, spanY)
        return (widthPx / density).toInt() to (heightPx / density).toInt()
    }
}
