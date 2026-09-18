// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.widget

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.gson.JsonElement
import io.agents.bqaagent.R

class SkillDetailDialog private constructor(context: Context) : Dialog(context, R.style.DialogStyle) {

    private var title: String = ""
    private var jsonContent: String = ""
    private var root: JsonElement? = null
    private var arrayItemBadge: ((arrayKey: String, index: Int) -> String?)? = null
    private var showingJson: Boolean = false

    companion object {
        @JvmStatic
        fun show(
            context: Context,
            title: String,
            jsonContent: String,
            root: JsonElement,
            arrayItemBadge: ((arrayKey: String, index: Int) -> String?)? = null
        ): SkillDetailDialog {
            return SkillDetailDialog(context).apply {
                this.title = title
                this.jsonContent = jsonContent
                this.root = root
                this.arrayItemBadge = arrayItemBadge
                show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_skill_detail)

        setCancelable(true)
        setCanceledOnTouchOutside(true)

        window?.apply {
            setGravity(Gravity.CENTER)
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(
                (context.resources.displayMetrics.widthPixels * 0.9).toInt(),
                (context.resources.displayMetrics.heightPixels * 0.8).toInt()
            )
        }

        findViewById<TextView>(R.id.tvTitle).text = title

        val scrollList = findViewById<View>(R.id.scrollList)
        val scrollJson = findViewById<View>(R.id.scrollJson)
        val contentList = findViewById<LinearLayout>(R.id.contentList)
        val tvContent = findViewById<TextView>(R.id.tvContent)
        tvContent.text = jsonContent

        val viewSwitch = findViewById<FrameLayout>(R.id.viewSwitch)
        val viewThumb = findViewById<View>(R.id.viewThumb)
        val tvModeList = findViewById<TextView>(R.id.tvModeList)
        val tvModeJson = findViewById<TextView>(R.id.tvModeJson)
        val activeColor = context.getColor(R.color.colorTextPrimary)
        val inactiveColor = context.getColor(R.color.colorTextTertiary)
        var thumbTravel = 0f
        var listBuilt = false

        fun updateSwitch(animated: Boolean) {
            val target = if (showingJson) thumbTravel else 0f
            if (animated) {
                viewThumb.animate().translationX(target).setDuration(160).start()
            } else {
                viewThumb.translationX = target
            }
            tvModeList.setTextColor(if (!showingJson) activeColor else inactiveColor)
            tvModeJson.setTextColor(if (showingJson) activeColor else inactiveColor)
            tvModeList.setTypeface(null, if (!showingJson) Typeface.BOLD else Typeface.NORMAL)
            tvModeJson.setTypeface(null, if (showingJson) Typeface.BOLD else Typeface.NORMAL)
        }

        fun applyView(animated: Boolean) {
            if (!showingJson && !listBuilt) {
                root?.let { contentList.addView(DetailContentRenderer.buildView(context, it, arrayItemBadge)) }
                listBuilt = true
            }
            scrollList.visibility = if (showingJson) View.GONE else View.VISIBLE
            scrollJson.visibility = if (showingJson) View.VISIBLE else View.GONE
            updateSwitch(animated)
        }

        viewSwitch.post {
            val lp = viewThumb.layoutParams as FrameLayout.LayoutParams
            lp.width = viewSwitch.width / 2 - lp.leftMargin - lp.rightMargin
            viewThumb.layoutParams = lp
            thumbTravel = (viewSwitch.width / 2).toFloat()
            updateSwitch(animated = false)
        }

        viewSwitch.setOnClickListener {
            showingJson = !showingJson
            applyView(animated = true)
        }

        applyView(animated = false)

        findViewById<KButton>(R.id.btnClose).apply {
            text = "Close"
            setBgColor(context.getColor(R.color.colorContainerBase))
            setTextColor(context.getColor(R.color.colorTextSecondary))
            setBorderColor(context.getColor(R.color.colorBorderBase))
            setOnClickListener { dismiss() }
        }
    }
}