package com.cennoxx.widgetrelay.tasker.widgets

import com.joaomgcd.taskerpluginlibrary.input.TaskerInputField
import com.joaomgcd.taskerpluginlibrary.input.TaskerInputRoot

/**
 * Shared input for widget plugin actions. The widget is bound once at
 * configuration time; the persistent appWidgetId is stored here so the runner
 * can recreate and read the widget when Tasker fires the action.
 *
 * [appName] and [widgetLabel] are only kept for display in the Tasker action
 * blurb; the actual lookup at runtime always goes through [appWidgetId].
 * [query] holds the selected element path (e.g. "/root/0/1").
 */
@TaskerInputRoot
class WidgetActionInput @JvmOverloads constructor(
    @field:TaskerInputField("appwidgetid", labelResIdName = "widget", ignoreInStringBlurb = true) var appWidgetId: Int = -1,
    @field:TaskerInputField("appname", labelResIdName = "widget", ignoreInStringBlurb = true) var appName: String? = null,
    @field:TaskerInputField("widgetlabel", labelResIdName = "widget", ignoreInStringBlurb = true) var widgetLabel: String? = null,
    @field:TaskerInputField("spanx", labelResIdName = "width_cells", ignoreInStringBlurb = true) var spanX: Int = 2,
    @field:TaskerInputField("spany", labelResIdName = "height_cells", ignoreInStringBlurb = true) var spanY: Int = 2,
    @field:TaskerInputField("query", labelResIdName = "query", ignoreInStringBlurb = true) var query: String? = null
)
