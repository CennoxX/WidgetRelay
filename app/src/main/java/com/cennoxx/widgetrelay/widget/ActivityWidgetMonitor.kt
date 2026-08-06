package com.cennoxx.widgetrelay.widget

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import com.cennoxx.widgetrelay.R
import com.cennoxx.widgetrelay.applyEdgeToEdgeInsets
import com.cennoxx.widgetrelay.tasker.widgets.WidgetActionRuntime

/**
 * The app's front page: what WidgetRelay is currently keeping alive for the
 * "Widget Updated" Tasker event, and whether it actually can.
 *
 * Everything that decides whether background monitoring works is collected
 * here - the overlay permission, notifications, the battery optimisation
 * exemption and the optional wake lock - because a background service can
 * request none of them itself. Each is shown with its own state and the reason
 * it exists, so a widget that reads back empty is traceable to a switch on
 * this screen.
 */
class ActivityWidgetMonitor : Activity() {

    private lateinit var listView: ListView
    private lateinit var header: View
    private lateinit var stateDot: ImageView
    private lateinit var stateText: TextView
    private lateinit var stateHint: TextView
    private lateinit var toggleButton: Button
    private lateinit var permissionsHeaderRow: View
    private lateinit var permissionsDot: ImageView
    private lateinit var permissionsChevron: TextView
    private lateinit var permissionsSummary: TextView
    private lateinit var permissionsContainer: LinearLayout

    /** Null until the user taps the row; then their choice wins over the default. */
    private var permissionsExpandedOverride: Boolean? = null

    /** Whether everything was granted the last time this was checked. */
    private var permissionsWereAllGranted = false
    private lateinit var widgetsSummary: TextView
    private lateinit var wakeLockCheckBox: CheckBox

    private val handler = Handler(Looper.getMainLooper())
    private val rows = mutableListOf<Row>()
    private lateinit var adapter: BaseAdapter

    /** Granted-state of all permissions, so their rows are only rebuilt on change. */
    private var permissionSignature: String? = null

    /** A registry entry plus the live state of its monitor, if it has one. */
    private data class Row(
        val entry: WidgetMonitorRegistry.Entry,
        val status: WidgetMonitorService.Status?
    )

