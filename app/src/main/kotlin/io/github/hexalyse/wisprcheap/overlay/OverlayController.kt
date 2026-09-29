package io.github.hexalyse.wisprcheap.overlay

import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import io.github.hexalyse.wisprcheap.WisprApp
import io.github.hexalyse.wisprcheap.a11y.WisprAccessibilityService
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * Phase 0 overlay: the bubble (hold = push-to-talk test, tap = test panel, move = drag) and the test panel,
 * both in TYPE_ACCESSIBILITY_OVERLAY windows that never take focus. Main thread only.
 */
@SuppressLint("ClickableViewAccessibility")
class OverlayController(private val service: WisprAccessibilityService) {
    private val app = WisprApp.instance
    private val wm = service.getSystemService(WindowManager::class.java)
    private val ctx = ContextThemeWrapper(service, android.R.style.Theme_DeviceDefault)
    private val main = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private val density = service.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val bubbleSize = dp(56)
    private val margin = dp(12)
    private val touchSlop = ViewConfiguration.get(service).scaledTouchSlop

    private val bubble = BubbleView(ctx)
    private val bubbleLp = overlayParams(bubbleSize, bubbleSize)
    private var bubbleShown = false

    private val panel = TestPanel(ctx, ::onPanelAction, ::closePanel, ::dragPanel)
    private val panelLp = overlayParams(dp(320), WindowManager.LayoutParams.WRAP_CONTENT)
    private var panelShown = false

    private var recording = false
    private var busy = false
    private var dragging = false
    private val hideBubble = Runnable { setBubbleShown(false) }

    init {
        bubble.setOnTouchListener(BubbleTouch())
        scope.launch { app.runtime.alwaysShowBubble.collect { onStateChanged() } }
        scope.launch {
            app.diagnostics.results.collect { list ->
                list.lastOrNull()?.let { r ->
                    panel.showResult("${if (r.ok) "OK" else "FAIL"} ${r.test} @ ${r.pkg}\n${r.summary}")
                }
            }
        }
    }

    private fun overlayParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0
    }

    /** Re-evaluates visibility and position after any editor, keyboard or setting change. */
    fun onStateChanged() {
        val s = app.runtime.editor.value
        panel.setStatus(s.describe())
        val want = app.runtime.alwaysShowBubble.value || recording || busy || panelShown ||
            (s.inputStarted && s.imeVisible && !s.isPassword)
        if (want) {
            main.removeCallbacks(hideBubble)
            setBubbleShown(true)
            if (!dragging) placeBubble()
        } else if (bubbleShown) {
            main.removeCallbacks(hideBubble)
            main.postDelayed(hideBubble, 300)
        }
    }

    private fun setBubbleShown(show: Boolean) {
        if (show == bubbleShown) return
        bubbleShown = show
        if (show) {
            placeBubble(update = false)
            runCatching { wm.addView(bubble, bubbleLp) }.onFailure { bubbleShown = false }
        } else {
            runCatching { wm.removeView(bubble) }
        }
    }

    /** Right edge by default; y is stored as a distance above the keyboard top, so the bubble follows it. */
    private fun placeBubble(update: Boolean = true) {
        val screen = wm.currentWindowMetrics.bounds
        val ime = app.runtime.editor.value.imeBounds
        val bottom = ime?.top ?: (screen.height() - dp(96))
        val x = app.runtime.bubbleX.takeIf { it >= 0 } ?: (screen.width() - bubbleSize - margin)
        val dy = app.runtime.bubbleDy.takeIf { it >= 0 } ?: margin
        bubbleLp.x = x.coerceIn(0, screen.width() - bubbleSize)
        bubbleLp.y = (bottom - bubbleSize - dy).coerceIn(0, screen.height() - bubbleSize)
        if (update && bubbleShown) runCatching { wm.updateViewLayout(bubble, bubbleLp) }
    }

    private fun saveBubblePosition() {
        val screen = wm.currentWindowMetrics.bounds
        val ime = app.runtime.editor.value.imeBounds
        val bottom = ime?.top ?: (screen.height() - dp(96))
        app.runtime.bubbleX = bubbleLp.x
        app.runtime.bubbleDy = (bottom - bubbleSize - bubbleLp.y).coerceAtLeast(0)
    }

    fun setBusy(message: String?) {
        busy = message != null
        bubble.mode = if (busy) BubbleView.Mode.BUSY else BubbleView.Mode.IDLE
        panel.setBusy(message)
        onStateChanged()
    }

    private fun onPanelAction(id: String) = service.runAction(id)

    private fun togglePanel() = if (panelShown) closePanel() else openPanel()

    private fun openPanel() {
        if (panelShown) return
        val screen = wm.currentWindowMetrics.bounds
        panelLp.x = (screen.width() - panelLp.width) / 2
        panelLp.y = dp(56)
        runCatching { wm.addView(panel, panelLp) }.onSuccess { panelShown = true }
        onStateChanged()
    }

    private fun closePanel() {
        if (!panelShown) return
        runCatching { wm.removeView(panel) }
        panelShown = false
        onStateChanged()
    }

    private fun dragPanel(dx: Float, dy: Float, done: Boolean) {
        if (done || !panelShown) return
        panelLp.x += dx.toInt()
        panelLp.y += dy.toInt()
        runCatching { wm.updateViewLayout(panel, panelLp) }
    }

    fun destroy() {
        scope.cancel()
        main.removeCallbacksAndMessages(null)
        if (bubbleShown) runCatching { wm.removeView(bubble) }
        if (panelShown) runCatching { wm.removeView(panel) }
        bubbleShown = false
        panelShown = false
    }

    /** Hold still MIC_START_DELAY_MS = push-to-talk test; move first = drag; release early = tap (panel). */
    private inner class BubbleTouch : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var downAt = 0L
        private var holding = false

        private val startHold = Runnable {
            holding = true
            recording = true
            bubble.cancelHoldRing()
            bubble.mode = BubbleView.Mode.RECORDING
            bubble.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
                else HapticFeedbackConstants.CLOCK_TICK,
            )
            service.startPtt(downAt)
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = bubbleLp.x
                    startY = bubbleLp.y
                    downAt = SystemClock.elapsedRealtime()
                    holding = false
                    dragging = false
                    if (!busy) {
                        bubble.startHoldRing(MIC_START_DELAY_MS)
                        main.postDelayed(startHold, MIC_START_DELAY_MS)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!holding && !dragging && hypot(dx, dy) > touchSlop) {
                        dragging = true
                        main.removeCallbacks(startHold)
                        bubble.cancelHoldRing()
                    }
                    if (dragging) {
                        val screen = wm.currentWindowMetrics.bounds
                        bubbleLp.x = (startX + dx.toInt()).coerceIn(0, screen.width() - bubbleSize)
                        bubbleLp.y = (startY + dy.toInt()).coerceIn(0, screen.height() - bubbleSize)
                        runCatching { wm.updateViewLayout(bubble, bubbleLp) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(startHold)
                    bubble.cancelHoldRing()
                    when {
                        dragging -> {
                            dragging = false
                            saveBubblePosition()
                        }
                        holding -> {
                            holding = false
                            recording = false
                            bubble.mode = BubbleView.Mode.IDLE
                            bubble.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            service.stopPtt()
                            onStateChanged()
                        }
                        e.actionMasked == MotionEvent.ACTION_UP -> togglePanel()
                    }
                }
            }
            return true
        }
    }

    companion object {
        const val MIC_START_DELAY_MS = 200L
    }
}
