// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.gson.JsonElement
import io.agents.bqaagent.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders any Gson [JsonElement] tree into a native view hierarchy for the
 * structured "list" view of the detail dialog.
 *
 * Style: every leaf field is one paragraph — "key: value" in a single
 * TextView where only the key is bold; wrapped lines naturally align with
 * the key start. All fields share the same vertical spacing.
 *
 * Structure-agnostic by design: objects, arrays and primitives are handled
 * purely recursively, so data-model changes keep rendering correctly.
 *
 * Determinism: object keys are always rendered in case-insensitive
 * alphabetical order, so two structurally identical objects (e.g. steps with
 * Map-based params whose iteration order is undefined) always display the
 * same field order.
 *
 * The only convention applied is key-based value formatting for "*Ms" keys:
 * duration-looking keys (elapsed/wait/timeout/duration/delay prefixes) get a
 * " ms" suffix, all other "*Ms" keys are treated as epoch timestamps.
 */
object DetailContentRenderer {

    private const val TIMESTAMP_PATTERN = "yyyy-MM-dd HH:mm:ss"
    private val DURATION_KEY_PREFIXES = listOf("elapsed", "wait", "timeout", "duration", "delay")

    private const val TEXT_SIZE_DP = 12f
    private const val COUNT_SIZE_DP = 11f
    private const val DEPTH_PADDING_DP = 14
    private const val ROW_SPACING_DP = 6
    private const val CARD_PADDING_DP = 10
    private const val BADGE_SIZE_DP = 20
    private const val BADGE_TEXT_DP = 10f
    /** Long unbreakable runs (ids, urls) get a zero-width break point every N chars. */
    private const val BREAK_RUN_LIMIT = 16
    private const val BREAK_MIN_TEXT_LENGTH = 24

    /**
     * @param arrayItemBadge optional hook producing a badge label (e.g. step
     *   number) shown on top of an array element card. Return null for none.
     */
    fun buildView(
        context: Context,
        root: JsonElement,
        arrayItemBadge: ((arrayKey: String, index: Int) -> String?)? = null
    ): View {
        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        renderElement(context, container, key = null, element = root, arrayItemBadge)
        return container
    }

    private fun renderElement(
        context: Context,
        parent: LinearLayout,
        key: String?,
        element: JsonElement,
        arrayItemBadge: ((String, Int) -> String?)?
    ) {
        when {
            element.isJsonObject -> renderObject(context, parent, key, element, arrayItemBadge)
            element.isJsonArray -> renderArray(context, parent, key, element, arrayItemBadge)
            else -> addLeafRow(context, parent, key, formatValue(key, element))
        }
    }

