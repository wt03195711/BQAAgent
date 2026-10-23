// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.TextView

/**
 * Capacity warnings on the Skill Management and Recorded Files screens used to sit permanently above
 * the list, stealing vertical space the user needs to operate the rows. This helper shows the banner
 * immediately when the screen is opened or refreshed, then after a short dwell collapses its height to
 * zero (a slide-up-and-out-of-the-way motion, with a matching fade) and hides it, so the list reclaims
 * the room. Re-invoking [show] cancels any in-flight countdown/animation, so rapid refreshes never
 * stack two animations on the same view.
 */
object CapacityWarningBanner {

    /** How long the banner stays fully visible before it starts sliding away. */
    private const val DWELL_MS = 3_000L

    /** Duration of the collapse (slide-up) animation. */
    private const val COLLAPSE_MS = 300L

    /**
     * Shows [textView] with [text] right now, then auto-dismisses it upward after [DWELL_MS].
     * If [text] is blank, the banner is hidden immediately (see [hide]).
     */
    fun show(textView: TextView, text: CharSequence) {
        if (text.isBlank()) {
            hide(textView)
            return
        }
        controllerFor(textView).show(text)
    }

    /** Cancels any pending countdown/animation and hides the banner without animating. */
    fun hide(textView: TextView) {
        controllerFor(textView).hide()
    }

    /** Cancels pending countdown/animation but leaves the current visibility untouched. */
    fun cancel(textView: TextView) {
        (textView.tag as? Controller)?.cancel()
    }

    private fun controllerFor(textView: TextView): Controller {
        val existing = textView.tag as? Controller
        if (existing != null) return existing
        val created = Controller(textView)
        textView.tag = created
        return created
    }

    /** Per-view state: the pending dismiss callback and the running collapse animator. */
    private class Controller(private val view: TextView) {

        private var animator: ValueAnimator? = null
        private val dismissRunnable = Runnable { collapse() }

        fun show(text: CharSequence) {
            cancel()
            view.text = text
            view.alpha = 1f
            restoreWrapHeight()
            view.visibility = View.VISIBLE
            view.postDelayed(dismissRunnable, DWELL_MS)
        }

        fun hide() {
            cancel()
            view.visibility = View.GONE
        }

        fun cancel() {
            view.removeCallbacks(dismissRunnable)
            animator?.cancel()
            animator = null
        }

        /** Shrinks the banner's layout height to zero so rows below glide up into its space. */
        private fun collapse() {
            val startHeight = view.height
            if (startHeight <= 0) {
                hide()
                return
            }
            animator = ValueAnimator.ofInt(startHeight, 0).apply {
                duration = COLLAPSE_MS
                interpolator = DecelerateInterpolator()
                addUpdateListener { animation ->
                    val height = animation.animatedValue as Int
                    view.layoutParams = view.layoutParams.apply { this.height = height }
                    view.alpha = height.toFloat() / startHeight
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        view.visibility = View.GONE
                        view.alpha = 1f
                        restoreWrapHeight()
                    }
                })
                start()
            }
        }

        /** Restores the wrap_content height so a later [show] lays out at full size again. */
        private fun restoreWrapHeight() {
            val lp = view.layoutParams
            if (lp != null && lp.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                view.layoutParams = lp
            }
        }
    }
}