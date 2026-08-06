package com.cennoxx.widgetrelay.widget

import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns the flat, breadth-first node list from [WidgetExtractor] into the
 * shapes the plugin needs: a nested JSON tree for output, and a flat
 * path -> value map used to detect *what* changed between two captures.
 */

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

/**
 * Rebuilds the parent/child structure from the nodes' paths. Returns null if
 * there is no root, i.e. nothing was extracted.
 */
fun List<WidgetNode>.toJsonTree(): JSONObject? {
    if (isEmpty()) return null

    // Insertion order is the extractor's breadth-first order, so parents are
    // always added before their children and siblings keep their order
    val jsonByPath = LinkedHashMap<String, JSONObject>(size)
    for (node in this) {
        jsonByPath[node.pathInTree] = node.toJson()
    }

    val root = jsonByPath["/root"] ?: return null

    for ((path, json) in jsonByPath) {
        if (path == "/root") continue
        val parentPath = path.substring(0, path.lastIndexOf('/'))
        val parentJson = jsonByPath[parentPath] ?: continue
        val children = parentJson.optJSONArray("children") ?: JSONArray().also {
            parentJson.put("children", it)
        }
        children.put(json)
    }

    return root
}

/**
 * Flat map of element path to its value, e.g. {"/root/0/1": "2 new messages"}.
 * This is what the monitor compares between captures: it is small, cheap to
 * diff, and lets an event tell which single element changed.
 */
fun List<WidgetNode>.toValueMap(): JSONObject {
    val values = JSONObject()
    for (node in this) {
        node.bestValue?.let { values.put(node.pathInTree, it) }
    }
    return values
}
