package com.cennoxx.widgetrelay.tasker.widgets

import android.content.Context
import com.cennoxx.widgetrelay.R
import com.cennoxx.widgetrelay.widget.WidgetNode
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
private const val ERROR_INVALID_QUERY = 5

/**
 * Clicks the first widget element matching the given text, firing the
 * provider's PendingIntent. The text is either an exact match or, written as
 * `/pattern/flags`, a regular expression - see [TextQuery].
 */
class ClickWidgetTextRunner : TaskerPluginRunnerActionNoOutput<WidgetActionInput>() {
    override fun run(context: Context, input: TaskerInput<WidgetActionInput>): TaskerPluginResult<Unit> {
        if (!WidgetActionRuntime.hasOverlayPermission(context)) {
            return TaskerPluginResultError(ERROR_NO_OVERLAY, context.getString(R.string.error_no_overlay))
        }
        val text = input.regular.query?.takeIf { it.isNotBlank() }
            ?: return TaskerPluginResultError(ERROR_NOT_FOUND, context.getString(R.string.error_no_text))
        val query = TextQuery.parse(text.trim())
            ?: return TaskerPluginResultError(ERROR_INVALID_QUERY, context.getString(R.string.error_text_invalid_regex, text))
        return when (WidgetActionRuntime.clickText(context, input.regular, query)) {
            WidgetActionRuntime.ClickResult.NOT_BOUND -> {
                WidgetRebindNotifier.notifyNotBound(
                    context, input.regular.appWidgetId, input.regular.widgetLabel, input.regular.appName
                )
                TaskerPluginResultError(
                    ERROR_NOT_BOUND,
                    context.getString(R.string.error_not_bound, input.regular.widgetLabel ?: input.regular.appWidgetId)
                )
            }
            WidgetActionRuntime.ClickResult.NOT_FOUND -> TaskerPluginResultError(
                ERROR_NOT_FOUND,
                context.getString(R.string.error_text_not_found, text)
            )
            WidgetActionRuntime.ClickResult.NOT_CLICKABLE -> TaskerPluginResultError(
                ERROR_NOT_CLICKABLE,
                context.getString(R.string.error_text_not_clickable, text)
            )
            WidgetActionRuntime.ClickResult.CLICKED -> TaskerPluginResultSucess()
        }
    }
}

class ClickWidgetTextHelper(config: TaskerPluginConfig<WidgetActionInput>) :
    TaskerPluginConfigHelperNoOutput<WidgetActionInput, ClickWidgetTextRunner>(config) {
    override val inputClass = WidgetActionInput::class.java
    override val runnerClass = ClickWidgetTextRunner::class.java

    override fun addToStringBlurb(input: TaskerInput<WidgetActionInput>, blurbBuilder: StringBuilder) {
        val regular = input.regular
        blurbBuilder.appendWidgetBlurbHeader(context, regular)
        blurbBuilder.appendLine(context.getString(R.string.blurb_text, regular.query))
        blurbBuilder.appendLine()
        blurbBuilder.append(
            context.getString(R.string.blurb_click_text, regular.query, regular.widgetLabel, regular.appName)
        )
    }
}

class ActivityConfigClickWidgetText : ActivityConfigWidgetActionBase() {
    override val queryLabelRes = R.string.label_element_text
    override fun queryValueForNode(node: WidgetNode) = node.text ?: node.contentDescription
    override fun isNodeSelectable(node: WidgetNode) = node.clickable && super.isNodeSelectable(node)
    override val selectableDescriptionRes = R.string.selectable_clickable_with_text
    override val helper by lazy { ClickWidgetTextHelper(this) }
}
