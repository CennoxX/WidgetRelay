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
private const val ERROR_INVALID_SELECTOR = 5

/** Clicks a widget element selected by path, visible text, or a regular expression. */
class ClickWidgetRunner : TaskerPluginRunnerActionNoOutput<WidgetActionInput>() {
    override fun run(context: Context, input: TaskerInput<WidgetActionInput>): TaskerPluginResult<Unit> {
        if (!WidgetActionRuntime.hasOverlayPermission(context)) {
            return TaskerPluginResultError(ERROR_NO_OVERLAY, context.getString(R.string.error_no_overlay))
        }
        val selector = input.regular.query?.trim()?.takeIf { it.isNotBlank() }
            ?: return TaskerPluginResultError(ERROR_NOT_FOUND, context.getString(R.string.error_no_selector))
        val result = if (selector.startsWith("/root/")) {
            WidgetActionRuntime.clickPath(context, input.regular, selector)
        } else {
            val query = TextQuery.parse(selector)
                ?: return TaskerPluginResultError(
                    ERROR_INVALID_SELECTOR,
                    context.getString(R.string.error_text_invalid_regex, selector)
                )
            WidgetActionRuntime.clickText(context, input.regular, query)
        }
        return when (result) {
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
                context.getString(R.string.error_selector_not_found, selector)
            )
            WidgetActionRuntime.ClickResult.NOT_CLICKABLE -> TaskerPluginResultError(
                ERROR_NOT_CLICKABLE,
                context.getString(R.string.error_selector_not_clickable, selector)
            )
            WidgetActionRuntime.ClickResult.CLICKED -> TaskerPluginResultSucess()
        }
    }
}

class ClickWidgetHelper(config: TaskerPluginConfig<WidgetActionInput>) :
    TaskerPluginConfigHelperNoOutput<WidgetActionInput, ClickWidgetRunner>(config) {
    override val inputClass = WidgetActionInput::class.java
    override val runnerClass = ClickWidgetRunner::class.java

    override fun addToStringBlurb(input: TaskerInput<WidgetActionInput>, blurbBuilder: StringBuilder) {
        val regular = input.regular
        blurbBuilder.appendWidgetBlurbHeader(context, regular)
        blurbBuilder.appendLine(context.getString(R.string.blurb_selector, regular.query))
        blurbBuilder.appendLine()
        blurbBuilder.append(
            context.getString(R.string.blurb_click_selector, regular.query, regular.widgetLabel, regular.appName)
        )
    }
}

class ActivityConfigClickWidget : ActivityConfigWidgetActionBase() {
    override val queryLabelRes = R.string.label_selector
    override fun isNodeSelectable(node: WidgetNode) = node.clickable
    override val selectableDescriptionRes = R.string.selectable_clickable
    override fun queryValueForNodeLongPress(node: WidgetNode) = node.text ?: node.contentDescription
    override val helper by lazy { ClickWidgetHelper(this) }
}
