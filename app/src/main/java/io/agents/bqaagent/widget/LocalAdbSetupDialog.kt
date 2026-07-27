// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.widget

import android.content.Context
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import io.agents.bqaagent.R

class LocalAdbSetupDialog private constructor(context: Context) : BottomSheetDialog(context) {

    companion object {
        const val DEFAULT_IP = "127.0.0.1"

        @JvmStatic
        fun show(
            context: Context,
            preset: Preset,
            onOpenWirelessDebugging: () -> Unit,
            onRefreshPorts: () -> Unit,
            onComplete: (Result) -> Unit,
        ): LocalAdbSetupDialog {
            return LocalAdbSetupDialog(context).apply {
                this.preset = preset
                this.onOpenWirelessDebugging = onOpenWirelessDebugging
                this.onRefreshPorts = onRefreshPorts
                this.onComplete = onComplete
                show()
            }
        }
    }

    data class Preset(
        val ip: String = DEFAULT_IP,
        val port: Int? = null,
        val connectPort: Int? = null,
        val portIsPairingPort: Boolean = false,
    )

    data class Result(
        val ip: String,
        val port: Int,
        val pairingCode: String?,
        val connectPort: Int?,
        val portIsPairingPort: Boolean,
        val portMatchesPreset: Boolean,
    )

    private var preset: Preset = Preset()
    private var onOpenWirelessDebugging: (() -> Unit)? = null
    private var onRefreshPorts: (() -> Unit)? = null
    private var onComplete: ((Result) -> Unit)? = null
    private var etIp: EditText? = null
    private var etPort: EditText? = null
    private var etPairingCode: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_local_adb_setup, null)
        setContentView(view)

        findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(context.getColor(R.color.colorBgPrimary))

        window?.apply {
            navigationBarColor = context.getColor(R.color.colorBgPrimary)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        initViews(view)
    }

    private fun initViews(view: View) {
        view.findViewById<ImageView>(R.id.btnClose).setOnClickListener { dismiss() }

        val etIp = view.findViewById<EditText>(R.id.etLocalAdbIp)
        val etPort = view.findViewById<EditText>(R.id.etLocalAdbPort)
        val etPairingCode = view.findViewById<EditText>(R.id.etLocalAdbPairingCode)
        val btnOpenWirelessDebugging = view.findViewById<KButton>(R.id.btnOpenWirelessDebugging)
        val btnRefreshPorts = view.findViewById<KButton>(R.id.btnRefreshPorts)
        val btnConfirm = view.findViewById<KButton>(R.id.btnConfirm)
        this.etIp = etIp
        this.etPort = etPort
        this.etPairingCode = etPairingCode

        updatePreset(preset, clearPairingCode = false)
        etPairingCode.filters = arrayOf(InputFilter.LengthFilter(6))

        bindFocusBackground(etIp, view.findViewById(R.id.ipContainer))
        bindFocusBackground(etPort, view.findViewById(R.id.portContainer))
        bindFocusBackground(etPairingCode, view.findViewById(R.id.pairingCodeContainer))

        etIp.imeOptions = EditorInfo.IME_ACTION_NEXT
        etPort.imeOptions = EditorInfo.IME_ACTION_NEXT
        etPairingCode.imeOptions = EditorInfo.IME_ACTION_DONE
        etIp.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        etPort.inputType = InputType.TYPE_CLASS_NUMBER
        etPairingCode.inputType = InputType.TYPE_CLASS_NUMBER

        etPairingCode.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                performConfirm(etIp, etPort, etPairingCode)
                true
            } else {
                false
            }
        }

        btnOpenWirelessDebugging.setOnClickListener {
            onOpenWirelessDebugging?.invoke()
        }

        btnRefreshPorts.setOnClickListener {
            onRefreshPorts?.invoke()
        }

        btnConfirm.setOnClickListener {
            performConfirm(etIp, etPort, etPairingCode)
        }

        etIp.setSelection(etIp.text.length)
        etIp.requestFocus()
    }

    fun updatePreset(newPreset: Preset, clearPairingCode: Boolean = true) {
        preset = newPreset
        etIp?.setText(newPreset.ip.ifBlank { DEFAULT_IP })
        etPort?.setText(newPreset.port?.toString().orEmpty())
        if (clearPairingCode) {
            etPairingCode?.text?.clear()
        }
    }

    private fun bindFocusBackground(editText: EditText, container: FrameLayout) {
        editText.setOnFocusChangeListener { _, hasFocus ->
            container.setBackgroundResource(
                if (hasFocus) R.drawable.bg_input_field_focused
                else R.drawable.bg_input_field
            )
        }
    }

    private fun performConfirm(etIp: EditText, etPort: EditText, etPairingCode: EditText) {
        val rawIp = etIp.text.toString().trim().ifBlank { DEFAULT_IP }
        val hostPort = parseHostPort(rawIp)
        val ip = hostPort?.first ?: rawIp
        val port = etPort.text.toString().trim().toIntOrNull() ?: hostPort?.second
        val pairingCode = etPairingCode.text.toString().trim().takeIf { it.isNotBlank() }

        if (port == null || port !in 1..65535) {
            Toast.makeText(context, R.string.local_adb_setup_invalid, Toast.LENGTH_SHORT).show()
            return
        }

        dismiss()
        onComplete?.invoke(
            Result(
                ip = ip,
                port = port,
                pairingCode = pairingCode,
                connectPort = preset.connectPort,
                portIsPairingPort = preset.portIsPairingPort,
                portMatchesPreset = preset.port == port,
            )
        )
    }

    private fun parseHostPort(value: String): Pair<String, Int>? {
        val separator = value.lastIndexOf(':')
        if (separator <= 0 || separator >= value.lastIndex - 1) return null
        val host = value.substring(0, separator).trim().removePrefix("[").removeSuffix("]")
        val port = value.substring(separator + 1).trim().toIntOrNull() ?: return null
        if (host.isBlank() || port !in 1..65535) return null
        return host to port
    }
}
