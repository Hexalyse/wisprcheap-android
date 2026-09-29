package io.github.hexalyse.wisprcheap.a11y

import android.accessibilityservice.InputMethod
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import io.github.hexalyse.wisprcheap.core.history.Delivered
import io.github.hexalyse.wisprcheap.core.insert.TextSplice
import io.github.hexalyse.wisprcheap.core.pipeline.CapturedSelection
import io.github.hexalyse.wisprcheap.core.pipeline.Delivery
import io.github.hexalyse.wisprcheap.core.pipeline.DeliveryKind
import io.github.hexalyse.wisprcheap.core.pipeline.DeliveryRequest
import io.github.hexalyse.wisprcheap.core.pipeline.DeliveryResult
import io.github.hexalyse.wisprcheap.core.settings.InsertMethod
import io.github.hexalyse.wisprcheap.runtime.AppGraph
import io.github.hexalyse.wisprcheap.runtime.Clipboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Inserts text in the focused field (PLAN.md section 8): accessibility input connection `commitText`, then
 * `ACTION_SET_TEXT`, then clipboard + paste, then clipboard only. Every step is verified before trying the next.
 */
class TextInserter(private val service: WisprAccessibilityService, private val graph: AppGraph) : Delivery {
    private val main = Handler(Looper.getMainLooper())

    override suspend fun deliver(request: DeliveryRequest): DeliveryResult =
        withContext(Dispatchers.IO) { deliverBlocking(request) }

    private fun inputConnection(): InputMethod.AccessibilityInputConnection? =
        service.inputMethod?.takeIf { it.currentInputStarted }?.currentInputConnection

