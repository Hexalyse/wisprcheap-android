package io.github.hexalyse.wisprcheap.state

import android.content.SharedPreferences
import android.text.InputType
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ScreenBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    override fun toString() = "[$left,$top][$right,$bottom]"
}

/** What the accessibility service currently knows about the focused editor and the keyboard. */
data class EditorState(
    val serviceConnected: Boolean = false,
    /** InputMethod.onStartInput fired and onFinishInput didn't (API 33 accessibility IME). */
    val inputStarted: Boolean = false,
    val editorPackage: String? = null,
    val inputType: Int = 0,
    val selStart: Int = -1,
    val selEnd: Int = -1,
    val imeVisible: Boolean = false,
    val imeBounds: ScreenBox? = null,
    /** Fallback signal: findFocus(FOCUS_INPUT) is an editable node. */
    val focusedEditable: Boolean = false,
    val focusedClass: String? = null,
    val focusedPackage: String? = null,
    val activePackage: String? = null,
) {
    val isPassword: Boolean get() = InputTypes.isPassword(inputType)

    val targetPackage: String? get() = editorPackage ?: focusedPackage ?: activePackage

    fun describe(): String = buildString {
        append(targetPackage ?: "?")
        append(" · input ").append(if (inputStarted) "started" else "none")
        append(" · IME ").append(if (imeVisible) "visible" else "hidden")
        if (focusedEditable) append(" · editable node")
        if (isPassword) append(" · password")
    }
}

object InputTypes {
    fun isPassword(t: Int): Boolean {
        val cls = t and InputType.TYPE_MASK_CLASS
        val v = t and InputType.TYPE_MASK_VARIATION
        return (cls == InputType.TYPE_CLASS_TEXT &&
            (v == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)) ||
            (cls == InputType.TYPE_CLASS_NUMBER && v == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    fun describe(t: Int): String {
        val cls = when (t and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> "text"
            InputType.TYPE_CLASS_NUMBER -> "number"
            InputType.TYPE_CLASS_PHONE -> "phone"
            InputType.TYPE_CLASS_DATETIME -> "datetime"
            0 -> "null"
            else -> "other"
        }
        return "0x%08x (%s, variation 0x%x%s)".format(
            t, cls, t and InputType.TYPE_MASK_VARIATION, if (isPassword(t)) ", password" else "",
        )
    }
}

class RuntimeState(private val prefs: SharedPreferences) {
    private val _editor = MutableStateFlow(EditorState())
    val editor: StateFlow<EditorState> = _editor.asStateFlow()

    private val _alwaysShowBubble = MutableStateFlow(prefs.getBoolean(KEY_ALWAYS_SHOW, false))
    val alwaysShowBubble: StateFlow<Boolean> = _alwaysShowBubble.asStateFlow()

    fun updateEditor(transform: (EditorState) -> EditorState) = _editor.update(transform)

    fun setAlwaysShowBubble(value: Boolean) {
        prefs.edit { putBoolean(KEY_ALWAYS_SHOW, value) }
        _alwaysShowBubble.value = value
    }

    /** A unique, recognisable string to insert in insertion tests. */
    @Synchronized
    fun nextToken(): String {
        val n = prefs.getInt(KEY_COUNTER, 0) + 1
        prefs.edit { putInt(KEY_COUNTER, n) }
        return "WisprCheap-$n "
    }

    /** Bubble position: x in pixels, and y as a distance above the keyboard top (or from the screen bottom). */
    var bubbleX: Int
        get() = prefs.getInt(KEY_BUBBLE_X, -1)
        set(v) = prefs.edit { putInt(KEY_BUBBLE_X, v) }

    var bubbleDy: Int
        get() = prefs.getInt(KEY_BUBBLE_DY, -1)
        set(v) = prefs.edit { putInt(KEY_BUBBLE_DY, v) }

    private companion object {
        const val KEY_ALWAYS_SHOW = "always_show_bubble"
        const val KEY_COUNTER = "token_counter"
        const val KEY_BUBBLE_X = "bubble_x"
        const val KEY_BUBBLE_DY = "bubble_dy"
    }
}
