package io.nekohasekai.sagernet.utils

internal enum class DefaultNetworkChange(val forcePlatformRefresh: Boolean) {
    INITIAL(false),
    AVAILABLE(false),
    AVAILABLE_CHANGED(true),
    CAPABILITIES(true),
    LINK_PROPERTIES(true),
    LOST(false),
}

internal fun <T> availableNetworkChange(
    currentNetwork: T?,
    availableNetwork: T,
): DefaultNetworkChange = if (
    currentNetwork != null && currentNetwork != availableNetwork
) {
    DefaultNetworkChange.AVAILABLE_CHANGED
} else {
    DefaultNetworkChange.AVAILABLE
}

internal fun <T> initialNetworkAfterRegistration(
    callbackRegistered: Boolean,
    currentNetwork: T?,
    activeNetwork: T?,
): T? = if (callbackRegistered) {
    currentNetwork ?: activeNetwork
} else {
    activeNetwork ?: currentNetwork
}

internal data class FallbackNetworkCandidate<T>(
    val network: T,
    val physical: Boolean,
)

internal fun <T> selectPhysicalFallbackNetwork(
    existingUnderlying: FallbackNetworkCandidate<T>?,
    activeNetwork: FallbackNetworkCandidate<T>?,
    availableNetworks: List<FallbackNetworkCandidate<T>>,
): T? = listOfNotNull(existingUnderlying, activeNetwork).asSequence()
    .plus(availableNetworks.asSequence())
    .firstOrNull { it.physical }
    ?.network
