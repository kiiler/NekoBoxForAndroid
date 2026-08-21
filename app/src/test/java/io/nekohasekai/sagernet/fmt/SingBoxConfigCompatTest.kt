package io.nekohasekai.sagernet.fmt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SingBoxConfigCompatTest {

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
