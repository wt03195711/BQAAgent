// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.floating

import android.app.Application
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.res.Resources
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.TextView
import com.blankj.utilcode.util.ThreadUtils
import io.agents.bqaagent.R
import io.agents.bqaagent.channel.Channel
import io.agents.bqaagent.utils.XLog
import io.agents.bqaagent.utils.KVUtils
import com.blankj.utilcode.util.BarUtils
import com.lzf.easyfloat.EasyFloat
import com.lzf.easyfloat.enums.ShowPattern
import com.lzf.easyfloat.enums.SidePattern
import com.lzf.easyfloat.interfaces.OnFloatCallbacks
import com.lzf.easyfloat.utils.DisplayUtils
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Floating window manager (EasyFloat based, draggable, remembers position).
 *
 * The window has exactly TWO visual styles:
 *  - a resting pill (system logo) shown whenever no task is running;
 *  - an active pill shown while a task runs, with the dynamic status on the left
 *    and a dedicated Stop button on the right.
 * Terminal states (success/error/stopped/cancelled) do NOT show a distinct icon —
 * they simply fall back to the resting pill.
 */
object FloatingCircleManager {

    private const val FLOAT_TAG = "circle_float"
    private const val KEY_FLOAT_X = "floating_circle_x"
    private const val KEY_FLOAT_Y = "floating_circle_y"

    enum class State {
        IDLE,           // resting
        TASK_NOTIFY,    // active: a task has just started
        RUNNING,        // active: agent loop running
        SUCCESS,        // finished → renders resting pill
        ERROR,          // finished → renders resting pill
        STOPPED,        // finished → renders resting pill
        CANCELLED       // finished → renders resting pill
    }

    /** Which screen edge the pill is docked to. It can only rest on the left/right edge. */
    enum class DockSide { LEFT, RIGHT }

    private var isShowing = false
    private var currentState: State = State.IDLE
    private var currentRound: Int = 0
    private var dockedSide: DockSide = DockSide.LEFT
    private var currentChannel: Channel? = null
    private var currentTokenState: io.agents.bqaagent.agent.TokenMonitor.State = io.agents.bqaagent.agent.TokenMonitor.State.NORMAL
    // Set when the agent loop auto-hides the pill mid-task because the target app
    // behaved like it rejects touches while an overlay is present (inert taps).
    // While set, show()/ensureShowing() stay quiet; any terminal setState releases it.
    private var taskSuppressed = false

    private const val TASK_NOTIFY_DURATION_MS = 3000L // show task text for 3s before collapsing to RUNNING

    // Intent scroll: one-shot right→left pan that stops at the text tail (no loop, no blank).
    // Speed is measured in DIP so it feels the same on any density. 30dp/s ≈ 1 CJK char/s at
    // 13sp, which is a comfortable reading pace (previous 0.1px/ms ≈ 3× too fast on xxhdpi).
    private const val INTENT_SCROLL_DP_PER_MS = 0.03f
    private const val INTENT_SCROLL_START_DELAY_MS = 700L
    private const val INTENT_MIN_SCROLL_MS = 1500L
    private const val INTENT_MAX_SCROLL_MS = 15000L
    // Dwell time after the intent becomes fully visible (either instantly, or once the scroll
    // reaches the tail). After this the pill collapses back to "Step N" only, so a completed
    // intent never keeps eating horizontal space while the next round is still thinking.
    private const val INTENT_HIDE_AFTER_MS = 3000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var notifyCollapseRunnable: Runnable? = null
    private var pendingTaskText: String = ""
    private var currentIntent: String = ""
    private var intentScrollAnimator: ValueAnimator? = null
    private var intentHideRunnable: Runnable? = null

    private var appRef: Application? = null

    // Incremented on every hide(). A float whose creation callback sees a changed
    // generation was created around a hide() and must NOT render a stale icon.
    private var creationGeneration = 0

    fun init(application: Application) {
        appRef = application
    }

