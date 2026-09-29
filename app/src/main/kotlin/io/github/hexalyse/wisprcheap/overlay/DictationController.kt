package io.github.hexalyse.wisprcheap.overlay

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import io.github.hexalyse.wisprcheap.a11y.WisprAccessibilityService
import io.github.hexalyse.wisprcheap.audio.MicSession
import io.github.hexalyse.wisprcheap.core.gesture.GestureConfig
import io.github.hexalyse.wisprcheap.core.gesture.GestureEffect
import io.github.hexalyse.wisprcheap.core.gesture.GestureMachine
import io.github.hexalyse.wisprcheap.core.gesture.GestureState
import io.github.hexalyse.wisprcheap.core.gesture.RecordMode
import io.github.hexalyse.wisprcheap.core.gesture.ToolbarButton
import io.github.hexalyse.wisprcheap.core.gesture.Zone
import io.github.hexalyse.wisprcheap.core.history.Delivered
import io.github.hexalyse.wisprcheap.core.overlay.BubblePosition
import io.github.hexalyse.wisprcheap.core.overlay.SavedPosition
import io.github.hexalyse.wisprcheap.core.overlay.ScreenArea
import io.github.hexalyse.wisprcheap.core.overlay.VisibilityInput
import io.github.hexalyse.wisprcheap.core.overlay.VisibilityPolicy
import io.github.hexalyse.wisprcheap.core.pipeline.CapturedSelection
import io.github.hexalyse.wisprcheap.core.pipeline.DiscardReason
import io.github.hexalyse.wisprcheap.core.pipeline.Job
import io.github.hexalyse.wisprcheap.core.pipeline.PipelineEvent
import io.github.hexalyse.wisprcheap.core.settings.Settings
import io.github.hexalyse.wisprcheap.core.settings.Validation
import io.github.hexalyse.wisprcheap.core.translate.TranslationPair
import io.github.hexalyse.wisprcheap.core.translate.TranslationPairs
import io.github.hexalyse.wisprcheap.runtime.AppGraph
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

/** What a recording needs once it's sent: the settings and target captured when it started. */
private class RecordingContext(
    val settings: Settings,
    val pair: TranslationPair?,
    val targetApp: String?,
    val selection: Future<CapturedSelection?>,
)

/**
 * The bubble's brain (main thread): feeds touches to the [GestureMachine], applies its effects (recording,
 * haptics, window layout), queues the recordings as jobs and shows the pipeline results on the bubble.
 */
class DictationController(private val service: WisprAccessibilityService, private val graph: AppGraph) {
    private val main = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private val micExecutor = Executors.newSingleThreadExecutor { Thread(it, "wc-mic") }
    private val probeExecutor = Executors.newSingleThreadExecutor { Thread(it, "wc-probe") }
    private val wm = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    private val positions = service.getSharedPreferences("bubble", Context.MODE_PRIVATE)

    val ui = OverlayState()
    private val window = OverlayWindow(service, ui, ::onBubbleTouch, ::onToolbarButton, ::onToolbarTouch, ::onAccessibilityClick)
    private val haptics = Haptics(service, window.view) { graph.settings.current.bubble.haptics }
    private val machine = GestureMachine(gestureConfig(graph.settings.current))

    private var session: MicSession? = null
    private var recording: RecordingContext? = null
    private var anchorX = 0
    private var anchorY = 0
    private var dragOriginX = 0
    private var dragOriginY = 0
    private var dragging = false
    private var silentCancel = false

    private val hideRunnable = Runnable { window.hide() }
    private val holdRunnable = Runnable { apply(machine.onHoldTimeout(SystemClock.uptimeMillis())) }
    private val maxRunnable = Runnable {
        graph.log.info("Max duration (${graph.settings.current.recording.maxDurationSec.roundToInt()}s) reached, stopping.")
        apply(machine.onMaxDuration())
    }
    private val feedbackEnd = Runnable { if (!machine.isRecording && machine.state !is GestureState.Pressed) ui.look = idleLook() }

