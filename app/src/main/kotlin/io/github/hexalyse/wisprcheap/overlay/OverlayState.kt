package io.github.hexalyse.wisprcheap.overlay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Window layout of the overlay. */
enum class OverlayShape { BUBBLE, HOLDING, TOOLBAR }

/** What the bubble shows. */
enum class BubbleLook { IDLE, PRESSED, RECORDING, COMMAND_ZONE, CANCEL_ZONE, PROCESSING, DONE, COPIED, ERROR, DISCARDED }

/** Observable state drawn by [OverlayContent]; written on the main thread by [DictationController]. */
class OverlayState {
    var shape by mutableStateOf(OverlayShape.BUBBLE)
    var look by mutableStateOf(BubbleLook.IDLE)
    var holdMs by mutableLongStateOf(200L)
    var recordingSince by mutableLongStateOf(0L)
    var commandMode by mutableStateOf(false)
    var commandEnabled by mutableStateOf(true)
    var hasSelection by mutableStateOf(false)
    var translationBadge by mutableStateOf<String?>(null)
    var pending by mutableIntStateOf(0)
    var sizeDp by mutableIntStateOf(48)
    var idleOpacity by mutableFloatStateOf(0.9f)
    var level by mutableFloatStateOf(0f)
    val levels = mutableStateListOf<Float>()

    /** Hold layout: horizontal offset of the bubble from the window centre (the window is kept on screen). */
    var holdOffsetXPx by mutableIntStateOf(0)

    /** Hold layout: the hint chip goes below the bubble when there's no room above it. */
    var chipBelow by mutableStateOf(false)

    fun pushLevel(value: Float) {
        level = value
        levels.add(value)
        while (levels.size > WAVEFORM_BARS) levels.removeAt(0)
    }

    fun clearLevels() {
        levels.clear()
        level = 0f
    }

    companion object {
        const val WAVEFORM_BARS = 28
    }
}
