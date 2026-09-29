package io.github.hexalyse.wisprcheap.core.pipeline

import io.github.hexalyse.wisprcheap.core.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JobQueueTest {
    @Test
    fun jobsRunInOrderAndAFailureDoesNotStopTheQueue() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ran = Collections.synchronizedList(mutableListOf<String>())
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val queue = JobQueue(
            scope,
            runner = { job ->
                val app = job.targetApp!!
                delay(if (app == "a") 100L else 1L) // the first job is the slowest: order must still hold
                if (app == "b") error("boom")
                ran += app
            },
            onError = { errors += it },
        )
        for (app in listOf("a", "b", "c")) queue.enqueue(Job.Dictation(ShortArray(0), Settings(), targetApp = app))
        assertEquals(3, queue.pending.value)
        assertTrue(queue.awaitIdle(5_000))
        assertEquals(listOf("a", "c"), ran)
        assertEquals("boom", errors.single().message)
        assertEquals(0, queue.pending.value)
        scope.cancel()
    }
}
