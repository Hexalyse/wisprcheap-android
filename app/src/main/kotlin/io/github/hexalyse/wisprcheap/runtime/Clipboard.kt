package io.github.hexalyse.wisprcheap.runtime

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle

object Clipboard {
    /** Puts [text] on the clipboard; [sensitive] hides the Android 13+ "copied" preview content. */
    fun set(context: Context, text: String, sensitive: Boolean) {
        val clip = ClipData.newPlainText("WisprCheap", text)
        if (sensitive) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }
}