    fun show(
        application: Application,
        x: Int? = null,
        y: Int? = null
    ) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                show(application, x, y)
            }
            return
        }

        if (!KVUtils.isFloatingWindowEnabled()) {
            XLog.i("FloatingCircle", "show() skipped — floating window feature is off")
            return
        }
        if (taskSuppressed) {
            XLog.i("FloatingCircle", "show() skipped — auto-hidden for this task (target app overlay sensitivity)")
            return
        }

        XLog.i("FloatingCircle", "show() called, isShowing=$isShowing")
        if (isShowing || EasyFloat.getFloatView(FLOAT_TAG) != null) {
            isShowing = true
            XLog.i("FloatingCircle", "show() skipped — already exists")
            return
        }
        appRef = application
        val genAtCreate = creationGeneration

        val screenHeight = DisplayUtils.getScreenHeight(application)
        val defaultX = 0
        val defaultY = screenHeight / 2

        val savedX = getSavedX() ?: x ?: defaultX
        val savedY = getSavedY() ?: y ?: defaultY

        EasyFloat.with(application)
            .setLayout(R.layout.layout_floating_circle)
            .setShowPattern(ShowPattern.ALL_TIME)
            .setSidePattern(SidePattern.DEFAULT)
            .setGravity(android.view.Gravity.START or android.view.Gravity.TOP, savedX, savedY)
            .setDragEnable(true)
            .hasEditText(false)
            .setTag(FLOAT_TAG)
            .registerCallbacks(object : OnFloatCallbacks {

                override fun createdResult(
                    isCreated: Boolean,
                    msg: String?,
                    view: View?
                ) {
                    if (genAtCreate != creationGeneration) {
                        XLog.i("FloatingCircle", "createdResult ignored — hide() raced creation")
                        EasyFloat.dismiss(FLOAT_TAG, true)
                        isShowing = false
                        return
                    }
                    XLog.i("FloatingCircle", "createdResult: isCreated=$isCreated, msg=$msg, view=${view != null}")
                    // During an active task the Stop button is the ONLY interactive control
                    // (it cancels the task and foregrounds the app). Tapping anywhere else on the
                    // active pill does nothing, so dragging/pressing the info area never triggers
                    // an action. When idle (no task), tapping the pill foregrounds the app.
                    view?.findViewById<View>(R.id.btnStop)?.setOnClickListener { onStopTask() }
                    view?.setOnClickListener {
                        if (currentState == State.TASK_NOTIFY || currentState == State.RUNNING) {
                            return@setOnClickListener
                        }
                        onFloatClick()
                    }
                    updateStateView(view, currentState)
                    view?.post {
                        enforceEdgeDocking(view, resolveSide = true)
                    }
                }

                override fun dismiss() {
                    isShowing = false
                }

                override fun drag(view: View, event: MotionEvent) {
                }

                override fun dragEnd(view: View) {
                    enforceEdgeDocking(view, resolveSide = true)
                }

                override fun hide(view: View) {
                    isShowing = false
                }

                override fun show(view: View) {
                    isShowing = true
                    // Persistent model: whenever the toggle is ON, the pill reappears in whatever
                    // its current visual state is once it becomes visible again (e.g. after leaving
                    // a FLAG_SECURE target app that temporarily hid it).
                    updateStateView(view, currentState)
                }

                override fun touchEvent(view: View, event: MotionEvent) {

                }
            })
            .show()
    }

    fun hide() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                hide()
            }
            return
        }

        EasyFloat.dismiss(FLOAT_TAG, true)
        isShowing = false
        currentState = State.IDLE
        isCancelling = false
        // Feature toggle off / explicit hide: drop suppression too — the next show()
        // must start from a clean slate.
        taskSuppressed = false
        creationGeneration++
        cancelNotifyCollapse()
        cancelIntentHide()
        intentScrollAnimator?.cancel()
        intentScrollAnimator = null
    }

    fun isShowing(): Boolean = isShowing

    fun ensureShowing() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                ensureShowing()
            }
            return
        }

        if (!KVUtils.isFloatingWindowEnabled()) {
            return
        }
        // Same gate as show(): TaskOrchestrator calls ensureShowing() at every task
        // start; without it the suppression would be undone on the next task round.
        if (taskSuppressed) {
            return
        }

        if (!isShowing && EasyFloat.getFloatView(FLOAT_TAG) == null) {
            val app = appRef
            if (app != null) {
                XLog.i("FloatingCircle", "ensureShowing: re-showing dismissed float")
                show(app)
            } else {
                XLog.w("FloatingCircle", "ensureShowing: no appRef, cannot re-show")
            }
        }
    }

    fun setIdleState() {
        ThreadUtils.runOnUiThread { setState(State.IDLE) }
    }

    fun showTaskNotify(taskText: String, channel: Channel) {
        ThreadUtils.runOnUiThread {
            pendingTaskText = taskText
            currentChannel = channel
            cancelNotifyCollapse()
            setState(State.TASK_NOTIFY)
            notifyCollapseRunnable = Runnable { setState(State.RUNNING) }
            mainHandler.postDelayed(notifyCollapseRunnable!!, TASK_NOTIFY_DURATION_MS)
        }
    }

    private fun cancelNotifyCollapse() {
        notifyCollapseRunnable?.let {
            mainHandler.removeCallbacks(it)
            notifyCollapseRunnable = null
        }
    }

    fun setRunningState(round: Int, channel: Channel) {
        ThreadUtils.runOnUiThread {
            currentChannel = channel
            // Do NOT bump the step here: step and intent must advance together (see updateTokenStatus).
            // Advancing the step ahead of the intent is exactly what made the pill show a new step
            // with the previous step's stale intent. Just make sure we are visually RUNNING.
            if (currentState != State.TASK_NOTIFY && currentState != State.RUNNING) {
                setState(State.RUNNING)
            }
        }
    }

    fun updateTokenStatus(step: Int, formattedTokens: String, formattedCost: String, tokenState: io.agents.bqaagent.agent.TokenMonitor.State, intent: String) {
        ThreadUtils.runOnUiThread {
            // Step and intent are the ONLY place both change, so they always stay a consistent pair.
            currentRound = step
            currentTokenState = tokenState
            currentIntent = intent
            // Render immediately while RUNNING. During the TASK_NOTIFY banner just record the latest
            // pair; its collapse to RUNNING (updateStateView → renderStepIntent) will show it.
            if (currentState != State.RUNNING) return@runOnUiThread
            renderStepIntent()
        }
    }

    /**
     * Render the current (step, intent) pair together onto the running pill. Stops any in-progress
     * intent scroll first, so new content appears at once (Step N fixed, intent from offset 0).
     */
    private fun renderStepIntent() {
        val view = EasyFloat.getFloatView(FLOAT_TAG) ?: return
        val tvStep = view.findViewById<TextView>(R.id.tvStep) ?: return
        val tvIntent = view.findViewById<TextView>(R.id.tvIntent)
        if (isCancelling) {
            tvStep.text = ""
            applyIntentText(tvIntent, stoppingLabel())
            view.post { enforceEdgeDocking(view, resolveSide = false) }
            return
        }
        tvStep.text = if (currentRound > 0) "Step $currentRound" else ""
        applyIntentText(tvIntent, currentIntent)
        // The intent length can grow/shrink the pill. Re-glue to the docked edge after layout so a
        // right-docked pill never leaves a gap when the text gets shorter.
        view.post { enforceEdgeDocking(view, resolveSide = false) }
    }

    /**
     * Show the intent text directly (no marquee fade-in). If it exceeds the fixed 160dp line,
     * scroll it once from right to left (revealing the tail) and STOP there — never loops,
     * never leaves blank. Short text simply sits at scroll offset 0. After the text has been
     * fully visible for [INTENT_HIDE_AFTER_MS] the intent collapses away and only "Step N"
     * remains, so the pill reclaims its horizontal footprint while the agent is still thinking.
     */
    private fun applyIntentText(tvIntent: TextView?, text: String) {
        if (tvIntent == null) return
        intentScrollAnimator?.cancel()
        intentScrollAnimator = null
        cancelIntentHide()
        // Show the FULL text on one horizontally-scrollable line: no "…" ellipsis. The width
        // hugs the content (wrap_content) and only caps at maxWidth(160dp); scrolling kicks in
        // only when the text is wider than the view.
        tvIntent.ellipsize = null
        tvIntent.setHorizontallyScrolling(true)
        tvIntent.scrollTo(0, 0)
        tvIntent.text = text
        if (text.isBlank()) return
        tvIntent.post {
            // Skip if the task already left the active states before layout ran.
            if (currentState != State.TASK_NOTIFY && currentState != State.RUNNING) return@post
            val available = tvIntent.width
            if (available <= 0) return@post
            val textWidth = tvIntent.paint.measureText(text)
            val extra = textWidth - available
            if (extra <= 1f) {
                tvIntent.scrollTo(0, 0)
                // Short intent: it is already fully readable — start the hide countdown now.
                scheduleIntentHide(tvIntent, INTENT_HIDE_AFTER_MS)
                return@post
            }
            val density = Resources.getSystem().displayMetrics.density
            val pxPerMs = (INTENT_SCROLL_DP_PER_MS * density).coerceAtLeast(0.01f)
            val duration = (extra / pxPerMs)
                .toLong()
                .coerceIn(INTENT_MIN_SCROLL_MS, INTENT_MAX_SCROLL_MS)
            val anim = ValueAnimator.ofInt(0, extra.toInt())
            anim.duration = duration
            anim.startDelay = INTENT_SCROLL_START_DELAY_MS
            anim.interpolator = LinearInterpolator()
            anim.addUpdateListener { tvIntent.scrollTo(it.animatedValue as Int, 0) }
            anim.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Only react to OUR animator; a newer intent may already own the slot.
                    // Identity check against the field is enough — no cast to ValueAnimator
                    // needed, and the parent signature (Animator) is what makes `override`
                    // actually match (using ValueAnimator here would silently define a NEW
                    // overload and the compiler would flag "overrides nothing").
                    if (intentScrollAnimator === animation) {
                        intentScrollAnimator = null
                        scheduleIntentHide(tvIntent, INTENT_HIDE_AFTER_MS)
                    }
                }
            })
            intentScrollAnimator = anim
            anim.start()
        }
    }

    /**
     * After [delayMs] the intent line is cleared and the pill is re-glued to its docked edge,
     * leaving only "Step N" visible. Cancelled by any new renderStepIntent / setState call.
     */
    private fun scheduleIntentHide(tvIntent: TextView, delayMs: Long) {
        cancelIntentHide()
        val runnable = Runnable {
            intentHideRunnable = null
            if (currentState != State.TASK_NOTIFY && currentState != State.RUNNING) return@Runnable
            // Do not fight a still-running scroll (shouldn't happen, but be defensive).
            intentScrollAnimator?.cancel()
            intentScrollAnimator = null
            tvIntent.text = ""
            tvIntent.scrollTo(0, 0)
            // The pill shrinks once the intent line empties; re-glue so it never floats off-edge.
            EasyFloat.getFloatView(FLOAT_TAG)?.let { root ->
                root.post { enforceEdgeDocking(root, resolveSide = false) }
            }
        }
        intentHideRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelIntentHide() {
        intentHideRunnable?.let { mainHandler.removeCallbacks(it) }
        intentHideRunnable = null
    }

    /** Task finished (success). No distinct icon — just returns to the resting pill. */
    fun setSuccessState() {
        ThreadUtils.runOnUiThread { setState(State.SUCCESS) }
    }

    /** Task failed → return to the resting pill. */
    fun setErrorState() {
        ThreadUtils.runOnUiThread { setState(State.ERROR) }
    }

    /** Task stopped by system → return to the resting pill. */
    fun setStoppedState() {
        ThreadUtils.runOnUiThread { setState(State.STOPPED) }
    }

    /**
     * Visual state between "running" and "cancelled": shown while the confirmed stop is still in
     * flight, so the pill never claims success before the background loop has actually exited.
     */
    fun setCancellingState() {
        ThreadUtils.runOnUiThread {
            isCancelling = true
            // Reuse the active pill (it is the only layout with a text line); the render guard below
            // replaces its content with "Stopping…" so no ad-hoc text setter is needed.
            if (currentState == State.TASK_NOTIFY || currentState == State.RUNNING) {
                renderStepIntent()
            } else {
                setState(State.RUNNING)
            }
        }
    }

    fun setCancelledState() {
        ThreadUtils.runOnUiThread {
            isCancelling = false
            setState(State.CANCELLED)
        }
    }

    /** Label shown on the pill while a stop is being confirmed (reuses the chat "Stopping…" string). */
    private fun stoppingLabel(): String =
        appRef?.getString(R.string.chat_stopping) ?: "Stopping…"

    private fun setState(state: State) {
        // Any terminal or idle state ends the "stopping" window; the pill falls back to its normal look.
        if (state != State.TASK_NOTIFY && state != State.RUNNING) {
            isCancelling = false
            // Task is over (success/error/stop/cancel or idle): the reason we hid the pill
            // (this target app) no longer owns the screen, so restore it for the next task.
            releaseTaskSuppression()
        }
        currentState = state
        // New visual state = new intent lifecycle. Kill any pending hide so a stale runnable
        // cannot blank a freshly rendered banner/step.
        cancelIntentHide()
        val view = EasyFloat.getFloatView(FLOAT_TAG)
        XLog.i("FloatingCircle", "setState: $state, view=${view != null}, isShowing=$isShowing")
        view?.let { updateStateView(it, state) }
        // The pill width differs between idle (circle) and active (capsule). Re-glue it to the
        // already-docked edge so a width change never leaves a gap or pushes it off-screen.
        view?.post { enforceEdgeDocking(view, resolveSide = false) }
    }

    private fun updateStateView(view: View?, state: State) {
        if (view == null) return

        val cardIdle = view.findViewById<View>(R.id.cardIdle)
        val cardActive = view.findViewById<View>(R.id.cardActive)

        // Exactly two styles: active pill (TASK_NOTIFY/RUNNING) vs resting pill (everything else).
        val isActive = state == State.TASK_NOTIFY || state == State.RUNNING
        cardActive?.visibility = if (isActive) View.VISIBLE else View.GONE
        cardIdle?.visibility = if (isActive) View.GONE else View.VISIBLE

        if (isActive) {
            val tvStep = view.findViewById<TextView>(R.id.tvStep)
            val tvIntent = view.findViewById<TextView>(R.id.tvIntent)
            if (state == State.TASK_NOTIFY) {
                val app = appRef
                val message = app?.getString(R.string.floating_task_received, pendingTaskText) ?: pendingTaskText
                tvStep?.text = ""
                applyIntentText(tvIntent, message)
            } else {
                // RUNNING: cancel pending notify-collapse; render step+intent together as one pair.
                cancelNotifyCollapse()
                renderStepIntent()
            }
        } else {
            intentScrollAnimator?.cancel()
            intentScrollAnimator = null
            cancelIntentHide()
        }
    }

    /**
     * Snap the pill to the nearest screen edge so it can never rest in the middle.
     * - [resolveSide] = true (after a drag / first show): decide LEFT/RIGHT from the pill's center.
     * - [resolveSide] = false (after a width change): keep the current side, just re-glue.
     * Also mirrors the active pill layout so the Stop button hugs the docked edge.
     */
    private fun enforceEdgeDocking(view: View, resolveSide: Boolean) {
        val screenWidth = Resources.getSystem().displayMetrics.widthPixels
        val screenHeight = Resources.getSystem().displayMetrics.heightPixels
        val navBarHeight = getNavigationBarHeight()

        var wmParams: WindowManager.LayoutParams? = null
        var wmView: View? = view
        while (wmView != null) {
            val lp = wmView.layoutParams
            if (lp is WindowManager.LayoutParams) {
                wmParams = lp
                break
            }
            wmView = wmView.parent as? View
        }
        val params = wmParams ?: return
        val measured = wmView ?: view
        val floatWidth = measured.width
        val floatHeight = measured.height
        if (floatWidth <= 0 || floatHeight <= 0) {
            // Not laid out yet — retry after the next layout pass.
            view.post { enforceEdgeDocking(view, resolveSide) }
            return
        }

        // Keep the whole pill within the screen width (shrink the intent cap on narrow screens).
        if (clampIntentMaxWidth(view)) {
            // maxWidth changed → pill width changes on the next layout; re-run to settle X/edge.
            view.post { enforceEdgeDocking(view, resolveSide) }
            return
        }

        val maxY = (screenHeight - floatHeight - navBarHeight - 50).coerceAtLeast(0)
        val clampedY = params.y.coerceIn(0, maxY)

        if (resolveSide) {
            val centerX = params.x + floatWidth / 2
            dockedSide = if (centerX > screenWidth / 2) DockSide.RIGHT else DockSide.LEFT
        }
        val targetX = if (dockedSide == DockSide.RIGHT) {
            (screenWidth - floatWidth).coerceAtLeast(0)
        } else {
            0
        }

        if (targetX != params.x || clampedY != params.y) {
            EasyFloat.updateFloat(FLOAT_TAG, targetX, clampedY)
        }
        savePosition(targetX, clampedY)
        applyDockSide(view)
    }

    /** Mirror the running pill for right-edge docking: Stop button to the right, text to the left. */
    private fun applyDockSide(view: View?) {
        if (view == null) return
        val container = view.findViewById<View>(R.id.activeContainer) ?: return
        container.layoutDirection = if (dockedSide == DockSide.RIGHT) {
            View.LAYOUT_DIRECTION_RTL
        } else {
            View.LAYOUT_DIRECTION_LTR
        }
    }

    /**
     * Cap the intent line width so the ENTIRE pill never exceeds the screen width.
     * target = min(160dp, screenWidth - chrome - safety). Returns true if the cap actually changed
     * (the caller then re-measures/re-glues on the next layout pass). Wide screens keep 160dp.
     */
    private fun clampIntentMaxWidth(view: View): Boolean {
        val density = Resources.getSystem().displayMetrics.density
        val screenW = Resources.getSystem().displayMetrics.widthPixels
        fun dp(value: Float) = (value * density)

        val tvIntent = view.findViewById<TextView>(R.id.tvIntent) ?: return false
        val tvStep = view.findViewById<TextView>(R.id.tvStep)
        val btnStop = view.findViewById<View>(R.id.btnStop)

        // Everything on the pill besides the intent line (use real widths once laid out).
        val stopW = btnStop?.width ?: dp(42f).toInt()                    // 32dp button + 10dp marginEnd
        val stepW = (tvStep?.width ?: dp(58f).toInt()) + dp(6f).toInt()  // Step text + 6dp marginEnd
        val chrome = stopW + stepW + dp(24f).toInt() + dp(4f).toInt()    // + paddings(8+16) + strokes(2*2)
        val safety = dp(8f).toInt()

        val screenCap = screenW - chrome - safety
        val target = screenCap.coerceAtMost(dp(160f).toInt()).coerceAtLeast(dp(24f).toInt())
        return if (tvIntent.maxWidth != target) {
            tvIntent.maxWidth = target
            true
        } else {
            false
        }
    }

    private fun getNavigationBarHeight(): Int = BarUtils.getNavBarHeight()

    private fun savePosition(x: Int, y: Int) {
        KVUtils.putInt(KEY_FLOAT_X, x)
        KVUtils.putInt(KEY_FLOAT_Y, y)
    }

    private fun getSavedX(): Int? {
        val x = KVUtils.getInt(KEY_FLOAT_X, -1)
        return if (x == -1) null else x
    }

    private fun getSavedY(): Int? {
        val y = KVUtils.getInt(KEY_FLOAT_Y, -1)
        return if (y == -1) null else y
    }

    var onFloatClick: () -> Unit = {}
    var onStopTask: () -> Unit = {}

    private var isCancelling = false

    /**
     * Temporarily hides the pill from the compositor so a `screencap -p` (or any other
     * full-framebuffer capture) does not bake the floating status into the image the VLM
     * sees. Blocks the caller until one frame has actually been rendered with the pill
     * hidden; safe to call from any thread. No-ops when the pill is not currently shown.
     *
     * Only meant for the short capture window (~200-500ms). Callers must not hold the
     * suppression open across an entire agent round — the user needs to see the pill.
     */
    fun <T> withScreenshotSuppression(block: () -> T): T {
        val view = EasyFloat.getFloatView(FLOAT_TAG)
        if (view == null) return block()
        val previousVisibility = view.visibility
        if (previousVisibility != View.VISIBLE) return block()

        val hidden = CountDownLatch(1)
        ThreadUtils.runOnUiThread {
            try {
                view.visibility = View.INVISIBLE
                // Wait for one posted frame so SystemUI has composited WITHOUT the pill.
                view.post { hidden.countDown() }
            } catch (e: Exception) {
                hidden.countDown()
            }
        }
        // Do not hang the caller if the UI thread is stuck — 400ms is well past one frame.
        hidden.await(400, TimeUnit.MILLISECONDS)
        try {
            return block()
        } finally {
            ThreadUtils.runOnUiThread {
                // Only restore if no one else re-showed/hid the pill meanwhile.
                if (view.visibility == View.INVISIBLE) view.visibility = previousVisibility
            }
        }
    }

    /**
     * Screen-space rectangle occupied by the current floating pill, or null if the pill is
     * not on-screen. Used by the ADB UI-tree parser to filter ONLY the overlay window's
     * nodes out of `uiautomator dump --compressed` results on ROMs that leak overlay
     * windows into the hierarchy.
     *
     * Deliberately NOT scoped by package name alone: when the user's task legitimately
     * targets this app's own Activity (settings page, chat list, batch panel, ...), we
     * must keep those nodes intact. Only the pill's own rectangle is safe to drop.
     *
     * Adds a small inset so anti-aliased borders/strokes of the pill do not accidentally
     * swallow an underlying target-app node that merely touches the pill's edge.
     */
    fun currentOverlayBounds(): Rect? {
        // Kill the whole overlay-aware pipeline in one place when the feature is off:
        // no header note, no UI-tree filter, no gesture guard. The rest of the code
        // treats null as "no pill", so this short-circuit is the ONLY switch we need.
        if (!KVUtils.isFloatingWindowEnabled()) return null
        val view = EasyFloat.getFloatView(FLOAT_TAG) ?: return null
        if (view.visibility != View.VISIBLE) return null
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return null
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + w, loc[1] + h)
    }

    /** Which edge the pill is currently glued to. Exposed for the get_screen_info header note. */
    fun isDockedRight(): Boolean = dockedSide == DockSide.RIGHT

    /**
     * Symptom-driven fallback for apps that refuse touch input while ANY overlay
     * window exists (banking anti-overlay policies, some ROMs' touch-theft guards).
     * The agent loop detects "successful tap gestures with zero screen response"
     * and hides the pill for the rest of the task — no package blacklist needed,
     * and every overlay-aware guard (header note / tree filter / gesture guard /
     * screenshot suppression) naturally no-ops because the view is not VISIBLE.
     */
    fun suppressForTask(reason: String) {
        ThreadUtils.runOnUiThread {
            if (taskSuppressed) return@runOnUiThread
            taskSuppressed = true
            XLog.i("FloatingCircle", "Pill auto-hidden for this task: $reason")
            EasyFloat.hide(FLOAT_TAG)
        }
    }

    /** Bring the pill back after the task reaches a terminal state. */
    fun releaseTaskSuppression() {
        if (!taskSuppressed) return
        taskSuppressed = false
        // Only re-show when the feature is on and the float still exists (hide() dismissed it otherwise).
        if (KVUtils.isFloatingWindowEnabled() && EasyFloat.getFloatView(FLOAT_TAG) != null) {
            EasyFloat.show(FLOAT_TAG)
            isShowing = true
        }
        XLog.i("FloatingCircle", "Task suppression released, pill restored")
    }

    fun isTaskSuppressed(): Boolean = taskSuppressed

}
