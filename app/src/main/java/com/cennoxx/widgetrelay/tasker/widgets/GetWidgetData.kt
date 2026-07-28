package com.cennoxx.widgetrelay.tasker.widgets

import android.content.Context
import com.cennoxx.widgetrelay.R
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
private const val ERROR_NO_OVERLAY = 3

@TaskerOutputObject
class WidgetDataOutput(
    @get:TaskerOutputVariable("widget_value", labelResIdName = "widget_value", htmlLabelResIdName = "widget_value_description")
    var widgetValue: String?
)

/** Gets the value of a single widget element selected by its path. */
class GetWidgetDataRunner : TaskerPluginRunnerAction<WidgetActionInput, WidgetDataOutput>() {
    override fun run(context: Context, input: TaskerInput<WidgetActionInput>): TaskerPluginResult<WidgetDataOutput> {
        if (!WidgetActionRuntime.hasOverlayPermission(context)) {
            return TaskerPluginResultErrorWithOutput(
                ERROR_NO_OVERLAY,
                context.getString(R.string.error_no_overlay)
            )
        }
        val path = input.regular.query
            ?: return TaskerPluginResultErrorWithOutput(
                ERROR_NOT_FOUND,
                context.getString(R.string.error_no_path)
            )
        // Only this one element is needed - no need to wait for the rest of the tree
        val nodes = WidgetActionRuntime.captureNodes(context, input.regular) { nodes ->
            nodes.any { it.pathInTree == path }
        }
            ?: return TaskerPluginResultErrorWithOutput(
                ERROR_NOT_BOUND,
                context.getString(R.string.error_not_bound, input.regular.widgetLabel ?: input.regular.appWidgetId)
            )
        val node = nodes.firstOrNull { it.pathInTree == path }
            ?: return TaskerPluginResultErrorWithOutput(
                ERROR_NOT_FOUND,
                context.getString(R.string.error_path_not_found, path)
            )
        return TaskerPluginResultSucess(WidgetDataOutput(node.bestValue ?: ""))
    }
}

class GetWidgetDataHelper(config: TaskerPluginConfig<WidgetActionInput>) :
    TaskerPluginConfigHelper<WidgetActionInput, WidgetDataOutput, GetWidgetDataRunner>(config) {
    override val inputClass = WidgetActionInput::class.java
    override val outputClass = WidgetDataOutput::class.java
    override val runnerClass = GetWidgetDataRunner::class.java

    override fun addToStringBlurb(input: TaskerInput<WidgetActionInput>, blurbBuilder: StringBuilder) {
        val regular = input.regular
        blurbBuilder.appendWidgetBlurbHeader(context, regular)
        blurbBuilder.appendLine(context.getString(R.string.blurb_path, regular.query))
        blurbBuilder.appendLine()
        blurbBuilder.append(
            context.getString(R.string.blurb_get_data, regular.query, regular.widgetLabel, regular.appName)
        )
    }
}

class ActivityConfigGetWidgetData : ActivityConfigWidgetActionBase() {
    override val queryLabelRes = R.string.label_element_path
    override val helper by lazy { GetWidgetDataHelper(this) }
}
