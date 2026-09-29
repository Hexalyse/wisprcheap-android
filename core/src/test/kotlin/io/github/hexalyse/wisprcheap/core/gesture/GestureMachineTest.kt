package io.github.hexalyse.wisprcheap.core.gesture

import io.github.hexalyse.wisprcheap.core.gesture.GestureEffect.CancelHoldTimer
import io.github.hexalyse.wisprcheap.core.gesture.GestureEffect.DragBy
import io.github.hexalyse.wisprcheap.core.gesture.GestureEffect.DragEnd
import io.github.hexalyse.wisprcheap.core.gesture.GestureEffect.DragStart
import io.github.hexalyse.wisprcheap.core.gesture.GestureEffect.ExpandToolbar
import io.github.hexalyse.wisprcheap.core.gesture.GestureEffect.ModeChanged
import io.github.hexalyse.wisprcheap.core.gesture.GestureEffect.PressFeedback
import io.github.hexalyse.wisprcheap.core.gesture.GestureEffect.ReleaseFeedback
import io.github.hexalyse.wisprcheap.core.gesture.GestureEffect.ScheduleHoldTimer
import io.github.hexalyse.wisprcheap.core.gesture.GestureEffect.Send
import io.github.hexalyse.wisprcheap.core.gesture.GestureEffect.StartRecording
import io.github.hexalyse.wisprcheap.core.gesture.GestureEffect.ZoneChanged
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GestureMachineTest {
    private fun machine(commandEnabled: Boolean = true) = GestureMachine(
        GestureConfig(
            micStartDelayMs = 200, tapMaxMs = 300, touchSlopPx = 10f, commandSlidePx = 64f, cancelSlidePx = 96f,
            commandEnabled = commandEnabled,
        ),
    )

    /** Finger down at (0, 0) at t=0, held still until the mic starts. */
    private fun holding(m: GestureMachine = machine()): GestureMachine {
        assertEquals(listOf(PressFeedback, ScheduleHoldTimer(200)), m.onDown(0f, 0f, 0))
        assertEquals(listOf(StartRecording(RecordMode.DICTATION, handsFree = false)), m.onHoldTimeout(200))
        assertTrue(m.isRecording)
        return m
    }

    @Test
    fun tapStartsHandsFreeRecording() {
        val m = machine()
        m.onDown(0f, 0f, 0)
        assertEquals(
            listOf(CancelHoldTimer, ReleaseFeedback, StartRecording(RecordMode.DICTATION, handsFree = true), ExpandToolbar),
            m.onUp(2f, 1f, 120),
        )
        assertEquals(GestureState.HandsFree(RecordMode.DICTATION), m.state)
        assertEquals(emptyList(), m.onHoldTimeout(200)) // a late timer is ignored
    }

    @Test
    fun holdIsPushToTalk() {
        val m = holding()
        assertEquals(emptyList(), m.onMove(5f, 5f, 500)) // jitter stays in the normal zone
        assertEquals(listOf(Send(RecordMode.DICTATION)), m.onUp(5f, 5f, 1500))
        assertEquals(GestureState.Idle, m.state)
    }

    @Test
    fun slowTapAfterTheMicStartedStillMeansHandsFree() {
        val m = holding()
        assertEquals(listOf(ExpandToolbar), m.onUp(0f, 0f, 260))
        assertEquals(GestureState.HandsFree(RecordMode.DICTATION), m.state)
    }

    @Test
    fun movingBeforeTheMicStartsIsADrag() {
        val m = machine()
        m.onDown(0f, 0f, 0)
        assertEquals(emptyList(), m.onMove(6f, 0f, 50)) // under the slop
        assertEquals(listOf(CancelHoldTimer, ReleaseFeedback, DragStart, DragBy(20f, 0f)), m.onMove(20f, 0f, 80))
        assertEquals(listOf(DragBy(30f, 5f)), m.onMove(30f, 5f, 120))
        assertEquals(emptyList(), m.onHoldTimeout(200))
        assertEquals(listOf(DragEnd), m.onUp(30f, 5f, 400))
        assertFalse(m.isRecording)
    }

    @Test
    fun slideUpForCommandAndAwayToCancel() {
        val m = holding()
        assertEquals(listOf(ZoneChanged(Zone.COMMAND)), m.onMove(10f, -70f, 400))
        assertEquals(RecordMode.COMMAND, m.recordMode)
        assertEquals(listOf(Send(RecordMode.COMMAND)), m.onUp(10f, -70f, 1200))

        val c = holding()
        assertEquals(listOf(ZoneChanged(Zone.CANCEL)), c.onMove(100f, 0f, 400))
        assertEquals(listOf(ZoneChanged(Zone.NORMAL)), c.onMove(20f, 0f, 500))
        assertEquals(listOf(ZoneChanged(Zone.CANCEL)), c.onMove(0f, 120f, 600))
        assertEquals(listOf(GestureEffect.Cancel), c.onUp(0f, 120f, 900))
    }

    @Test
    fun releasingInTheCancelZoneEarlyStillCancels() {
        val m = holding()
        m.onMove(120f, 0f, 220)
        assertEquals(listOf(GestureEffect.Cancel), m.onUp(120f, 0f, 250))
    }

    @Test
    fun withoutCommandModeSlidingUpIsNormalThenCancel() {
        val m = holding(machine(commandEnabled = false))
        assertEquals(emptyList(), m.onMove(0f, -70f, 400))
        assertEquals(listOf(ZoneChanged(Zone.CANCEL)), m.onMove(0f, -100f, 450))
    }

    @Test
    fun toolbarButtons() {
        val m = machine()
        m.onDown(0f, 0f, 0)
        m.onUp(0f, 0f, 50)
        assertEquals(listOf(ModeChanged(RecordMode.COMMAND)), m.onToolbar(ToolbarButton.TOGGLE_COMMAND))
        assertEquals(listOf(Send(RecordMode.COMMAND)), m.onToolbar(ToolbarButton.SEND))
        assertEquals(GestureState.Idle, m.state)

        m.onDown(0f, 0f, 1000)
        m.onUp(0f, 0f, 1050)
        assertEquals(listOf(GestureEffect.Cancel), m.onToolbar(ToolbarButton.CANCEL))
        assertEquals(emptyList(), m.onToolbar(ToolbarButton.SEND)) // nothing to send anymore

        val noCommand = machine(commandEnabled = false)
        noCommand.onDown(0f, 0f, 0)
        noCommand.onUp(0f, 0f, 50)
        assertEquals(emptyList(), noCommand.onToolbar(ToolbarButton.TOGGLE_COMMAND))
    }

    @Test
    fun theHandsFreeToolbarCanBeDragged() {
        val m = machine()
        m.onDown(0f, 0f, 0)
        m.onUp(0f, 0f, 50)
        assertEquals(emptyList(), m.onDown(100f, 100f, 500))
        assertEquals(listOf(DragStart, DragBy(0f, 30f)), m.onMove(100f, 130f, 520))
        assertEquals(listOf(DragEnd), m.onUp(100f, 130f, 600))
        assertIs<GestureState.HandsFree>(m.state)
        assertTrue(m.isRecording)
    }

    @Test
    fun maxDurationAndInterruptions() {
        assertEquals(listOf(Send(RecordMode.DICTATION)), holding().onMaxDuration())
        val hf = machine().also { it.onDown(0f, 0f, 0); it.onUp(0f, 0f, 10); it.onToolbar(ToolbarButton.TOGGLE_COMMAND) }
        assertEquals(listOf(Send(RecordMode.COMMAND)), hf.onMaxDuration())

        assertEquals(listOf(GestureEffect.Cancel), holding().onInterrupt(send = false))
        assertEquals(listOf(Send(RecordMode.DICTATION)), holding().onInterrupt(send = true))
        val inCancel = holding().also { it.onMove(200f, 0f, 400) }
        assertEquals(listOf(GestureEffect.Cancel), inCancel.onInterrupt(send = true))

        val pressed = machine().also { it.onDown(0f, 0f, 0) }
        assertEquals(listOf(CancelHoldTimer, ReleaseFeedback), pressed.onInterrupt(send = true))
        assertEquals(emptyList(), machine().onMaxDuration())
    }

    @Test
    fun systemCancel() {
        val pressed = machine().also { it.onDown(0f, 0f, 0) }
        assertEquals(listOf(CancelHoldTimer, ReleaseFeedback), pressed.onCancel())
        assertEquals(GestureState.Idle, pressed.state)
        // A cancelled hold is never turned into a tap.
        assertEquals(listOf(Send(RecordMode.DICTATION)), holding().onCancel())
    }
}
