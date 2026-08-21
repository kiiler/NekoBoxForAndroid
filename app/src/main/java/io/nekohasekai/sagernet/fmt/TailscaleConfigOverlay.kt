package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import java.net.InetAddress

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
        val bypassLan: Boolean = false,
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
        bypassLan = DataStore.bypassLan,
        magicDns = DataStore.tailscaleMagicDns,
        routeCidrs = DataStore.tailscaleRouteCidrs,
        replaceExisting = DataStore.tailscaleReplaceExisting,
    )

    fun apply(config: MutableMap<String, Any?>, settings: Settings = currentSettings()) {
        if (!settings.enabled) return

        val directTag = if (settings.bypassLan) ensureDirectOutbound(config) else null

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
        rewriteEndpointReferences(config, removedEndpointTags)
        ensureTailscaleTunIpv6Address(config)
        val proxyDetour = proxyDetourTag(config)

        val route = mutableObjectMap(config["route"])
        val routeRules = mutableObjectList(route["rules"])
        routeRules.removeAll(::isManagedDynamicRouteRule)
        routeRules.removeAll(::isManagedStaticRouteRule)
        putRuleFirst(
            routeRules,
            linkedMapOf(
                "ip_cidr" to defaultTailnetCidrs + parseCidrs(settings.routeCidrs),
                "outbound" to ENDPOINT_TAG,
            ),
        )
        putRuleFirst(
            routeRules,
            linkedMapOf(
                "domain_suffix" to listOf("ts.net"),
                "outbound" to ENDPOINT_TAG,
            ),
        )
        if (settings.acceptRoutes) {
            putRuleFirst(
                routeRules,
                linkedMapOf(
                    "preferred_by" to listOf(ENDPOINT_TAG),
                    "outbound" to ENDPOINT_TAG,
                ),
            )
        }
        if (
            settings.bypassLan &&
            directTag != null &&
            routeRules.none { isGlobalPrivateDirectRule(it, directTag) }
        ) {
            routeRules += linkedMapOf(
                "ip_is_private" to true,
                "outbound" to directTag,
            )
        }
        route["rules"] = routeRules
        config["route"] = route

        val dns = mutableObjectMap(config["dns"])
        val dnsServers = mutableObjectList(dns["servers"])
        dnsServers.removeAll { it["tag"] == BOOTSTRAP_DNS_TAG }
        dnsServers += linkedMapOf<String, Any?>(
            "type" to "https",
            "tag" to BOOTSTRAP_DNS_TAG,
            "server" to "1.1.1.1",
        ).apply {
            proxyDetour?.let { put("detour", it) }
        }
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
        if (settings.magicDns) {
            dnsRules.removeAll { rule ->
                rule["server"] in removedDnsTags && isUnscopedDnsRouteRule(rule)
            }
            dnsRules.forEach { rule ->
                if (rule["server"] in removedDnsTags) rule["server"] = DNS_TAG
            }
            if (dns["final"] in removedDnsTags) dns.remove("final")
            dnsServers += linkedMapOf(
                "type" to "tailscale",
                "tag" to DNS_TAG,
                "endpoint" to ENDPOINT_TAG,
                "accept_default_resolvers" to false,
            )
            putRuleFirst(
                dnsRules,
                linkedMapOf(
                    "domain_suffix" to listOf("ts.net"),
                    "server" to DNS_TAG,
                ),
            )
        } else {
            dnsRules.removeAll { rule -> rule["server"] in removedDnsTags }
            if (dns["final"] in removedDnsTags) dns.remove("final")
        }
        putRuleFirst(
            dnsRules,
            linkedMapOf(
                "domain_suffix" to listOf("tailscale.com", "tailscale.io"),
                "server" to BOOTSTRAP_DNS_TAG,
            ),
        )
        dns["servers"] = dnsServers
        dns["rules"] = dnsRules
        config["dns"] = dns
    }

    private const val BOOTSTRAP_DNS_TAG = "tailscale-bootstrap"

    private const val TUN_IPV6_ADDRESS = "fdfe:dcba:9876::1/126"

    private val defaultTailnetCidrs = listOf("100.64.0.0/10", "fd7a:115c:a1e0::/48")

    private fun parseCidrs(value: String): List<String> =
        value.split(',', '\n', ';').map(String::trim).filter(String::isNotEmpty).filter(::isCidr)

    private fun ensureDirectOutbound(config: MutableMap<String, Any?>): String {
        val outbounds = mutableObjectList(config["outbounds"])
        outbounds.firstOrNull { it["tag"] == TAG_DIRECT && it["type"] == "direct" }
            ?.let { return TAG_DIRECT }
        outbounds.firstOrNull { it["type"] == "direct" && !it["tag"]?.toString().isNullOrBlank() }
            ?.get("tag")?.toString()?.let { return it }

        val usedTags = outbounds.mapNotNull { it["tag"]?.toString() }.toSet()
        var tag = if (TAG_DIRECT !in usedTags) TAG_DIRECT else "tailscale-direct"
        var suffix = 2
        while (tag in usedTags) tag = "tailscale-direct-${suffix++}"
        outbounds += linkedMapOf("type" to "direct", "tag" to tag)
        config["outbounds"] = outbounds
        return tag
    }

    private fun rewriteEndpointReferences(
        config: MutableMap<String, Any?>,
        removedTags: Set<String>,
    ) {
        if (removedTags.isEmpty()) return

        val endpoints = mutableObjectList(config["endpoints"])
        endpoints.forEach { endpoint ->
            if (endpoint["detour"] in removedTags) endpoint["detour"] = ENDPOINT_TAG
        }
        config["endpoints"] = endpoints

        val outbounds = mutableObjectList(config["outbounds"])
        outbounds.forEach { outbound ->
            if (outbound["detour"] in removedTags) outbound["detour"] = ENDPOINT_TAG
            rewriteTagField(outbound, "outbounds", removedTags)
            rewriteTagField(outbound, "default", removedTags)
        }
        config["outbounds"] = outbounds

        val route = mutableObjectMap(config["route"])
        if (route["final"] in removedTags) route["final"] = ENDPOINT_TAG
        val routeRules = mutableObjectList(route["rules"])
        routeRules.forEach { rule -> rewriteRouteRuleReferences(rule, removedTags) }
        route["rules"] = routeRules
        config["route"] = route

        val dns = mutableObjectMap(config["dns"])
        val dnsServers = mutableObjectList(dns["servers"])
        dnsServers.forEach { server ->
            if (server["detour"] in removedTags) server["detour"] = ENDPOINT_TAG
        }
        dns["servers"] = dnsServers
        val dnsRules = mutableObjectList(dns["rules"])
        dnsRules.forEach { rule -> rewriteDnsRuleReferences(rule, removedTags) }
        dns["rules"] = dnsRules
        config["dns"] = dns
    }

    private fun rewriteRouteRuleReferences(
        rule: MutableMap<String, Any?>,
        removedTags: Set<String>,
    ) {
        if (rule["outbound"] in removedTags) rule["outbound"] = ENDPOINT_TAG
        rewriteTagField(rule, "preferred_by", removedTags)
        rewriteNestedRules(rule, removedTags, ::rewriteRouteRuleReferences)
    }

    private fun rewriteDnsRuleReferences(
        rule: MutableMap<String, Any?>,
        removedTags: Set<String>,
    ) {
        rewriteTagField(rule, "outbound", removedTags)
        rewriteNestedRules(rule, removedTags, ::rewriteDnsRuleReferences)
    }

    private fun rewriteNestedRules(
        rule: MutableMap<String, Any?>,
        removedTags: Set<String>,
        rewrite: (MutableMap<String, Any?>, Set<String>) -> Unit,
    ) {
        if (rule["rules"] !is List<*>) return
        val nestedRules = mutableObjectList(rule["rules"])
        nestedRules.forEach { nestedRule -> rewrite(nestedRule, removedTags) }
        rule["rules"] = nestedRules
    }

    private fun rewriteTagField(
        target: MutableMap<String, Any?>,
        field: String,
        removedTags: Set<String>,
    ) {
        if (!target.containsKey(field)) return
        val value = target[field]
        target[field] = when (value) {
            is List<*> -> value.map { item ->
                if (item?.toString() in removedTags) ENDPOINT_TAG else item
            }
            else -> if (value?.toString() in removedTags) ENDPOINT_TAG else value
        }
    }

    private fun ensureTailscaleTunIpv6Address(config: MutableMap<String, Any?>) {
        val inbounds = mutableObjectList(config["inbounds"])
        var changed = false
        inbounds.filter { it["type"] == "tun" }.forEach { inbound ->
            val addresses = mutableValueList(inbound["address"])
            if (addresses.none { it?.toString() == TUN_IPV6_ADDRESS }) {
                addresses += TUN_IPV6_ADDRESS
                inbound["address"] = addresses
                changed = true
            }
        }
        if (changed) config["inbounds"] = inbounds
    }

    private fun isGlobalPrivateDirectRule(rule: Map<String, Any?>, directTag: String): Boolean {
        return rule["ip_is_private"] == true &&
            rule["outbound"] == directTag &&
            rule.keys.all { it in setOf("ip_is_private", "outbound", "invert") } &&
            rule["invert"] != true
    }

    private fun isManagedDynamicRouteRule(rule: Map<String, Any?>): Boolean {
        return rule["outbound"] == ENDPOINT_TAG &&
            stringValues(rule["preferred_by"]) == listOf(ENDPOINT_TAG) &&
            rule.keys.all { it in setOf("preferred_by", "outbound") }
    }

    private fun isManagedStaticRouteRule(rule: Map<String, Any?>): Boolean {
        return rule["outbound"] == ENDPOINT_TAG &&
            stringValues(rule["ip_cidr"]).containsAll(defaultTailnetCidrs) &&
            rule.keys.all { it in setOf("ip_cidr", "outbound") }
    }

    private fun isUnscopedDnsRouteRule(rule: Map<String, Any?>): Boolean {
        return rule.keys.all {
            it in setOf(
                "type",
                "action",
                "server",
                "strategy",
                "disable_cache",
                "rewrite_ttl",
                "client_subnet",
                "ip_accept_any",
            )
        }
    }

    private fun putRuleFirst(
        rules: MutableList<MutableMap<String, Any?>>,
        rule: LinkedHashMap<String, Any?>,
    ) {
        rules.removeAll { it == rule }
        rules.add(0, rule)
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
        if (parts[1] != prefix.toString()) return false
        val address = parts[0]
        if (':' in address) {
            if (prefix !in 0..128 || '%' in address || '[' in address || ']' in address) return false
            if ('.' in address && !isCanonicalIpv4(address.substringAfterLast(':'))) return false
            return runCatching {
                InetAddress.getByName(address)
                true
            }.getOrDefault(false)
        }
        return prefix in 0..32 && isCanonicalIpv4(address)
    }

    private fun isCanonicalIpv4(address: String): Boolean {
        val octets = address.split('.')
        return octets.size == 4 && octets.all { octet ->
            val value = octet.toIntOrNull()
            value in 0..255 && octet == value.toString()
        }
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

    private fun mutableValueList(value: Any?): MutableList<Any?> = when (value) {
        is List<*> -> value.toMutableList()
        null -> mutableListOf()
        else -> mutableListOf(value)
    }

    private fun stringValues(value: Any?): List<String> = when (value) {
        is List<*> -> value.mapNotNull { it?.toString() }
        null -> emptyList()
        else -> listOf(value.toString())
    }
}
