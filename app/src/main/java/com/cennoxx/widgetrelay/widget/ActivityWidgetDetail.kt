package com.cennoxx.widgetrelay.widget

import android.appwidget.AppWidgetHostView
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.cennoxx.widgetrelay.R
import com.cennoxx.widgetrelay.applyEdgeToEdgeInsets

class ActivityWidgetDetail : ComponentActivity() {
    private lateinit var widgetHost: WidgetHost
    private lateinit var titleTextView: TextView
    private lateinit var containerFrame: FrameLayout
    private lateinit var dataTextView: TextView
    private lateinit var refreshButton: Button
    private lateinit var spanXSpinner: Spinner
    private lateinit var spanYSpinner: Spinner
    private var hostView: AppWidgetHostView? = null
    private var spinnersReady = false

    private val spans = listOf(1, 2, 3, 4, 5)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_widget_detail)
        applyEdgeToEdgeInsets()

        widgetHost = WidgetHost.get(this)
        widgetHost.startListening()

        titleTextView = findViewById(R.id.widgetTitle)
        containerFrame = findViewById(R.id.containerFrame)
        dataTextView = findViewById(R.id.dataTextView)
        refreshButton = findViewById(R.id.refreshButton)
        spanXSpinner = findViewById(R.id.spanXSpinner)
        spanYSpinner = findViewById(R.id.spanYSpinner)

        val providerInfo = widgetHost.getCurrentProviderInfo()
        if (providerInfo == null) {
            dataTextView.text = "No widget bound. Go back and select one."
            return
        }

        titleTextView.text = try {
            providerInfo.loadLabel(packageManager)
        } catch (e: Exception) {
            providerInfo.provider.shortClassName
        }

        displayWidget()
        val (defaultX, defaultY) = providerInfo.getSpans(this)
        setupSizeSpinner(defaultX, defaultY)

        refreshButton.setOnClickListener {
            displayExtractedData()
        }

        // The widget needs a moment to receive its first RemoteViews update
        containerFrame.postDelayed({ displayExtractedData() }, 1000)
    }

    private fun setupSizeSpinner(defaultX: Int, defaultY: Int) {
        val labels = spans.map { it.toString() }
        val adapterX = ArrayAdapter(this, R.layout.spinner_item, labels)
        adapterX.setDropDownViewResource(R.layout.spinner_item)
        spanXSpinner.adapter = adapterX

        val adapterY = ArrayAdapter(this, R.layout.spinner_item, labels)
        adapterY.setDropDownViewResource(R.layout.spinner_item)
        spanYSpinner.adapter = adapterY

        // Preselect the widget's default size before attaching listeners
        spanXSpinner.setSelection(spans.indexOf(defaultX.coerceIn(1, 5)))
        spanYSpinner.setSelection(spans.indexOf(defaultY.coerceIn(1, 5)))

        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!spinnersReady) return
                applySelectedSize()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        spanXSpinner.onItemSelectedListener = listener
        spanYSpinner.onItemSelectedListener = listener

        // Apply the default size once, then let the spinners take over
        spanXSpinner.post {
            spinnersReady = true
            applySelectedSize()
        }
    }

    private fun applySelectedSize() {
        val x = spans[spanXSpinner.selectedItemPosition]
        val y = spans[spanYSpinner.selectedItemPosition]
        applyWidgetSize(x, y)
    }

    private fun applyWidgetSize(spanX: Int, spanY: Int) {
        val hostView = hostView ?: return
        val density = resources.displayMetrics.density

        // Size cells like a launcher grid (5 columns across the available width),
        // not the 70dp minimum formula - widgets pick their layout based on this
        val innerWidth = containerFrame.width - containerFrame.paddingLeft - containerFrame.paddingRight
        val availableWidth = innerWidth.takeIf { it > 0 }
            ?: (resources.displayMetrics.widthPixels - (48 * density).toInt())
        val columnWidth = availableWidth / 5
        val rowHeight = (columnWidth * 1.4f).toInt()
        val widthPx = spanX * columnWidth
        val heightPx = spanY * rowHeight
        val widthDp = (widthPx / density).toInt()
        val heightDp = (heightPx / density).toInt()

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

        // Resize the preview to the same cell size so it renders like on a launcher
        hostView.layoutParams = FrameLayout.LayoutParams(widthPx, heightPx, Gravity.CENTER)

        // Give the provider a moment to deliver the resized RemoteViews, then re-extract
        containerFrame.postDelayed({ displayExtractedData() }, 800)
    }

    private fun displayWidget() {
        val view = widgetHost.createHostView(this)
        if (view == null) {
            dataTextView.text = "Could not create widget view."
            return
        }
        hostView = view
        containerFrame.removeAllViews()
        containerFrame.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )
    }

    private fun displayExtractedData() {
        widgetHost.refreshExtractedData()
        val nodes = widgetHost.widgetNodes.value

        if (nodes.isEmpty()) {
            dataTextView.text = "No widget data extracted yet. Try Refresh."
            return
        }

        val text = nodes.joinToString("\n\n") { node ->
            buildString {
                append("Path: ${node.pathInTree}\n")
                append("Class: ${node.className}\n")
                if (!node.resourceIdName.isNullOrEmpty()) {
                    append("ID: ${node.resourceIdName}\n")
                }
                if (!node.text.isNullOrEmpty()) {
                    append("Text: ${node.text}\n")
                }
                if (!node.contentDescription.isNullOrEmpty()) {
                    append("Description: ${node.contentDescription}\n")
                }
                append("Children: ${node.childCount}\n")
                if (!node.bestValue.isNullOrEmpty()) {
                    append("Value: ${node.bestValue}")
                }
            }
        }

        dataTextView.text = text
    }
}
