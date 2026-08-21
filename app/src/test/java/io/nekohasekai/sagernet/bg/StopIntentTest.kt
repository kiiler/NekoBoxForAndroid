package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopIntentTest {

    @Test
    fun `explicit stop during reload cancels the pending restart`() {
        val intent = StopIntent()

        assertTrue(intent.request(restart = true))
        assertFalse(intent.request(restart = false))

        var restart = true
        intent.complete { restart = it }
        assertFalse(restart)
    }

    @Test
    fun `reload during an in-flight stop upgrades the pending restart`() {
        val intent = StopIntent()

        assertTrue(intent.request(restart = false))
        assertFalse(intent.request(restart = true))

        var restart = false
        intent.complete { restart = it }
        assertTrue(restart)
    }

    @Test
    fun `failed completion does not leave the stop intent stuck`() {
        val intent = StopIntent()
        assertTrue(intent.request(restart = false))

        runCatching {
            intent.complete { error("finish failed") }
        }

        assertTrue(intent.request(restart = false))
    }

    @Test
    fun `restart update only succeeds while cleanup is active`() {
        val intent = StopIntent()

        assertFalse(intent.updateActive(restart = true))
        assertTrue(intent.request(restart = false))
        assertTrue(intent.updateActive(restart = true))

        var restart = false
        intent.complete { restart = it }
        assertTrue(restart)
        assertFalse(intent.updateActive(restart = true))
    }
}
