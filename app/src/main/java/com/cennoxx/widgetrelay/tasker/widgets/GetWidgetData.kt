package com.cennoxx.widgetrelay.tasker.widgets

import android.content.Context
import com.joaomgcd.taskerpluginlibrary.action.TaskerPluginRunnerAction
import com.joaomgcd.taskerpluginlibrary.config.TaskerPluginConfig
import com.joaomgcd.taskerpluginlibrary.config.TaskerPluginConfigHelper
import com.joaomgcd.taskerpluginlibrary.input.TaskerInput
import com.joaomgcd.taskerpluginlibrary.output.TaskerOutputObject
import com.joaomgcd.taskerpluginlibrary.output.TaskerOutputVariable
import com.joaomgcd.taskerpluginlibrary.runner.TaskerPluginResult
import com.joaomgcd.taskerpluginlibrary.runner.TaskerPluginResultErrorWithOutput
import com.joaomgcd.taskerpluginlibrary.runner.TaskerPluginResultSucess

private const val ERROR_NOT_BOUND = 1
private const val ERROR_NOT_FOUND = 2

@TaskerOutputObject
class WidgetDataOutput(
    @get:TaskerOutputVariable("widget_value", labelResIdName = "widget_value", htmlLabelResIdName = "widget_value_description")
    var widgetValue: String?
)

/** Gets the value of a single widget element selected by its path. */
class GetWidgetDataRunner : TaskerPluginRunnerAction<WidgetActionInput, WidgetDataOutput>() {
    override fun run(context: Context, input: TaskerInput<WidgetActionInput>): TaskerPluginResult<WidgetDataOutput> {
        val nodes = WidgetActionRuntime.captureNodes(context, input.regular)
            ?: return TaskerPluginResultErrorWithOutput(
                ERROR_NOT_BOUND,
                "Widget '${input.regular.widgetLabel ?: input.regular.appWidgetId}' is not bound anymore. Reconfigure the Tasker action."
            )
        val path = input.regular.query
        val node = nodes.firstOrNull { it.pathInTree == path }
            ?: return TaskerPluginResultErrorWithOutput(ERROR_NOT_FOUND, "No element found at path '$path'")
        return TaskerPluginResultSucess(WidgetDataOutput(node.bestValue ?: ""))
    }
}

class GetWidgetDataHelper(config: TaskerPluginConfig<WidgetActionInput>) :
    TaskerPluginConfigHelper<WidgetActionInput, WidgetDataOutput, GetWidgetDataRunner>(config) {
    override val inputClass = WidgetActionInput::class.java
    override val outputClass = WidgetDataOutput::class.java
    override val runnerClass = GetWidgetDataRunner::class.java

    // All fields are marked ignoreInStringBlurb - the blurb below is fully custom
    override fun addToStringBlurb(input: TaskerInput<WidgetActionInput>, blurbBuilder: StringBuilder) {
        val regular = input.regular
        blurbBuilder.append("App: ${regular.appName}\n")
        blurbBuilder.append("Widget: ${regular.widgetLabel}\n")
        blurbBuilder.append("Size: ${regular.spanX} x ${regular.spanY}\n\n")
        blurbBuilder.append("Get '${regular.query}' from Widget '${regular.widgetLabel}' from App '${regular.appName}'")
    }
}

class ActivityConfigGetWidgetData : ActivityConfigWidgetActionBase() {
    override val queryLabel = "Element path (tap an element below)"
    override val helper by lazy { GetWidgetDataHelper(this) }
}