    private fun focusedNode(): AccessibilityNodeInfo? =
        runCatching { service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()?.takeIf { it.isEditable }

    private fun targetPackage(node: AccessibilityNodeInfo?): String? =
        service.inputMethod?.takeIf { it.currentInputStarted }?.currentInputEditorInfo?.packageName
            ?: node?.packageName?.toString()

    /** The selection in the focused field, captured when a recording starts (for command mode). */
    fun captureSelection(): CapturedSelection? {
        val ic = inputConnection()
        val st = ic?.let { runCatching { it.getSurroundingText(0, 0, 0) }.getOrNull() }
        if (st != null) {
            val a = st.selectionStart.coerceIn(0, st.text.length)
            val b = st.selectionEnd.coerceIn(a, st.text.length)
            return if (b > a) CapturedSelection(st.text.subSequence(a, b).toString(), st.offset + a, st.offset + b) else null
        }
        val node = focusedNode() ?: return null
        val text = if (node.isShowingHintText) return null else node.text?.toString() ?: return null
        val a = minOf(node.textSelectionStart, node.textSelectionEnd)
        val b = maxOf(node.textSelectionStart, node.textSelectionEnd)
        return if (a >= 0 && b > a && b <= text.length) CapturedSelection(text.substring(a, b), a, b) else null
    }

    private fun deliverBlocking(req: DeliveryRequest): DeliveryResult {
        val settings = graph.settings.current
        if (req.kind == DeliveryKind.CLIPBOARD_ONLY) return clipboard(req.text)
        val ic = inputConnection()
        val node = focusedNode()
        if (ic == null && node == null) {
            toast("No text field: copied to the clipboard")
            return clipboard(req.text)
        }
        val pkg = targetPackage(node)
        val selection = req.selection
        if (req.kind == DeliveryKind.COMMAND && selection != null) return replaceSelection(selection, req.text, ic, node)

        val text = if (req.kind == DeliveryKind.DICTATION) spaced(req, ic, node) else req.text
        val methods = order(settings.output.insertMethod, settings.output.perApp[pkg])
        val nodeLengthBefore = node?.let { if (it.isShowingHintText) 0 else it.text?.length ?: 0 }
        for ((i, method) in methods.withIndex()) {
            if (i > 0 && alreadyThere(text, node, nodeLengthBefore)) return success(method, pkg, methods.first(), settings.output.insertMethod)
            val ok = runCatching {
                when (method) {
                    InsertMethod.INPUT_CONNECTION -> viaInputConnection(text, ic)
                    InsertMethod.SET_TEXT -> viaSetText(text, node, ic)
                    InsertMethod.PASTE -> viaPaste(text, node, ic, settings.output.hideClipboardPreview)
                    else -> false
                }
            }.getOrElse {
                graph.log.detail("  [insert] ${method.name} failed: ${it.javaClass.simpleName}: ${it.message}")
                false
            }
            if (ok) return success(method, pkg, methods.first(), settings.output.insertMethod)
        }
        if (methods.isNotEmpty()) toast("Couldn't insert the text: copied to the clipboard")
        return clipboard(text)
    }

    private fun success(method: InsertMethod, pkg: String?, first: InsertMethod, configured: InsertMethod): DeliveryResult {
        // Remember what works for this app when it's not the default first choice.
        if (configured == InsertMethod.AUTO && pkg != null && method != first && method != InsertMethod.INPUT_CONNECTION) {
            graph.settings.update { it.copy(output = it.output.copy(perApp = it.output.perApp + (pkg to method))) }
            graph.log.detail("  [insert] $pkg: ${method.name} worked, it will be tried first next time.")
        } else if (configured == InsertMethod.AUTO && pkg != null && method == InsertMethod.INPUT_CONNECTION &&
            graph.settings.current.output.perApp.containsKey(pkg)
        ) {
            graph.settings.update { it.copy(output = it.output.copy(perApp = it.output.perApp - pkg)) }
        }
        return DeliveryResult(if (method == InsertMethod.PASTE) Delivered.PASTED else Delivered.INSERTED, method.id)
    }

    private val InsertMethod.id: String
        get() = when (this) {
            InsertMethod.INPUT_CONNECTION -> "inputConnection"
            InsertMethod.SET_TEXT -> "setText"
            InsertMethod.PASTE -> "paste"
            InsertMethod.CLIPBOARD -> "clipboard"
            InsertMethod.AUTO -> "auto"
        }

    private fun order(configured: InsertMethod, learned: InsertMethod?): List<InsertMethod> {
        val all = listOf(InsertMethod.INPUT_CONNECTION, InsertMethod.SET_TEXT, InsertMethod.PASTE)
        return when (configured) {
            InsertMethod.AUTO -> (listOfNotNull(learned?.takeIf { it in all }) + all).distinct()
            InsertMethod.CLIPBOARD -> emptyList()
            else -> listOf(configured)
        }
    }

    /** Dictation spacing from the characters around the cursor (or the desktop trailing-space rule). */
    private fun spaced(req: DeliveryRequest, ic: InputMethod.AccessibilityInputConnection?, node: AccessibilityNodeInfo?): String {
        var before: Char? = null
        var after: Char? = null
        var known = false
        val st = ic?.let { runCatching { it.getSurroundingText(1, 1, 0) }.getOrNull() }
        if (st != null) {
            known = true
            val t = st.text
            val a = st.selectionStart.coerceIn(0, t.length)
            val b = st.selectionEnd.coerceIn(a, t.length)
            before = if (a > 0) t[a - 1] else null
            after = if (b < t.length) t[b] else null
        } else if (node != null) {
            val text = if (node.isShowingHintText) "" else node.text?.toString()
            val a = minOf(node.textSelectionStart, node.textSelectionEnd)
            val b = maxOf(node.textSelectionStart, node.textSelectionEnd)
            if (text != null && (text.isEmpty() || (a >= 0 && b <= text.length))) {
                known = true
                if (text.isNotEmpty()) {
                    before = if (a > 0) text[a - 1] else null
                    after = if (b < text.length) text[b] else null
                }
            }
        }
        return TextSplice.forDictation(req.text, before, after, known, req.smartSpacing, req.trailingSpace)
    }

    private fun textBeforeCursor(ic: InputMethod.AccessibilityInputConnection?, length: Int): String? {
        val st = ic?.let { runCatching { it.getSurroundingText(length, 0, 0) }.getOrNull() } ?: return null
        return st.text.subSequence(0, st.selectionStart.coerceIn(0, st.text.length)).toString()
    }

    /** Polls until [check] is true (the app may apply the change asynchronously). */
    private fun eventually(check: () -> Boolean?): Boolean? {
        var last: Boolean? = null
        repeat(6) { i ->
            if (i > 0) Thread.sleep(60)
            last = check()
            if (last == true) return true
        }
        return last
    }

    private fun viaInputConnection(text: String, ic: InputMethod.AccessibilityInputConnection?): Boolean {
        ic ?: return false
        val before = textBeforeCursor(ic, text.length + 1)
        ic.commitText(text, 1, null)
        val verified = eventually { textBeforeCursor(ic, text.length + 1)?.endsWith(text) }
        // Editors that don't report their text: trust the commit rather than inserting twice.
        return verified ?: (before == null)
    }

    private fun viaSetText(text: String, node: AccessibilityNodeInfo?, ic: InputMethod.AccessibilityInputConnection?): Boolean {
        node ?: return false
        val current = if (node.isShowingHintText) "" else node.text?.toString() ?: ""
        var s = node.textSelectionStart
        var e = node.textSelectionEnd
        if (s < 0 || e < 0 || s > current.length || e > current.length) {
            s = current.length
            e = s
        }
        val (newText, cursor) = TextSplice.replace(current, s, e, text)
        val ok = node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText) },
        )
        if (!ok) return false
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_SELECTION,
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
            },
        )
        val verified = eventually {
            runCatching { node.refresh() }
            node.text?.toString()?.contains(text.trim())
        } == true
        if (verified) {
            runCatching { node.refresh() }
            if (node.textSelectionStart != cursor) runCatching { ic?.setSelection(cursor, cursor) }
        }
        return verified
    }

    private fun viaPaste(text: String, node: AccessibilityNodeInfo?, ic: InputMethod.AccessibilityInputConnection?, sensitive: Boolean): Boolean {
        Clipboard.set(service, text, sensitive)
        val pasted = when {
            node != null -> node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            ic != null -> runCatching { ic.performContextMenuAction(android.R.id.paste) }.isSuccess
            else -> false
        }
        if (!pasted) return false
        return eventually {
            textBeforeCursor(ic, text.length + 1)?.endsWith(text)
                ?: node?.let { runCatching { it.refresh() }; it.text?.toString()?.contains(text.trim()) }
        } == true
    }

    /** True when a previous step inserted the text after all (verification missed it): avoids inserting twice. */
    private fun alreadyThere(text: String, node: AccessibilityNodeInfo?, lengthBefore: Int?): Boolean {
        node ?: return false
        runCatching { node.refresh() }
        val now = node.text?.toString() ?: return false
        return lengthBefore != null && now.length >= lengthBefore + text.trim().length && now.contains(text.trim())
    }

    /** Command result: replaces the selection captured at the start of the recording, if it's still there. */
    private fun replaceSelection(
        sel: CapturedSelection,
        text: String,
        ic: InputMethod.AccessibilityInputConnection?,
        node: AccessibilityNodeInfo?,
    ): DeliveryResult {
        if (ic != null) {
            val st = runCatching { ic.getSurroundingText(0, 0, 0) }.getOrNull()
            if (st != null) {
                val current = st.text.subSequence(
                    st.selectionStart.coerceIn(0, st.text.length),
                    st.selectionEnd.coerceIn(st.selectionStart.coerceIn(0, st.text.length), st.text.length),
                ).toString()
                val selectedAgain = current == sel.text || run {
                    val (prevStart, prevEnd) = (st.offset + st.selectionStart) to (st.offset + st.selectionEnd)
                    ic.setSelection(sel.start, sel.end)
                    val check = runCatching { ic.getSurroundingText(0, 0, 0) }.getOrNull()
                    val ok = check != null && check.text.subSequence(
                        check.selectionStart.coerceIn(0, check.text.length),
                        check.selectionEnd.coerceIn(check.selectionStart.coerceIn(0, check.text.length), check.text.length),
                    ).toString() == sel.text
                    if (!ok) ic.setSelection(prevStart, prevEnd)
                    ok
                }
                if (selectedAgain) {
                    ic.commitText(text, 1, null)
                    return DeliveryResult(Delivered.INSERTED, "inputConnection")
                }
            }
        }
        if (node != null && !node.isShowingHintText) {
            val current = node.text?.toString()
            if (current != null && sel.end <= current.length && current.substring(sel.start, sel.end) == sel.text) {
                val (newText, cursor) = TextSplice.replace(current, sel.start, sel.end, text)
                val ok = node.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText) },
                )
                if (ok) {
                    node.performAction(
                        AccessibilityNodeInfo.ACTION_SET_SELECTION,
                        Bundle().apply {
                            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
                        },
                    )
                    return DeliveryResult(Delivered.INSERTED, "setText")
                }
            }
        }
        toast("The selection changed: the result was copied to the clipboard")
        return clipboard(text)
    }

    private fun clipboard(text: String): DeliveryResult {
        Clipboard.set(service, text, graph.settings.current.output.hideClipboardPreview)
        return DeliveryResult(Delivered.CLIPBOARD, "clipboard")
    }

    private fun toast(message: String) = main.post { Toast.makeText(service, message, Toast.LENGTH_SHORT).show() }
}
