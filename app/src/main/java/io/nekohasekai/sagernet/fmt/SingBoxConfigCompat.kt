package io.nekohasekai.sagernet.fmt

/** Runtime migrations for configuration formats removed by newer sing-box cores. */
object SingBoxConfigCompat {

    fun migrate(config: MutableMap<String, Any?>) {
        migrateLegacyInbounds(config)
        migrateLegacyWireGuard(config)
    }

    private fun migrateLegacyInbounds(config: MutableMap<String, Any?>) {
        val inbounds = mutableObjectList(config["inbounds"])
        if (inbounds.isEmpty()) return

        val actionRules = mutableListOf<MutableMap<String, Any?>>()
        val usedTags = inbounds.mapNotNullTo(mutableSetOf()) { it["tag"]?.toString() }
        for ((index, inbound) in inbounds.withIndex()) {
            migrateLegacyTunFields(inbound)

            val domainStrategy = inbound.remove("domain_strategy")?.toString().orEmpty()
            val sniffEnabled = inbound.remove("sniff").asBoolean()
            inbound.remove("sniff_override_destination")
            val sniffTimeout = inbound.remove("sniff_timeout")
            val disableDomainUnmapping = inbound.remove("udp_disable_domain_unmapping").asBoolean()

            if (domainStrategy.isEmpty() && !sniffEnabled && !disableDomainUnmapping) continue
            val tag = inbound["tag"]?.toString()?.takeIf { it.isNotBlank() }
                ?: uniqueInboundTag(index, usedTags).also { inbound["tag"] = it }

            if (domainStrategy.isNotEmpty()) {
                actionRules += linkedMapOf(
                    "inbound" to tag,
                    "action" to "resolve",
                    "strategy" to domainStrategy,
                )
            }
            if (sniffEnabled) {
                actionRules += linkedMapOf<String, Any?>(
                    "inbound" to tag,
                    "action" to "sniff",
                ).apply {
                    if (sniffTimeout != null) put("timeout", sniffTimeout)
                }
            }
            if (disableDomainUnmapping) {
                actionRules += linkedMapOf(
                    "inbound" to tag,
                    "action" to "route-options",
                    "udp_disable_domain_unmapping" to true,
                )
            }
        }

        config["inbounds"] = inbounds
        if (actionRules.isNotEmpty()) {
            val route = mutableObject(config["route"])
            route["rules"] = (actionRules + mutableObjectList(route["rules"])).toMutableList()
            config["route"] = route
        }
    }

    private fun migrateLegacyTunFields(inbound: MutableMap<String, Any?>) {
        if (inbound["type"] != "tun") return
        mergeFields(inbound, "address", "inet4_address", "inet6_address")
        mergeFields(inbound, "route_address", "inet4_route_address", "inet6_route_address")
        mergeFields(
            inbound,
            "route_exclude_address",
            "inet4_route_exclude_address",
            "inet6_route_exclude_address",
        )
        inbound.remove("gso")
    }

    private fun mergeFields(
        target: MutableMap<String, Any?>,
        destination: String,
        vararg legacyFields: String,
    ) {
        val values = mutableListOf<Any?>()
        values.addAll(target[destination].asList())
        for (field in legacyFields) values.addAll(target.remove(field).asList())
        if (values.isNotEmpty()) target[destination] = values
    }

    private fun uniqueInboundTag(index: Int, usedTags: MutableSet<String>): String {
        var tag = "compat-in-$index"
        var suffix = 2
        while (!usedTags.add(tag)) tag = "compat-in-$index-${suffix++}"
        return tag
    }

    private fun migrateLegacyWireGuard(config: MutableMap<String, Any?>) {
        val outbounds = mutableObjectList(config["outbounds"])
        val legacy = outbounds.filter { it["type"] == "wireguard" }
        if (legacy.isEmpty()) return

        outbounds.removeAll(legacy.toSet())
        val endpoints = mutableObjectList(config["endpoints"])
        for (outbound in legacy) {
            val tag = outbound["tag"]?.toString().orEmpty()
            endpoints.removeAll { it["tag"] == tag }
            val peer = linkedMapOf<String, Any?>(
                "address" to outbound["server"],
                "port" to outbound["server_port"],
                "public_key" to outbound["peer_public_key"],
                "pre_shared_key" to outbound["pre_shared_key"],
                "allowed_ips" to listOf("0.0.0.0/0", "::/0"),
            )
            outbound["reserved"]?.let { peer["reserved"] = it }
            val endpoint = linkedMapOf<String, Any?>(
                "type" to "wireguard",
                "tag" to tag,
                "address" to outbound["local_address"],
                "private_key" to outbound["private_key"],
                "peers" to listOf(peer.filterValues { it != null }),
            )
            outbound["mtu"]?.let { endpoint["mtu"] = it }
            for (field in dialFields) outbound[field]?.let { endpoint[field] = it }
            endpoints += endpoint.filterValues { it != null }.toMutableMap()
        }
        config["outbounds"] = outbounds
        config["endpoints"] = endpoints
    }

    private val dialFields = listOf(
        "detour",
        "bind_interface",
        "inet4_bind_address",
        "inet6_bind_address",
        "routing_mark",
        "reuse_addr",
        "connect_timeout",
        "tcp_fast_open",
        "udp_fragment",
        "domain_resolver",
        "network_strategy",
        "network_type",
        "fallback_network_type",
        "fallback_delay",
    )

    private fun mutableObjectList(value: Any?): MutableList<MutableMap<String, Any?>> =
        (value as? List<*>)?.mapNotNull { item ->
            (item as? Map<*, *>)?.entries?.associateTo(linkedMapOf()) { (key, mapValue) ->
                key.toString() to mapValue
            }
        }?.toMutableList() ?: mutableListOf()

    private fun mutableObject(value: Any?): MutableMap<String, Any?> =
        (value as? Map<*, *>)?.entries?.associateTo(linkedMapOf()) { (key, mapValue) ->
            key.toString() to mapValue
        } ?: linkedMapOf()

    private fun Any?.asList(): List<Any?> = when (this) {
        null -> emptyList()
        is List<*> -> this
        else -> listOf(this)
    }

    private fun Any?.asBoolean(): Boolean = when (this) {
        is Boolean -> this
        is Number -> toInt() != 0
        is String -> equals("true", ignoreCase = true) || this == "1"
        else -> false
    }
}