    /** One line in the permissions card. */
    private class Permission(
        @StringRes val title: Int,
        @StringRes val why: Int,
        val granted: Boolean,
        val request: () -> Unit
    )

    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_widget_monitor)
        applyEdgeToEdgeInsets()

        listView = findViewById(R.id.monitorListView)
        // The whole page scrolls with the list instead of squeezing it into
        // whatever is left below a fixed block
        header = layoutInflater.inflate(R.layout.header_widget_monitor, listView, false)
        listView.addHeaderView(header, null, false)

        stateDot = header.findViewById(R.id.stateDot)
        stateText = header.findViewById(R.id.monitorStateText)
        stateHint = header.findViewById(R.id.monitorStateHint)
        toggleButton = header.findViewById(R.id.toggleMonitoringButton)
        permissionsHeaderRow = header.findViewById(R.id.permissionsHeaderRow)
        permissionsDot = header.findViewById(R.id.permissionsDot)
        permissionsChevron = header.findViewById(R.id.permissionsChevron)
        permissionsSummary = header.findViewById(R.id.permissionsSummary)
        permissionsContainer = header.findViewById(R.id.permissionsContainer)
        widgetsSummary = header.findViewById(R.id.widgetsSummary)
        wakeLockCheckBox = header.findViewById(R.id.wakeLockCheckBox)

        // Created once: re-assigning an adapter would throw the scroll
        // position away on every refresh tick
        adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(position: Int) = rows[position]
            override fun getItemId(position: Int) = rows[position].entry.appWidgetId.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView
                    ?: layoutInflater.inflate(R.layout.item_monitored_widget, parent, false)
                bind(view, rows[position])
                return view
            }
        }
        listView.adapter = adapter

        permissionsHeaderRow.setOnClickListener {
            permissionsExpandedOverride = !isPermissionsExpanded()
            renderPermissions()
        }

        toggleButton.setOnClickListener { toggleMonitoring() }
        wakeLockCheckBox.setOnCheckedChangeListener { _, checked ->
            if (checked == WidgetMonitorRegistry.usesWakeLock(this)) return@setOnCheckedChangeListener
            WidgetMonitorService.setWakeLockEnabled(this, checked)
        }
    }

    override fun onResume() {
        super.onResume()
        // The service pushes changes; the tick keeps the relative times honest
        WidgetMonitorService.onStatusesChanged = { handler.post { render() } }
        WidgetMonitorService.ensureRunning(this)
        handler.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        WidgetMonitorService.onStatusesChanged = null
        handler.removeCallbacks(refresh)
    }

    // --- Rendering ---

    private fun render() {
        val statuses = WidgetMonitorService.statuses.associateBy { it.entry.appWidgetId }
        rows.clear()
        WidgetMonitorRegistry.entries(this)
            .mapTo(rows) { Row(it, statuses[it.appWidgetId]) }

        renderState()
        renderPermissions()
        renderWidgets()
    }

    private fun renderState() {
        val enabled = WidgetMonitorRegistry.isEnabled(this)
        val active = rows.count { it.status?.attached == true }
        val total = rows.size
        val stalled = total - active

        val color: Int
        when {
            total == 0 -> {
                color = R.color.stateOff
                stateText.setText(R.string.monitor_state_nothing)
                stateHint.setText(R.string.monitor_state_nothing_hint)
            }
            !enabled -> {
                color = R.color.stateOff
                stateText.setText(R.string.monitor_state_paused)
                stateHint.setText(R.string.monitor_state_paused_hint)
            }
            active == 0 -> {
                color = R.color.stateWarn
                stateText.setText(R.string.monitor_state_starting)
                stateHint.setText(R.string.monitor_state_starting_hint)
            }
            // Only mention the total when it differs - "1 of 1" says nothing
            stalled > 0 -> {
                color = R.color.stateWarn
                stateText.text = getString(R.string.monitor_state_partial, active, total)
                stateHint.text = if (stalled == 1) {
                    getString(R.string.monitor_state_partial_hint, stalled)
                } else {
                    getString(R.string.monitor_state_partial_hint_plural, stalled)
                }
            }
            else -> {
                color = R.color.stateOk
                stateText.text = resources.getQuantityString(
                    R.plurals.monitor_state_running, active, active
                )
                stateHint.setText(R.string.monitor_state_running_hint)
            }
        }
        tint(stateDot, color)

        toggleButton.setText(if (enabled) R.string.monitor_pause else R.string.monitor_resume)
        toggleButton.visibility = if (total == 0) View.GONE else View.VISIBLE
        wakeLockCheckBox.isChecked = WidgetMonitorRegistry.usesWakeLock(this)
    }

    private fun isPermissionsExpanded(): Boolean {
        val allGranted = permissions().all { it.granted }
        return permissionsExpandedOverride ?: !allGranted
    }

    private fun renderPermissions() {
        val permissions = permissions()
        val allGranted = permissions.all { it.granted }
        permissionsSummary.setText(
            if (allGranted) R.string.monitor_permissions_all_granted
            else R.string.monitor_permissions_missing
        )
        tint(permissionsDot, if (allGranted) R.color.stateOk else R.color.stateWarn)

        // Something newly missing should surface itself even if the user
        // folded the section away earlier - a stale grant is otherwise invisible
        if (!allGranted && permissionsWereAllGranted) permissionsExpandedOverride = null
        permissionsWereAllGranted = allGranted

        val expanded = permissionsExpandedOverride ?: !allGranted
        permissionsContainer.visibility = if (expanded) View.VISIBLE else View.GONE
        permissionsChevron.text = if (expanded) "▴" else "▾"
        // Folded away, there is nothing left to say beyond the summary line
        permissionsSummary.visibility = if (expanded || !allGranted) View.VISIBLE else View.GONE

        // Only rebuild the rows when something actually changed - the refresh
        // tick must not swap the buttons out from under a tap
        val signature = permissions.joinToString(",") { "${it.title}=${it.granted}" }
        if (signature == permissionSignature) return
        permissionSignature = signature

        permissionsContainer.removeAllViews()
        permissions.forEach { permission ->
            val view = layoutInflater.inflate(
                R.layout.item_permission, permissionsContainer, false
            )
            view.findViewById<TextView>(R.id.permissionTitle).setText(permission.title)
            view.findViewById<TextView>(R.id.permissionWhy).setText(permission.why)

            val state = view.findViewById<TextView>(R.id.permissionState)
            val color = if (permission.granted) R.color.stateOk else R.color.stateWarn
            state.setText(
                if (permission.granted) R.string.monitor_permission_granted
                else R.string.monitor_permission_missing
            )
            state.setTextColor(colorOf(color))
            tint(view.findViewById(R.id.permissionDot), color)

            val button = view.findViewById<Button>(R.id.permissionButton)
            button.visibility = if (permission.granted) View.GONE else View.VISIBLE
            button.setOnClickListener { permission.request() }

            permissionsContainer.addView(view)
        }
    }

    private fun renderWidgets() {
        widgetsSummary.setText(
            if (rows.isEmpty()) R.string.monitor_empty else R.string.monitor_widgets_summary
        )
        adapter.notifyDataSetChanged()
    }

    private fun bind(view: View, row: Row) {
        val entry = row.entry
        view.findViewById<TextView>(R.id.monitorWidgetText).text =
            entry.widgetLabel ?: entry.providerComponent?.shortClassName ?: ""
        view.findViewById<TextView>(R.id.monitorAppText).text = getString(
            R.string.monitor_item_app, entry.appName ?: "", entry.spanX, entry.spanY
        )

        val stateLine = view.findViewById<TextView>(R.id.monitorStateLine)
        val detailLine = view.findViewById<TextView>(R.id.monitorDetailLine)
        val status = row.status
        val color: Int
        when {
            status?.error != null -> {
                color = R.color.stateError
                stateLine.setText(R.string.monitor_item_state_error)
                detailLine.text = status.error
            }
            status?.attached != true -> {
                color = R.color.stateOff
                stateLine.setText(R.string.monitor_item_state_inactive)
                detailLine.setText(R.string.monitor_item_inactive_hint)
            }
            else -> {
                color = R.color.stateOk
                stateLine.setText(R.string.monitor_item_state_active)
                detailLine.text = getString(
                    R.string.monitor_item_detail, status.elements, changesText(status)
                )
            }
        }
        stateLine.setTextColor(colorOf(color))
        tint(view.findViewById(R.id.monitorDot), color)

        view.findViewById<Button>(R.id.monitorRemoveButton).setOnClickListener {
            confirmRemove(entry)
        }
    }

    /**
     * Removing an entry here is the only way to stop watching a widget -
     * Tasker never tells this app when the corresponding event was deleted -
     * and it can't be undone from this screen. A stray tap should not have to
     * mean reconfiguring the Tasker event from scratch, so this asks first and
     * explains why it exists.
     */
    private fun confirmRemove(entry: WidgetMonitorRegistry.Entry) {
        val name = entry.widgetLabel ?: entry.providerComponent?.shortClassName ?: ""
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.monitor_remove_confirm_title, name))
            .setMessage(R.string.monitor_remove_confirm_message)
            .setPositiveButton(R.string.monitor_remove_confirm_button) { _, _ -> removeWidget(entry) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun removeWidget(entry: WidgetMonitorRegistry.Entry) {
        WidgetMonitorRegistry.remove(this, entry.appWidgetId)
        if (WidgetMonitorRegistry.entries(this).isEmpty()) {
            WidgetMonitorService.stop(this)
        } else {
            WidgetMonitorService.ensureRunning(this)
        }
        render()
    }

    private fun changesText(status: WidgetMonitorService.Status): String {
        if (status.lastChangeAt == 0L) return getString(R.string.monitor_item_no_change_yet)
        val relative = DateUtils.getRelativeTimeSpanString(
            status.lastChangeAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
        )
        return getString(R.string.monitor_item_changes, status.updates, relative)
    }

    private fun colorOf(@ColorRes color: Int) =
        @Suppress("DEPRECATION") resources.getColor(color)

    private fun tint(dot: ImageView, @ColorRes color: Int) {
        dot.setColorFilter(colorOf(color), PorterDuff.Mode.SRC_IN)
    }

    // --- Keeping the monitor alive ---

    private fun toggleMonitoring() {
        WidgetMonitorService.setMonitoringEnabled(this, !WidgetMonitorRegistry.isEnabled(this))
        render()
    }

    private fun permissions(): List<Permission> {
        val result = mutableListOf(
            Permission(
                title = R.string.monitor_permission_overlay,
                why = R.string.monitor_permission_overlay_why,
                granted = WidgetActionRuntime.hasOverlayPermission(this),
                request = {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            result.add(
                Permission(
                    title = R.string.monitor_permission_notifications,
                    why = R.string.monitor_permission_notifications_why,
                    granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED,
                    request = {
                        requestPermissions(
                            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                            REQUEST_NOTIFICATIONS
                        )
                    }
                )
            )
        }
        result.add(
            Permission(
                title = R.string.monitor_permission_battery,
                why = R.string.monitor_permission_battery_why,
                granted = isIgnoringBatteryOptimizations(),
                request = { requestBatteryExemption() }
            )
        )
        return result
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return power.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestBatteryExemption() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            // Some ROMs refuse the direct request - fall back to the list
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                e2.printStackTrace()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    companion object {
        private const val REFRESH_MS = 2000L
        private const val REQUEST_NOTIFICATIONS = 4000
    }
}
