package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore

/** Adds the managed Tailscale endpoint and routing rules to a complete sing-box config. */
object TailscaleConfigOverlay {

    const val ENDPOINT_TAG = "tailscale-in"
    const val DNS_TAG = "tailscale-dns"

    data class Settings(
        val enabled: Boolean,
        val stateDirectory: String = "tailscale-neko",
        val authKey: String = "",
        val hostname: String = "",
        val controlUrl: String = "",
        val acceptRoutes: Boolean = true,
        val magicDns: Boolean = true,
        val routeCidrs: String = "",
        val replaceExisting: Boolean = true,
    )

    fun currentSettings() = Settings(
        enabled = DataStore.tailscaleEnabled,
        stateDirectory = SagerNet.application.noBackupFilesDir.resolve("tailscale").absolutePath,
        authKey = DataStore.tailscaleAuthKey,
        hostname = DataStore.tailscaleHostname,
        controlUrl = DataStore.tailscaleControlUrl,
        acceptRoutes = DataStore.tailscaleAcceptRoutes,
        magicDns = DataStore.tailscaleMagicDns,
        routeCidrs = DataStore.tailscaleRouteCidrs,
        replaceExisting = DataStore.tailscaleReplaceExisting,
    )

    fun apply(config: MutableMap<String, Any?>, settings: Settings = currentSettings()) {
        if (!settings.enabled) return

        ensureDirectOutbound(config)
        val proxyDetour = proxyDetourTag(config)

        val endpoints = mutableObjectList(config["endpoints"])
        val removedEndpointTags = endpoints.mapNotNull { endpoint ->
            val managed = endpoint["tag"] == ENDPOINT_TAG ||
                settings.replaceExisting && endpoint["type"] == "tailscale"
            endpoint["tag"]?.toString().takeIf { managed }
        }.toSet()
        endpoints.removeAll { endpoint ->
            endpoint["tag"] == ENDPOINT_TAG ||
                settings.replaceExisting && endpoint["type"] == "tailscale"
        }
        endpoints += linkedMapOf<String, Any?>(
            "type" to "tailscale",
            "tag" to ENDPOINT_TAG,
            "state_directory" to settings.stateDirectory,
            "accept_routes" to settings.acceptRoutes,
            "domain_resolver" to linkedMapOf(
                "server" to BOOTSTRAP_DNS_TAG,
                "strategy" to "ipv4_only",
            ),
        ).apply {
            settings.authKey.trim().takeIf(String::isNotEmpty)?.let { put("auth_key", it) }
            settings.hostname.trim().takeIf(String::isNotEmpty)?.let { put("hostname", it) }
            settings.controlUrl.trim().takeIf(String::isNotEmpty)?.let { put("control_url", it) }
        }
        config["endpoints"] = endpoints

        val route = mutableObjectMap(config["route"])
        val routeRules = mutableObjectList(route["rules"])
        routeRules.removeAll { rule ->
            rule["outbound"] == ENDPOINT_TAG || rule["outbound"] in removedEndpointTags
        }
        routeRules.add(
            0,
            linkedMapOf(
                "ip_cidr" to defaultTailnetCidrs + parseCidrs(settings.routeCidrs),
                "outbound" to ENDPOINT_TAG,
            ),
        )
        routeRules.add(
            0,
            linkedMapOf(
                "domain_suffix" to listOf("ts.net"),
                "outbound" to ENDPOINT_TAG,
            ),
        )
        route["rules"] = routeRules
        config["route"] = route

        val dns = mutableObjectMap(config["dns"])
        val dnsServers = mutableObjectList(dns["servers"])
        dnsServers.removeAll { it["tag"] == BOOTSTRAP_DNS_TAG }
        dnsServers.add(
            0,
            linkedMapOf<String, Any?>(
                "type" to "https",
                "tag" to BOOTSTRAP_DNS_TAG,
                "server" to "1.1.1.1",
            ).apply {
                proxyDetour?.let { put("detour", it) }
            },
        )
        val removedDnsTags = dnsServers.mapNotNull { server ->
            val managed = server["tag"] == DNS_TAG ||
                settings.replaceExisting && server["type"] == "tailscale"
            server["tag"]?.toString().takeIf { managed }
        }.toSet()
        dnsServers.removeAll { server ->
            server["tag"] == DNS_TAG ||
                settings.replaceExisting && server["type"] == "tailscale"
        }
        val dnsRules = mutableObjectList(dns["rules"])
        dnsRules.removeAll { rule ->
            rule["server"] == DNS_TAG || rule["server"] in removedDnsTags
        }
        if (settings.magicDns) {
            dnsServers += linkedMapOf(
                "type" to "tailscale",
                "tag" to DNS_TAG,
                "endpoint" to ENDPOINT_TAG,
                "accept_default_resolvers" to false,
            )
            dnsRules.add(
                0,
                linkedMapOf(
                    "domain_suffix" to listOf("ts.net"),
                    "server" to DNS_TAG,
                ),
            )
        }
        dnsRules.add(
            0,
            linkedMapOf(
                "domain_suffix" to listOf("tailscale.com", "tailscale.io"),
                "server" to BOOTSTRAP_DNS_TAG,
            ),
        )
        if (proxyDetour != null) {
            dnsServers.forEach { server ->
                if (server["type"] in remoteDnsTypes && (server["detour"] as? String).isNullOrBlank()) {
                    server["detour"] = proxyDetour
                }
            }
        }
        dns["servers"] = dnsServers
        dns["rules"] = dnsRules
        config["dns"] = dns
    }

