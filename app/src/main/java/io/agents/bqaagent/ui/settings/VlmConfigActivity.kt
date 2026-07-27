// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.ui.settings

import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import io.agents.bqaagent.R
import io.agents.bqaagent.agent.VlmConfigRepository
import io.agents.bqaagent.base.BaseActivity
import io.agents.bqaagent.ui.chat.ThemeManager
import io.agents.bqaagent.widget.CommonToolbar
import io.agents.bqaagent.widget.KButton
import java.util.concurrent.Executors
import kotlin.math.max
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Configuration page for VLM (Vision Language Model).
 *
 * Simplified compared to [LlmConfigActivity]:
 * - No provider switching (always Custom/OpenAI-compatible)
 * - No local model management
 * - Only 3 fields: API Key, Base URL, Model Name
 * - Test connection + Save
 */
class VlmConfigActivity : BaseActivity() {

    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vlm_config)

        val tc = ThemeManager.getColors()
        window.statusBarColor = tc.toolbarBg
        window.decorView.setBackgroundColor(tc.bg)
        val contentFrame = findViewById<android.view.ViewGroup>(android.R.id.content)
        contentFrame?.setBackgroundColor(tc.bg)
        (contentFrame?.getChildAt(0) as? View)?.setBackgroundColor(tc.bg)

        // Toolbar
        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle("Vision Model")
            showBackButton(true) { finish() }
            setBackgroundColor(tc.toolbarBg)
            setTitleColor(tc.aiText)
            findViewById<android.widget.ImageView>(R.id.ivBack)?.setColorFilter(tc.aiText)
        }

        // Views
        val tvStatus = findViewById<TextView>(R.id.tvVlmStatus)
        val tvStatusMeta = findViewById<TextView>(R.id.tvVlmStatusMeta)
        val etApiKey = findViewById<EditText>(R.id.etVlmApiKey)
        val etBaseUrl = findViewById<EditText>(R.id.etVlmBaseUrl)
        val etModelName = findViewById<EditText>(R.id.etVlmModelName)
        val tvConnectionStatus = findViewById<TextView>(R.id.tvVlmConnectionStatus)
        val btnTest = findViewById<TextView>(R.id.btnVlmTestConnection)
        val btnSave = findViewById<KButton>(R.id.btnVlmSave)
        val btnClear = findViewById<TextView>(R.id.btnVlmClearApiKey)
        val scrollView = findViewById<ScrollView>(R.id.scrollVlmContent)

        // Apply theme colors
        tvStatus.setTextColor(tc.aiText)
        tvStatusMeta.setTextColor(tc.toolDefault)

        // Load current config
        val currentConfig = VlmConfigRepository.load()
        etApiKey.setText(currentConfig.apiKey)
        etBaseUrl.setText(currentConfig.baseUrl)
        etModelName.setText(currentConfig.modelName)

        // Update status card
        updateStatusCard(tvStatus, tvStatusMeta, tc, currentConfig.isConfigured)

        // Clear API key
        btnClear.setOnClickListener {
            etApiKey.setText("")
            Toast.makeText(this, "API key cleared", Toast.LENGTH_SHORT).show()
        }

        // Keyboard-aware scrolling
        installKeyboardAwareScrolling(scrollView, listOf(etApiKey, etBaseUrl, etModelName))

        // Test Connection
        btnTest.setOnClickListener {
            val apiKey = etApiKey.text.toString().trim()
            val baseUrl = etBaseUrl.text.toString().trim()
            val modelName = etModelName.text.toString().trim()

            if (baseUrl.isEmpty() || modelName.isEmpty()) {
                Toast.makeText(this, "Enter Base URL and Model Name first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            tvConnectionStatus.visibility = View.VISIBLE
            tvConnectionStatus.text = "Testing connection..."
            tvConnectionStatus.setTextColor(tc.toolDefault)
            btnTest.isEnabled = false
            btnTest.alpha = 0.6f

            executor.submit {
                try {
                    val result = testVlmConnection(apiKey, baseUrl, modelName)
                    runOnUiThread {
                        tvConnectionStatus.text = result
                        tvConnectionStatus.setTextColor(tc.toolDefault)
                        btnTest.isEnabled = true
                        btnTest.alpha = 1f
                    }
                } catch (t: Throwable) {
                    runOnUiThread {
                        tvConnectionStatus.text = "Failed: ${t.message ?: t.javaClass.simpleName}"
                        tvConnectionStatus.setTextColor(getColor(R.color.colorErrorPrimary))
                        btnTest.isEnabled = true
                        btnTest.alpha = 1f
                    }
                }
            }
        }

        // Save
        btnSave.setOnClickListener {
            val apiKey = etApiKey.text.toString().trim()
            val baseUrl = etBaseUrl.text.toString().trim()
            val modelName = etModelName.text.toString().trim()

            if (baseUrl.isEmpty() || modelName.isEmpty()) {
                Toast.makeText(this, "Base URL and Model Name are required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            VlmConfigRepository.save(apiKey, baseUrl, modelName)
            updateStatusCard(tvStatus, tvStatusMeta, tc, VlmConfigRepository.isConfigured())
            Toast.makeText(this, "Vision model config saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun updateStatusCard(
        tvStatus: TextView,
        tvStatusMeta: TextView,
        tc: ThemeManager.ChatColors,
        isConfigured: Boolean
    ) {
        if (isConfigured) {
            val config = VlmConfigRepository.load()
            tvStatus.text = config.modelName
            tvStatusMeta.text = "Configured · ${config.baseUrl}"
        } else {
            tvStatus.text = "Not configured"
            tvStatusMeta.text = "Enter API Key, Base URL and Model Name below"
        }
    }

    private fun testVlmConnection(apiKey: String, baseUrl: String, modelName: String): String {
        if (baseUrl.isBlank()) throw IllegalArgumentException("Base URL is required")
        if (modelName.isBlank()) throw IllegalArgumentException("Model Name is required")

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        try {
            // Build a minimal vision-compatible request
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "You are a test endpoint. Reply briefly.")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "Reply with a short confirmation for this connection test.")
                })
            }

            val body = JSONObject().apply {
                put("model", modelName)
                put("max_tokens", 50)
                put("messages", messages)
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/chat/completions")
                .header("Authorization", "Bearer ${apiKey.ifEmpty { "test" }}")
                .header("Content-Type", "application/json")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw RuntimeException("HTTP ${response.code}: ${responseBody.take(200)}")
                }
                val root = JSONObject(responseBody)
                val returnedModel = root.optString("model").takeIf { it.isNotBlank() } ?: modelName
                val preview = root.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    ?.trim()
                    ?.take(120)
                    ?: "(empty response)"
                return "Connected ($returnedModel): $preview"
            }
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun installKeyboardAwareScrolling(scrollView: ScrollView, fields: List<EditText>) {
        val baseBottomPadding = scrollView.paddingBottom
        val focusListener = View.OnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                scrollView.postDelayed({ scrollFieldIntoView(scrollView, view) }, 180)
            }
        }
        fields.forEach { it.onFocusChangeListener = focusListener }

        ViewCompat.setOnApplyWindowInsetsListener(scrollView) { view, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val systemInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = baseBottomPadding + max(imeInsets.bottom, systemInsets.bottom))
            if (imeInsets.bottom > 0) {
                val focused = currentFocus
                if (focused is EditText && fields.contains(focused)) {
                    view.post { scrollFieldIntoView(scrollView, focused) }
                }
            }
            insets
        }
        ViewCompat.requestApplyInsets(scrollView)
    }

    private fun scrollFieldIntoView(scrollView: ScrollView, field: View) {
        val rect = Rect()
        field.getDrawingRect(rect)
        scrollView.offsetDescendantRectToMyCoords(field, rect)
        val margin = dp(16)
        val visibleTop = scrollView.scrollY + margin
        val visibleBottom = scrollView.scrollY + scrollView.height - scrollView.paddingBottom - margin
        when {
            rect.top < visibleTop -> scrollView.smoothScrollTo(0, (rect.top - margin).coerceAtLeast(0))
            rect.bottom > visibleBottom -> {
                val targetScroll = scrollView.scrollY + (rect.bottom - visibleBottom) + margin
                scrollView.smoothScrollTo(0, targetScroll.coerceAtLeast(0))
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
