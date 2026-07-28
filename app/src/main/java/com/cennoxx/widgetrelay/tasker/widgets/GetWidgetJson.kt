package com.cennoxx.widgetrelay.tasker.widgets

import android.content.Context
import com.cennoxx.widgetrelay.R
import com.cennoxx.widgetrelay.widget.WidgetNode
import com.joaomgcd.taskerpluginlibrary.action.TaskerPluginRunnerAction
import com.joaomgcd.taskerpluginlibrary.config.TaskerPluginConfig
import com.joaomgcd.taskerpluginlibrary.config.TaskerPluginConfigHelper
import com.joaomgcd.taskerpluginlibrary.input.TaskerInput
import com.joaomgcd.taskerpluginlibrary.output.TaskerOutputObject
import com.joaomgcd.taskerpluginlibrary.output.TaskerOutputVariable
import com.joaomgcd.taskerpluginlibrary.runner.TaskerPluginResult
import com.joaomgcd.taskerpluginlibrary.runner.TaskerPluginResultErrorWithOutput
import com.joaomgcd.taskerpluginlibrary.runner.TaskerPluginResultSucess
import org.json.JSONArray
import org.json.JSONObject

private const val ERROR_NOT_BOUND = 1
private const val ERROR_NO_TREE = 2
private const val ERROR_NO_OVERLAY = 3

@TaskerOutputObject
class WidgetJsonOutput(
    @get:TaskerOutputVariable("widget_json", labelResIdName = "widget_json", htmlLabelResIdName = "widget_json_description")
    var widgetJson: String
)

private fun WidgetNode.toJson(): JSONObject {
    val obj = JSONObject().apply {
        put("type", className)
        putOpt("id", resourceIdName)
        putOpt("text", text)
        putOpt("description", contentDescription)
    }
    if (childCount > 0) {
        obj.put("children", JSONArray())
    }
    return obj
}

private fun buildNodeTree(flatNodes: List<WidgetNode>): JSONObject? {
    if (flatNodes.isEmpty()) return null

    val nodeMap = mutableMapOf<String, Pair<WidgetNode, JSONObject>>()

    for (node in flatNodes) {
        val jsonObj = node.toJson()
        nodeMap[node.pathInTree] = node to jsonObj
    }

    val root = nodeMap["/root"]?.second ?: return null

    for ((path, nodeEntry) in nodeMap) {
        if (path == "/root") continue

        val lastSlash = path.lastIndexOf('/')
        val parentPath = path.substring(0, lastSlash)
        val parentJson = nodeMap[parentPath]?.second ?: continue

        val childrenArray = parentJson.optJSONArray("children") ?: JSONArray().also {
            parentJson.put("children", it)
        }
        val (node, jsonObj) = nodeEntry
        childrenArray.put(jsonObj)
    }

    return root
}

/** Gets all extracted widget data (every element's text, description, etc.) as a nested JSON tree. */
class GetWidgetJsonRunner : TaskerPluginRunnerAction<WidgetActionInput, WidgetJsonOutput>() {
    override fun run(context: Context, input: TaskerInput<WidgetActionInput>): TaskerPluginResult<WidgetJsonOutput> {
        if (!WidgetActionRuntime.hasOverlayPermission(context)) {
            return TaskerPluginResultErrorWithOutput(
                ERROR_NO_OVERLAY,
                context.getString(R.string.error_no_overlay)
            )
        }
        val nodes = WidgetActionRuntime.captureNodes(context, input.regular)
            ?: return TaskerPluginResultErrorWithOutput(
                ERROR_NOT_BOUND,
                context.getString(R.string.error_not_bound, input.regular.widgetLabel ?: input.regular.appWidgetId)
            )
        val jsonRoot = buildNodeTree(nodes) ?: return TaskerPluginResultErrorWithOutput(
            ERROR_NO_TREE,
            context.getString(R.string.error_no_tree)
        )
        return TaskerPluginResultSucess(WidgetJsonOutput(jsonRoot.toString()))
    }
}

class GetWidgetJsonHelper(config: TaskerPluginConfig<WidgetActionInput>) :
    TaskerPluginConfigHelper<WidgetActionInput, WidgetJsonOutput, GetWidgetJsonRunner>(config) {
    override val inputClass = WidgetActionInput::class.java
    override val outputClass = WidgetJsonOutput::class.java
    override val runnerClass = GetWidgetJsonRunner::class.java

    override fun addToStringBlurb(input: TaskerInput<WidgetActionInput>, blurbBuilder: StringBuilder) {
        val regular = input.regular
        blurbBuilder.appendWidgetBlurbHeader(context, regular)
        blurbBuilder.appendLine()
        blurbBuilder.append(context.getString(R.string.blurb_get_json, regular.widgetLabel, regular.appName))
    }
}

class ActivityConfigGetWidgetJson : ActivityConfigWidgetActionBase() {
    override val helper by lazy { GetWidgetJsonHelper(this) }
}
