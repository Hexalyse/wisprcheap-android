package io.github.hexalyse.wisprcheap.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import io.github.hexalyse.wisprcheap.TAG
import io.github.hexalyse.wisprcheap.WisprApp
import io.github.hexalyse.wisprcheap.audio.MicFgsService
import io.github.hexalyse.wisprcheap.audio.MicSession
import io.github.hexalyse.wisprcheap.audio.Trampoline
import io.github.hexalyse.wisprcheap.audio.TrampolineActivity
import io.github.hexalyse.wisprcheap.diag.ProbeResult
import io.github.hexalyse.wisprcheap.overlay.OverlayController
import io.github.hexalyse.wisprcheap.state.EditorState
import io.github.hexalyse.wisprcheap.state.ScreenBox
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Phase 0 version of the runtime: tracks the focused editor and the keyboard, shows the bubble, and runs the
 * microphone and insertion tests from the bubble / test panel.
 */
class WisprAccessibilityService : AccessibilityService() {
    private val app get() = WisprApp.instance
    private val main = Handler(Looper.getMainLooper())
    private val micExecutor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "wc-mic") }
    private val probeExecutor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "wc-probe") }
    private var overlay: OverlayController? = null
    private lateinit var insertion: InsertionProbe

    private var ptt: MicSession? = null
    private var pttTarget: String? = null
    private var pttDownAt = 0L
    private var pttRequestedAt = 0L

    override fun onCreateInputMethod(): InputMethod = A11yInputMethod(this)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        insertion = InsertionProbe(this)
        overlay = OverlayController(this)
        updateEditor { it.copy(serviceConnected = true) }
        refreshWindows()
        refreshFocus()
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> refreshWindows()
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString()
                if (pkg != null && pkg != packageName) updateEditor { it.copy(activePackage = pkg) }
                refreshWindows()
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> refreshFocus()
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        if (instance === this) instance = null
        ptt?.stop()
        ptt = null
        overlay?.destroy()
        overlay = null
        updateEditor { EditorState() }
    }

    // --- Editor and keyboard tracking ---

    private fun updateEditor(transform: (EditorState) -> EditorState) {
        app.runtime.updateEditor(transform)
        main.post { overlay?.onStateChanged() }
    }

    fun onEditorStarted(info: EditorInfo) = updateEditor {
        it.copy(
            inputStarted = true,
            editorPackage = info.packageName,
            inputType = info.inputType,
            selStart = info.initialSelStart,
            selEnd = info.initialSelEnd,
        )
    }

    fun onEditorFinished() = updateEditor { it.copy(inputStarted = false, editorPackage = null, inputType = 0) }

    fun onSelectionChanged(start: Int, end: Int) = app.runtime.updateEditor { it.copy(selStart = start, selEnd = end) }

    private fun refreshWindows() {
        val ime = runCatching { windows }.getOrDefault(emptyList())
            .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        val box = ime?.let {
            val r = Rect()
            it.getBoundsInScreen(r)
            ScreenBox(r.left, r.top, r.right, r.bottom)
        }
        val visible = box != null && box.bottom > box.top && box.right > box.left
        updateEditor { it.copy(imeVisible = visible, imeBounds = if (visible) box else null) }
    }

    private fun refreshFocus() {
        val node = runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        updateEditor {
            it.copy(
                focusedEditable = node?.isEditable == true,
                focusedClass = node?.className?.toString(),
                focusedPackage = node?.packageName?.toString(),
            )
        }
    }

    // --- Push-to-talk test (hold the bubble) ---

    fun startPtt(downAt: Long) {
        if (ptt != null) return
        pttDownAt = downAt
        pttRequestedAt = SystemClock.elapsedRealtime()
        pttTarget = app.runtime.editor.value.targetPackage
        val session = MicSession(this)
        ptt = session
        micExecutor.execute { session.start(pttRequestedAt) }
    }

    fun stopPtt() {
        val session = ptt ?: return
        ptt = null
        val target = pttTarget
        val holdDelay = pttRequestedAt - pttDownAt
        micExecutor.execute {
            val r = session.stop()
            app.diagnostics.add(
                ProbeResult(
                    System.currentTimeMillis(), "mic.hold", target, r.ok, r.summary(),
                    r.details() + listOf("mic start delay ms (hold before recording)" to "$holdDelay"),
                ),
            )
        }
    }

    // --- Test panel actions ---

    fun runAction(id: String) {
        val requestedAt = SystemClock.elapsedRealtime()
        val target = app.runtime.editor.value.targetPackage
        when (id) {
            ACTION_MIC_DIRECT -> {
                overlay?.setBusy("Recording 3 s: speak now…")
                micExecutor.execute {
                    val session = MicSession(this)
                    session.start(requestedAt)
                    Thread.sleep(3000)
                    val r = session.stop()
                    app.diagnostics.add(
                        ProbeResult(System.currentTimeMillis(), "mic.direct", target, r.ok, r.summary(), r.details()),
                    )
                    main.post { overlay?.setBusy(null) }
                }
            }
            ACTION_MIC_TRAMPOLINE -> startTrampoline(target)
            ACTION_MIC_FGS -> startFgsFromService(target)
            ACTION_EDITOR -> probe { insertion.editorInfo() }
            ACTION_COMMIT -> probe { insertion.commitText(app.runtime.nextToken()) }
            ACTION_SET_TEXT -> probe { insertion.setText(app.runtime.nextToken()) }
            ACTION_PASTE_NODE -> probe { insertion.pasteNode(app.runtime.nextToken()) }
            ACTION_PASTE_IC -> probe { insertion.pasteIc(app.runtime.nextToken()) }
            ACTION_SELECTION -> probe { insertion.selection() }
        }
    }

    private fun probe(block: () -> ProbeResult) {
        overlay?.setBusy("Running…")
        probeExecutor.execute {
            val r = runCatching(block).getOrElse {
                ProbeResult(
                    System.currentTimeMillis(), "probe", app.runtime.editor.value.targetPackage, false,
                    "crashed: ${it.javaClass.simpleName}: ${it.message}",
                )
            }
            app.diagnostics.add(r)
            main.post { overlay?.setBusy(null) }
        }
    }

    private fun startTrampoline(target: String?) {
        Trampoline.reset()
        overlay?.setBusy("Trampoline: recording 3 s, speak…")
        try {
            startActivity(
                Intent(this, TrampolineActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                ),
            )
        } catch (e: Exception) {
            app.diagnostics.add(
                ProbeResult(
                    System.currentTimeMillis(), "mic.trampoline", target, false,
                    "startActivity failed: ${e.javaClass.simpleName}", listOf("exception" to "$e"),
                ),
            )
            overlay?.setBusy(null)
            return
        }
        main.postDelayed({
            if (Trampoline.activityCreatedAt == 0L) {
                app.diagnostics.add(
                    ProbeResult(
                        System.currentTimeMillis(), "mic.trampoline", target, false,
                        "activity did not start within 2 s (background activity start blocked?)",
                    ),
                )
            } else if (Trampoline.startError != null) {
                app.diagnostics.add(
                    ProbeResult(
                        System.currentTimeMillis(), "mic.trampoline", target, false,
                        "startService from the activity failed", listOf("exception" to "${Trampoline.startError}"),
                    ),
                )
            }
        }, 2000)
        main.postDelayed({ overlay?.setBusy(null) }, 4500)
    }

    private fun startFgsFromService(target: String?) {
        try {
            startService(
                Intent(this, MicFgsService::class.java)
                    .putExtra(MicFgsService.EXTRA_MODE, MicFgsService.MODE_DIRECT)
                    .putExtra(MicFgsService.EXTRA_REQUESTED_AT, SystemClock.elapsedRealtime()),
            )
            overlay?.setBusy("FGS from service: recording 3 s if allowed…")
            main.postDelayed({ overlay?.setBusy(null) }, 4000)
        } catch (e: Exception) {
            app.diagnostics.add(
                ProbeResult(
                    System.currentTimeMillis(), "mic.fgs-from-service", target, false,
                    "startService refused: ${e.javaClass.simpleName}", listOf("exception" to "$e"),
                ),
            )
        }
    }

    companion object {
        @Volatile var instance: WisprAccessibilityService? = null
            private set

        const val ACTION_MIC_DIRECT = "mic.direct"
        const val ACTION_MIC_TRAMPOLINE = "mic.trampoline"
        const val ACTION_MIC_FGS = "mic.fgs"
        const val ACTION_EDITOR = "editor"
        const val ACTION_COMMIT = "insert.commitText"
        const val ACTION_SET_TEXT = "insert.setText"
        const val ACTION_PASTE_NODE = "insert.pasteNode"
        const val ACTION_PASTE_IC = "insert.pasteIc"
        const val ACTION_SELECTION = "selection"
    }
}
