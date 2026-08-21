package io.nekohasekai.sagernet.fmt

/** Runtime migrations for configuration formats removed by newer sing-box cores. */
object SingBoxConfigCompat {

    fun migrate(config: MutableMap<String, Any?>) {
        migrateLegacyWireGuard(config)
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
}
