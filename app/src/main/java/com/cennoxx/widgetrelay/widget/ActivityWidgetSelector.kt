package com.cennoxx.widgetrelay.widget

import android.app.Activity
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ExpandableListView
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.cennoxx.widgetrelay.R
import com.cennoxx.widgetrelay.applyEdgeToEdgeInsets

/**
 * Searchable widget picker. Launched by the Tasker config activity; returns
 * the selected [AppWidgetProviderInfo] as a parcelable result extra.
 */
class ActivityWidgetSelector : ComponentActivity() {
    private lateinit var widgetHost: WidgetHost
    private lateinit var widgetListView: ExpandableListView
    private lateinit var searchBox: EditText
    private lateinit var clearButton: TextView
    private lateinit var statusTextView: TextView
    private lateinit var adapter: WidgetExpandableAdapter

    private var allApps = emptyList<AppWithWidgets>()
    private var filteredApps = emptyList<AppWithWidgets>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_widget_selector)
        applyEdgeToEdgeInsets()

        widgetHost = WidgetHost.get(this)
        widgetHost.startListening()

        widgetListView = findViewById(R.id.widgetListView)
        statusTextView = findViewById(R.id.statusTextView)
        searchBox = findViewById(R.id.searchBox)
        clearButton = findViewById(R.id.clearButton)

        clearButton.setOnClickListener {
            searchBox.text.clear()
        }

        adapter = WidgetExpandableAdapter(this)
        widgetListView.setAdapter(adapter)

        widgetListView.setOnChildClickListener { _, _, groupPosition, childPosition, _ ->
            val widget = filteredApps[groupPosition].widgets[childPosition]
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_PICKED_PROVIDER, widget.info))
            finish()
            true
        }

        loadWidgetData()
        setupSearch()
    }

    private fun loadWidgetData() {
        val pm = packageManager
        val providers = widgetHost.getAvailableWidgetProviders()
        val collator = java.text.Collator.getInstance()

        allApps = providers
            .groupBy { it.provider.packageName }
            .map { (packageName, infos) ->
                val appName = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
                } catch (e: Exception) {
                    packageName
                }
                val icon = try {
                    pm.getApplicationIcon(packageName)
                } catch (e: Exception) {
                    null
                }
                val widgets = infos.map { info -> info.toWidgetEntry() }
                    .sortedWith(compareBy(collator) { it.label })
                AppWithWidgets(packageName, appName, icon, widgets)
            }
            .sortedWith(compareBy(collator) { it.appName })

        filterWidgets("")
    }

    private fun AppWidgetProviderInfo.toWidgetEntry(): WidgetEntry {
        val label = try {
            loadLabel(packageManager) ?: provider.shortClassName
        } catch (e: Exception) {
            provider.shortClassName
        }
        val description = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                loadDescription(this@ActivityWidgetSelector)?.toString()
            } catch (e: Exception) {
                null
            }
        } else null
        val preview = try {
            loadPreviewImage(this@ActivityWidgetSelector, 0)
        } catch (e: Exception) {
            null
        }
        val (spanX, spanY) = getSpans(this@ActivityWidgetSelector)
        return WidgetEntry(label, description, "($spanX x $spanY)", preview, this)
    }

    private fun setupSearch() {
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                clearButton.visibility = if (s.isNotEmpty()) View.VISIBLE else View.GONE
                filterWidgets(s.toString())
            }
            override fun afterTextChanged(s: Editable) {}
        })
    }

    private fun filterWidgets(query: String) {
        filteredApps = if (query.isBlank()) {
            allApps
        } else {
            allApps.mapNotNull { app ->
                if (app.appName.contains(query, ignoreCase = true)) {
                    app
                } else {
                    val matching = app.widgets.filter {
                        it.label.contains(query, ignoreCase = true)
                    }
                    if (matching.isNotEmpty()) app.copy(widgets = matching) else null
                }
            }
        }

        adapter.setData(filteredApps)

        for (i in filteredApps.indices) {
            if (query.isBlank()) {
                widgetListView.collapseGroup(i)
            } else {
                widgetListView.expandGroup(i)
            }
        }

        val widgetCount = filteredApps.sumOf { it.widgets.size }
        statusTextView.text = "$widgetCount widgets in ${filteredApps.size} apps"
    }

    companion object {
        /** The picked AppWidgetProviderInfo, returned as parcelable result extra. */
        const val EXTRA_PICKED_PROVIDER = "pickedProvider"
    }
}
