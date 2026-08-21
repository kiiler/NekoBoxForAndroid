package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/** Publishes a lazy coroutine before it can run, then clears only that coroutine on completion. */
internal class PublishedJob {

    private val lock = Any()
    private var value: Job? = null

    val current: Job?
        get() = synchronized(lock) { value }

    fun launch(
        scope: CoroutineScope,
        context: CoroutineContext,
        block: suspend CoroutineScope.() -> Unit,
    ): Job {
        val job = scope.launch(context, start = CoroutineStart.LAZY, block = block)
        job.invokeOnCompletion { clear(job) }
        synchronized(lock) { value = job }
        job.start()
        return job
    }

    suspend fun cancelAndJoin() {
        current?.cancelAndJoin()
    }

    private fun clear(job: Job) = synchronized(lock) {
        if (value === job) value = null
    }
}
