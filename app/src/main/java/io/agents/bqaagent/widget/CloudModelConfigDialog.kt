// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.widget

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import io.agents.bqaagent.R

/**
 * Reusable cloud model config dialog shared by LLM and VLM settings.
 * Only "Custom" style config is supported (Title / API Key / Base URL / Model Name).
 * The Save button stays disabled until every field is non-empty.
 *
 * Add mode:  show(context, preset = null) { input -> ... }
 * Edit mode: show(context, preset = CloudModelInput(...)) { input -> ... }
 */
class CloudModelConfigDialog private constructor(context: Context) : BottomSheetDialog(context) {

    data class CloudModelInput(
        val title: String,
        val apiKey: String,
        val baseUrl: String,
        val modelName: String
    )

    companion object {
        @JvmStatic
        fun show(
            context: Context,
            dialogTitle: String,
            preset: CloudModelInput? = null,
            onSave: (CloudModelInput) -> Unit
        ): CloudModelConfigDialog {
            return CloudModelConfigDialog(context).apply {
                this.dialogTitle = dialogTitle
                this.preset = preset
                this.onSave = onSave
                show()
            }
        }
    }

    private var dialogTitle: String = "Cloud Model Config"
    private var preset: CloudModelInput? = null
    private var onSave: ((CloudModelInput) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_cloud_model_config, null)
        setContentView(view)

        findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(context.getColor(R.color.colorBgPrimary))

        window?.apply {
            navigationBarColor = context.getColor(R.color.colorBgPrimary)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        findViewById<NestedScrollView>(R.id.scrollView)
            ?.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                false
            }

        findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
            BottomSheetBehavior.from(sheet).apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
                isFitToContents = true
            }
        }

        initViews(view)
    }

    private fun initViews(view: View) {
        view.findViewById<TextView>(R.id.tvTitle).text = dialogTitle
        view.findViewById<ImageView>(R.id.btnClose).setOnClickListener { dismiss() }

        val etTitle = view.findViewById<EditText>(R.id.etTitle)
        val etApiKey = view.findViewById<EditText>(R.id.etApiKey)
        val etBaseUrl = view.findViewById<EditText>(R.id.etBaseUrl)
        val etModelName = view.findViewById<EditText>(R.id.etModelName)
        val btnSave = view.findViewById<KButton>(R.id.btnSave)
        val btnToggleApiKey = view.findViewById<ImageView>(R.id.btnToggleApiKey)

        bindFocusBackground(etTitle, view.findViewById(R.id.titleContainer))
        bindFocusBackground(etApiKey, view.findViewById(R.id.apiKeyContainer))
        bindFocusBackground(etBaseUrl, view.findViewById(R.id.baseUrlContainer))
        bindFocusBackground(etModelName, view.findViewById(R.id.modelNameContainer))

        var apiKeyVisible = false
        btnToggleApiKey.setOnClickListener {
            apiKeyVisible = !apiKeyVisible
            val cursor = etApiKey.selectionEnd
            if (apiKeyVisible) {
                etApiKey.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                btnToggleApiKey.setImageResource(R.drawable.ic_visibility_on)
            } else {
                etApiKey.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                btnToggleApiKey.setImageResource(R.drawable.ic_visibility_off)
            }
            if (cursor >= 0) etApiKey.setSelection(cursor.coerceAtMost(etApiKey.text.length))
        }

        preset?.let {
            etTitle.setText(it.title)
            etApiKey.setText(it.apiKey)
            etBaseUrl.setText(it.baseUrl)
            etModelName.setText(it.modelName)
        }

        fun updateSaveState() {
            val ok = etTitle.text.isNotBlank() && etApiKey.text.isNotBlank() &&
                    etBaseUrl.text.isNotBlank() && etModelName.text.isNotBlank()
            btnSave.isEnabled = ok
            btnSave.alpha = if (ok) 1f else 0.4f
        }
        listOf(etTitle, etApiKey, etBaseUrl, etModelName).forEach {
            it.doAfterTextChanged { updateSaveState() }
        }
        updateSaveState()

        btnSave.setOnClickListener {
            if (!btnSave.isEnabled) return@setOnClickListener
            val input = CloudModelInput(
                title = etTitle.text.toString().trim(),
                apiKey = etApiKey.text.toString().trim(),
                baseUrl = etBaseUrl.text.toString().trim(),
                modelName = etModelName.text.toString().trim()
            )
            dismiss()
            onSave?.invoke(input)
        }

        etTitle.requestFocus()
    }

    private fun bindFocusBackground(editText: EditText, container: FrameLayout) {
        editText.setOnFocusChangeListener { _, hasFocus ->
            container.setBackgroundResource(
                if (hasFocus) R.drawable.bg_input_field_focused
                else R.drawable.bg_input_field
            )
        }
    }
}