package com.cennoxx.widgetrelay

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Pads the activity's content root so it is not covered by the status or
 * navigation bar (edge-to-edge is enforced when targeting SDK 35+).
 */
fun Activity.applyEdgeToEdgeInsets() {
    val root = findViewById<ViewGroup>(android.R.id.content).getChildAt(0) ?: return
    val baseTop = root.paddingTop
    val baseBottom = root.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(root) { view: View, insets: WindowInsetsCompat ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.setPadding(
            view.paddingLeft,
            baseTop + bars.top,
            view.paddingRight,
            baseBottom + bars.bottom
        )
        insets
    }
}
