package io.nekohasekai.sagernet.fmt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TailscaleConfigOverlayTest {

    @Test
    fun disabledLeavesConfigUnchanged() {
        val config = baseConfig()
        val before = config.toString()

        TailscaleConfigOverlay.apply(config, TailscaleConfigOverlay.Settings(enabled = false))

        assertEquals(before, config.toString())
    }

    @Test
    fun injectsEndpointBootstrapDnsAndScopedRoutes() {
        val config = baseConfig()

        TailscaleConfigOverlay.apply(
            config,
            TailscaleConfigOverlay.Settings(
                enabled = true,
                stateDirectory = "/data/tailscale",
                hostname = "neko-phone",
                routeCidrs = "10.20.0.0/16, invalid",
            ),
        )

        val endpoints = objectList(config["endpoints"])
        val endpoint = endpoints.single { it["tag"] == TailscaleConfigOverlay.ENDPOINT_TAG }
        assertEquals("/data/tailscale", endpoint["state_directory"])
        assertEquals("neko-phone", endpoint["hostname"])
        assertEquals("tailscale-bootstrap", objectMap(endpoint["domain_resolver"])["server"])

        val dns = objectMap(config["dns"])
        val servers = objectList(dns["servers"])
        val bootstrap = servers.single { it["tag"] == "tailscale-bootstrap" }
        assertEquals("proxy", bootstrap["detour"])
        assertNotNull(servers.singleOrNull { it["tag"] == TailscaleConfigOverlay.DNS_TAG })
        val dnsRules = objectList(dns["rules"])
        assertTrue(dnsRules.any { it["server"] == TailscaleConfigOverlay.DNS_TAG })
        assertFalse(dnsRules.any { it["server"] == TailscaleConfigOverlay.DNS_TAG && it["domain_suffix"] == null })

        val routeRules = objectList(objectMap(config["route"])["rules"])
        val cidrs = routeRules.first { it["outbound"] == TailscaleConfigOverlay.ENDPOINT_TAG && it["ip_cidr"] != null }["ip_cidr"] as List<*>
        assertTrue("10.20.0.0/16" in cidrs)
        assertFalse("invalid" in cidrs)
    }

    @Test
    fun replacesOtherTailscaleObjectsButPreservesProxyConfig() {
        val config = baseConfig().apply {
            this["endpoints"] = listOf(mapOf("type" to "tailscale", "tag" to "old-ts"))
        }

        TailscaleConfigOverlay.apply(config, TailscaleConfigOverlay.Settings(enabled = true))

        assertTrue(objectList(config["endpoints"]).none { it["tag"] == "old-ts" })
        assertTrue(objectList(config["outbounds"]).any { it["tag"] == "proxy" })
    }

    private fun baseConfig(): MutableMap<String, Any?> = linkedMapOf(
        "outbounds" to listOf(
            mapOf("type" to "selector", "tag" to "proxy", "outbounds" to listOf("node")),
            mapOf("type" to "direct", "tag" to "direct"),
        ),
        "route" to linkedMapOf(
            "final" to "proxy",
            "rules" to listOf(mapOf("ip_is_private" to true, "outbound" to "direct")),
        ),
        "dns" to linkedMapOf(
            "servers" to listOf(mapOf("type" to "https", "tag" to "remote", "server" to "8.8.8.8")),
            "rules" to emptyList<Map<String, Any?>>(),
        ),
    )

    private fun objectMap(value: Any?): Map<String, Any?> = value as Map<String, Any?>
    private fun objectList(value: Any?): List<Map<String, Any?>> = value as List<Map<String, Any?>>
}
