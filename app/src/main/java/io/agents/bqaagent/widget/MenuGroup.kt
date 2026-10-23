// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.widget

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.google.android.material.card.MaterialCardView
import androidx.core.view.isVisible
import io.agents.bqaagent.R

/**
 * Menu group component - square card container with a title
 */
class MenuGroup @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val tvTitle: TextView
    private val cardContainer: MaterialCardView
    private val itemsContainer: LinearLayout

    /**
     * One collapsible section: a header row whose click toggles every item inserted while the
     * section was open. Multiple named sections can coexist in a group; insertion-time membership
     * is decided by which section is currently open, so persisted state survives re-creation.
     */
    private inner class CollapsibleSection(
        val id: String,
        val header: MenuItem,
        val items: MutableList<MenuItem> = mutableListOf(),
        var expanded: Boolean = false,
    )

    private val sections = mutableMapOf<String, CollapsibleSection>()
    private var latestSectionId: String? = null
    private var openSection: CollapsibleSection? = null

    // Indentation for rows inside a collapsible section: shift the whole content (icon, title,
    // divider) right of the section header so the parent-child relation reads visually.
    private val collapsibleIndentPx: Int =
        (resources.displayMetrics.density * 20).toInt()

    init {
        LayoutInflater.from(context).inflate(R.layout.widget_menu_group, this, true)
        tvTitle = findViewById(R.id.tvTitle)
        cardContainer = findViewById(R.id.cardContainer)
        itemsContainer = findViewById(R.id.itemsContainer)
    }

    /**
     * Set title; hides the title when null or empty
     */
    fun setTitle(title: CharSequence?) {
        tvTitle.isVisible = !title.isNullOrEmpty()
        tvTitle.text = title
    }

    /**
     * Set title text color
     */
    fun setTitleColor(color: Int) {
        tvTitle.setTextColor(color)
    }

    /**
     * Set card background color
     */
    fun setCardBackgroundColor(color: Int) {
        cardContainer.setCardBackgroundColor(color)
    }

    /**
     * Add a menu item
     */
    fun addMenuItem(item: MenuItem) {
        itemsContainer.addView(item)
    }

    /**
     * Add a menu item (with configuration). Items added while a collapsible section is open
     * join that section automatically.
     */
    fun addMenuItem(
        leadingIcon: Int,
        title: String,
        onClick: () -> Unit,
        subtitle: String? = null,
        trailingText: String? = null,
        trailingIcon: Int? = null,
        showTrailingIcon: Boolean = true,
        showDivider: Boolean = true,
        onInfoClick: (() -> Unit)? = null,
    ): MenuItem {
        val item = MenuItem(context).apply {
            setLeadingIcon(leadingIcon)
            setTitle(title)
            setSubtitle(subtitle)
            setOnClickListener { onClick() }
            trailingText?.let { setTrailingText(it) }
            trailingIcon?.let { setTrailingIcon(it) }
            setShowTrailingIcon(showTrailingIcon)
            setShowDivider(showDivider)
            if (onInfoClick != null) {
                setInfoButtonVisible(true)
                setOnInfoClickListener { onInfoClick() }
            }
        }
        val section = openSection
        if (section != null) {
            // Apply the section state at insertion time: callers may restore the persisted
            // expanded state BEFORE the items exist, so visibility must follow the section here.
            item.isVisible = section.expanded
            item.setPadding(
                collapsibleIndentPx,
                item.paddingTop,
                item.paddingRight,
                item.paddingBottom
            )
            section.items.add(item)
        }
        itemsContainer.addView(item)
        return item
    }

    /**
     * Starts a named collapsible section: appends a header row whose click toggles the
     * visibility of every item added until the section is closed. Opening a new section
     * closes the previous one.
     */
    fun beginCollapsibleSection(
        sectionId: String,
        title: String,
        @DrawableRes headerIcon: Int,
        onToggle: (Boolean) -> Unit = {},
    ) {
        val header = MenuItem(context).apply {
            setLeadingIcon(headerIcon)
            setTitle(title)
            setShowDivider(true)
            setTrailingIcon(R.drawable.ic_chevron_up)
        }
        val section = CollapsibleSection(sectionId, header)
        header.setOnClickListener {
            setSectionExpanded(sectionId, !section.expanded)
            onToggle(section.expanded)
        }
        itemsContainer.addView(header)
        sections[sectionId] = section
        latestSectionId = sectionId
        openSection = section
    }

    /** Close the currently open section; later items are plain rows again. */
    fun endCollapsibleSection() {
        openSection = null
    }

    fun setSectionExpanded(sectionId: String, expanded: Boolean) {
        val section = sections[sectionId] ?: return
        section.expanded = expanded
        section.items.forEach { it.isVisible = expanded }
        section.header.setTrailingIcon(
            if (expanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down
        )
    }

    /** Collapsed-state summary for a named section, e.g. "⚠ 1 to enable" / "All set ✓". */
    fun setSectionSummary(sectionId: String, text: String, warning: Boolean) {
        val section = sections[sectionId] ?: return
        section.header.setTrailingText(text)
        section.header.setTrailingTextColor(
            if (warning) context.getColor(R.color.colorErrorPrimary)
            else context.getColor(R.color.colorTextSecondary)
        )
    }

    // ---- Single-section conveniences (the most recently opened section) ----

    fun beginCollapsibleSection(
        title: String,
        @DrawableRes headerIcon: Int,
        onToggle: (Boolean) -> Unit,
    ) {
        beginCollapsibleSection("__latest__", title, headerIcon, onToggle)
    }

    fun setSectionExpanded(expanded: Boolean) {
        latestSectionId?.let { setSectionExpanded(it, expanded) }
    }

    fun setSectionSummary(text: String, warning: Boolean) {
        latestSectionId?.let { setSectionSummary(it, text, warning) }
    }

    /**
     * Remove all menu items
     */
    fun clearMenuItems() {
        itemsContainer.removeAllViews()
    }

    /**
     * Get menu item count
     */
    fun getMenuItemCount(): Int = itemsContainer.childCount

    /**
     * Get menu item at given index
     */
    fun getMenuItemAt(index: Int): MenuItem? {
        return itemsContainer.getChildAt(index) as? MenuItem
    }
}
