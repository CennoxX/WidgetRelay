package com.cennoxx.widgetrelay.tasker.widgets

import android.app.Activity
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.cennoxx.widgetrelay.R
import com.cennoxx.widgetrelay.applyEdgeToEdgeInsets
import com.cennoxx.widgetrelay.widget.ActivityWidgetSelector
import com.cennoxx.widgetrelay.widget.WidgetExtractor
import com.cennoxx.widgetrelay.widget.WidgetHost
import com.cennoxx.widgetrelay.widget.WidgetNode
import com.cennoxx.widgetrelay.widget.getSpans
import com.joaomgcd.taskerpluginlibrary.TaskerPluginConstants
import com.joaomgcd.taskerpluginlibrary.config.TaskerPluginConfig
import com.joaomgcd.taskerpluginlibrary.config.TaskerPluginConfigHelper
import com.joaomgcd.taskerpluginlibrary.input.TaskerInput

/**
 * Shared config UI for widget plugin actions. Reuses the app's searchable
 * widget selector (pick mode) and shows the live widget preview like the
 * detail page, so the user sees exactly which element they are selecting.
 */
abstract class ActivityConfigWidgetActionBase : Activity(), TaskerPluginConfig<WidgetActionInput> {
    protected abstract val queryLabel: String
    protected abstract val helper: TaskerPluginConfigHelper<WidgetActionInput, *, *>

    override val context get() = applicationContext

    private lateinit var widgetHost: WidgetHost
    private lateinit var appNameText: TextView
    private lateinit var widgetNameText: TextView
    private lateinit var spanXSpinner: Spinner
    private lateinit var spanYSpinner: Spinner
    private lateinit var previewFrame: FrameLayout
    private lateinit var queryEditText: EditText
    private lateinit var statusText: TextView
    private lateinit var nodesListView: ListView

    private var currentNodes = emptyList<WidgetNode>()
    private val spans = listOf(1, 2, 3, 4, 5)

    private var boundWidgetId = -1
    private var savedWidgetId = -1
    private var currentAppName: String? = null
    private var currentWidgetName: String? = null
    private var saved = false
    private var suppressListeners = false
    private var hostView: AppWidgetHostView? = null
    private var viewsInitialized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        widgetHost = WidgetHost.get(this)
        widgetHost.startListening()

