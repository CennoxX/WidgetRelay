package com.cennoxx.widgetrelay.tasker.widgets

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.SizeF
import com.cennoxx.widgetrelay.widget.WidgetExtractor
import com.cennoxx.widgetrelay.widget.WidgetHost
import com.cennoxx.widgetrelay.widget.WidgetNode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runtime helper for the Tasker plugin actions: inflates the persistently
 * bound widget off-screen on the main thread, waits for the provider to
 * deliver its RemoteViews, then extracts the data.
 */
object WidgetActionRuntime {
    private const val SETTLE_MS = 1500L
    private const val TIMEOUT_MS = 8000L

    /**
     * Must be called from a background thread (Tasker runners are).
     * Returns null if the widget id is no longer bound or inflation failed.
     */
    fun captureNodes(context: Context, input: WidgetActionInput): List<WidgetNode>? {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "captureNodes() must not run on the main thread"
        }

        var nodes: List<WidgetNode>? = null
        val latch = CountDownLatch(1)
        val handler = Handler(Looper.getMainLooper())

        handler.post {
            try {
                val host = WidgetHost.get(context)
                host.startListening()
                val hostView = host.createHostViewForId(context, input.appWidgetId)
                if (hostView == null) {
                    latch.countDown()
                    return@post
                }
                applySize(context, hostView, input.spanX, input.spanY)

                // Give the provider time to deliver its (resized) RemoteViews
                handler.postDelayed({
                    try {
                        nodes = WidgetExtractor(context).extractFromRemoteViews(hostView)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    latch.countDown()
                }, SETTLE_MS)
            } catch (e: Exception) {
                e.printStackTrace()
                latch.countDown()
            }
        }

        latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return nodes
    }

    /** Same launcher-grid cell math as the widget detail page. */
    fun applySize(context: Context, hostView: AppWidgetHostView, spanX: Int, spanY: Int) {
        val metrics = context.resources.displayMetrics
        val density = metrics.density
        val availableWidth = metrics.widthPixels - (48 * density).toInt()
        val columnWidth = availableWidth / 5
        val rowHeight = (columnWidth * 1.4f).toInt()
        val widthDp = (spanX * columnWidth / density).toInt()
        val heightDp = (spanY * rowHeight / density).toInt()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                hostView.updateAppWidgetSize(
                    Bundle(),
                    listOf(SizeF(widthDp.toFloat(), heightDp.toFloat()))
                )
            } else {
                @Suppress("DEPRECATION")
                hostView.updateAppWidgetSize(null, widthDp, heightDp, widthDp, heightDp)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
