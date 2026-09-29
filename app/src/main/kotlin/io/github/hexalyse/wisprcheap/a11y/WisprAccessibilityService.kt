package io.github.hexalyse.wisprcheap.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import io.github.hexalyse.wisprcheap.WisprApp
import io.github.hexalyse.wisprcheap.runtime.EditorState
import io.github.hexalyse.wisprcheap.runtime.ScreenBox
import io.github.hexalyse.wisprcheap.overlay.DictationController

/**
 * The runtime: tracks the focused editor and the keyboard, shows the bubble, records, and inserts the text.
 * Bound by the system while enabled, which keeps the process alive and allows recording from the background.
 */
class WisprAccessibilityService : AccessibilityService() {
    private val graph get() = WisprApp.graph
    private var controller: DictationController? = null
    lateinit var inserter: TextInserter
        private set

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> controller?.onScreenOff()
                Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON -> controller?.refresh()
            }
        }
    }

    override fun onCreateInputMethod(): InputMethod = A11yInputMethod(this)

    override fun onServiceConnected() {
        super.onServiceConnected()
        inserter = TextInserter(this, graph)
        graph.delivery = inserter
        graph.state.updateEditor { it.copy(serviceConnected = true) }
        controller = DictationController(this, graph)
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            RECEIVER_NOT_EXPORTED,
        )
        refreshWindows()
        refreshFocus()
        graph.log.info("Accessibility service connected.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> refreshWindows()
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString()
                if (pkg != null && pkg != packageName) graph.state.updateEditor { it.copy(activePackage = pkg) }
                refreshWindows()
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> refreshFocus()
        }
    }

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        controller?.onConfigurationChanged()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        val c = controller ?: return
        controller = null
        runCatching { unregisterReceiver(screenReceiver) }
        c.destroy()
        if (graph.delivery === inserter) graph.delivery = null
        graph.state.updateEditor { EditorState() }
        graph.log.info("Accessibility service stopped.")
    }

    fun onEditorStarted(info: EditorInfo) {
        graph.state.updateEditor { it.copy(inputStarted = true, editorPackage = info.packageName, inputType = info.inputType) }
        controller?.refresh()
    }

    fun onEditorFinished() {
        graph.state.updateEditor { it.copy(inputStarted = false, editorPackage = null, inputType = 0) }
        controller?.refresh()
    }

    private fun refreshWindows() {
        val ime = runCatching { windows }.getOrDefault(emptyList())
            .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        val box = ime?.let {
            val r = Rect()
            it.getBoundsInScreen(r)
            ScreenBox(r.left, r.top, r.right, r.bottom)
        }
        val visible = box != null && box.bottom > box.top && box.right > box.left
        val before = graph.state.editor.value
        if (before.imeVisible != visible || before.imeBounds != box) {
            graph.state.updateEditor { it.copy(imeVisible = visible, imeBounds = if (visible) box else null) }
            controller?.refresh()
        }
    }

    private fun refreshFocus() {
        val node = runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        graph.state.updateEditor {
            it.copy(focusedEditable = node?.isEditable == true, focusedPackage = node?.packageName?.toString())
        }
        controller?.refresh()
    }
}
