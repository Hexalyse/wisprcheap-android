package io.github.hexalyse.wisprcheap.a11y

import android.accessibilityservice.InputMethod
import android.view.inputmethod.EditorInfo

/** The accessibility service's partial IME (API 33, flagInputMethodEditor): editor start/finish and selection. */
class A11yInputMethod(private val service: WisprAccessibilityService) : InputMethod(service) {
    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        service.onEditorStarted(attribute)
    }

    override fun onFinishInput() {
        super.onFinishInput()
        service.onEditorFinished()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        service.onSelectionChanged(newSelStart, newSelEnd)
    }
}
