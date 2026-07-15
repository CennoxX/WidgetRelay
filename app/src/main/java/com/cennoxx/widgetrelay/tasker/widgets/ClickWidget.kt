package com.cennoxx.widgetrelay.tasker.widgets

import android.content.Context
import com.joaomgcd.taskerpluginlibrary.action.TaskerPluginRunnerActionNoOutput
import com.joaomgcd.taskerpluginlibrary.config.TaskerPluginConfig
import com.joaomgcd.taskerpluginlibrary.config.TaskerPluginConfigHelperNoOutput
import com.joaomgcd.taskerpluginlibrary.input.TaskerInput
import com.joaomgcd.taskerpluginlibrary.runner.TaskerPluginResult
import com.joaomgcd.taskerpluginlibrary.runner.TaskerPluginResultError
import com.joaomgcd.taskerpluginlibrary.runner.TaskerPluginResultSucess

private const val ERROR_NOT_BOUND = 1
private const val ERROR_NOT_FOUND = 2
private const val ERROR_NOT_CLICKABLE = 3
private const val ERROR_NO_OVERLAY = 4

/** Clicks the widget element selected by its path, firing the provider's PendingIntent. */
class ClickWidgetRunner : TaskerPluginRunnerActionNoOutput<WidgetActionInput>() {
    override fun run(context: Context, input: TaskerInput<WidgetActionInput>): TaskerPluginResult<Unit> {
        if (!WidgetActionRuntime.hasOverlayPermission(context)) {
            return TaskerPluginResultError(
                ERROR_NO_OVERLAY,
                "WidgetRelay needs the 'Display over other apps' permission to click widgets in the background. Please grant it in the Android settings."
            )
        }
        val path = input.regular.query
            ?: return TaskerPluginResultError(ERROR_NOT_FOUND, "No element path configured. Reconfigure the Tasker action.")
        return when (WidgetActionRuntime.clickPath(context, input.regular, path)) {
            WidgetActionRuntime.ClickResult.NOT_BOUND -> TaskerPluginResultError(
                ERROR_NOT_BOUND,
                "Widget '${input.regular.widgetLabel ?: input.regular.appWidgetId}' is not bound anymore. Reconfigure the Tasker action."
            )
            WidgetActionRuntime.ClickResult.NOT_FOUND -> TaskerPluginResultError(
                ERROR_NOT_FOUND,
                "No element found at path '$path'"
            )
            WidgetActionRuntime.ClickResult.NOT_CLICKABLE -> TaskerPluginResultError(
                ERROR_NOT_CLICKABLE,
                "Element at path '$path' (or any of its parents) is not clickable"
            )
            WidgetActionRuntime.ClickResult.CLICKED -> TaskerPluginResultSucess()
        }
    }
}

class ClickWidgetHelper(config: TaskerPluginConfig<WidgetActionInput>) :
    TaskerPluginConfigHelperNoOutput<WidgetActionInput, ClickWidgetRunner>(config) {
    override val inputClass = WidgetActionInput::class.java
    override val runnerClass = ClickWidgetRunner::class.java

    // All fields are marked ignoreInStringBlurb - the blurb below is fully custom
    override fun addToStringBlurb(input: TaskerInput<WidgetActionInput>, blurbBuilder: StringBuilder) {
        val regular = input.regular
        blurbBuilder.append("App: ${regular.appName}\n")
        blurbBuilder.append("Widget: ${regular.widgetLabel}\n")
        blurbBuilder.append("Size: ${regular.spanX} x ${regular.spanY}\n")
        blurbBuilder.append("Path: ${regular.query}\n\n")
        blurbBuilder.append("Click '${regular.query}' of widget '${regular.widgetLabel}' from app '${regular.appName}'")
    }
}

class ActivityConfigClickWidget : ActivityConfigWidgetActionBase() {
    override val queryLabel = "Element path (tap an element below)"
    override val helper by lazy { ClickWidgetHelper(this) }
}
