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
                routeCidrs = "10.20.0.0/16, invalid, ::::/64",
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
        assertFalse("::::/64" in cidrs)
    }

    @Test
    fun routeCidrsUseTheSameStrictNumericSyntaxAsSingBox() {
        val config = baseConfig()

        TailscaleConfigOverlay.apply(
            config,
            TailscaleConfigOverlay.Settings(
                enabled = true,
                routeCidrs = listOf(
                    "10.20.0.0/16",
                    "2001:db8::/64",
                    "01.2.3.0/24",
                    "1.2.3.0/+24",
                    "2001:db8::ffff:192.168.001.001/128",
                    "[2001:db8::1]/128",
                ).joinToString(","),
            ),
        )

        val routeRules = objectList(objectMap(config["route"])["rules"])
        val cidrs = routeRules.single {
            it["outbound"] == TailscaleConfigOverlay.ENDPOINT_TAG && it["ip_cidr"] != null
        }["ip_cidr"] as List<*>
        assertTrue("10.20.0.0/16" in cidrs)
        assertTrue("2001:db8::/64" in cidrs)
        assertFalse("01.2.3.0/24" in cidrs)
        assertFalse("1.2.3.0/+24" in cidrs)
        assertFalse("2001:db8::ffff:192.168.001.001/128" in cidrs)
        assertFalse("[2001:db8::1]/128" in cidrs)
    }

    @Test
    fun acceptedRoutesUseDynamicTailscalePreferencesBeforePrivateBypass() {
        val config = baseConfig()

        TailscaleConfigOverlay.apply(
            config,
            TailscaleConfigOverlay.Settings(
                enabled = true,
                acceptRoutes = true,
            ),
        )

        val routeRules = objectList(objectMap(config["route"])["rules"])
        assertEquals(
            mapOf(
                "preferred_by" to listOf(TailscaleConfigOverlay.ENDPOINT_TAG),
                "outbound" to TailscaleConfigOverlay.ENDPOINT_TAG,
            ),
            routeRules.first(),
        )
        val staticTailscaleIndex = routeRules.indexOfFirst {
            it["outbound"] == TailscaleConfigOverlay.ENDPOINT_TAG && it["ip_cidr"] != null
        }
        val privateDirectIndex = routeRules.indexOfFirst {
            it["ip_is_private"] == true && it["outbound"] == "direct"
        }
        assertTrue(staticTailscaleIndex in 1 until privateDirectIndex)
    }

    @Test
    fun disabledAcceptedRoutesKeepStaticRoutesWithoutDynamicPreference() {
        val config = baseConfig()
        objectMap(config["route"]).toMutableMap().also { route ->
            route["rules"] = emptyList<Map<String, Any?>>()
            config["route"] = route
        }

        TailscaleConfigOverlay.apply(
            config,
            TailscaleConfigOverlay.Settings(
                enabled = true,
                acceptRoutes = false,
                bypassLan = true,
                routeCidrs = "10.20.0.0/16",
            ),
        )

        val endpoint = objectList(config["endpoints"])
            .single { it["tag"] == TailscaleConfigOverlay.ENDPOINT_TAG }
        assertEquals(false, endpoint["accept_routes"])
        val routeRules = objectList(objectMap(config["route"])["rules"])
        assertFalse(routeRules.any { it["preferred_by"] != null })
        val privateDirectIndex = routeRules.indexOfFirst {
            it["ip_is_private"] == true && it["outbound"] == "direct"
        }
        val staticTailscaleIndex = routeRules.indexOfFirst {
            it["outbound"] == TailscaleConfigOverlay.ENDPOINT_TAG && it["ip_cidr"] != null
        }
        assertEquals(staticTailscaleIndex + 1, privateDirectIndex)
        val staticCidrs = routeRules.single {
            it["outbound"] == TailscaleConfigOverlay.ENDPOINT_TAG && it["ip_cidr"] != null
        }["ip_cidr"] as List<*>
        assertTrue("100.64.0.0/10" in staticCidrs)
        assertTrue("10.20.0.0/16" in staticCidrs)
    }

    @Test
    fun reapplyReplacesManagedCidrsAndAcceptedRoutePreference() {
        val config = baseConfig()

        TailscaleConfigOverlay.apply(
            config,
            TailscaleConfigOverlay.Settings(
                enabled = true,
                acceptRoutes = true,
                routeCidrs = "10.20.0.0/16",
            ),
        )
        TailscaleConfigOverlay.apply(
            config,
            TailscaleConfigOverlay.Settings(
                enabled = true,
                acceptRoutes = false,
                routeCidrs = "10.30.0.0/16",
            ),
        )

        val routeRules = objectList(objectMap(config["route"])["rules"])
        assertFalse(routeRules.any { it["preferred_by"] != null })
        val managedCidrs = routeRules.filter {
            it["outbound"] == TailscaleConfigOverlay.ENDPOINT_TAG && it["ip_cidr"] != null
        }
        assertEquals(1, managedCidrs.size)
        val cidrs = managedCidrs.single()["ip_cidr"] as List<*>
        assertTrue("10.30.0.0/16" in cidrs)
        assertFalse("10.20.0.0/16" in cidrs)
    }

    @Test
    fun acceptedRoutesAndLanBypassInsertPrivateDirectAfterTailscaleRules() {
        val config = baseConfig()
        objectMap(config["route"]).toMutableMap().also { route ->
            route["rules"] = emptyList<Map<String, Any?>>()
            config["route"] = route
        }

        TailscaleConfigOverlay.apply(
            config,
            TailscaleConfigOverlay.Settings(
                enabled = true,
                acceptRoutes = true,
                bypassLan = true,
            ),
        )

        val routeRules = objectList(objectMap(config["route"])["rules"])
        val privateDirectIndex = routeRules.indexOfFirst {
            it["ip_is_private"] == true && it["outbound"] == "direct"
        }
        val lastTailscaleIndex = routeRules.indexOfLast {
            it["outbound"] == TailscaleConfigOverlay.ENDPOINT_TAG
        }
        assertEquals(lastTailscaleIndex + 1, privateDirectIndex)
    }

    @Test
    fun lanBypassFallbackDoesNotOverrideExistingFirewallRules() {
        val config = baseConfig()
        objectMap(config["route"]).toMutableMap().also { route ->
            route["rules"] = listOf(
                mapOf(
                    "ip_cidr" to listOf("224.0.0.0/3", "ff00::/8"),
                    "action" to "reject",
                ),
            )
            config["route"] = route
        }

        TailscaleConfigOverlay.apply(
            config,
            TailscaleConfigOverlay.Settings(
                enabled = true,
                acceptRoutes = true,
                bypassLan = true,
            ),
        )

        val routeRules = objectList(objectMap(config["route"])["rules"])
        val firewallIndex = routeRules.indexOfFirst { it["action"] == "reject" }
        val privateDirectIndex = routeRules.indexOfFirst {
            it["ip_is_private"] == true && it["outbound"] == "direct"
        }
        assertTrue(routeRules.indexOfLast { it["outbound"] == TailscaleConfigOverlay.ENDPOINT_TAG } < firewallIndex)
        assertTrue(firewallIndex < privateDirectIndex)
    }

    @Test
    fun acceptedRoutesAndLanBypassDoNotDuplicateExistingPrivateDirect() {
        val config = baseConfig()

        TailscaleConfigOverlay.apply(
            config,
            TailscaleConfigOverlay.Settings(
                enabled = true,
                acceptRoutes = true,
                bypassLan = true,
            ),
        )

        val routeRules = objectList(objectMap(config["route"])["rules"])
        assertEquals(
            1,
            routeRules.count { it["ip_is_private"] == true && it["outbound"] == "direct" },
        )
    }

    @Test
    fun conditionalPrivateRuleDoesNotSuppressGlobalLanFallback() {
        val config = baseConfig()
        objectMap(config["route"]).toMutableMap().also { route ->
            route["rules"] = listOf(
                mapOf(
                    "network" to "tcp",
                    "ip_is_private" to true,
                    "outbound" to "direct",
                ),
            )
            config["route"] = route
        }

        TailscaleConfigOverlay.apply(
            config,
            TailscaleConfigOverlay.Settings(enabled = true, bypassLan = true),
        )

        val routeRules = objectList(objectMap(config["route"])["rules"])
        assertEquals(
            2,
            routeRules.count { it["ip_is_private"] == true && it["outbound"] == "direct" },
        )
        assertEquals(
            mapOf("ip_is_private" to true, "outbound" to "direct"),
            routeRules.last(),
        )
    }

    @Test
    fun directTagCollisionUsesDedicatedManagedOutbound() {
        val config = baseConfig().apply {
            this["outbounds"] = listOf(
                mapOf("type" to "selector", "tag" to "proxy", "outbounds" to listOf("node")),
                mapOf("type" to "selector", "tag" to "direct", "outbounds" to listOf("node")),
            )
            this["route"] = linkedMapOf(
                "final" to "proxy",
                "rules" to emptyList<Map<String, Any?>>(),
            )
        }

        TailscaleConfigOverlay.apply(
            config,
            TailscaleConfigOverlay.Settings(enabled = true, bypassLan = true),
        )

        val outbounds = objectList(config["outbounds"])
        assertEquals(1, outbounds.count { it["tag"] == "direct" })
        assertEquals("direct", outbounds.single { it["tag"] == "tailscale-direct" }["type"])
        val privateRule = objectList(objectMap(config["route"])["rules"]).single {
            it["ip_is_private"] == true
        }
        assertEquals("tailscale-direct", privateRule["outbound"])
    }

    @Test
    fun tailscaleAddsIpv6AddressToTunInbound() {
        val config = baseConfig().apply {
            this["inbounds"] = listOf(
                mapOf(
                    "type" to "tun",
                    "tag" to "tun-in",
                    "address" to listOf("172.19.0.1/28"),
                ),
            )
        }

        TailscaleConfigOverlay.apply(config, TailscaleConfigOverlay.Settings(enabled = true))

        val tunInbound = objectList(config["inbounds"]).single { it["type"] == "tun" }
        assertTrue("fdfe:dcba:9876::1/126" in (tunInbound["address"] as List<*>))
    }

    @Test
    fun preservesExistingEncryptedDnsServerRouting() {
        val originalRemote = linkedMapOf<String, Any?>(
            "type" to "https",
            "tag" to "remote",
            "server" to "8.8.8.8",
        )
        val config = baseConfig()
        val dns = objectMap(config["dns"]).toMutableMap()
        dns["servers"] = listOf(originalRemote)
        config["dns"] = dns

        TailscaleConfigOverlay.apply(
            config,
            TailscaleConfigOverlay.Settings(enabled = true),
        )

        val servers = objectList(objectMap(config["dns"])["servers"])
        assertEquals(originalRemote, servers.single { it["tag"] == "remote" })
        assertEquals(
            "proxy",
            servers.single { it["tag"] == "tailscale-bootstrap" }["detour"],
        )
    }

    @Test
    fun preservesImplicitDefaultDnsServerWhenDnsFinalIsAbsent() {
        val config = baseConfig()
        val dns = objectMap(config["dns"]).toMutableMap()
        dns.remove("final")
        config["dns"] = dns

        TailscaleConfigOverlay.apply(
            config,
            TailscaleConfigOverlay.Settings(enabled = true),
        )

        val updatedDns = objectMap(config["dns"])
        val servers = objectList(updatedDns["servers"])
        assertEquals("remote", servers.first()["tag"])
        assertEquals(null, updatedDns["final"])
        assertNotNull(servers.singleOrNull { it["tag"] == "tailscale-bootstrap" })
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

    @Test
    fun replacingExistingEndpointRewritesTagReferences() {
        val config = baseConfig().apply {
            this["endpoints"] = listOf(mapOf("type" to "tailscale", "tag" to "old-ts"))
            this["outbounds"] = listOf(
                mapOf(
                    "type" to "selector",
                    "tag" to "proxy",
                    "outbounds" to listOf("node", "old-ts"),
                ),
                mapOf("type" to "direct", "tag" to "direct"),
            )
            this["route"] = linkedMapOf(
                "final" to "old-ts",
                "rules" to listOf(mapOf("domain" to listOf("internal.example"), "outbound" to "old-ts")),
            )
        }

        TailscaleConfigOverlay.apply(config, TailscaleConfigOverlay.Settings(enabled = true))

        val route = objectMap(config["route"])
        assertEquals(TailscaleConfigOverlay.ENDPOINT_TAG, route["final"])
        assertEquals(
            TailscaleConfigOverlay.ENDPOINT_TAG,
            objectList(route["rules"]).single { it["domain"] != null }["outbound"],
        )
        val selector = objectList(config["outbounds"]).single { it["tag"] == "proxy" }
        assertTrue(TailscaleConfigOverlay.ENDPOINT_TAG in (selector["outbounds"] as List<*>))
        assertFalse("old-ts" in (selector["outbounds"] as List<*>))
    }

    @Test
    fun replacingExistingEndpointRewritesSelectorsAndRuleMatchers() {
        val config = baseConfig().apply {
            this["endpoints"] = listOf(
                mapOf("type" to "tailscale", "tag" to "old-ts"),
                mapOf(
                    "type" to "wireguard",
                    "tag" to "kept-wg",
                    "detour" to "old-ts",
                ),
            )
            this["outbounds"] = listOf(
                mapOf(
                    "type" to "selector",
                    "tag" to "proxy",
                    "outbounds" to listOf("node", "old-ts"),
                    "default" to "old-ts",
                ),
                mapOf("type" to "direct", "tag" to "direct"),
            )
            this["route"] = linkedMapOf(
                "final" to "proxy",
                "rules" to listOf(
                    mapOf(
                        "preferred_by" to listOf("old-ts"),
                        "outbound" to "old-ts",
                    ),
                    mapOf(
                        "type" to "logical",
                        "mode" to "or",
                        "rules" to listOf(
                            mapOf(
                                "domain_suffix" to listOf("nested.internal.example"),
                                "preferred_by" to listOf("old-ts"),
                            ),
                        ),
                        "outbound" to "proxy",
                    ),
                ),
            )
            this["dns"] = linkedMapOf(
                "servers" to listOf(
                    mapOf("type" to "https", "tag" to "remote", "server" to "8.8.8.8"),
                ),
                "rules" to listOf(
                    mapOf(
                        "domain_suffix" to listOf("internal.example"),
                        "outbound" to listOf("old-ts", "proxy"),
                        "server" to "remote",
                    ),
                    mapOf(
                        "type" to "logical",
                        "mode" to "or",
                        "rules" to listOf(
                            mapOf(
                                "domain_suffix" to listOf("nested.internal.example"),
                                "outbound" to "old-ts",
                            ),
                        ),
                        "server" to "remote",
                    ),
                ),
            )
        }

        TailscaleConfigOverlay.apply(config, TailscaleConfigOverlay.Settings(enabled = true))

        val selector = objectList(config["outbounds"]).single { it["tag"] == "proxy" }
        assertEquals(TailscaleConfigOverlay.ENDPOINT_TAG, selector["default"])
        val preferredRoute = objectList(objectMap(config["route"])["rules"]).single {
            it["preferred_by"] != null && it["ip_cidr"] == null
        }
        assertEquals(listOf(TailscaleConfigOverlay.ENDPOINT_TAG), preferredRoute["preferred_by"])
        val logicalRoute = objectList(objectMap(config["route"])["rules"]).single {
            it["type"] == "logical"
        }
        val nestedRoute = objectList(logicalRoute["rules"]).single()
        assertEquals(listOf(TailscaleConfigOverlay.ENDPOINT_TAG), nestedRoute["preferred_by"])
        val dnsRule = objectList(objectMap(config["dns"])["rules"]).single {
            it["domain_suffix"] == listOf("internal.example")
        }
        assertEquals(
            listOf(TailscaleConfigOverlay.ENDPOINT_TAG, "proxy"),
            dnsRule["outbound"],
        )
        val logicalDnsRule = objectList(objectMap(config["dns"])["rules"]).single {
            it["type"] == "logical"
        }
        val nestedDnsRule = objectList(logicalDnsRule["rules"]).single()
        assertEquals(TailscaleConfigOverlay.ENDPOINT_TAG, nestedDnsRule["outbound"])
        val retainedEndpoint = objectList(config["endpoints"]).single { it["tag"] == "kept-wg" }
        assertEquals(TailscaleConfigOverlay.ENDPOINT_TAG, retainedEndpoint["detour"])
    }

    @Test
    fun replacingTailscaleDnsDoesNotMakeMagicDnsTheDefaultOrCatchAll() {
        val config = baseConfig().apply {
            this["endpoints"] = listOf(mapOf("type" to "tailscale", "tag" to "old-ts"))
            this["dns"] = linkedMapOf(
                "servers" to listOf(
                    mapOf("type" to "https", "tag" to "remote", "server" to "8.8.8.8"),
                    mapOf(
                        "type" to "tailscale",
                        "tag" to "old-ts-dns",
                        "endpoint" to "old-ts",
                    ),
                ),
                "rules" to listOf(
                    mapOf("action" to "route", "server" to "old-ts-dns"),
                    mapOf(
                        "domain_suffix" to listOf("internal.example"),
                        "server" to "old-ts-dns",
                    ),
                ),
                "final" to "old-ts-dns",
            )
        }

        TailscaleConfigOverlay.apply(config, TailscaleConfigOverlay.Settings(enabled = true))

        val dns = objectMap(config["dns"])
        assertFalse(dns.containsKey("final"))
        val rules = objectList(dns["rules"])
        assertFalse(rules.any {
            it["server"] == TailscaleConfigOverlay.DNS_TAG &&
                it.keys.all { key -> key in setOf("action", "server", "strategy", "disable_cache") }
        })
        val scopedRule = rules.single { it["domain_suffix"] == listOf("internal.example") }
        assertEquals(TailscaleConfigOverlay.DNS_TAG, scopedRule["server"])
        assertEquals("remote", objectList(dns["servers"]).first()["tag"])
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
