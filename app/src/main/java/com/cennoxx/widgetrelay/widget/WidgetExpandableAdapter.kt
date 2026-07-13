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

        val previewView = view.findViewById<ImageView>(R.id.widgetPreview)
        if (widget.preview != null) {
            previewView.visibility = View.VISIBLE
            previewView.setImageDrawable(widget.preview)
        } else {
            previewView.visibility = View.GONE
        }

        return view
    }
}
