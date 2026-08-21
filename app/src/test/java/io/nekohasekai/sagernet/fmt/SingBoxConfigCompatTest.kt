package io.nekohasekai.sagernet.fmt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingBoxConfigCompatTest {

    @Test
    fun migratesLegacyInboundFieldsToRouteActions() {
        val config = linkedMapOf<String, Any?>(
            "inbounds" to listOf(
                mapOf(
                    "type" to "tun",
                    "tag" to "tun-in",
                    "inet4_address" to listOf("172.19.0.1/30"),
                    "inet6_address" to listOf("fdfe:dcba:9876::1/126"),
                    "gso" to true,
                    "domain_strategy" to "prefer_ipv4",
                    "sniff" to true,
                    "sniff_override_destination" to true,
                    "udp_disable_domain_unmapping" to true,
                ),
                mapOf(
                    "type" to "mixed",
                    "tag" to "mixed-in",
                    "sniff" to true,
                ),
            ),
            "route" to mapOf(
                "rules" to listOf(mapOf("ip_is_private" to true, "outbound" to "direct")),
            ),
        )

        SingBoxConfigCompat.migrate(config)

        val inbounds = config["inbounds"] as List<Map<String, Any?>>
        for (inbound in inbounds) {
            assertFalse(inbound.containsKey("domain_strategy"))
            assertFalse(inbound.containsKey("sniff"))
            assertFalse(inbound.containsKey("sniff_override_destination"))
        }
        assertEquals(
            listOf("172.19.0.1/30", "fdfe:dcba:9876::1/126"),
            inbounds.first()["address"],
        )
        assertFalse(inbounds.first().containsKey("inet4_address"))
        assertFalse(inbounds.first().containsKey("inet6_address"))
        assertFalse(inbounds.first().containsKey("gso"))
        val route = config["route"] as Map<String, Any?>
        val rules = route["rules"] as List<Map<String, Any?>>
        assertEquals(
            listOf(
                mapOf("inbound" to "tun-in", "action" to "resolve", "strategy" to "prefer_ipv4"),
                mapOf("inbound" to "tun-in", "action" to "sniff"),
                mapOf(
                    "inbound" to "tun-in",
                    "action" to "route-options",
                    "udp_disable_domain_unmapping" to true,
                ),
                mapOf("inbound" to "mixed-in", "action" to "sniff"),
                mapOf("ip_is_private" to true, "outbound" to "direct"),
            ),
            rules,
        )
    }

    @Test
    fun migratesLegacyWireGuardOutboundToEndpoint() {
        val config = linkedMapOf<String, Any?>(
            "outbounds" to listOf(
                mapOf(
                    "type" to "wireguard",
                    "tag" to "wg",
                    "server" to "vpn.example.com",
                    "server_port" to 51820,
                    "local_address" to listOf("10.0.0.2/32"),
                    "private_key" to "private",
                    "peer_public_key" to "public",
                    "reserved" to "AQID",
                ),
                mapOf("type" to "direct", "tag" to "direct"),
            ),
        )

        SingBoxConfigCompat.migrate(config)

        val outbounds = config["outbounds"] as List<Map<String, Any?>>
        assertTrue(outbounds.none { it["type"] == "wireguard" })
        val endpoint = (config["endpoints"] as List<Map<String, Any?>>).single()
        assertEquals("wireguard", endpoint["type"])
        assertEquals("wg", endpoint["tag"])
        val peer = (endpoint["peers"] as List<Map<String, Any?>>).single()
        assertEquals("vpn.example.com", peer["address"])
        assertEquals("AQID", peer["reserved"])
    }
}
