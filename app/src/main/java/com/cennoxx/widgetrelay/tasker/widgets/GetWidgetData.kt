package com.cennoxx.widgetrelay.tasker.widgets

import android.content.Context
import com.cennoxx.widgetrelay.R
import com.cennoxx.widgetrelay.widget.WidgetNode
import com.cennoxx.widgetrelay.widget.toJsonTree
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
private const val ERROR_NO_TREE = 4

@TaskerOutputObject
class WidgetDataOutput(
    @get:TaskerOutputVariable("widget_value", labelResIdName = "widget_value", htmlLabelResIdName = "widget_value_description")
    var widgetValue: String?,
    @get:TaskerOutputVariable("widget_json", labelResIdName = "widget_json", htmlLabelResIdName = "widget_json_description")
    var widgetJson: String?
)

/**
 * Reads a widget. With an element path it returns that one element's value;
 * without one it returns the whole widget as a JSON tree.
 *
 * The two modes are not just different outputs, they capture differently: a
 * single element is done as soon as that element shows up, while the full tree
 * has to wait for the widget to settle so asynchronously loaded collection
 * content is included (see [WidgetActionRuntime.captureNodes]). So asking for
 * one value stays fast, and only the JSON mode pays for completeness.
 */
class GetWidgetDataRunner : TaskerPluginRunnerAction<WidgetActionInput, WidgetDataOutput>() {
    override fun run(context: Context, input: TaskerInput<WidgetActionInput>): TaskerPluginResult<WidgetDataOutput> {
        if (!WidgetActionRuntime.hasOverlayPermission(context)) {
            return TaskerPluginResultErrorWithOutput(
                ERROR_NO_OVERLAY,
                context.getString(R.string.error_no_overlay)
            )
        }
        val path = input.regular.query?.takeIf { it.isNotBlank() }

        // Only one element is needed - no need to wait for the rest of the tree
        val nodes = WidgetActionRuntime.captureNodes(
            context,
            input.regular,
            stopWhen = path?.let { wanted -> { nodes -> nodes.any { it.pathInTree == wanted } } }
        )
            ?: run {
                WidgetRebindNotifier.notifyNotBound(
                    context, input.regular.appWidgetId, input.regular.widgetLabel, input.regular.appName
                )
                return TaskerPluginResultErrorWithOutput(
                    ERROR_NOT_BOUND,
                    context.getString(R.string.error_not_bound, input.regular.widgetLabel ?: input.regular.appWidgetId)
                )
            }

        if (path == null) {
            val jsonRoot = nodes.toJsonTree() ?: return TaskerPluginResultErrorWithOutput(
                ERROR_NO_TREE,
                context.getString(R.string.error_no_tree)
            )
            return TaskerPluginResultSucess(WidgetDataOutput("", jsonRoot.toString()))
        }

        val node = nodes.firstOrNull { it.pathInTree == path }
            ?: return TaskerPluginResultErrorWithOutput(
                ERROR_NOT_FOUND,
                context.getString(R.string.error_path_not_found, path)
            )
        return TaskerPluginResultSucess(WidgetDataOutput(node.bestValue ?: "", ""))
    }
}

class GetWidgetDataHelper(config: TaskerPluginConfig<WidgetActionInput>) :
    TaskerPluginConfigHelper<WidgetActionInput, WidgetDataOutput, GetWidgetDataRunner>(config) {
    override val inputClass = WidgetActionInput::class.java
    override val outputClass = WidgetDataOutput::class.java
    override val runnerClass = GetWidgetDataRunner::class.java

    override fun addToStringBlurb(input: TaskerInput<WidgetActionInput>, blurbBuilder: StringBuilder) {
        val regular = input.regular
        val path = regular.query?.takeIf { it.isNotBlank() }
        blurbBuilder.appendWidgetBlurbHeader(context, regular)
        if (path != null) blurbBuilder.appendLine(context.getString(R.string.blurb_path, path))
        blurbBuilder.appendLine()
        blurbBuilder.append(
            if (path != null) {
                context.getString(R.string.blurb_get_data, path, regular.widgetLabel, regular.appName)
            } else {
                context.getString(R.string.blurb_get_json, regular.widgetLabel, regular.appName)
            }
        )
    }
}

class ActivityConfigGetWidgetData : ActivityConfigWidgetActionBase() {
    override val queryLabelRes = R.string.label_element_path_or_json
    // Empty is a valid configuration here: it means "return everything"
    override val queryRequired = false
    // Nodes without a value would just return an empty string
    override fun isNodeSelectable(node: WidgetNode) = node.bestValue != null
    override val helper by lazy { GetWidgetDataHelper(this) }
}
