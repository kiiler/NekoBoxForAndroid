package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnRoutePolicyTest {

    @Test
    fun `regular VPN captures all routes when LAN bypass is disabled`() {
        assertTrue(
            shouldCaptureAllRoutes(
                bypassLan = false,
                tailscaleEnabled = false,
            )
        )
    }

    @Test
    fun `LAN bypass keeps private routes outside VPN when Tailscale is disabled`() {
        assertFalse(
            shouldCaptureAllRoutes(
                bypassLan = true,
                tailscaleEnabled = false,
            )
        )
    }

    @Test
    fun `enabled Tailscale captures static tailnet routes even without accepted subnet routes`() {
        assertTrue(
            shouldCaptureAllRoutes(
                bypassLan = true,
                tailscaleEnabled = true,
            )
        )
    }

    @Test
    fun `enabled Tailscale keeps the IPv6 TUN active when regular IPv6 is disabled`() {
        assertTrue(
            shouldEnableIpv6Tun(
                regularIpv6Enabled = false,
                tailscaleEnabled = true,
            )
        )
    }

    @Test
    fun `regular VPN keeps IPv6 disabled when both settings are disabled`() {
        assertFalse(
            shouldEnableIpv6Tun(
                regularIpv6Enabled = false,
                tailscaleEnabled = false,
            )
        )
    }

}
