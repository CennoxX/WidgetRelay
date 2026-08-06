package com.cennoxx.widgetrelay.widget

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseExpandableListAdapter
import android.widget.ImageView
import android.widget.TextView
import com.cennoxx.widgetrelay.R

class WidgetExpandableAdapter(private val context: Context) : BaseExpandableListAdapter() {

    private var apps: List<AppWithWidgets> = emptyList()
    private val inflater = LayoutInflater.from(context)

    /** Size for the app-icon fallback, matching the app icon used elsewhere in this list. */
    private val iconFallbackSizePx =
        (56 * context.resources.displayMetrics.density).toInt()

    fun setData(apps: List<AppWithWidgets>) {
        this.apps = apps
        notifyDataSetChanged()
    }

    override fun getGroupCount() = apps.size
    override fun getChildrenCount(groupPosition: Int) = apps[groupPosition].widgets.size
    override fun getGroup(groupPosition: Int) = apps[groupPosition]
    override fun getChild(groupPosition: Int, childPosition: Int) =
        apps[groupPosition].widgets[childPosition]

    override fun getGroupId(groupPosition: Int) = groupPosition.toLong()
    override fun getChildId(groupPosition: Int, childPosition: Int) =
        (groupPosition * 10000 + childPosition).toLong()

    override fun hasStableIds() = false
    override fun isChildSelectable(groupPosition: Int, childPosition: Int) = true

    override fun getGroupView(
        groupPosition: Int,
        isExpanded: Boolean,
        convertView: View?,
        parent: ViewGroup
    ): View {
        val view = convertView ?: inflater.inflate(R.layout.item_widget_app, parent, false)
        val app = apps[groupPosition]

        view.findViewById<ImageView>(R.id.appIcon).setImageDrawable(app.icon)
        view.findViewById<TextView>(R.id.appName).text = app.appName
        view.findViewById<TextView>(R.id.widgetCount).text =
            if (app.widgets.size == 1) "1 Widget" else "${app.widgets.size} Widgets"
        view.findViewById<TextView>(R.id.expandIndicator).text = if (isExpanded) "▴" else "▾"

        return view
    }

    override fun getChildView(
        groupPosition: Int,
        childPosition: Int,
        isLastChild: Boolean,
        convertView: View?,
        parent: ViewGroup
    ): View {
        val view = convertView ?: inflater.inflate(R.layout.item_widget_widget, parent, false)
        val widget = apps[groupPosition].widgets[childPosition]

        view.findViewById<TextView>(R.id.widgetLabel).text = "${widget.label} ${widget.sizeText}"

        val descriptionView = view.findViewById<TextView>(R.id.widgetDescription)
        if (widget.description.isNullOrBlank()) {
            descriptionView.visibility = View.GONE
        } else {
            descriptionView.visibility = View.VISIBLE
            descriptionView.text = widget.description
        }

        // Not every widget ships a preview image - fall back to the app's icon
        // rather than leaving a blank gap where the preview would be
        val previewView = view.findViewById<ImageView>(R.id.widgetPreview)
        val fallbackIcon = apps[groupPosition].icon
        when {
            widget.preview != null -> {
                previewView.visibility = View.VISIBLE
                // A recycled row may have been sized for the icon fallback below
                previewView.layoutParams = previewView.layoutParams.apply {
                    width = ViewGroup.LayoutParams.WRAP_CONTENT
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                previewView.setImageDrawable(widget.preview)
            }
            fallbackIcon != null -> {
                previewView.visibility = View.VISIBLE
                // The icon has no widget aspect ratio to preserve - a fixed
                // size keeps it from ballooning to its full source resolution
                previewView.layoutParams = previewView.layoutParams.apply {
                    width = iconFallbackSizePx
                    height = iconFallbackSizePx
                }
                previewView.setImageDrawable(fallbackIcon)
            }
            else -> previewView.visibility = View.GONE
        }

        return view
    }
}
