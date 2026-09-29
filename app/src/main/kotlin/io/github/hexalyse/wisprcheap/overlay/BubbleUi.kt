package io.github.hexalyse.wisprcheap.overlay

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hexalyse.wisprcheap.core.gesture.ToolbarButton
import kotlinx.coroutines.delay

/** Recording red, readable on both light and dark palettes. */
val RecordRed = Color(0xFFE5484D)

/** Extra room around the bubble inside its window, for the ring, the badge and the shadow. */
const val BUBBLE_BOX_EXTRA_DP = 20
const val HOLD_CHIP_AREA_DP = 44
const val HOLD_WIDTH_DP = 260
const val TOOLBAR_WIDTH_DP = 272
const val TOOLBAR_HEIGHT_DP = 68

@Composable
fun OverlayTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun OverlayContent(ui: OverlayState, onToolbar: (ToolbarButton) -> Unit, onToolbarTouch: (MotionEvent) -> Boolean) {
    when (ui.shape) {
        OverlayShape.TOOLBAR -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Toolbar(ui, onToolbar, onToolbarTouch)
        }
        OverlayShape.HOLDING -> Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = if (ui.chipBelow) Arrangement.Top else Arrangement.Bottom,
        ) {
            if (!ui.chipBelow) {
                HoldChip(ui)
                Spacer(Modifier.height(6.dp))
            }
            Box(
                Modifier
                    .offset { IntOffset(ui.holdOffsetXPx, 0) }
                    .size((ui.sizeDp + BUBBLE_BOX_EXTRA_DP).dp),
                contentAlignment = Alignment.Center,
            ) { Bubble(ui) }
            if (ui.chipBelow) {
                Spacer(Modifier.height(6.dp))
                HoldChip(ui)
            }
        }
        OverlayShape.BUBBLE -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Bubble(ui) }
    }
}

private fun iconFor(look: BubbleLook): ImageVector = when (look) {
    BubbleLook.COMMAND_ZONE -> Icons.Rounded.AutoAwesome
    BubbleLook.CANCEL_ZONE -> Icons.Rounded.Close
    BubbleLook.DONE -> Icons.Rounded.Check
    BubbleLook.COPIED -> Icons.Rounded.ContentPaste
    BubbleLook.ERROR -> Icons.Rounded.PriorityHigh
    BubbleLook.DISCARDED -> Icons.Rounded.MicOff
    else -> Icons.Rounded.Mic
}

