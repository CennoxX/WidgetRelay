package com.cennoxx.widgetrelay.widget

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.cennoxx.widgetrelay.R
import com.cennoxx.widgetrelay.applyEdgeToEdgeInsets

class ActivityAbout : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        applyEdgeToEdgeInsets()

        findViewById<TextView>(R.id.versionText).text =
            getString(
                R.string.about_version,
                packageManager.getPackageInfo(packageName, 0).versionName
            )

        val noticeHeaderRow = findViewById<LinearLayout>(R.id.noticeHeaderRow)
        val noticeChevron = findViewById<TextView>(R.id.noticeChevron)
        val noticeText = findViewById<TextView>(R.id.noticeText)

        try {
            noticeText.text = resources.openRawResource(R.raw.notice).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        noticeHeaderRow.setOnClickListener {
            val expanded = noticeText.visibility != View.VISIBLE
            noticeText.visibility = if (expanded) View.VISIBLE else View.GONE
            noticeChevron.text = if (expanded) "▴" else "▾"
        }

        val licenseHeaderRow = findViewById<LinearLayout>(R.id.licenseHeaderRow)
        val licenseChevron = findViewById<TextView>(R.id.licenseChevron)
        val licenseText = findViewById<TextView>(R.id.licenseText)

        try {
            licenseText.text = resources.openRawResource(R.raw.license).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        licenseHeaderRow.setOnClickListener {
            val expanded = licenseText.visibility != View.VISIBLE
            licenseText.visibility = if (expanded) View.VISIBLE else View.GONE
            licenseChevron.text = if (expanded) "▴" else "▾"
        }
    }
}