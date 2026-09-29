package io.github.hexalyse.wisprcheap.core.overlay

import io.github.hexalyse.wisprcheap.core.settings.ShowWhen
import kotlin.math.roundToInt

data class VisibilityInput(
    val showWhen: ShowWhen = ShowWhen.EDITING,
    val paused: Boolean = false,
    val keyguardLocked: Boolean = false,
    val packageName: String? = null,
    val excludedApps: Set<String> = emptySet(),
    val isPassword: Boolean = false,
    val hideOnPasswordFields: Boolean = true,
    /** The accessibility InputMethod reported an active editor. */
    val inputStarted: Boolean = false,
    val imeVisible: Boolean = false,
    /** Recording or hands-free toolbar shown: never hide then. */
    val sessionActive: Boolean = false,
)

/** When the bubble is shown (PLAN.md 5.1). Hiding is delayed by the caller (grace period). */
object VisibilityPolicy {
    fun shouldShow(i: VisibilityInput): Boolean {
        if (i.sessionActive) return true
        if (i.paused || i.keyguardLocked) return false
        if (i.packageName != null && i.packageName in i.excludedApps) return false
        if (i.hideOnPasswordFields && i.isPassword) return false
        return when (i.showWhen) {
            ShowWhen.EDITING -> i.inputStarted && i.imeVisible
            ShowWhen.EDITOR_ACTIVE -> i.inputStarted
            ShowWhen.KEYBOARD_VISIBLE -> i.imeVisible
            ShowWhen.ALWAYS -> true
        }
    }
}

/**
 * Saved bubble position: [xFraction] across the usable width, and [dyPx] between the bubble's bottom and the
 * anchor (keyboard top, or the screen bottom when there's no keyboard or follow-keyboard is off). A negative
 * [dyPx] puts the bubble over the keyboard.
 */
data class SavedPosition(val xFraction: Float, val dyPx: Int)

data class ScreenArea(val width: Int, val height: Int, val topInset: Int = 0, val bottomInset: Int = 0)

object BubblePosition {
    fun default(marginPx: Int) = SavedPosition(1f, marginPx)

    /** Top-left corner of the bubble, clamped to the screen. */
    fun place(saved: SavedPosition?, sizePx: Int, marginPx: Int, screen: ScreenArea, imeTop: Int?): Pair<Int, Int> {
        val pos = saved ?: default(marginPx)
        val minX = marginPx
        val maxX = (screen.width - sizePx - marginPx).coerceAtLeast(minX)
        val x = (minX + pos.xFraction.coerceIn(0f, 1f) * (maxX - minX)).roundToInt()
        val anchor = imeTop ?: (screen.height - screen.bottomInset)
        val maxY = (screen.height - sizePx).coerceAtLeast(screen.topInset)
        val y = (anchor - sizePx - pos.dyPx).coerceIn(screen.topInset, maxY)
        return x to y
    }

    /** Inverse of [place], after a drag ended at ([x], [y]). */
    fun save(x: Int, y: Int, sizePx: Int, marginPx: Int, screen: ScreenArea, imeTop: Int?): SavedPosition {
        val minX = marginPx
        val range = (screen.width - sizePx - marginPx - minX).coerceAtLeast(1)
        val xf = ((x - minX).toFloat() / range).coerceIn(0f, 1f)
        val anchor = imeTop ?: (screen.height - screen.bottomInset)
        return SavedPosition(xf, anchor - sizePx - y)
    }
}
