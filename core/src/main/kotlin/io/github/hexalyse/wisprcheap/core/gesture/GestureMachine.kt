package io.github.hexalyse.wisprcheap.core.gesture

import kotlin.math.abs
import kotlin.math.hypot

enum class RecordMode { DICTATION, COMMAND }

/** Where the finger is while holding (push-to-talk). */
enum class Zone { NORMAL, COMMAND, CANCEL }

enum class ToolbarButton { CANCEL, TOGGLE_COMMAND, SEND }

data class GestureConfig(
    /** Hold still this long before push-to-talk recording starts. */
    val micStartDelayMs: Long = 200,
    /** Releasing before this is a tap (→ hands-free), even if the mic already started. */
    val tapMaxMs: Long = 300,
    val touchSlopPx: Float,
    val commandSlidePx: Float,
    val cancelSlidePx: Float,
    val commandEnabled: Boolean = true,
)

/** What the caller (overlay) must do. The machine itself has no timers or side effects. */
sealed interface GestureEffect {
    /** Call [GestureMachine.onHoldTimeout] after [delayMs] unless cancelled. */
    data class ScheduleHoldTimer(val delayMs: Long) : GestureEffect
    data object CancelHoldTimer : GestureEffect

    /** Finger down on the idle bubble: press feedback and the "hold to talk" ring. */
    data object PressFeedback : GestureEffect
    data object ReleaseFeedback : GestureEffect

    data class StartRecording(val mode: RecordMode, val handsFree: Boolean) : GestureEffect
    data class ZoneChanged(val zone: Zone) : GestureEffect

    /** Turn the bubble into the hands-free toolbar (recording continues). */
    data object ExpandToolbar : GestureEffect
    data class ModeChanged(val mode: RecordMode) : GestureEffect

    /** Stop (after the tail) and process the recording. */
    data class Send(val mode: RecordMode) : GestureEffect

    /** Stop and drop the recording. */
    data object Cancel : GestureEffect

    data object DragStart : GestureEffect

    /** Total displacement since the finger went down. */
    data class DragBy(val dx: Float, val dy: Float) : GestureEffect
    data object DragEnd : GestureEffect
}

sealed interface GestureState {
    data object Idle : GestureState
    data class Pressed(val x: Float, val y: Float, val at: Long) : GestureState
    data class Dragging(val x: Float, val y: Float) : GestureState
    data class Holding(val x: Float, val y: Float, val at: Long, val zone: Zone) : GestureState

    /** Hands-free recording; [pressX]/[pressY] track a finger on the toolbar's drag area. */
    data class HandsFree(
        val mode: RecordMode,
        val pressX: Float? = null,
        val pressY: Float? = null,
        val dragging: Boolean = false,
    ) : GestureState
}

/**
 * Bubble gestures (PLAN.md 5.2): hold still = push-to-talk (slide up = command, slide away = cancel),
 * tap = hands-free toolbar, move before the mic starts = drag. Coordinates are screen pixels, times are ms.
 */
class GestureMachine(var config: GestureConfig) {
    var state: GestureState = GestureState.Idle
        private set

    val isRecording: Boolean get() = state is GestureState.Holding || state is GestureState.HandsFree

    val recordMode: RecordMode?
        get() = when (val s = state) {
            is GestureState.Holding -> if (s.zone == Zone.COMMAND) RecordMode.COMMAND else RecordMode.DICTATION
            is GestureState.HandsFree -> s.mode
            else -> null
        }

    fun onDown(x: Float, y: Float, t: Long): List<GestureEffect> = when (val s = state) {
        GestureState.Idle -> {
            state = GestureState.Pressed(x, y, t)
            listOf(GestureEffect.PressFeedback, GestureEffect.ScheduleHoldTimer(config.micStartDelayMs))
        }
        is GestureState.HandsFree -> {
            state = s.copy(pressX = x, pressY = y, dragging = false)
            emptyList()
        }
        else -> emptyList()
    }

    fun onMove(x: Float, y: Float, @Suppress("UNUSED_PARAMETER") t: Long): List<GestureEffect> =
        when (val s = state) {
            is GestureState.Pressed -> {
                val dx = x - s.x
                val dy = y - s.y
                if (hypot(dx, dy) > config.touchSlopPx) {
                    state = GestureState.Dragging(s.x, s.y)
                    listOf(
                        GestureEffect.CancelHoldTimer, GestureEffect.ReleaseFeedback,
                        GestureEffect.DragStart, GestureEffect.DragBy(dx, dy),
                    )
                } else {
                    emptyList()
                }
            }
            is GestureState.Dragging -> listOf(GestureEffect.DragBy(x - s.x, y - s.y))
            is GestureState.Holding -> {
                val zone = zoneFor(x - s.x, y - s.y)
                if (zone != s.zone) {
                    state = s.copy(zone = zone)
                    listOf(GestureEffect.ZoneChanged(zone))
                } else {
                    emptyList()
                }
            }
            is GestureState.HandsFree -> {
                val px = s.pressX
                val py = s.pressY
                if (px == null || py == null) {
                    emptyList()
                } else if (s.dragging) {
                    listOf(GestureEffect.DragBy(x - px, y - py))
                } else if (hypot(x - px, y - py) > config.touchSlopPx) {
                    state = s.copy(dragging = true)
                    listOf(GestureEffect.DragStart, GestureEffect.DragBy(x - px, y - py))
                } else {
                    emptyList()
                }
            }
            GestureState.Idle -> emptyList()
        }