    private const val BOOTSTRAP_DNS_TAG = "tailscale-bootstrap"

    private val defaultTailnetCidrs = listOf("100.64.0.0/10", "fd7a:115c:a1e0::/48")
    private val remoteDnsTypes = setOf("https", "h3", "http3", "tls", "quic")

    private fun parseCidrs(value: String): List<String> =
        value.split(',', '\n', ';').map(String::trim).filter(String::isNotEmpty).filter(::isCidr)

    private fun ensureDirectOutbound(config: MutableMap<String, Any?>) {
        val outbounds = mutableObjectList(config["outbounds"])
        if (outbounds.none { it["tag"] == TAG_DIRECT && it["type"] == "direct" }) {
            outbounds += linkedMapOf("type" to "direct", "tag" to TAG_DIRECT)
            config["outbounds"] = outbounds
        }
    }

    private fun proxyDetourTag(config: MutableMap<String, Any?>): String? {
        val outbounds = mutableObjectList(config["outbounds"])
        val byTag = outbounds.associateBy { it["tag"]?.toString() }
        val finalTag = mutableObjectMap(config["route"])["final"]?.toString()
        if (finalTag != null && byTag[finalTag]?.let { !isDeadEnd(it) } == true) return finalTag
        return outbounds.firstOrNull { it["type"] in setOf("selector", "urltest") && !isDeadEnd(it) }
            ?.get("tag")?.toString()
            ?: outbounds.firstOrNull { !isDeadEnd(it) }?.get("tag")?.toString()
    }

    private fun isDeadEnd(outbound: Map<String, Any?>): Boolean {
        val type = outbound["type"]?.toString()?.lowercase()
        val tag = outbound["tag"]?.toString()?.lowercase()
        return type in setOf("direct", "block", "dns", "blackhole") ||
            tag in setOf("direct", "block", "dns", "reject", "blackhole")
    }

    private fun isCidr(value: String): Boolean {
        val parts = value.split('/')
        if (parts.size != 2) return false
        val prefix = parts[1].toIntOrNull() ?: return false
        val address = parts[0]
        if (':' in address) return address.isNotBlank() && prefix in 0..128
        val octets = address.split('.')
        return prefix in 0..32 && octets.size == 4 &&
            octets.all { it.toIntOrNull() in 0..255 }
    }

    private fun mutableObjectMap(value: Any?): MutableMap<String, Any?> =
        (value as? Map<*, *>)?.entries?.associateTo(linkedMapOf()) { (key, item) ->
            key.toString() to item
        } ?: linkedMapOf()

    private fun mutableObjectList(value: Any?): MutableList<MutableMap<String, Any?>> =
        (value as? List<*>)?.mapNotNull { item ->
            (item as? Map<*, *>)?.entries?.associateTo(linkedMapOf()) { (key, mapValue) ->
                key.toString() to mapValue
            }
        }?.toMutableList() ?: mutableListOf()
}
