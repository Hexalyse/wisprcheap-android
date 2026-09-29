package io.github.hexalyse.wisprcheap.a11y

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Bundle
import android.os.PersistableBundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import io.github.hexalyse.wisprcheap.WisprApp
import io.github.hexalyse.wisprcheap.diag.ProbeResult
import io.github.hexalyse.wisprcheap.state.InputTypes

/**
 * Phase 0 insertion tests on the focused editor. Every method blocks (IPC + short waits): call it off the
 * main thread.
 */
class InsertionProbe(private val service: WisprAccessibilityService) {
    private val editor get() = WisprApp.instance.runtime.editor.value

    private fun ic() = service.inputMethod?.currentInputConnection

    private fun focusedNode(): AccessibilityNodeInfo? =
        runCatching { service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()

    private fun result(test: String, ok: Boolean, summary: String, details: List<Pair<String, String>>) =
        ProbeResult(System.currentTimeMillis(), test, editor.targetPackage, ok, summary, details + context())

    private fun context(): List<Pair<String, String>> = listOf("editor state" to editor.describe())

    /** Text before the cursor as reported by the input connection, or null if unavailable. */
    private fun textBeforeCursor(): String? = runCatching {
        val st = ic()?.getSurroundingText(80, 0, 0) ?: return null
        st.text.subSequence(0, st.selectionStart.coerceIn(0, st.text.length)).toString()
    }.getOrNull()

    private fun nodeContains(node: AccessibilityNodeInfo?, token: String): Boolean? {
        node ?: return null
        runCatching { node.refresh() }
        return node.text?.toString()?.contains(token.trim())
    }

    fun commitText(token: String): ProbeResult {
        val test = "insert.commitText"
        val ic = ic() ?: return result(
            test, false, "no input connection",
            listOf("inputStarted" to "${service.inputMethod?.currentInputStarted}"),
        )
        val before = textBeforeCursor()
        val error = runCatching { ic.commitText(token, 1, null) }.exceptionOrNull()
        Thread.sleep(300)
        val after = textBeforeCursor()
        val viaIc = after?.endsWith(token)
        val viaNode = nodeContains(focusedNode(), token)
        val ok = error == null && (viaIc == true || (viaIc == null && viaNode == true))
        val how = when {
            viaIc == true -> "verified via surrounding text"
            viaIc == null && viaNode == true -> "verified via node text (surrounding text unavailable)"
            viaIc == null && viaNode == null -> "UNVERIFIED (no surrounding text, no node)"
            else -> "text not found after commit"
        }
        return result(
            test, ok, if (error != null) "exception: ${error.javaClass.simpleName}" else how,
            listOf(
                "exception" to (error?.toString() ?: "none"),
                "surrounding text before" to (before?.let { "available (${it.length} chars)" } ?: "unavailable"),
                "surrounding text after" to (after?.let { "…" + it.takeLast(40) } ?: "unavailable"),
                "node contains token" to "$viaNode",
            ),
        )
    }

    fun setText(token: String): ProbeResult {
        val test = "insert.setText"
        val node = focusedNode() ?: return result(test, false, "no input-focused node", emptyList())
        if (!node.isEditable) return result(test, false, "focused node is not editable", nodeDetails(node))
        val hint = node.isShowingHintText
        val current = if (hint) "" else node.text?.toString() ?: ""
        var s = node.textSelectionStart
        var e = node.textSelectionEnd
        if (s < 0 || e < 0 || s > current.length || e > current.length) {
            s = current.length
            e = s
        }
        val a = minOf(s, e)
        val b = maxOf(s, e)
        val newText = current.substring(0, a) + token + current.substring(b)
        val setOk = node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
            },
        )
        val pos = a + token.length
        val selOk = node.performAction(
            AccessibilityNodeInfo.ACTION_SET_SELECTION,
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, pos)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, pos)
            },
        )
        Thread.sleep(300)
        val contains = nodeContains(node, token)
        val cursorAfter = node.textSelectionStart
        val lostText = !hint && current.isNotEmpty() && node.text?.toString()?.contains(current) == false
        return result(
            test, setOk && contains == true,
            when {
                !setOk -> "ACTION_SET_TEXT returned false"
                contains != true -> "text not found after SET_TEXT"
                lostText -> "inserted, but the previous text changed"
                cursorAfter != pos -> "inserted; cursor at $cursorAfter instead of $pos"
                else -> "inserted and verified, cursor placed"
            },
            nodeDetails(node) + listOf(
                "showing hint" to "$hint",
                "text length before" to "${current.length}",
                "selection before" to "$s..$e",
                "SET_TEXT returned" to "$setOk",
                "SET_SELECTION returned" to "$selOk",
                "cursor after" to "$cursorAfter (expected $pos)",
                "previous text preserved" to "${!lostText}",
            ),
        )
    }

    fun pasteNode(token: String): ProbeResult {
        val test = "insert.pasteNode"
        val node = focusedNode() ?: return result(test, false, "no input-focused node", emptyList())
        val readable = setClip(token)
        val pasteOk = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        Thread.sleep(300)
        val contains = nodeContains(node, token)
        val viaIc = textBeforeCursor()?.endsWith(token)
        val ok = pasteOk && (contains == true || viaIc == true)
        return result(
            test, ok,
            when {
                !pasteOk -> "ACTION_PASTE returned false"
                ok -> "pasted and verified"
                else -> "text not found after paste"
            },
            nodeDetails(node) + listOf(
                "ACTION_PASTE returned" to "$pasteOk",
                "node contains token" to "$contains",
                "surrounding text ends with token" to "$viaIc",
                "clipboard readable by the service" to readable,
            ),
        )
    }

    fun pasteIc(token: String): ProbeResult {
        val test = "insert.pasteIc"
        val ic = ic() ?: return result(test, false, "no input connection", emptyList())
        setClip(token)
        val error = runCatching { ic.performContextMenuAction(android.R.id.paste) }.exceptionOrNull()
        Thread.sleep(300)
        val viaIc = textBeforeCursor()?.endsWith(token)
        val viaNode = nodeContains(focusedNode(), token)
        val ok = error == null && (viaIc == true || viaNode == true)
        return result(
            test, ok, if (error != null) "exception: ${error.javaClass.simpleName}" else if (ok) "pasted and verified" else "text not found after paste",
            listOf("surrounding text ends with token" to "$viaIc", "node contains token" to "$viaNode"),
        )
    }

    fun selection(): ProbeResult {
        val test = "selection"
        val st = runCatching { ic()?.getSurroundingText(0, 0, 0) }.getOrNull()
        val icSel = st?.let {
            val a = it.selectionStart.coerceIn(0, it.text.length)
            val b = it.selectionEnd.coerceIn(a, it.text.length)
            it.text.subSequence(a, b).toString()
        }
        val node = focusedNode()
        val nodeSel = node?.let { n ->
            val t = if (n.isShowingHintText) null else n.text?.toString()
            val a = minOf(n.textSelectionStart, n.textSelectionEnd)
            val b = maxOf(n.textSelectionStart, n.textSelectionEnd)
            if (t != null && a >= 0 && b <= t.length) t.substring(a, b) else null
        }
        val ok = !icSel.isNullOrEmpty() || !nodeSel.isNullOrEmpty()
        fun show(s: String?) = when {
            s == null -> "unavailable"
            s.isEmpty() -> "(empty)"
            else -> "\"${s.take(60)}${if (s.length > 60) "…" else ""}\" (${s.length} chars)"
        }
        return result(
            test, ok, "IC: ${show(icSel)} | node: ${show(nodeSel)}",
            listOf(
                "input connection selection" to show(icSel),
                "IC selection offsets" to (st?.let { "${it.offset + it.selectionStart}..${it.offset + it.selectionEnd}" } ?: "-"),
                "node selection" to show(nodeSel),
            ) + (node?.let { nodeDetails(it) } ?: emptyList()),
        )
    }

    fun editorInfo(): ProbeResult {
        val im = service.inputMethod
        val info = im?.currentInputEditorInfo
        val started = im?.currentInputStarted == true
        val ic = im?.currentInputConnection
        val surrounding = runCatching { ic?.getSurroundingText(20, 20, 0) }.getOrNull()
        val node = focusedNode()
        val details = mutableListOf(
            "inputStarted" to "$started",
            "input connection" to if (ic != null) "available" else "null",
            "surrounding text" to (surrounding?.let { "available (${it.text.length} chars)" } ?: "unavailable"),
        )
        if (info != null) {
            details += listOf(
                "EditorInfo.packageName" to "${info.packageName}",
                "EditorInfo.inputType" to InputTypes.describe(info.inputType),
                "EditorInfo.imeOptions" to "0x%08x".format(info.imeOptions),
                "EditorInfo.fieldId / fieldName" to "${info.fieldId} / ${info.fieldName}",
                "EditorInfo.hintText" to "${info.hintText}",
                "EditorInfo.initialSel" to "${info.initialSelStart}..${info.initialSelEnd}",
            )
        }
        details += listOf("IME window" to (editor.imeBounds?.toString() ?: "not visible"))
        details += node?.let { nodeDetails(it) } ?: listOf("focused node" to "none")
        return result(
            "editor", started && ic != null,
            "input ${if (started) "started" else "NOT started"}, IC ${if (ic != null) "ok" else "null"}, " +
                "node ${node?.className ?: "none"}",
            details,
        )
    }

    private fun nodeDetails(node: AccessibilityNodeInfo): List<Pair<String, String>> {
        val actions = node.actionList.mapNotNull {
            when (it.id) {
                AccessibilityAction.ACTION_SET_TEXT.id -> "SET_TEXT"
                AccessibilityAction.ACTION_PASTE.id -> "PASTE"
                AccessibilityAction.ACTION_SET_SELECTION.id -> "SET_SELECTION"
                AccessibilityAction.ACTION_COPY.id -> "COPY"
                AccessibilityAction.ACTION_CUT.id -> "CUT"
                AccessibilityAction.ACTION_IME_ENTER.id -> "IME_ENTER"
                else -> null
            }
        }
        return listOf(
            "node class" to "${node.className}",
            "node package" to "${node.packageName}",
            "node viewId" to "${node.viewIdResourceName}",
            "node editable / password" to "${node.isEditable} / ${node.isPassword}",
            "node showing hint" to "${node.isShowingHintText}",
            "node text length" to "${node.text?.length ?: -1}",
            "node selection" to "${node.textSelectionStart}..${node.textSelectionEnd}",
            "node actions" to actions.joinToString(),
        )
    }

    /** Puts [text] on the clipboard (marked sensitive) and reports whether the service can read it back. */
    private fun setClip(text: String): String {
        val cm = service.getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText("WisprCheap", text)
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        cm.setPrimaryClip(clip)
        return runCatching {
            val back = cm.primaryClip?.getItemAt(0)?.text?.toString()
            if (back == null) "no (null)" else if (back == text) "yes" else "different content"
        }.getOrElse { "no (${it.javaClass.simpleName})" }
    }
}
