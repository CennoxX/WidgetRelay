package com.cennoxx.widgetrelay.tasker.widgets

import android.app.Activity
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
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
import androidx.annotation.StringRes
import com.cennoxx.widgetrelay.R
import com.cennoxx.widgetrelay.applyEdgeToEdgeInsets
import com.cennoxx.widgetrelay.widget.ActivityWidgetSelector
import com.cennoxx.widgetrelay.widget.WidgetExtractor
import com.cennoxx.widgetrelay.widget.WidgetGrid
import com.cennoxx.widgetrelay.widget.WidgetHost
import com.cennoxx.widgetrelay.widget.WidgetMonitorRegistry
import com.cennoxx.widgetrelay.widget.WidgetMonitorService
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
 *
 * When [queryLabelRes] is null, the action doesn't need a single selected
 * element (e.g. it reads all extracted data) - the element path row is
 * hidden and the extracted data list becomes view-only.
 */
abstract class ActivityConfigWidgetActionBase : Activity(), TaskerPluginConfig<WidgetActionInput> {
    @get:StringRes
    protected open val queryLabelRes: Int? = null
    protected abstract val helper: TaskerPluginConfigHelper<WidgetActionInput, *, *>

    /** Whether non-path queries must be regular expressions. */
    protected open val queryMustBePathOrRegex = false

    /** Whether the query field has to be filled in before saving. */
    protected open val queryRequired = true

    /** What tapping an element in the extracted-data list puts into the query field. */
    protected open fun queryValueForNode(node: WidgetNode): String? = node.pathInTree

    /** What long-pressing an element puts into the query field, if supported. */
    protected open fun queryValueForNodeLongPress(node: WidgetNode): String? = null

    /** Called with the final input just before it is handed back to Tasker. */
    protected open fun onSavingForTasker(input: WidgetActionInput) {}

    /** Elements that aren't selectable are shown dimmed, but can still be tapped. */
    protected open fun isNodeSelectable(node: WidgetNode) = queryValueForNode(node) != null

    /** Describes what makes a node selectable, e.g. "clickable" - shown in the status line. */
    @get:StringRes
    protected open val selectableDescriptionRes = R.string.selectable_with_value

    override val context get() = applicationContext

    private lateinit var widgetHost: WidgetHost
    private lateinit var appNameText: TextView
    private lateinit var widgetNameText: TextView
    private lateinit var spanXSpinner: Spinner
    private lateinit var spanYSpinner: Spinner
    private lateinit var previewHeaderRow: LinearLayout
    private lateinit var previewChevron: TextView
    private lateinit var previewFrame: FrameLayout

    private lateinit var queryEditText: EditText
    private lateinit var queryClearButton: TextView
    private lateinit var statusText: TextView
    private lateinit var nodesListView: ListView

    private var previewExpandedOverride: Boolean? = null
    private var currentNodes = emptyList<WidgetNode>()
    // Y tops out one row lower than X - see WidgetGrid.MAX_SPAN_Y
    private val spansX = (1..WidgetGrid.MAX_SPAN_X).toList()
    private val spansY = (1..WidgetGrid.MAX_SPAN_Y).toList()

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

        appNameText = findViewById(R.id.appNameText)
        widgetNameText = findViewById(R.id.widgetNameText)
        spanXSpinner = findViewById(R.id.spanXSpinner)
        spanYSpinner = findViewById(R.id.spanYSpinner)
        previewFrame = findViewById(R.id.previewFrame)
        queryEditText = findViewById(R.id.queryEditText)
        queryClearButton = findViewById(R.id.queryClearButton)
        statusText = findViewById(R.id.statusText)
        nodesListView = findViewById(R.id.nodesListView)
        previewHeaderRow = findViewById(R.id.previewHeaderRow)
        previewChevron = findViewById(R.id.previewChevron)
        previewFrame = findViewById(R.id.previewFrame)

        previewHeaderRow.setOnClickListener {
            previewExpandedOverride = !isPreviewExpanded()
            renderPreviewVisibility()
        }
        setupSizeSpinners()
        setupQueryClearButton()

