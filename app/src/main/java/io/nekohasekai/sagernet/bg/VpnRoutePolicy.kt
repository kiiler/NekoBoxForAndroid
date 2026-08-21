package io.nekohasekai.sagernet.bg

internal fun shouldCaptureAllRoutes(
    bypassLan: Boolean,
    tailscaleEnabled: Boolean,
): Boolean {
    return !bypassLan || tailscaleEnabled
}

internal fun shouldEnableIpv6Tun(
    regularIpv6Enabled: Boolean,
    tailscaleEnabled: Boolean,
): Boolean {
    return regularIpv6Enabled || tailscaleEnabled
}