    init {
        onSettings(graph.settings.current)
        scope.launch { graph.settings.settings.collect { onSettings(it) } }
        scope.launch {
            graph.queue.pending.collect { n ->
                ui.pending = n
                if (n == 0 && ui.look == BubbleLook.PROCESSING) ui.look = BubbleLook.IDLE
            }
        }
        scope.launch { graph.events.collect { onPipelineEvent(it) } }
        refresh()
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()

    private fun gestureConfig(s: Settings) = GestureConfig(
        micStartDelayMs = s.bubble.micStartDelayMs,
        tapMaxMs = max(s.bubble.tapMaxMs, s.bubble.micStartDelayMs),
        touchSlopPx = ViewConfiguration.get(service).scaledTouchSlop.toFloat(),
        commandSlidePx = dp(s.bubble.commandSlideDp).toFloat(),
        cancelSlidePx = dp(s.bubble.cancelSlideDp).toFloat(),
        commandEnabled = s.command.enabled,
    )

    private fun onSettings(s: Settings) {
        machine.config = gestureConfig(s)
        val sizeChanged = ui.sizeDp != s.bubble.size.dp
        ui.sizeDp = s.bubble.size.dp
        ui.idleOpacity = s.bubble.idleOpacity.coerceIn(0.3f, 1f)
        ui.commandEnabled = s.command.enabled
        ui.translationBadge = TranslationPairs.active(s.translation)?.to?.uppercase()
        if (s.bubble.paused && machine.isRecording) apply(machine.onInterrupt(send = false))
        if (sizeChanged && window.isShown && !machine.isRecording) placeBubble()
        refresh()
    }

    // --- Visibility and position ---

    /** Re-evaluates whether the bubble is shown, and follows the keyboard. */
    fun refresh() {
        val s = graph.settings.current
        val e = graph.state.editor.value
        val active = machine.isRecording || machine.state !is GestureState.Idle
        val show = VisibilityPolicy.shouldShow(
            VisibilityInput(
                showWhen = s.bubble.showWhen,
                paused = s.bubble.paused,
                keyguardLocked = service.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true,
                packageName = e.targetPackage,
                excludedApps = s.bubble.excludedApps.toSet(),
                isPassword = e.isPassword,
                hideOnPasswordFields = s.bubble.hideOnPasswordFields,
                inputStarted = e.inputStarted,
                imeVisible = e.imeVisible,
                sessionActive = active,
            ),
        )
        if (show) {
            main.removeCallbacks(hideRunnable)
            if (!window.isShown) {
                placeBubble()
                window.show()
            } else if (!active && ui.shape == OverlayShape.BUBBLE) {
                placeBubble()
            }
        } else if (window.isShown) {
            main.removeCallbacks(hideRunnable)
            main.postDelayed(hideRunnable, 300)
        }
    }

    fun onConfigurationChanged() {
        if (!machine.isRecording) placeBubble()
    }

    private fun screen(): ScreenArea {
        val metrics = wm.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        return ScreenArea(metrics.bounds.width(), metrics.bounds.height(), insets.top, insets.bottom)
    }

    private fun boxPx() = dp(ui.sizeDp + BUBBLE_BOX_EXTRA_DP)

    private fun orientationKey() =
        if (service.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "land" else "port"

    private fun imeTop(): Int? =
        if (graph.settings.current.bubble.followKeyboard) graph.state.editor.value.imeBounds?.top else null

    private fun placeBubble() {
        val key = orientationKey()
        val saved = if (positions.contains("x_$key")) {
            SavedPosition(positions.getFloat("x_$key", 1f), positions.getInt("dy_$key", 0))
        } else {
            null
        }
        val box = boxPx()
        val (x, y) = BubblePosition.place(saved, box, dp(4), screen(), imeTop())
        anchorX = x + box / 2
        anchorY = y + box / 2
        layoutFor(ui.shape)
    }

    private fun saveBubblePosition() {
        val box = boxPx()
        val p = BubblePosition.save(window.x, window.y, box, dp(4), screen(), imeTop())
        val key = orientationKey()
        positions.edit().putFloat("x_$key", p.xFraction).putInt("dy_$key", p.dyPx).apply()
    }

    /** Sizes and positions the window for [shape] around the bubble anchor. */
    private fun layoutFor(shape: OverlayShape) {
        val sc = screen()
        val box = boxPx()
        when (shape) {
            OverlayShape.BUBBLE -> window.setBounds(anchorX - box / 2, anchorY - box / 2, box, box)
            OverlayShape.HOLDING -> {
                // The window (and so the hint chip) stays on screen; the bubble moves inside it to stay under the finger.
                val w = max(box, dp(HOLD_WIDTH_DP)).coerceAtMost(sc.width)
                val chip = dp(HOLD_CHIP_AREA_DP)
                val x = (anchorX - w / 2).coerceIn(0, max(0, sc.width - w))
                val above = anchorY - box / 2 - chip >= sc.topInset
                ui.holdOffsetXPx = anchorX - (x + w / 2)
                ui.chipBelow = !above
                window.setBounds(x, if (above) anchorY - box / 2 - chip else anchorY - box / 2, w, box + chip)
            }
            OverlayShape.TOOLBAR -> {
                val w = dp(TOOLBAR_WIDTH_DP)
                val h = dp(TOOLBAR_HEIGHT_DP)
                val x = (anchorX - w / 2).coerceIn(dp(4), max(dp(4), sc.width - w - dp(4)))
                val y = (anchorY - h / 2).coerceIn(sc.topInset, max(sc.topInset, sc.height - sc.bottomInset - h))
                window.setBounds(x, y, w, h)
            }
        }
    }

    // --- Touch ---

    private fun onBubbleTouch(e: MotionEvent): Boolean {
        apply(
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> machine.onDown(e.rawX, e.rawY, e.eventTime)
                MotionEvent.ACTION_MOVE -> machine.onMove(e.rawX, e.rawY, e.eventTime)
                MotionEvent.ACTION_UP -> machine.onUp(e.rawX, e.rawY, e.eventTime)
                MotionEvent.ACTION_CANCEL -> machine.onCancel()
                else -> emptyList()
            },
        )
        return true
    }

    /** The waveform area of the hands-free toolbar drags the toolbar. */
    private fun onToolbarTouch(e: MotionEvent): Boolean {
        onBubbleTouch(e)
        return true
    }

    private fun onToolbarButton(button: ToolbarButton) = apply(machine.onToolbar(button))

    /** TalkBack's double tap: acts like a tap (start hands-free), or sends a hands-free recording. */
    private fun onAccessibilityClick() {
        when (machine.state) {
            is GestureState.HandsFree -> apply(machine.onToolbar(ToolbarButton.SEND))
            GestureState.Idle -> {
                val t = SystemClock.uptimeMillis()
                apply(machine.onDown(anchorX.toFloat(), anchorY.toFloat(), t))
                apply(machine.onUp(anchorX.toFloat(), anchorY.toFloat(), t + 1))
            }
            else -> Unit
        }
    }

    fun onScreenOff() = apply(machine.onInterrupt(send = true))

    // --- Effects ---

    private fun apply(effects: List<GestureEffect>) {
        for (effect in effects) {
            when (effect) {
                is GestureEffect.ScheduleHoldTimer -> main.postDelayed(holdRunnable, effect.delayMs)
                GestureEffect.CancelHoldTimer -> main.removeCallbacks(holdRunnable)
                GestureEffect.PressFeedback -> {
                    main.removeCallbacks(feedbackEnd)
                    ui.holdMs = machine.config.micStartDelayMs
                    ui.look = BubbleLook.PRESSED
                }
                GestureEffect.ReleaseFeedback -> if (ui.look == BubbleLook.PRESSED) ui.look = idleLook()
                is GestureEffect.StartRecording -> startRecording(effect.handsFree)
                is GestureEffect.ZoneChanged -> {
                    ui.look = when (effect.zone) {
                        Zone.NORMAL -> BubbleLook.RECORDING
                        Zone.COMMAND -> BubbleLook.COMMAND_ZONE
                        Zone.CANCEL -> BubbleLook.CANCEL_ZONE
                    }
                    haptics.tick()
                }
                GestureEffect.ExpandToolbar -> {
                    ui.commandMode = false
                    ui.look = BubbleLook.RECORDING
                    ui.shape = OverlayShape.TOOLBAR
                    layoutFor(OverlayShape.TOOLBAR)
                }
                is GestureEffect.ModeChanged -> {
                    ui.commandMode = effect.mode == RecordMode.COMMAND
                    haptics.toggle(ui.commandMode)
                }
                is GestureEffect.Send -> {
                    haptics.confirm()
                    stopAndSend(effect.mode)
                }
                GestureEffect.Cancel -> {
                    if (!silentCancel) haptics.cancel()
                    silentCancel = false
                    cancelRecording()
                }
                GestureEffect.DragStart -> {
                    dragging = true
                    dragOriginX = window.x
                    dragOriginY = window.y
                }
                is GestureEffect.DragBy -> {
                    val sc = screen()
                    window.moveTo(
                        (dragOriginX + effect.dx.roundToInt()).coerceIn(0, max(0, sc.width - window.width)),
                        (dragOriginY + effect.dy.roundToInt()).coerceIn(0, max(0, sc.height - window.height)),
                    )
                }
                GestureEffect.DragEnd -> {
                    dragging = false
                    anchorX = window.x + window.width / 2
                    anchorY = window.y + window.height / 2
                    if (ui.shape == OverlayShape.BUBBLE) saveBubblePosition()
                    refresh()
                }
            }
        }
    }

    private fun idleLook() = if (ui.pending > 0) BubbleLook.PROCESSING else BubbleLook.IDLE

    // --- Recording ---

    private fun startRecording(handsFree: Boolean) {
        val s = graph.settings.current
        val problem = when {
            service.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                "Allow the microphone in the WisprCheap app first."
            else -> Validation.issues(s, graph.secrets.current).firstOrNull { it.blocking }?.message
        }
        if (problem != null) {
            graph.log.error("Can't record: $problem")
            graph.notifier.error("WisprCheap can't record", problem, openSettings = true)
            main.post {
                silentCancel = true
                apply(machine.onInterrupt(send = false))
                showFeedback(BubbleLook.ERROR, 2000)
                haptics.error()
            }
            return
        }
        val selection = probeExecutor.submit<CapturedSelection?> { runCatching { service.inserter.captureSelection() }.getOrNull() }
        recording = RecordingContext(s, TranslationPairs.active(s.translation), graph.state.editor.value.targetPackage, selection)
        val requestedAt = SystemClock.elapsedRealtime()
        val mic = MicSession(service, s.recording.audioSource) { level -> main.post { if (session != null) ui.pushLevel(level) } }
        session = mic
        micExecutor.execute { if (!mic.start(requestedAt)) main.post { onMicFailed(mic) } }
        probeExecutor.execute {
            val has = runCatching { selection.get() != null }.getOrDefault(false)
            main.post { ui.hasSelection = has }
        }

        main.removeCallbacks(feedbackEnd)
        ui.clearLevels()
        ui.recordingSince = requestedAt
        ui.hasSelection = false
        ui.look = BubbleLook.RECORDING
        if (!handsFree) {
            ui.shape = OverlayShape.HOLDING
            layoutFor(OverlayShape.HOLDING)
        }
        haptics.start()
        graph.state.recording.value = true
        window.keepScreenOn(s.bubble.keepScreenOnWhileRecording)
        main.postDelayed(maxRunnable, (s.recording.maxDurationSec * 1000).toLong())
    }

    private fun onMicFailed(mic: MicSession) {
        if (session !== mic) return // already stopped: the stop path reports it
        silentCancel = true
        apply(machine.onInterrupt(send = false))
        reportMicError(mic.error)
    }

    private fun reportMicError(error: String?) {
        val message = error ?: "unknown error"
        graph.log.error("Could not start the microphone: $message")
        graph.notifier.error("Microphone unavailable", message)
        showFeedback(BubbleLook.ERROR, 2000)
        haptics.error()
    }

    private fun endRecordingUi() {
        main.removeCallbacks(maxRunnable)
        window.keepScreenOn(false)
        graph.state.recording.value = false
        ui.commandMode = false
        ui.shape = OverlayShape.BUBBLE
        placeBubble()
    }

    private fun stopAndSend(mode: RecordMode) {
        val mic = session ?: return
        val ctx = recording ?: return
        session = null
        recording = null
        endRecordingUi()
        ui.look = BubbleLook.PROCESSING
        micExecutor.execute {
            Thread.sleep(ctx.settings.recording.tailMs) // keep recording the end of the last word
            val result = mic.stop()
            if (result.error != null) {
                main.post {
                    ui.look = idleLook()
                    reportMicError(result.error)
                }
                return@execute
            }
            val pcm = mic.pcm()
            val selection = runCatching { ctx.selection.get(1, TimeUnit.SECONDS) }.getOrNull()
            val job = if (mode == RecordMode.COMMAND) {
                Job.Command(pcm, ctx.settings, ctx.targetApp, selection)
            } else {
                Job.Dictation(pcm, ctx.settings, ctx.targetApp, ctx.pair)
            }
            graph.enqueue(job)
            main.post { refresh() }
        }
    }

    private fun cancelRecording() {
        val mic = session
        session = null
        recording = null
        endRecordingUi()
        if (ui.look != BubbleLook.ERROR) ui.look = idleLook()
        if (mic != null) {
            graph.log.info("Recording cancelled.")
            micExecutor.execute { mic.stop() }
        }
        refresh()
    }

    // --- Results ---

    private fun onPipelineEvent(e: PipelineEvent) {
        when (e) {
            is PipelineEvent.Done -> if (!e.retry) {
                if (e.delivered == Delivered.CLIPBOARD) {
                    showFeedback(BubbleLook.COPIED, 1200)
                } else {
                    showFeedback(BubbleLook.DONE, 700)
                    if (graph.settings.current.bubble.hapticOnInsert) haptics.confirm()
                }
            }
            is PipelineEvent.TranscriptionFailed, is PipelineEvent.CommandFailed, is PipelineEvent.DeliveryFailed,
            PipelineEvent.MicSilenced,
            -> {
                showFeedback(BubbleLook.ERROR, 2000)
                haptics.error()
            }
            is PipelineEvent.Discarded -> if (e.reason != DiscardReason.TOO_SHORT) {
                showFeedback(BubbleLook.DISCARDED, 900)
                haptics.cancel()
            }
            else -> Unit
        }
    }

    private fun showFeedback(look: BubbleLook, durationMs: Long) {
        if (machine.isRecording || machine.state is GestureState.Pressed) return
        ui.look = look
        main.removeCallbacks(feedbackEnd)
        main.postDelayed(feedbackEnd, durationMs)
    }

    fun destroy() {
        if (machine.isRecording) apply(machine.onInterrupt(send = false))
        main.removeCallbacksAndMessages(null)
        scope.cancel()
        window.destroy()
        micExecutor.shutdown()
        probeExecutor.shutdown()
    }
}
