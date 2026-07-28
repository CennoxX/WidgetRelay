package com.cennoxx.widgetrelay.widget

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ImageView
import android.widget.TextView
import java.util.LinkedList

class WidgetExtractor(private val context: Context) {

    fun extractFromRemoteViews(hostView: AppWidgetHostView?): List<WidgetNode> {
        if (hostView == null) return emptyList()

        val nodes = mutableListOf<WidgetNode>()
        val queue = LinkedList<Triple<View, String, Boolean>>()

        queue.add(Triple(hostView, "/root", hostView.hasOnClickListeners()))

        while (queue.isNotEmpty()) {
            val (view, path, clickable) = queue.removeFirst()
            val node = createNodeFromView(view, path, clickable)
            nodes.add(node)

            if (view is ViewGroup) {
                repeat(view.childCount) { index ->
                    val child = view.getChildAt(index)
                    if (child != null) {
                        val childPath = "$path/$index"
                        // A click is dispatched to the nearest ancestor that handles
                        // it; collection rows are clicked through their AdapterView
                        val childClickable = clickable ||
                            child.hasOnClickListeners() ||
                            view is AdapterView<*>
                        queue.add(Triple(child, childPath, childClickable))
                    }
                }
            }
        }

        return nodes
    }

    private fun createNodeFromView(view: View, path: String, clickable: Boolean): WidgetNode {
        val className = view::class.simpleName ?: "Unknown"
        val resourceId = view.id
        val resourceIdName = getResourceIdName(resourceId)

        val text = when (view) {
            is TextView -> view.text?.toString()
            else -> null
        }

        val contentDescription = when (view) {
            is ImageView -> view.contentDescription?.toString()
            else -> null
        }

        val childCount = if (view is ViewGroup) view.childCount else 0

        val bestValue = text ?: contentDescription ?: resourceIdName

        return WidgetNode(
            className = className,
            resourceId = resourceId.takeIf { it != View.NO_ID }?.toString(),
            resourceIdName = resourceIdName,
            pathInTree = path,
            text = text,
            contentDescription = contentDescription,
            childCount = childCount,
            bestValue = bestValue,
            clickable = clickable
        )
    }

    private fun getResourceIdName(id: Int): String? {
        return if (id != View.NO_ID) {
            try {
                context.resources.getResourceEntryName(id)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }
}
