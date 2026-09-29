package io.github.hexalyse.wisprcheap.runtime

import android.text.InputType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

data class ScreenBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    override fun toString() = "[$left,$top][$right,$bottom]"
}

/** What the accessibility service knows about the focused editor and the keyboard. */
data class EditorState(
    val serviceConnected: Boolean = false,
    /** InputMethod.onStartInput fired and onFinishInput didn't (API 33 accessibility IME). */
    val inputStarted: Boolean = false,
    val editorPackage: String? = null,
    val inputType: Int = 0,
    val imeVisible: Boolean = false,
    val imeBounds: ScreenBox? = null,
    val focusedEditable: Boolean = false,
    val focusedPackage: String? = null,
    val activePackage: String? = null,
) {
    val isPassword: Boolean get() = InputTypes.isPassword(inputType)
    val targetPackage: String? get() = editorPackage ?: focusedPackage ?: activePackage
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
}

/** A failed recording kept in memory for "Retry". */
class LastFailed(val pcm: ShortArray, val message: String, val audioFile: String?, val at: Long)

/** App-wide state shown by the UI and used by the runtime. */
class AppState {
    val editor = MutableStateFlow(EditorState())
    val recording = MutableStateFlow(false)
    val lastText = MutableStateFlow<String?>(null)
    val lastFailed = MutableStateFlow<LastFailed?>(null)
    val busyLabel = MutableStateFlow("Transcribing...")

    fun updateEditor(transform: (EditorState) -> EditorState) = editor.update(transform)
}
