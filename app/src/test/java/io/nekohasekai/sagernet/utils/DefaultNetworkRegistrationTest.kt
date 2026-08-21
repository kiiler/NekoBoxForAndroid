package io.nekohasekai.sagernet.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultNetworkRegistrationTest {

    @Test
    fun `VPN active network is skipped in favor of a physical fallback`() {
        assertEquals(
            "wifi",
            selectPhysicalFallbackNetwork(
                existingUnderlying = null,
                activeNetwork = FallbackNetworkCandidate("neko-vpn", physical = false),
                availableNetworks = listOf(
                    FallbackNetworkCandidate("neko-vpn", physical = false),
                    FallbackNetworkCandidate("wifi", physical = true),
                ),
            ),
        )
    }

    @Test
    fun `no physical candidate returns no fallback network`() {
        assertNull(
            selectPhysicalFallbackNetwork(
                existingUnderlying = null,
                activeNetwork = FallbackNetworkCandidate("neko-vpn", physical = false),
                availableNetworks = listOf(
                    FallbackNetworkCandidate("other-vpn", physical = false),
                ),
            )
        )
    }

    @Test
    fun `existing physical underlying network remains the first choice`() {
        assertEquals(
            "wifi",
            selectPhysicalFallbackNetwork(
                existingUnderlying = FallbackNetworkCandidate("wifi", physical = true),
                activeNetwork = FallbackNetworkCandidate("cellular", physical = true),
                availableNetworks = emptyList(),
            ),
        )
    }

    @Test
    fun `registration failure seeds the active physical network`() {
        assertEquals(
            "wifi",
            initialNetworkAfterRegistration(
                callbackRegistered = false,
                currentNetwork = null,
                activeNetwork = "wifi",
            ),
        )
    }

    @Test
    fun `successful registration seeds the physical fallback before the callback`() {
        assertEquals(
            "wifi",
            initialNetworkAfterRegistration(
                callbackRegistered = true,
                currentNetwork = null,
                activeNetwork = "wifi",
            )
        )
    }
}
