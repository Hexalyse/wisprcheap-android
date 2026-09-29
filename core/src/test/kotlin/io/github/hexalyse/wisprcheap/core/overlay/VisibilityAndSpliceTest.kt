package io.github.hexalyse.wisprcheap.core.overlay

import io.github.hexalyse.wisprcheap.core.insert.TextSplice
import io.github.hexalyse.wisprcheap.core.settings.ShowWhen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VisibilityPolicyTest {
    private val editing = VisibilityInput(packageName = "com.whatsapp", inputStarted = true, imeVisible = true)

    @Test
    fun defaultPolicyNeedsAnEditorAndTheKeyboard() {
        assertTrue(VisibilityPolicy.shouldShow(editing))
        assertFalse(VisibilityPolicy.shouldShow(editing.copy(imeVisible = false)))
        assertFalse(VisibilityPolicy.shouldShow(editing.copy(inputStarted = false)))
        assertTrue(VisibilityPolicy.shouldShow(editing.copy(imeVisible = false, showWhen = ShowWhen.EDITOR_ACTIVE)))
        assertTrue(VisibilityPolicy.shouldShow(editing.copy(inputStarted = false, showWhen = ShowWhen.KEYBOARD_VISIBLE)))
        assertTrue(VisibilityPolicy.shouldShow(VisibilityInput(showWhen = ShowWhen.ALWAYS)))
    }

    @Test
    fun hiddenWhenPausedLockedExcludedOrPassword() {
        assertFalse(VisibilityPolicy.shouldShow(editing.copy(paused = true)))
        assertFalse(VisibilityPolicy.shouldShow(editing.copy(keyguardLocked = true)))
        assertFalse(VisibilityPolicy.shouldShow(editing.copy(excludedApps = setOf("com.whatsapp"))))
        assertFalse(VisibilityPolicy.shouldShow(editing.copy(isPassword = true)))
        assertTrue(VisibilityPolicy.shouldShow(editing.copy(isPassword = true, hideOnPasswordFields = false)))
    }

    @Test
    fun neverHiddenDuringARecording() {
        assertTrue(VisibilityPolicy.shouldShow(VisibilityInput(sessionActive = true, inputStarted = false)))
    }
}

class BubblePositionTest {
    private val screen = ScreenArea(width = 1000, height = 2000, topInset = 50, bottomInset = 40)

    @Test
    fun defaultIsRightEdgeAboveTheKeyboard() {
        assertEquals(1000 - 100 - 10 to 1500 - 100 - 10, BubblePosition.place(null, 100, 10, screen, imeTop = 1500))
        // No keyboard: above the navigation bar.
        assertEquals(890 to 2000 - 40 - 100 - 10, BubblePosition.place(null, 100, 10, screen, imeTop = null))
    }

    @Test
    fun followsTheKeyboardAndRoundTrips() {
        val saved = BubblePosition.save(300, 1200, 100, 10, screen, imeTop = 1500)
        assertEquals(300 to 1200, BubblePosition.place(saved, 100, 10, screen, imeTop = 1500))
        // A taller keyboard moves the bubble up by the same amount.
        assertEquals(300 to 1100, BubblePosition.place(saved, 100, 10, screen, imeTop = 1400))
    }

    @Test
    fun clampedToTheScreen() {
        val far = BubblePosition.place(SavedPosition(2f, 5000), 100, 10, screen, imeTop = 1500)
        assertEquals(890 to 50, far)
        val overKeyboard = BubblePosition.save(0, 1900, 100, 10, screen, imeTop = 1500)
        assertTrue(overKeyboard.dyPx < 0)
        assertEquals(10 to 1900, BubblePosition.place(overKeyboard, 100, 10, screen, imeTop = 1500))
    }
}

class TextSpliceTest {
    private fun spaced(text: String, before: Char?, after: Char?, known: Boolean = true, trailing: Boolean = true) =
        TextSplice.forDictation(text, before, after, known, smartSpacing = true, trailingSpace = trailing)

    @Test
    fun smartSpacing() {
        assertEquals("Hello. ", spaced("Hello.", null, null)) // empty field
        assertEquals(" and more ", spaced("and more", 'd', null)) // after a word
        assertEquals("and more ", spaced("and more", ' ', null)) // after a space
        assertEquals(" and more", spaced("and more", 'd', ' ')) // before a space: no trailing space
        assertEquals(", then ", spaced(", then", 'd', null)) // starts with punctuation
        assertEquals(" word", spaced("word", 'a', '.')) // before punctuation
        assertEquals("word ", spaced("word", '(', null)) // after an opening bracket
        assertEquals(" word", spaced("word", 'a', null, trailing = false))
    }

    @Test
    fun fallsBackToTheDesktopRule() {
        assertEquals("text ", spaced("text", 'a', null, known = false))
        assertEquals("text", spaced("text", 'a', null, known = false, trailing = false))
        assertEquals("text ", TextSplice.forDictation("text", 'a', null, true, smartSpacing = false, trailingSpace = true))
        assertEquals("", spaced("", 'a', 'b'))
    }

    @Test
    fun replace() {
        assertEquals("Hello big world" to 10, TextSplice.replace("Hello world", 6, 6, "big "))
        assertEquals("Hi there" to 2, TextSplice.replace("Hello there", 0, 5, "Hi"))
        assertEquals("abcX" to 4, TextSplice.replace("abc", 10, 12, "X"))
    }
}
