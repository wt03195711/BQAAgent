// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.widget

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.view.isVisible
import io.agents.bqaagent.R

/**
 * Menu item component - clickable row with leading icon, title, and trailing text/arrow
 */
class MenuItem @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val ivLeading: ImageView
    private val tvTitle: TextView
    private val tvSubtitle: TextView
    private val viewRedDot: View
    private val tvTrailing: TextView
    private val ivTrailing: ImageView
    private val ivInfo: ImageView
    private val divider: View

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.widget_menu_item, this, true)

        ivLeading = findViewById(R.id.ivLeading)
        tvTitle = findViewById(R.id.tvTitle)
        tvSubtitle = findViewById(R.id.tvSubtitle)
        viewRedDot = findViewById(R.id.viewRedDot)
        tvTrailing = findViewById(R.id.tvTrailing)
        ivTrailing = findViewById(R.id.ivTrailing)
        ivInfo = findViewById(R.id.ivInfo)
        divider = findViewById(R.id.divider)

        // Show trailing arrow by default
        setShowTrailingIcon(true)
    }

    /**
     * Set leading icon
     */
    fun setLeadingIcon(@DrawableRes iconRes: Int) {
        ivLeading.setImageResource(iconRes)
    }

    /**
     * Set leading icon color
     */
    fun setLeadingIconColor(color: Int) {
        ivLeading.setColorFilter(color)
    }

    /**
     * Set title
     */
    fun setTitle(title: CharSequence) {
        tvTitle.text = title
    }

    /**
     * Set subtitle; hides the row when null or blank so item heights stay unchanged
     * for items without a description.
     */
    fun setSubtitle(subtitle: CharSequence?) {
        tvSubtitle.isVisible = !subtitle.isNullOrEmpty()
        tvSubtitle.text = subtitle
    }

    fun setSubtitleColor(color: Int) {
        tvSubtitle.setTextColor(color)
    }

    /**
     * Set title text color
     */
    fun setTitleColor(color: Int) {
        tvTitle.setTextColor(color)
    }

    /**
     * Set trailing text
     */
    fun setTrailingText(text: CharSequence?) {
        tvTrailing.isVisible = !text.isNullOrEmpty()
        tvTrailing.text = text
    }

    /**
     * Set trailing text color
     */
    fun setTrailingTextColor(color: Int) {
        tvTrailing.setTextColor(color)
    }

    /**
     * Set trailing icon
     */
    fun setTrailingIcon(@DrawableRes iconRes: Int) {
        ivTrailing.setImageResource(iconRes)
    }

    /**
     * Set trailing icon color
     */
    fun setTrailingIconColor(color: Int) {
        ivTrailing.setColorFilter(color)
    }

    /**
     * Set whether to show trailing icon
     */
    fun setShowTrailingIcon(show: Boolean) {
        ivTrailing.visibility = if (show) View.VISIBLE else View.GONE
        val lp = tvTrailing.layoutParams as MarginLayoutParams
        lp.marginEnd = if (show) 0 else TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_PT, 8f, resources.displayMetrics
        ).toInt()
        tvTrailing.layoutParams = lp
    }

    /**
     * Set whether to show divider
     */
    fun setShowDivider(show: Boolean) {
        divider.isVisible = show
    }

    /**
     * Get trailing text
     */
    fun getTrailingText(): CharSequence? {
        return tvTrailing.text
    }

    /**
     * Set whether to show red dot
     */
    fun setShowRedDot(show: Boolean) {
        viewRedDot.visibility = if (show) View.VISIBLE else View.GONE
    }

    /**
     * Show/hide the inline info button. Hidden by default so rows without a guide stay unchanged.
     */
    fun setInfoButtonVisible(visible: Boolean) {
        ivInfo.isVisible = visible
    }

    /**
     * Attach a click handler to the info button. The button consumes its own taps, so this never
     * fires the row's onClick.
     */
    fun setOnInfoClickListener(listener: OnClickListener?) {
        ivInfo.setOnClickListener(listener)
    }

    /**
     * Content description for the info button (set by callers so the generic widget stays
     * free of feature-specific strings).
     */
    fun setInfoButtonContentDescription(description: CharSequence?) {
        ivInfo.contentDescription = description
    }

    /**
     * Get title text
     */
    fun getTitle(): CharSequence? {
        return tvTitle.text
    }
}
