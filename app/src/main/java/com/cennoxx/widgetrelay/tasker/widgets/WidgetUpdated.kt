package com.cennoxx.widgetrelay.tasker.widgets

import android.content.Context
import com.cennoxx.widgetrelay.R
import com.cennoxx.widgetrelay.premium.PlayBillingEntitlement
import com.cennoxx.widgetrelay.premium.TaskerConfigurationLimit
import com.cennoxx.widgetrelay.premium.TaskerConfigurationRegistry
import com.cennoxx.widgetrelay.widget.WidgetMonitorRegistry
import com.cennoxx.widgetrelay.widget.WidgetMonitorService
import com.cennoxx.widgetrelay.widget.WidgetNode
import com.joaomgcd.taskerpluginlibrary.condition.TaskerPluginRunnerConditionEvent
import com.joaomgcd.taskerpluginlibrary.config.TaskerPluginConfig
import com.joaomgcd.taskerpluginlibrary.config.TaskerPluginConfigHelper
import com.joaomgcd.taskerpluginlibrary.input.TaskerInput
import com.joaomgcd.taskerpluginlibrary.input.TaskerInputField
import com.joaomgcd.taskerpluginlibrary.input.TaskerInputRoot
import com.joaomgcd.taskerpluginlibrary.output.TaskerOutputObject
import com.joaomgcd.taskerpluginlibrary.output.TaskerOutputVariable
import com.joaomgcd.taskerpluginlibrary.runner.TaskerPluginResultCondition
import com.joaomgcd.taskerpluginlibrary.runner.TaskerPluginResultConditionSatisfied
import com.joaomgcd.taskerpluginlibrary.runner.TaskerPluginResultConditionUnsatisfied
import org.json.JSONObject

/**
 * What [WidgetMonitorService] hands to Tasker when a hosted widget changed.
 * Tasker passes this through to every enabled instance of the event, which is
 * why the payload describes the change completely: the runner, not the
 * service, decides whether a given event instance cares about it.
 */
@TaskerInputRoot
class WidgetUpdate @JvmOverloads constructor(
    @field:TaskerInputField("appwidgetid") var appWidgetId: Int = -1,
    @field:TaskerInputField("json") var json: String? = null,
    /** Flat path -> value map after the update. */
    @field:TaskerInputField("values") var values: String? = null,
    /** The same map before it, so a single element's change can be spotted. */
    @field:TaskerInputField("previousvalues") var previousValues: String? = null
)

@TaskerOutputObject
class WidgetUpdatedOutput(
    @get:TaskerOutputVariable("widget_value", labelResIdName = "widget_value", htmlLabelResIdName = "widget_value_description")
    var widgetValue: String?,
    @get:TaskerOutputVariable("widget_old_value", labelResIdName = "widget_old_value", htmlLabelResIdName = "widget_old_value_description")
    var widgetOldValue: String?,
    // An array type is what makes this a Tasker array (%widget_changed()):
    // the library keys off the getter's return type being an array
    @get:TaskerOutputVariable("widget_changed", labelResIdName = "widget_changed", htmlLabelResIdName = "widget_changed_description")
    var widgetChanged: Array<String>,
    @get:TaskerOutputVariable("widget_json", labelResIdName = "widget_json", htmlLabelResIdName = "widget_json_description")
    var widgetJson: String?
)

private fun String?.asValueMap(): JSONObject = try {
    if (isNullOrBlank()) JSONObject() else JSONObject(this)
} catch (e: Exception) {
    JSONObject()
}

private fun JSONObject.valueAt(path: String): String? =
    if (has(path)) optString(path, null) else null

/**
 * Puts the event's widget back into the monitor registry if it isn't there at
 * all - e.g. after the app's data was cleared while the Tasker profile
 * survived. Existence is checked by [WidgetActionInput.appWidgetId] alone: a
 * `WidgetActionInput` is re-deserialized by Tasker on every single query
 * (including the one Tasker sends back in response to our own fired event),
 * so its cosmetic fields (appName, widgetLabel) can drift a little from what
 * was originally stored without that meaning anything changed. Upserting on
 * that drift would replace a live entry the running monitor still holds a
 * reference to, which [WidgetMonitorService.sync] would then read as
 * "this widget needs to be re-hosted" and reset its change baseline - the
 * event would only ever catch every second change.
 */
private fun ensureMonitored(context: Context, input: WidgetActionInput) {
    if (input.appWidgetId == -1) return
    if (WidgetMonitorRegistry.entries(context).any { it.appWidgetId == input.appWidgetId }) return
    WidgetMonitorRegistry.upsert(
        context,
        WidgetMonitorRegistry.Entry(
            appWidgetId = input.appWidgetId,
            provider = input.provider,
            appName = input.appName,
            widgetLabel = input.widgetLabel,
            spanX = input.spanX,
            spanY = input.spanY
        )
    )
}

/** Element paths whose value differs between the two captures. */
private fun changedPaths(old: JSONObject, new: JSONObject): List<String> {
    val paths = LinkedHashSet<String>()
    new.keys().forEach { paths.add(it) }
    old.keys().forEach { paths.add(it) }
    return paths.filter { old.valueAt(it) != new.valueAt(it) }
}

/**
 * Fires when a monitored widget's content changes. If an element path is
 * configured, only changes to that one element count; if a regex is configured,
 * only changes to elements matching that regex count, otherwise any change to
 * the widget does.
 */
