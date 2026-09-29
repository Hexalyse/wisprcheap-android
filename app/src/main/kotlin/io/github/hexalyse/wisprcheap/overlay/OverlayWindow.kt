package io.github.hexalyse.wisprcheap.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.VibrationEffect
import android.os.VibratorManager
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.hexalyse.wisprcheap.core.gesture.ToolbarButton

/** Lifecycle for a ComposeView living in a service window (no activity). */
private class OverlayLifecycleOwner : SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    fun start() {
        savedState.performAttach()
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}

/**
 * The overlay window (TYPE_ACCESSIBILITY_OVERLAY: no "display over other apps" permission, drawn above the
 * keyboard, never focusable so the text field keeps focus). Main thread only.
 */
class OverlayWindow(
    service: AccessibilityService,
    private val ui: OverlayState,
    onTouch: (MotionEvent) -> Boolean,
    onToolbar: (ToolbarButton) -> Unit,
    onToolbarTouch: (MotionEvent) -> Boolean,
    onAccessibilityClick: () -> Unit,
) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val owner = OverlayLifecycleOwner().also { it.start() }
    private val lp = WindowManager.LayoutParams(
        1, 1,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0
        title = "WisprCheap bubble"
    }

    val view: ComposeView = ComposeView(service).apply {
        setViewTreeLifecycleOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(owner))
        setContent { OverlayTheme { OverlayContent(ui, onToolbar, onToolbarTouch) } }
        setOnTouchListener { _, e -> if (ui.shape == OverlayShape.TOOLBAR) false else onTouch(e) }
        setOnClickListener { onAccessibilityClick() }
        contentDescription = "Dictate"
    }

    var isShown = false
        private set
    val x: Int get() = lp.x
    val y: Int get() = lp.y
    val width: Int get() = lp.width
    val height: Int get() = lp.height

    fun show() {
        if (isShown) return
        runCatching { wm.addView(view, lp) }.onSuccess { isShown = true }
    }

    fun hide() {
        if (!isShown) return
        runCatching { wm.removeView(view) }
        isShown = false
    }

    fun setBounds(x: Int, y: Int, width: Int, height: Int) {
        if (lp.x == x && lp.y == y && lp.width == width && lp.height == height) return
        lp.x = x
        lp.y = y
        lp.width = width
        lp.height = height
        if (isShown) runCatching { wm.updateViewLayout(view, lp) }
    }

    fun moveTo(x: Int, y: Int) = setBounds(x, y, lp.width, lp.height)

    fun keepScreenOn(on: Boolean) {
        val flags = if (on) lp.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        else lp.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        if (flags == lp.flags) return
        lp.flags = flags
        if (isShown) runCatching { wm.updateViewLayout(view, lp) }
    }

    fun destroy() {
        hide()
        owner.destroy()
    }
}

/** Haptic cues replacing the desktop sounds (PLAN.md 5.5). */
class Haptics(context: Context, private val view: View, private val enabled: () -> Boolean) {
    private val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    private val modern = Build.VERSION.SDK_INT >= 34

    private fun perform(constant: Int) {
        if (enabled()) view.performHapticFeedback(constant)
    }

    fun start() = perform(if (modern) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.CLOCK_TICK)

    fun tick() = perform(if (modern) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK)

    fun toggle(on: Boolean) = perform(
        if (modern) (if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF) else HapticFeedbackConstants.CLOCK_TICK,
    )

    fun confirm() = perform(HapticFeedbackConstants.CONFIRM)

    fun cancel() = perform(if (modern) HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE else HapticFeedbackConstants.CLOCK_TICK)

    /** Not tied to a touch (the result arrives later), so it uses the vibrator directly. */
    fun error() {
        if (enabled()) runCatching { vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)) }
    }
}
