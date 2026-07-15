package com.cennoxx.widgetrelay.tasker.widgets

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.SizeF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import com.cennoxx.widgetrelay.widget.WidgetExtractor
import com.cennoxx.widgetrelay.widget.WidgetHost
import com.cennoxx.widgetrelay.widget.WidgetNode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runtime helper for the Tasker plugin actions: attaches the persistently
 * bound widget to an invisible overlay window on the main thread - collection
 * views (ListView, GridView, ...) only connect to their RemoteViewsService
 * and load their rows once the view is attached to a real window - waits for
 * the provider to deliver its RemoteViews, then extracts or clicks.
 */
object WidgetActionRuntime {
    private const val SETTLE_MS = 1500L
    private const val POLL_MS = 750L
    private const val TIMEOUT_MS = 8000L

    /** The invisible overlay window needs "Display over other apps" on M+. */
    fun hasOverlayPermission(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    /**
     * Must be called from a background thread (Tasker runners are).
     * Returns null if the widget id is no longer bound or inflation failed.
     * Polls until the extracted tree is stable so asynchronously loaded
     * collection content (list rows etc.) is included.
     */
    fun captureNodes(context: Context, input: WidgetActionInput): List<WidgetNode>? {
        var previous: List<WidgetNode>? = null
        var nodes: List<WidgetNode>? = null
        val bound = withAttachedWidget(context, input) { hostView ->
            val extracted = WidgetExtractor(context).extractFromRemoteViews(hostView)
            val stable = extracted == previous
            previous = extracted
            nodes = extracted
            stable
        }
        return if (bound) nodes ?: emptyList() else null
    }

    /**
     * Attaches the widget host view to an invisible overlay window and calls
     * [poll] on the main thread every [POLL_MS] (after an initial [SETTLE_MS])
     * until it returns true or [TIMEOUT_MS] elapses, then removes the window.
     * Returns false if the widget id is no longer bound.
     */
    private fun withAttachedWidget(
        context: Context,
        input: WidgetActionInput,
        poll: (AppWidgetHostView) -> Boolean
    ): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "withAttachedWidget() must not run on the main thread"
        }

        var bound = false
        val latch = CountDownLatch(1)
        val handler = Handler(Looper.getMainLooper())
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS

        handler.post {
            try {
                val host = WidgetHost.get(context)
                host.startListening()
                val hostView = host.createHostViewForId(context, input.appWidgetId)
                if (hostView == null) {
                    latch.countDown()
                    return@post
                }
                bound = true
                applySize(context, hostView, input.spanX, input.spanY)
                windowManager.addView(hostView, invisibleOverlayParams(context, input.spanX, input.spanY))

                fun finish() {
                    try {
                        windowManager.removeViewImmediate(hostView)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    latch.countDown()
                }

                lateinit var tick: Runnable
                tick = Runnable {
                    val done = try {
                        poll(hostView)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        true
                    }
                    if (done || SystemClock.uptimeMillis() >= deadline) {
                        finish()
                    } else {
                        handler.postDelayed(tick, POLL_MS)
                    }
                }
                // Give the provider time to deliver its (resized) RemoteViews
                handler.postDelayed(tick, SETTLE_MS)
            } catch (e: Exception) {
                e.printStackTrace()
                latch.countDown()
            }
        }

        latch.await(TIMEOUT_MS + SETTLE_MS + 2000, TimeUnit.MILLISECONDS)
        return bound
    }

    /** A zero-alpha, non-interactive window sized with the launcher grid cell math. */
    private fun invisibleOverlayParams(context: Context, spanX: Int, spanY: Int): WindowManager.LayoutParams {
        val metrics = context.resources.displayMetrics
        val density = metrics.density
        val availableWidth = metrics.widthPixels - (48 * density).toInt()
        val columnWidth = availableWidth / 5
        val rowHeight = (columnWidth * 1.4f).toInt()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            spanX * columnWidth,
            spanY * rowHeight,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            alpha = 0f
            gravity = Gravity.TOP or Gravity.START
        }
    }

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