class WidgetUpdatedRunner :
    TaskerPluginRunnerConditionEvent<WidgetActionInput, WidgetUpdatedOutput, WidgetUpdate>() {

    override fun getSatisfiedCondition(
        context: Context,
        input: TaskerInput<WidgetActionInput>,
        update: WidgetUpdate?
    ): TaskerPluginResultCondition<WidgetUpdatedOutput> {
        // Only reached for queries carrying a pass-through id, i.e. ones this
        // app asked for - the library answers every other query without ever
        // calling here. So this repairs a registry that lost its entry, but it
        // cannot restart a dead service: no service, no events, no queries.
        // That job belongs to WidgetMonitorWatchdog.
        val regular = input.regular

        // Free-tier guard: only the most recently saved integration may fire.
        val isPremium = PlayBillingEntitlement.get(context).isPremiumCached()
        if (!TaskerConfigurationLimit.isConfigActive(context, isPremium, regular)) {
            return TaskerPluginResultConditionUnsatisfied()
        }

        ensureMonitored(context, regular)
        WidgetMonitorService.ensureRunning(context)

        if (update == null || update.appWidgetId != regular.appWidgetId) {
            return TaskerPluginResultConditionUnsatisfied()
        }

        val newValues = update.values.asValueMap()
        val oldValues = update.previousValues.asValueMap()
        val changed = changedPaths(oldValues, newValues)
        val selector = regular.query?.takeIf { it.isNotBlank() }

        if (selector != null) {
            if (selector.startsWith("/root/")) {
                if (!changed.contains(selector)) {
                    // Something else in the widget changed, not the watched element
                    return TaskerPluginResultConditionUnsatisfied()
                }

                return TaskerPluginResultConditionSatisfied(
                    context,
                    WidgetUpdatedOutput(
                        widgetValue = newValues.valueAt(selector),
                        widgetOldValue = oldValues.valueAt(selector),
                        widgetChanged = changed.toTypedArray(),
                        widgetJson = update.json ?: ""
                    )
                )
            }

            val query = TextQuery.parseRegexOnly(selector)
                ?: return TaskerPluginResultConditionUnsatisfied()

            val path = changed.firstOrNull { path ->
                newValues.valueAt(path)?.let { query.matches(it) } == true
            } ?: return TaskerPluginResultConditionUnsatisfied()

            return TaskerPluginResultConditionSatisfied(
                context,
                WidgetUpdatedOutput(
                    widgetValue = newValues.valueAt(path),
                    widgetOldValue = oldValues.valueAt(path),
                    widgetChanged = changed.toTypedArray(),
                    widgetJson = update.json ?: ""
                )
            )
        }

        return TaskerPluginResultConditionSatisfied(
            context,
            WidgetUpdatedOutput(
                widgetValue = "",
                widgetOldValue = "",
                widgetChanged = changed.toTypedArray(),
                widgetJson = update.json ?: ""
            )
        )
    }
}

class WidgetUpdatedHelper(config: TaskerPluginConfig<WidgetActionInput>) :
    TaskerPluginConfigHelper<WidgetActionInput, WidgetUpdatedOutput, WidgetUpdatedRunner>(config) {
    override val inputClass = WidgetActionInput::class.java
    override val outputClass = WidgetUpdatedOutput::class.java
    override val runnerClass = WidgetUpdatedRunner::class.java

    override fun addToStringBlurb(input: TaskerInput<WidgetActionInput>, blurbBuilder: StringBuilder) {
        val regular = input.regular
        blurbBuilder.appendWidgetBlurbHeader(context, regular)
        val selector = regular.query?.takeIf { it.isNotBlank() }
        if (selector != null) blurbBuilder.appendLine(context.getString(R.string.blurb_selector, selector))
        blurbBuilder.appendLine()
        blurbBuilder.append(
            if (selector != null) {
                context.getString(R.string.blurb_updated_element, selector, regular.widgetLabel, regular.appName)
            } else {
                context.getString(R.string.blurb_updated_any, regular.widgetLabel, regular.appName)
            }
        )
    }
}

class ActivityConfigWidgetUpdated : ActivityConfigWidgetActionBase() {
    override val taskerConfigurationType = TaskerConfigurationRegistry.Type.WIDGET_UPDATED
    override val queryLabelRes = R.string.label_selector_path_regex_optional
    override val queryRequired = false
    override val queryMustBePathOrRegex = true
    override fun isNodeSelectable(node: WidgetNode) = node.bestValue != null
    override fun queryValueForNodeLongPress(node: WidgetNode) = node.text ?: node.contentDescription
    override val helper by lazy { WidgetUpdatedHelper(this) }

    /**
     * An event needs its widget hosted permanently, so saving registers it
     * with the monitor and makes sure the service is up.
     */
    override fun onSavingForTasker(input: WidgetActionInput) {
        WidgetMonitorRegistry.upsert(
            this,
            WidgetMonitorRegistry.Entry(
                appWidgetId = input.appWidgetId,
                provider = input.provider,
                appName = input.appName,
                widgetLabel = input.widgetLabel,
                spanX = input.spanX,
                spanY = input.spanY
            )
        )
        WidgetMonitorRegistry.setEnabled(this, true)
        WidgetMonitorService.ensureRunning(this)
    }
}
