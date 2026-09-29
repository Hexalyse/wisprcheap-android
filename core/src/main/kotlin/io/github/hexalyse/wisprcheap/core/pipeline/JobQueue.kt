package io.github.hexalyse.wisprcheap.core.pipeline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs jobs one at a time, in order, "so text lands in the order it was spoken". A failing job is reported to
 * [onError] and doesn't stop the queue.
 */
class JobQueue(
    scope: CoroutineScope,
    private val runner: suspend (Job) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private val channel = Channel<Job>(Channel.UNLIMITED)
    private val _pending = MutableStateFlow(0)

    /** Jobs queued or running. */
    val pending: StateFlow<Int> = _pending.asStateFlow()

    init {
        scope.launch {
            for (job in channel) {
                try {
                    runner(job)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    onError(e)
                } finally {
                    _pending.update { it - 1 }
                }
            }
        }
    }

    fun enqueue(job: Job) {
        _pending.update { it + 1 }
        if (channel.trySend(job).isFailure) _pending.update { it - 1 }
    }

    /** Waits until every queued job has finished (or [timeoutMs] elapsed); true when idle. */
    suspend fun awaitIdle(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { pending.first { it == 0 } } != null

    fun close() {
        channel.close()
    }
}
