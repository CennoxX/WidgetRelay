package com.cennoxx.widgetrelay.widget

import android.appwidget.AppWidgetProviderInfo
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class WidgetAdapter(
    private val onWidgetClick: (AppWidgetProviderInfo) -> Unit
) : RecyclerView.Adapter<WidgetAdapter.WidgetViewHolder>() {

    private var widgets = listOf<AppWidgetProviderInfo>()

    fun submitList(newWidgets: List<AppWidgetProviderInfo>) {
        widgets = newWidgets
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WidgetViewHolder {
        val textView = TextView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                120
            )
            setPadding(16, 16, 16, 16)
            setBackgroundColor(0xFFE0E0E0.toInt())
            isClickable = true
            isFocusable = true
        }
        return WidgetViewHolder(textView)
    }

    override fun onBindViewHolder(holder: WidgetViewHolder, position: Int) {
        val widget = widgets[position]
        holder.bind(widget, onWidgetClick)
    }

    override fun getItemCount() = widgets.size

    class WidgetViewHolder(private val textView: TextView) : RecyclerView.ViewHolder(textView) {
        fun bind(widget: AppWidgetProviderInfo, onClick: (AppWidgetProviderInfo) -> Unit) {
            textView.text = "${widget.provider.packageName}\n${widget.provider.className}"
            textView.setOnClickListener { onClick(widget) }
        }
    }
}
