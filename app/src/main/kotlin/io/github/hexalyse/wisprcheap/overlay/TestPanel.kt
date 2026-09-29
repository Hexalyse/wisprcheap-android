package io.github.hexalyse.wisprcheap.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.TextView
import io.github.hexalyse.wisprcheap.a11y.WisprAccessibilityService as S

/** Phase 0 test panel shown over other apps (in a non-focusable window, so the text field keeps focus). */
@SuppressLint("ViewConstructor", "SetTextI18n", "ClickableViewAccessibility")
class TestPanel(
    context: Context,
    private val onAction: (String) -> Unit,
    private val onClose: () -> Unit,
    private val onDrag: (dx: Float, dy: Float, done: Boolean) -> Unit,
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val status = TextView(context)
    private val result = TextView(context)
    private val buttons = mutableListOf<TextView>()

    init {
        orientation = VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(12))
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(0xF01E1F24.toInt())
        }

        val header = TextView(context).apply {
            text = "WisprCheap tests  ·  drag here"
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 14f
            setPadding(0, dp(2), 0, dp(4))
        }
        var lastX = 0f
        var lastY = 0f
        header.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.rawX
                    lastY = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    onDrag(e.rawX - lastX, e.rawY - lastY, false)
                    lastX = e.rawX
                    lastY = e.rawY
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onDrag(0f, 0f, true)
            }
            true
        }
        addView(header)

        status.apply {
            setTextColor(0xFFB4B8C0.toInt())
            textSize = 11f
        }
        addView(status)

        val rows = listOf(
            listOf(S.ACTION_MIC_DIRECT to "Mic 3 s", S.ACTION_MIC_TRAMPOLINE to "Mic trampoline"),
            listOf(S.ACTION_MIC_FGS to "Mic FGS (bg)", S.ACTION_EDITOR to "Editor info"),
            listOf(S.ACTION_COMMIT to "commitText", S.ACTION_SET_TEXT to "SET_TEXT"),
            listOf(S.ACTION_PASTE_NODE to "Paste (node)", S.ACTION_PASTE_IC to "Paste (IC)"),
            listOf(S.ACTION_SELECTION to "Read selection", "close" to "Close"),
        )
        for (row in rows) {
            val line = LinearLayout(context).apply { orientation = HORIZONTAL }
            for ((id, label) in row) {
                val b = TextView(context).apply {
                    text = label
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    setPadding(dp(6), dp(9), dp(6), dp(9))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(if (id == "close") 0xFF3A3D45.toInt() else 0xFF4A4FB8.toInt())
                    }
                    setOnClickListener { if (id == "close") onClose() else onAction(id) }
                }
                buttons += b
                line.addView(b, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            }
            addView(line, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }

        result.apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            maxLines = 8
            setPadding(0, dp(6), 0, 0)
            text = "Tap a test. Results are also listed in the app."
        }
        addView(result)
    }

    fun setStatus(text: String) {
        status.text = text
    }

    fun showResult(text: String) {
        result.text = text
    }

    fun setBusy(message: String?) {
        buttons.forEach {
            val enabled = message == null || it.text == "Close"
            it.isEnabled = enabled
            it.alpha = if (enabled) 1f else 0.4f
        }
        if (message != null) result.text = message
    }
}