        val isEditingExisting = intent?.getBundleExtra(TaskerPluginConstants.EXTRA_BUNDLE) != null
        if (isEditingExisting) {
            initViews()
            // Triggers assignFromInput() with the existing configuration
            helper.onCreate()
        } else {
            // Fresh configuration: open the widget search immediately, with
            // no intermediate config screen to flash past
            launchWidgetPicker()
        }
    }

    private fun initViews() {
        if (viewsInitialized) return
        viewsInitialized = true

        setContentView(R.layout.activity_config_widget_action)
        applyEdgeToEdgeInsets()

        findViewById<TextView>(R.id.queryLabel).text = queryLabel
        appNameText = findViewById(R.id.appNameText)
        widgetNameText = findViewById(R.id.widgetNameText)
        spanXSpinner = findViewById(R.id.spanXSpinner)
        spanYSpinner = findViewById(R.id.spanYSpinner)
        previewFrame = findViewById(R.id.previewFrame)
        queryEditText = findViewById(R.id.queryEditText)
        statusText = findViewById(R.id.statusText)
        nodesListView = findViewById(R.id.nodesListView)

        setupSizeSpinners()

        nodesListView.setOnItemClickListener { _, _, position, _ ->
            currentNodes.getOrNull(position)?.let { queryEditText.setText(it.pathInTree) }
        }
        findViewById<Button>(R.id.changeWidgetButton).setOnClickListener { launchWidgetPicker() }
        findViewById<Button>(R.id.refreshButton).setOnClickListener {
            if (boundWidgetId != -1) showWidget() else statusText.text = "Select a widget first."
        }
        findViewById<Button>(R.id.saveButton).setOnClickListener { save() }
    }

    private fun setupSizeSpinners() {
        val labels = spans.map { it.toString() }
        listOf(spanXSpinner, spanYSpinner).forEach { spinner ->
            val adapter = ArrayAdapter(this, R.layout.spinner_item, labels)
            adapter.setDropDownViewResource(R.layout.spinner_item)
            spinner.adapter = adapter
            spinner.setSelection(1) // default 2
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (suppressListeners) return
                    applySizeAndExtract()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
    }

    // --- Widget selection via the app's searchable selector (pick mode) ---

    private fun launchWidgetPicker() {
        val intent = Intent(this, ActivityWidgetSelector::class.java)
        startActivityForResult(intent, REQUEST_PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_PICK -> {
                @Suppress("DEPRECATION")
                val info = if (resultCode == RESULT_OK) {
                    data?.getParcelableExtra<AppWidgetProviderInfo>(ActivityWidgetSelector.EXTRA_PICKED_PROVIDER)
                } else null
                if (info != null) {
                    initViews()
                    onProviderPicked(info)
                } else if (!viewsInitialized) {
                    // Fresh config, search cancelled before our screen was ever shown - just close
                    finish()
                } else {
                    statusText.text = "No widget selected yet."
                }
            }
            REQUEST_BIND -> if (resultCode == RESULT_OK) {
                onWidgetBound()
            } else {
                abandonCurrentBinding("Widget binding was cancelled.")
            }
            REQUEST_CONFIGURE -> if (resultCode == RESULT_OK) {
                showWidget()
            } else {
                abandonCurrentBinding("Widget configuration was cancelled.")
            }
        }
    }

    // --- Binding flow (same as the app: bind permission, then config activity) ---

    private fun onProviderPicked(info: AppWidgetProviderInfo) {
        // Selecting a new widget releases a previously bound one from this session
        if (boundWidgetId != -1 && boundWidgetId != savedWidgetId) {
            widgetHost.deleteId(boundWidgetId)
        }
        boundWidgetId = widgetHost.allocateId()
        val (appName, widgetName) = getAppAndWidgetNames(info)
        currentAppName = appName
        currentWidgetName = widgetName
        appNameText.text = "App: $appName"
        widgetNameText.text = "Widget: $widgetName"
        queryEditText.setText("")

        // Preselect the widget's default size
        val (defaultX, defaultY) = info.getSpans(this)
        suppressListeners = true
        spanXSpinner.setSelection(spans.indexOf(defaultX.coerceIn(1, 5)))
        spanYSpinner.setSelection(spans.indexOf(defaultY.coerceIn(1, 4)))
        suppressListeners = false

        if (widgetHost.bindId(boundWidgetId, info)) {
            onWidgetBound()
        } else {
            statusText.text = "Permission needed - requesting via system dialog..."
            startActivityForResult(widgetHost.getBindIntentForId(boundWidgetId, info), REQUEST_BIND)
        }
    }

    private fun onWidgetBound() {
        val needsConfigure = widgetHost.getProviderInfoForId(boundWidgetId)?.configure != null
        if (needsConfigure) {
            statusText.text = "Widget needs to be configured first..."
            if (!widgetHost.startConfigureForId(this, boundWidgetId, REQUEST_CONFIGURE)) {
                showWidget()
            }
        } else {
            showWidget()
        }
    }

    private fun abandonCurrentBinding(message: String) {
        if (boundWidgetId != -1 && boundWidgetId != savedWidgetId) {
            widgetHost.deleteId(boundWidgetId)
        }
        boundWidgetId = -1
        currentAppName = null
        currentWidgetName = null
        hostView = null
        previewFrame.removeAllViews()
        appNameText.text = "No widget selected"
        widgetNameText.text = ""
        statusText.text = message
    }

    // --- Preview + extraction (like the widget detail page) ---

    private fun showWidget() {
        val view = widgetHost.createHostViewForId(this, boundWidgetId)
        if (view == null) {
            statusText.text = "Could not create widget view."
            return
        }
        hostView = view
        previewFrame.removeAllViews()
        previewFrame.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        applySizeAndExtract()
    }

    private fun applySizeAndExtract() {
        val hostView = hostView ?: return
        val appWidgetId = boundWidgetId
        val spanX = selectedSpanX()
        val spanY = selectedSpanY()
        val density = resources.displayMetrics.density

        // Same launcher-grid cell math as the widget detail page
        val innerWidth = previewFrame.width - previewFrame.paddingLeft - previewFrame.paddingRight
        val availableWidth = innerWidth.takeIf { it > 0 }
            ?: (resources.displayMetrics.widthPixels - (48 * density).toInt())
        val columnWidth = availableWidth / 5
        val rowHeight = (columnWidth * 1.4f).toInt()
        val widthPx = spanX * columnWidth
        val heightPx = spanY * rowHeight

        WidgetActionRuntime.applySize(this, hostView, spanX, spanY)
        hostView.layoutParams = FrameLayout.LayoutParams(widthPx, heightPx, Gravity.CENTER)

        statusText.text = "Loading widget data..."
        nodesListView.postDelayed({
            if (isFinishing || boundWidgetId != appWidgetId) return@postDelayed
            currentNodes = WidgetExtractor(this).extractFromRemoteViews(hostView)
            statusText.text = "${currentNodes.size} elements extracted"
            nodesListView.adapter = object : ArrayAdapter<WidgetNode>(
                this, android.R.layout.simple_list_item_2, android.R.id.text1, currentNodes
            ) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = super.getView(position, convertView, parent)
                    val node = currentNodes[position]
                    view.findViewById<TextView>(android.R.id.text1).text =
                        node.bestValue ?: node.className
                    view.findViewById<TextView>(android.R.id.text2).text =
                        "${node.pathInTree} (${node.className})"
                    return view
                }
            }
        }, 1200)
    }

    private fun selectedSpanX() = spans[spanXSpinner.selectedItemPosition]
    private fun selectedSpanY() = spans[spanYSpinner.selectedItemPosition]

    private fun getAppAndWidgetNames(info: AppWidgetProviderInfo): Pair<String, String> {
        val pm = packageManager
        val appName = try {
            pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)).toString()
        } catch (e: Exception) {
            info.provider.packageName
        }
        val widgetName = try {
            info.loadLabel(pm) ?: info.provider.shortClassName
        } catch (e: Exception) {
            info.provider.shortClassName
        }
        return appName to widgetName
    }

    // --- Tasker plumbing ---

    override val inputForTasker: TaskerInput<WidgetActionInput>
        get() = TaskerInput(
            WidgetActionInput(
                appWidgetId = boundWidgetId,
                appName = currentAppName,
                widgetLabel = currentWidgetName,
                spanX = selectedSpanX(),
                spanY = selectedSpanY(),
                query = queryEditText.text?.toString()?.takeIf { it.isNotBlank() }
            )
        )

    override fun assignFromInput(input: TaskerInput<WidgetActionInput>) {
        val regular = input.regular
        suppressListeners = true
        spanXSpinner.setSelection(spans.indexOf(regular.spanX.coerceIn(1, 5)))
        spanYSpinner.setSelection(spans.indexOf(regular.spanY.coerceIn(1, 5)))
        queryEditText.setText(regular.query ?: "")
        suppressListeners = false

        val providerInfo = if (regular.appWidgetId != -1) {
            widgetHost.getProviderInfoForId(regular.appWidgetId)
        } else null

        if (providerInfo != null) {
            savedWidgetId = regular.appWidgetId
            boundWidgetId = regular.appWidgetId
            val (appName, widgetName) = getAppAndWidgetNames(providerInfo)
            currentAppName = regular.appName ?: appName
            currentWidgetName = regular.widgetLabel ?: widgetName
            appNameText.text = "App: $currentAppName"
            widgetNameText.text = "Widget: $currentWidgetName"
            // Wait for the layout pass so the preview frame has a width
            previewFrame.post { showWidget() }
        }
    }

    private fun save() {
        if (boundWidgetId == -1) {
            Toast.makeText(this, "Please select a widget first", Toast.LENGTH_SHORT).show()
            return
        }
        if (queryEditText.text.isNullOrBlank()) {
            Toast.makeText(this, "Please select an element from the extracted data", Toast.LENGTH_SHORT).show()
            return
        }
        // A newly bound widget replaces the one from the previous configuration
        if (savedWidgetId != -1 && savedWidgetId != boundWidgetId) {
            widgetHost.deleteId(savedWidgetId)
        }
        saved = true
        helper.finishForTasker()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Config abandoned: release the widget bound in this session
        if (!saved && boundWidgetId != -1 && boundWidgetId != savedWidgetId) {
            widgetHost.deleteId(boundWidgetId)
        }
    }

    companion object {
        private const val REQUEST_PICK = 3000
        private const val REQUEST_BIND = 3001
        private const val REQUEST_CONFIGURE = 3002
    }
}
