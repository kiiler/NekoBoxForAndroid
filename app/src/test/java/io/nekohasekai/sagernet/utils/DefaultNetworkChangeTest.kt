package io.nekohasekai.sagernet.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultNetworkChangeTest {

    @Test
    fun `link property changes force a platform interface refresh`() {
        assertTrue(DefaultNetworkChange.CAPABILITIES.forcePlatformRefresh)
        assertTrue(DefaultNetworkChange.LINK_PROPERTIES.forcePlatformRefresh)
    }

    @Test
    fun `initial available and lost events preserve normal interface transitions`() {
        assertFalse(DefaultNetworkChange.INITIAL.forcePlatformRefresh)
        assertFalse(DefaultNetworkChange.AVAILABLE.forcePlatformRefresh)
        assertFalse(DefaultNetworkChange.LOST.forcePlatformRefresh)
    }

    @Test
    fun `available event only forces refresh when the network handle changes`() {
        assertFalse(
            availableNetworkChange(
                currentNetwork = null,
                availableNetwork = "wifi",
            ).forcePlatformRefresh
        )
        assertFalse(
            availableNetworkChange(
                currentNetwork = "wifi",
                availableNetwork = "wifi",
            ).forcePlatformRefresh
        )
        assertTrue(
            availableNetworkChange(
                currentNetwork = "wifi",
                availableNetwork = "cellular",
            ).forcePlatformRefresh
        )
    }
}
