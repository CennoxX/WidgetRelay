package com.cennoxx.widgetrelay.widget

data class WidgetNode(
    val className: String,
    val resourceId: String? = null,
    val resourceIdName: String? = null,
    val pathInTree: String,
    val text: String? = null,
    val contentDescription: String? = null,
    val childCount: Int = 0,
    val bestValue: String? = null,
    /** True if this element or one of its ancestors reacts to a click. */
    val clickable: Boolean = false
) {
    override fun toString(): String {
        return "WidgetNode(class=$className, id=$resourceIdName, path=$pathInTree, text=$text, desc=$contentDescription, value=$bestValue)"
    }
}