        val labelRes = queryLabelRes
        if (labelRes != null) {
            findViewById<TextView>(R.id.queryLabel).setText(labelRes)
            nodesListView.setOnItemClickListener { _, _, position, _ ->
                currentNodes.getOrNull(position)
                    ?.let { queryValueForNode(it) }
                    ?.let { queryEditText.setText(it) }
            }
            nodesListView.setOnItemLongClickListener { _, _, position, _ ->
                val value = currentNodes.getOrNull(position)
                    ?.let { queryValueForNodeLongPress(it) }
                if (value == null) {
                    false
                } else {
                    queryEditText.setText("/${value.map { if (it in """\.^$|?*+()[]{}""") "\\$it" else it }.joinToString("")}/")
                    true
                }
            }
        } else {
            findViewById<View>(R.id.queryRow).visibility = View.GONE
            findViewById<TextView>(R.id.nodesHeaderText).setText(R.string.header_extracted_data)
        }
        findViewById<Button>(R.id.changeWidgetButton).setOnClickListener { launchWidgetPicker() }
        findViewById<Button>(R.id.refreshButton).setOnClickListener {
            if (boundWidgetId != -1) showWidget() else statusText.setText(R.string.status_select_widget_first)
        }
        findViewById<Button>(R.id.saveButton).setOnClickListener { save() }
    }

    private fun setupQueryClearButton() {
        queryClearButton.setOnClickListener { queryEditText.text.clear() }
        queryEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                queryClearButton.visibility = if (s.isNotEmpty()) View.VISIBLE else View.GONE
            }
            override fun afterTextChanged(s: Editable) {}
        })
    }

    private fun isPreviewExpanded(): Boolean {
        return previewExpandedOverride ?: true
    }

    private fun renderPreviewVisibility() {
        val expanded = isPreviewExpanded()
        previewFrame.visibility = if (expanded) View.VISIBLE else View.GONE
        previewChevron.text = if (expanded) "▴" else "▾"
    }

    private fun setupSizeSpinners() {
        listOf(spanXSpinner to spansX, spanYSpinner to spansY).forEach { (spinner, spans) ->
            val labels = spans.map { it.toString() }
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
                    statusText.setText(R.string.status_no_widget_selected)
                }
            }
            REQUEST_BIND -> if (resultCode == RESULT_OK) {
                onWidgetBound()
            } else {
                abandonCurrentBinding(R.string.status_binding_cancelled)
            }
            REQUEST_CONFIGURE -> if (resultCode == RESULT_OK) {
                showWidget()
            } else {
                abandonCurrentBinding(R.string.status_configuration_cancelled)
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
        appNameText.text = getString(R.string.app_line, appName)
        widgetNameText.text = getString(R.string.widget_line, widgetName)
        queryEditText.setText("")

        // Preselect the widget's default size
        val (defaultX, defaultY) = info.getSpans(this)
        suppressListeners = true
        spanXSpinner.setSelection(spansX.indexOf(defaultX.coerceIn(1, WidgetGrid.MAX_SPAN_X)))
        spanYSpinner.setSelection(spansY.indexOf(defaultY.coerceIn(1, WidgetGrid.MAX_SPAN_Y)))
        suppressListeners = false

        if (widgetHost.bindId(boundWidgetId, info)) {
            onWidgetBound()
        } else {
            statusText.setText(R.string.status_requesting_permission)
            startActivityForResult(widgetHost.getBindIntentForId(boundWidgetId, info), REQUEST_BIND)
        }
    }

    private fun onWidgetBound() {
        val needsConfigure = widgetHost.getProviderInfoForId(boundWidgetId)?.configure != null
        if (needsConfigure) {
            statusText.setText(R.string.status_needs_configuration)
            if (!widgetHost.startConfigureForId(this, boundWidgetId, REQUEST_CONFIGURE)) {
                showWidget()
            }
        } else {
            showWidget()
        }
    }

    private fun abandonCurrentBinding(@StringRes message: Int) {
        if (boundWidgetId != -1 && boundWidgetId != savedWidgetId) {
            widgetHost.deleteId(boundWidgetId)
        }
        boundWidgetId = -1
        currentAppName = null
        currentWidgetName = null
        hostView = null
        previewFrame.removeAllViews()
        appNameText.setText(R.string.no_widget_selected)
        widgetNameText.text = ""
        statusText.setText(message)
    }

    // --- Preview + extraction (like the widget detail page) ---

    private fun showWidget() {
        val view = widgetHost.createHostViewForId(this, boundWidgetId)
        if (view == null) {
            statusText.setText(R.string.status_no_host_view)
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
        renderPreviewVisibility()
    }

    private fun applySizeAndExtract() {
        val hostView = hostView ?: return
        val appWidgetId = boundWidgetId
        val spanX = selectedSpanX()
        val spanY = selectedSpanY()

        // Size the preview like the home screen would, using the frame's own
        // width once it has been laid out
        val innerWidth = previewFrame.width - previewFrame.paddingLeft - previewFrame.paddingRight
        val (widthPx, heightPx) = WidgetGrid.sizePx(this, spanX, spanY, innerWidth)

        WidgetActionRuntime.applySize(this, hostView, spanX, spanY)
        hostView.layoutParams = FrameLayout.LayoutParams(widthPx, heightPx, Gravity.CENTER)

        statusText.setText(R.string.status_loading)
        nodesListView.postDelayed({
            if (isFinishing || boundWidgetId != appWidgetId) return@postDelayed
            currentNodes = WidgetExtractor(this).extractFromRemoteViews(hostView)
            val selectable = currentNodes.count { isNodeSelectable(it) }
            statusText.text = if (selectable == currentNodes.size) {
                getString(R.string.status_elements, currentNodes.size)
            } else {
                getString(
                    R.string.status_elements_selectable,
                    currentNodes.size,
                    selectable,
                    getString(selectableDescriptionRes)
                )
            }
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
                    // Dim elements the action likely can't use, but leave them
                    // tappable - the extraction heuristics can be wrong, and the
                    // action itself gives the real error if the pick was bad
                    view.alpha = if (isNodeSelectable(node)) 1f else 0.4f
                    return view
                }
            }
        }, 1200)
    }

    private fun selectedSpanX() = spansX[spanXSpinner.selectedItemPosition]
    private fun selectedSpanY() = spansY[spanYSpinner.selectedItemPosition]

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
                query = queryEditText.text?.toString()?.takeIf { it.isNotBlank() },
                // Stored so the widget can be rebound if the id dies (reinstall)
                provider = widgetHost.getProviderInfoForId(boundWidgetId)?.provider?.flattenToString()
            )
        )

    override fun assignFromInput(input: TaskerInput<WidgetActionInput>) {
        val regular = input.regular
        suppressListeners = true
        // coerceIn also repairs an action saved before MAX_SPAN_Y was lowered
        // from 5 to 4 - reopening it corrects the stored size once Saved again
        spanXSpinner.setSelection(spansX.indexOf(regular.spanX.coerceIn(1, WidgetGrid.MAX_SPAN_X)))
        spanYSpinner.setSelection(spansY.indexOf(regular.spanY.coerceIn(1, WidgetGrid.MAX_SPAN_Y)))
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
            appNameText.text = getString(R.string.app_line, currentAppName)
            widgetNameText.text = getString(R.string.widget_line, currentWidgetName)
            // Wait for the layout pass so the preview frame has a width
            previewFrame.post { showWidget() }
        } else if (regular.appWidgetId != -1) {
            rebindLostWidget(regular)
        }
    }

    /**
     * The system deletes all of a host's widget bindings when the app is
     * uninstalled (a reinstall with a different signature - e.g. switching
     * between debug and release builds - counts), so a stored appWidgetId can
     * be dead although the Tasker action is intact. The provider is stored
     * too, so the same widget can be bound to a fresh id, keeping the
     * configured element and size.
     */
    private fun rebindLostWidget(regular: WidgetActionInput) {
        val provider = regular.provider?.let { ComponentName.unflattenFromString(it) }
        val info = provider?.let { p ->
            widgetHost.getAvailableWidgetProviders().firstOrNull { it.provider == p }
        }
        if (info == null) {
            // Saved before the provider was stored, or the widget's app is gone
            statusText.setText(R.string.status_rebind_failed)
            return
        }

        statusText.setText(R.string.status_rebinding)
        boundWidgetId = widgetHost.allocateId()
        val (appName, widgetName) = getAppAndWidgetNames(info)
        currentAppName = regular.appName ?: appName
        currentWidgetName = regular.widgetLabel ?: widgetName
        appNameText.text = getString(R.string.app_line, currentAppName)
        widgetNameText.text = getString(R.string.widget_line, currentWidgetName)

        if (widgetHost.bindId(boundWidgetId, info)) {
            onWidgetBound()
        } else {
            statusText.setText(R.string.status_requesting_permission)
            startActivityForResult(widgetHost.getBindIntentForId(boundWidgetId, info), REQUEST_BIND)
        }
    }

    private fun save() {
        if (boundWidgetId == -1) {
            Toast.makeText(this, R.string.toast_select_widget, Toast.LENGTH_SHORT).show()
            return
        }
        if (queryLabelRes != null && queryRequired && queryEditText.text.isNullOrBlank()) {
            Toast.makeText(this, R.string.toast_select_element, Toast.LENGTH_SHORT).show()
            return
        }
        val query = queryEditText.text?.toString()?.trim()
        if (queryLabelRes != null && queryMustBePathOrRegex && !query.isNullOrBlank() &&
            !query.startsWith("/root/") && TextQuery.parse(query) !is TextQuery.Pattern
        ) {
            Toast.makeText(this, R.string.error_selector_must_be_path_or_regex, Toast.LENGTH_SHORT).show()
            return
        }
        // The runners attach the widget to an invisible overlay window so that
        // list content loads in the background - that needs this permission
        if (!WidgetActionRuntime.hasOverlayPermission(this)) {
            Toast.makeText(this, R.string.toast_overlay_permission, Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        // A newly bound widget replaces the one from the previous configuration
        if (savedWidgetId != -1 && savedWidgetId != boundWidgetId) {
            widgetHost.deleteId(savedWidgetId)
            // Nothing can be hosted for an id that no longer exists
            WidgetMonitorRegistry.remove(this, savedWidgetId)
        }
        saved = true
        onSavingForTasker(inputForTasker.regular)
        helper.finishForTasker()
    }

    /**
     * Builds a non-dismissible banner informing free-tier users that saving will
     * replace any previous free integration as the active one, with a shortcut
     * to the Premium purchase dialog.
     */
    private fun buildFreemiumBanner(): View? {
        val banner = LinearLayout(this)
        banner.orientation = LinearLayout.HORIZONTAL
        banner.gravity = Gravity.CENTER_VERTICAL
        banner.setPadding(32, 24, 32, 24)
        banner.setBackgroundColor(0xFFFFF3CD.toInt()) // warm amber, accessible
        banner.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = 16
        }

        val text = TextView(this)
        text.setText(R.string.freemium_banner)
        text.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        text.setTextColor(0xFF856404.toInt())
        banner.addView(text)

        val button = Button(this)
        button.setText(R.string.freemium_unlock_premium)
        button.setOnClickListener { PremiumDialog.show(this) {} }
        banner.addView(button)

        return banner
    }

    override fun onDestroy() {
        super.onDestroy()
        // Config abandoned: release the widget bound in this session
        if (!saved && boundWidgetId != -1 && boundWidgetId != savedWidgetId) {
            widgetHost.deleteId(boundWidgetId)
        }
        // The preview took over the host's view for this id - hand it back to
        // the monitor, which would otherwise stop receiving updates
        if (boundWidgetId != -1) {
            WidgetMonitorService.rebuild(this, boundWidgetId)
        }
    }

    companion object {
        private const val REQUEST_PICK = 3000
        private const val REQUEST_BIND = 3001
        private const val REQUEST_CONFIGURE = 3002
    }
}