    private fun renderObject(
        context: Context,
        parent: LinearLayout,
        key: String?,
        element: JsonElement,
        arrayItemBadge: ((String, Int) -> String?)?
    ) {
        val obj = element.asJsonObject
        if (obj.size() == 0) {
            addLeafRow(context, parent, key, "(empty)")
            return
        }
        if (key != null) addSectionRow(context, parent, "$key:", null)
        val target = if (key == null) {
            parent
        } else {
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, DEPTH_PADDING_DP), 0, 0, 0)
                parent.addView(this)
            }
        }
        obj.entrySet()
            .sortedBy { it.key.lowercase(Locale.ROOT) }
            .forEach { entry -> renderElement(context, target, entry.key, entry.value, arrayItemBadge) }
    }

    private fun renderArray(
        context: Context,
        parent: LinearLayout,
        key: String?,
        element: JsonElement,
        arrayItemBadge: ((String, Int) -> String?)?
    ) {
        val arr = element.asJsonArray
        val arrayKey = key ?: ""
        if (arr.size() == 0) {
            addLeafRow(context, parent, key, "(empty)")
            return
        }
        if (arr.all { it.isJsonPrimitive }) {
            addLeafRow(context, parent, key, arr.joinToString(", ") { it.asJsonPrimitive.asString })
            return
        }
        addSectionRow(context, parent, arrayKey, "${arr.size()}")
        arr.forEachIndexed { index, child ->
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_detail_card)
                setPadding(
                    dp(context, CARD_PADDING_DP), dp(context, CARD_PADDING_DP),
                    dp(context, CARD_PADDING_DP), dp(context, CARD_PADDING_DP)
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(context, ROW_SPACING_DP) }
            }
            val badge = runCatching { arrayItemBadge?.invoke(arrayKey, index) }.getOrNull()
            if (badge != null) {
                card.addView(buildBadge(context, badge))
            }
            renderElement(context, card, null, child, arrayItemBadge)
            parent.addView(card)
        }
    }

    private fun buildBadge(context: Context, label: String): TextView {
        return TextView(context).apply {
            text = label
            setBackgroundResource(R.drawable.bg_detail_badge)
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, BADGE_TEXT_DP)
            setTextColor(context.getColor(R.color.colorBrandOnPrimary))
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                dp(context, BADGE_SIZE_DP), dp(context, BADGE_SIZE_DP)
            ).apply { bottomMargin = dp(context, ROW_SPACING_DP) }
        }
    }

    /**
     * One leaf field rendered as a single paragraph: "key: value" in one
     * TextView, key bold. Wrapped lines align with the key start because the
     * whole paragraph shares the same left edge.
     */
    @SuppressLint("WrongConstant")
    private fun addLeafRow(context: Context, parent: LinearLayout, key: String?, value: String) {
        val text = SpannableStringBuilder()
        if (key != null) {
            val start = text.length
            text.append("$key: ")
            text.setSpan(StyleSpan(Typeface.BOLD), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        text.append(makeWrappable(value))
        val view = TextView(context).apply {
            setText(text)
            breakStrategy = 0
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DP)
            setTextColor(context.getColor(R.color.colorTextSecondary))
            setTextIsSelectable(true)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, ROW_SPACING_DP) }
        }
        parent.addView(view)
    }

    /**
     * Inserts invisible zero-width spaces into long unbreakable character runs
     * (ids, urls, package names) so they can wrap anywhere instead of jumping
     * to the next line as a whole. Short texts are returned untouched to keep
     * copy-paste clean.
     */
    private fun makeWrappable(text: String): String {
        if (text.length < BREAK_MIN_TEXT_LENGTH) return text
        val sb = StringBuilder(text.length + text.length / BREAK_RUN_LIMIT)
        var runLength = 0
        for (ch in text) {
            sb.append(ch)
            if (ch.isWhitespace()) {
                runLength = 0
            } else if (++runLength >= BREAK_RUN_LIMIT) {
                sb.append('\u200B')
                runLength = 0
            }
        }
        return sb.toString()
    }

    /** Section header for a nested object or an array of cards. */
    private fun addSectionRow(context: Context, parent: LinearLayout, label: String, count: String?) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, ROW_SPACING_DP) }
        }
        row.addView(TextView(context).apply {
            text = label
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DP)
            setTextColor(context.getColor(R.color.colorTextPrimary))
        })
        if (count != null) {
            row.addView(TextView(context).apply {
                text = count
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, COUNT_SIZE_DP)
                setTextColor(context.getColor(R.color.colorTextTertiary))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(context, ROW_SPACING_DP) }
            })
        }
        parent.addView(row)
    }

    private fun formatValue(key: String?, element: JsonElement): String {
        if (element.isJsonNull) return "null"
        val primitive = element.asJsonPrimitive
        if (key != null && key.endsWith("Ms") && primitive.isNumber) {
            val value = runCatching { primitive.asLong }.getOrNull()
            if (value != null) {
                val lowerKey = key.lowercase(Locale.ROOT)
                return if (DURATION_KEY_PREFIXES.any { lowerKey.startsWith(it) }) {
                    "$value ms"
                } else if (value <= 0L) {
                    "(never)"
                } else {
                    SimpleDateFormat(TIMESTAMP_PATTERN, Locale.getDefault()).format(Date(value))
                }
            }
        }
        return primitive.asString
    }

    private fun dp(context: Context, value: Int): Int =
        (context.resources.displayMetrics.density * value).toInt()

    private fun dp(context: Context, value: Float): Int =
        (context.resources.displayMetrics.density * value).toInt()
}