    fun onHoldTimeout(@Suppress("UNUSED_PARAMETER") t: Long): List<GestureEffect> {
        val s = state as? GestureState.Pressed ?: return emptyList()
        state = GestureState.Holding(s.x, s.y, s.at, Zone.NORMAL)
        return listOf(GestureEffect.StartRecording(RecordMode.DICTATION, handsFree = false))
    }

    fun onUp(x: Float, y: Float, t: Long): List<GestureEffect> = when (val s = state) {
        is GestureState.Pressed -> {
            state = GestureState.HandsFree(RecordMode.DICTATION)
            listOf(
                GestureEffect.CancelHoldTimer, GestureEffect.ReleaseFeedback,
                GestureEffect.StartRecording(RecordMode.DICTATION, handsFree = true), GestureEffect.ExpandToolbar,
            )
        }
        is GestureState.Holding -> {
            val zone = zoneFor(x - s.x, y - s.y)
            if (zone == Zone.NORMAL && t - s.at < config.tapMaxMs) {
                state = GestureState.HandsFree(RecordMode.DICTATION)
                listOf(GestureEffect.ExpandToolbar)
            } else {
                finishHold(zone)
            }
        }
        is GestureState.Dragging -> {
            state = GestureState.Idle
            listOf(GestureEffect.DragEnd)
        }
        is GestureState.HandsFree -> {
            val wasDragging = s.dragging
            state = s.copy(pressX = null, pressY = null, dragging = false)
            if (wasDragging) listOf(GestureEffect.DragEnd) else emptyList()
        }
        GestureState.Idle -> emptyList()
    }

    /** The system cancelled the touch (e.g. the window moved away): like a release, but never a tap. */
    fun onCancel(): List<GestureEffect> = when (val s = state) {
        is GestureState.Pressed -> {
            state = GestureState.Idle
            listOf(GestureEffect.CancelHoldTimer, GestureEffect.ReleaseFeedback)
        }
        is GestureState.Holding -> finishHold(s.zone)
        is GestureState.Dragging -> {
            state = GestureState.Idle
            listOf(GestureEffect.DragEnd)
        }
        is GestureState.HandsFree -> {
            val wasDragging = s.dragging
            state = s.copy(pressX = null, pressY = null, dragging = false)
            if (wasDragging) listOf(GestureEffect.DragEnd) else emptyList()
        }
        GestureState.Idle -> emptyList()
    }

    fun onToolbar(button: ToolbarButton): List<GestureEffect> {
        val s = state as? GestureState.HandsFree ?: return emptyList()
        return when (button) {
            ToolbarButton.CANCEL -> {
                state = GestureState.Idle
                listOf(GestureEffect.Cancel)
            }
            ToolbarButton.SEND -> {
                state = GestureState.Idle
                listOf(GestureEffect.Send(s.mode))
            }
            ToolbarButton.TOGGLE_COMMAND -> {
                if (!config.commandEnabled) return emptyList()
                val mode = if (s.mode == RecordMode.DICTATION) RecordMode.COMMAND else RecordMode.DICTATION
                state = s.copy(mode = mode)
                listOf(GestureEffect.ModeChanged(mode))
            }
        }
    }

    /** The maximum recording duration was reached: send what was recorded. */
    fun onMaxDuration(): List<GestureEffect> = when (val s = state) {
        is GestureState.Holding -> {
            state = GestureState.Idle
            listOf(GestureEffect.Send(if (s.zone == Zone.COMMAND) RecordMode.COMMAND else RecordMode.DICTATION))
        }
        is GestureState.HandsFree -> {
            state = GestureState.Idle
            listOf(GestureEffect.Send(s.mode))
        }
        else -> emptyList()
    }

    /** Pause, service interruption ([send] = false) or screen turned off ([send] = true). */
    fun onInterrupt(send: Boolean): List<GestureEffect> = when (val s = state) {
        is GestureState.Pressed -> {
            state = GestureState.Idle
            listOf(GestureEffect.CancelHoldTimer, GestureEffect.ReleaseFeedback)
        }
        is GestureState.Dragging -> {
            state = GestureState.Idle
            listOf(GestureEffect.DragEnd)
        }
        is GestureState.Holding, is GestureState.HandsFree -> {
            val mode = recordMode ?: RecordMode.DICTATION
            state = GestureState.Idle
            listOf(if (send && !(s is GestureState.Holding && s.zone == Zone.CANCEL)) GestureEffect.Send(mode) else GestureEffect.Cancel)
        }
        GestureState.Idle -> emptyList()
    }

    private fun finishHold(zone: Zone): List<GestureEffect> {
        state = GestureState.Idle
        return listOf(
            when (zone) {
                Zone.NORMAL -> GestureEffect.Send(RecordMode.DICTATION)
                Zone.COMMAND -> GestureEffect.Send(RecordMode.COMMAND)
                Zone.CANCEL -> GestureEffect.Cancel
            },
        )
    }

    private fun zoneFor(dx: Float, dy: Float): Zone = when {
        config.commandEnabled && dy <= -config.commandSlidePx && abs(dx) < config.commandSlidePx -> Zone.COMMAND
        hypot(dx, dy) >= config.cancelSlidePx -> Zone.CANCEL
        else -> Zone.NORMAL
    }
}