@Composable
private fun Bubble(ui: OverlayState) {
    val cs = MaterialTheme.colorScheme
    val look = ui.look
    val neutral = look == BubbleLook.IDLE || look == BubbleLook.PRESSED || look == BubbleLook.PROCESSING || look == BubbleLook.DISCARDED
    val bg by animateColorAsState(
        when (look) {
            BubbleLook.RECORDING -> RecordRed
            BubbleLook.COMMAND_ZONE -> cs.tertiary
            BubbleLook.CANCEL_ZONE -> cs.outline
            BubbleLook.DONE -> cs.primary
            BubbleLook.COPIED -> cs.secondary
            BubbleLook.ERROR -> cs.error
            else -> cs.surfaceContainerHighest
        },
        label = "bubble color",
    )
    val fg = when (look) {
        BubbleLook.RECORDING -> Color.White
        BubbleLook.COMMAND_ZONE -> cs.onTertiary
        BubbleLook.CANCEL_ZONE -> cs.surface
        BubbleLook.DONE -> cs.onPrimary
        BubbleLook.COPIED -> cs.onSecondary
        BubbleLook.ERROR -> cs.onError
        else -> if (neutral) cs.primary else cs.onSurface
    }
    val recording = look == BubbleLook.RECORDING || look == BubbleLook.COMMAND_ZONE
    val scale by animateFloatAsState(
        when {
            look == BubbleLook.PRESSED -> 0.9f
            recording -> 1f + 0.16f * ui.level
            look == BubbleLook.CANCEL_ZONE -> 0.85f
            else -> 1f
        },
        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "bubble scale",
    )
    val alpha by animateFloatAsState(if (look == BubbleLook.IDLE) ui.idleOpacity else 1f, label = "bubble alpha")
    val shake = remember { Animatable(0f) }
    LaunchedEffect(look) {
        if (look == BubbleLook.ERROR) {
            shake.snapTo(0f)
            shake.animateTo(0f, keyframes {
                durationMillis = 360
                10f at 60
                -10f at 120
                7f at 180
                -7f at 240
                3f at 300
            })
        }
    }
    val size = ui.sizeDp.dp
    Box(contentAlignment = Alignment.Center) {
        if (look == BubbleLook.PRESSED) PressRing(size, ui.holdMs, cs.primary)
        Box(
            Modifier
                .offset { IntOffset(shake.value.dp.roundToPx(), 0) }
                .size(size)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                }
                .shadow(6.dp, CircleShape)
                .background(bg, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (look == BubbleLook.PROCESSING) {
                CircularProgressIndicator(Modifier.size(size * 0.5f), color = cs.primary, strokeWidth = 3.dp)
            } else {
                Icon(iconFor(look), contentDescription = null, tint = fg, modifier = Modifier.size(size * 0.52f))
            }
        }
        val badge = ui.translationBadge
        if (badge != null && neutral) {
            Text(
                badge,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .background(cs.tertiaryContainer, RoundedCornerShape(6.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                color = cs.onTertiaryContainer,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun PressRing(size: Dp, durationMs: Long, color: Color) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(durationMs.toInt(), easing = LinearEasing)) }
    Canvas(Modifier.size(size + 12.dp)) {
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 360f * progress.value,
            useCenter = false,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun HoldChip(ui: OverlayState) {
    val cs = MaterialTheme.colorScheme
    val (text, bg, fg) = when (ui.look) {
        BubbleLook.COMMAND_ZONE -> Triple("Release for a command", cs.tertiary, cs.onTertiary)
        BubbleLook.CANCEL_ZONE -> Triple("Release to cancel", cs.inverseSurface, cs.inverseOnSurface)
        else -> Triple(
            if (ui.commandEnabled) "↑ Command  ·  slide away to cancel" else "Slide away to cancel",
            cs.secondaryContainer,
            cs.onSecondaryContainer,
        )
    }
    Row(
        Modifier
            .shadow(4.dp, CircleShape)
            .background(bg, CircleShape)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ui.look == BubbleLook.COMMAND_ZONE) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = fg, modifier = Modifier.size(14.dp))
            Spacer(Modifier.size(4.dp))
        }
        Text(text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Toolbar(ui: OverlayState, onToolbar: (ToolbarButton) -> Unit, onTouch: (MotionEvent) -> Boolean) {
    val cs = MaterialTheme.colorScheme
    val accent = if (ui.commandMode) cs.tertiary else RecordRed
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(ui.recordingSince) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    val secs = ((now - ui.recordingSince) / 1000).coerceAtLeast(0)
    val label = if (ui.commandMode) "Command" + if (ui.hasSelection) " · selection" else "" else "Dictation"
    Row(
        Modifier
            .padding(6.dp)
            .fillMaxWidth()
            .height(56.dp)
            .shadow(8.dp, CircleShape)
            .background(cs.surfaceContainerHighest, CircleShape)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(onClick = { onToolbar(ToolbarButton.CANCEL) }) {
            Icon(Icons.Rounded.Close, contentDescription = "Cancel")
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInteropFilter { onTouch(it) }
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Waveform(ui.levels, accent, Modifier.fillMaxWidth().height(18.dp))
                Text(
                    "%d:%02d  %s".format(secs / 60, secs % 60, label),
                    color = cs.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
        }
        if (ui.commandEnabled) {
            FilledIconToggleButton(checked = ui.commandMode, onCheckedChange = { onToolbar(ToolbarButton.TOGGLE_COMMAND) }) {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = "Command mode")
            }
        }
        FilledIconButton(
            onClick = { onToolbar(ToolbarButton.SEND) },
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = accent, contentColor = Color.White),
        ) {
            Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send")
        }
    }
}

@Composable
private fun Waveform(levels: List<Float>, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val bars = OverlayState.WAVEFORM_BARS
        val step = size.width / bars
        val w = step * 0.55f
        val offset = bars - levels.size
        levels.forEachIndexed { i, level ->
            val h = (size.height * level).coerceAtLeast(w)
            val x = (offset + i) * step + step / 2
            drawLine(
                color = color,
                start = Offset(x, size.height / 2 - h / 2),
                end = Offset(x, size.height / 2 + h / 2),
                strokeWidth = w,
                cap = StrokeCap.Round,
            )
        }
    }
}
