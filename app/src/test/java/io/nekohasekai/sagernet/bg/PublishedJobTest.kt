package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PublishedJobTest {

    @Test
    fun `immediate job is visible while running and cleared after completion`() = runBlocking {
        val publishedJob = PublishedJob()
        var observedJob: kotlinx.coroutines.Job? = null

        val launchedJob = publishedJob.launch(this, Dispatchers.Unconfined) {
            observedJob = publishedJob.current
        }
        launchedJob.join()

        assertSame(launchedJob, observedJob)
        assertNull(publishedJob.current)
    }

    @Test
    fun `cancelling the published job waits for it and clears the slot`() = runBlocking {
        val publishedJob = PublishedJob()
        val launchedJob = publishedJob.launch(this, Dispatchers.Unconfined) {
            awaitCancellation()
        }

        publishedJob.cancelAndJoin()

        assertTrue(launchedJob.isCancelled)
        assertNull(publishedJob.current)
    }
}
