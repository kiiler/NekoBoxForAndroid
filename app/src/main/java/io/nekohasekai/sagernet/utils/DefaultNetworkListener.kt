package io.nekohasekai.sagernet.utils

import android.annotation.TargetApi
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.runBlocking
import java.net.UnknownHostException

object DefaultNetworkListener {
    private sealed class NetworkMessage {
        class Start(
            val key: Any,
            val listener: (Network?, DefaultNetworkChange) -> Unit,
        ) : NetworkMessage() {
            val completion = CompletableDeferred<Unit>()
        }
        class Get : NetworkMessage() {
            val response = CompletableDeferred<Network>()
        }

        class Stop(val key: Any) : NetworkMessage() {
            val completion = CompletableDeferred<Unit>()
        }

        class Put(val generation: Long, val network: Network) : NetworkMessage()
        class Update(
            val generation: Long,
            val network: Network,
            val change: DefaultNetworkChange,
        ) : NetworkMessage()
        class Lost(val generation: Long, val network: Network) : NetworkMessage()
    }

    private val networkActor = GlobalScope.actor<NetworkMessage>(Dispatchers.Unconfined) {
        val listeners = mutableMapOf<Any, (Network?, DefaultNetworkChange) -> Unit>()
        var network: Network? = null
        var activeGeneration = 0L
        var nextGeneration = 0L
        val pendingRequests = arrayListOf<NetworkMessage.Get>()
        fun notifyListener(
            listener: (Network?, DefaultNetworkChange) -> Unit,
            currentNetwork: Network?,
            change: DefaultNetworkChange,
        ) {
            runCatching { listener(currentNetwork, change) }.onFailure { Logs.w(it) }
        }
        for (message in channel) when (message) {
            is NetworkMessage.Start -> {
                if (listeners.isEmpty()) {
                    activeGeneration = ++nextGeneration
                    val callbackRegistered = register(activeGeneration)
                    network = initialNetworkAfterRegistration(
                        callbackRegistered = callbackRegistered,
                        currentNetwork = network,
                        activeNetwork = physicalFallbackNetwork(),
                    )
                }
                listeners[message.key] = message.listener
                if (network != null) {
                    notifyListener(message.listener, network, DefaultNetworkChange.INITIAL)
                }
                message.completion.complete(Unit)
            }
            is NetworkMessage.Get -> {
                if (listeners.isEmpty()) {
                    message.response.completeExceptionally(
                        UnknownHostException("Getting network without an active listener")
                    )
                } else if (network == null) {
                    pendingRequests += message
                } else {
                    message.response.complete(network)
                }
            }
            is NetworkMessage.Stop -> {
                val listener = listeners.remove(message.key)
                if (listener != null) notifyListener(listener, null, DefaultNetworkChange.LOST)
                if (listener != null && listeners.isEmpty()) {
                    network = null
                    activeGeneration = 0L
                    runCatching { unregister() }.onFailure { Logs.w(it) }
                    val stopped = UnknownHostException("Default network listener stopped")
                    pendingRequests.forEach { it.response.completeExceptionally(stopped) }
                    pendingRequests.clear()
                }
                // The acknowledgement is the ordering barrier used by service restarts.
                message.completion.complete(Unit)
            }

            is NetworkMessage.Put -> {
                if (listeners.isEmpty() || message.generation != activeGeneration) continue
                val change = availableNetworkChange(network, message.network)
                network = message.network
                pendingRequests.forEach { it.response.complete(message.network) }
                pendingRequests.clear()
                listeners.values.forEach {
                    notifyListener(it, network, change)
                }
            }
            is NetworkMessage.Update -> if (
                listeners.isNotEmpty() &&
                message.generation == activeGeneration &&
                network == message.network
            ) {
                listeners.values.forEach { notifyListener(it, network, message.change) }
            }
            is NetworkMessage.Lost -> if (
                listeners.isNotEmpty() &&
                message.generation == activeGeneration &&
                network == message.network
            ) {
                network = null
                listeners.values.forEach {
                    notifyListener(it, null, DefaultNetworkChange.LOST)
                }
            }
        }
    }

    suspend fun start(key: Any, listener: (Network?) -> Unit) =
        startWithEvents(key) { network, _ -> listener(network) }

    internal suspend fun startWithEvents(
        key: Any,
        listener: (Network?, DefaultNetworkChange) -> Unit,
    ) =
        NetworkMessage.Start(key, listener).run {
            networkActor.send(this)
            completion.await()
        }

    suspend fun get() = if (fallback) {
        physicalFallbackNetwork()
            ?: throw UnknownHostException() // failed to listen, return current if available
    } else NetworkMessage.Get().run {
        networkActor.send(this)
        response.await()
    }

    suspend fun stop(key: Any) = NetworkMessage.Stop(key).run {
        networkActor.send(this)
        completion.await()
    }

    // NB: this runs in ConnectivityThread, and this behavior cannot be changed until API 26
    private fun callback(generation: Long) = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = runBlocking {
            networkActor.send(NetworkMessage.Put(generation, network))
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) = runBlocking {
            networkActor.send(
                NetworkMessage.Update(
                    generation,
                    network,
                    DefaultNetworkChange.CAPABILITIES,
                )
            )
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
            runBlocking {
                networkActor.send(
                    NetworkMessage.Update(
                        generation,
                        network,
                        DefaultNetworkChange.LINK_PROPERTIES,
                    )
                )
            }

        override fun onLost(network: Network) = runBlocking {
            networkActor.send(NetworkMessage.Lost(generation, network))
        }
    }

    @Volatile
    private var fallback = false
    private val request = NetworkRequest.Builder().apply {
        addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        if (Build.VERSION.SDK_INT == 23) {  // workarounds for OEM bugs
            removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            removeCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        }
    }.build()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var registeredCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Unfortunately registerDefaultNetworkCallback is going to return VPN interface since Android P DP1:
     * https://android.googlesource.com/platform/frameworks/base/+/dda156ab0c5d66ad82bdcf76cda07cbc0a9c8a2e
     *
     * This makes doing a requestNetwork with REQUEST necessary so that we don't get ALL possible networks that
     * satisfies default network capabilities but only THE default network. Unfortunately, we need to have
     * android.permission.CHANGE_NETWORK_STATE to be able to call requestNetwork.
     *
     * Source: https://android.googlesource.com/platform/frameworks/base/+/2df4c7d/services/core/java/com/android/server/ConnectivityService.java#887
     */
    private fun register(generation: Long): Boolean {
        val callback = callback(generation)
        registeredCallback = callback
        try {
            fallback = false
            when (Build.VERSION.SDK_INT) {
                in 31..Int.MAX_VALUE -> @TargetApi(31) {
                    SagerNet.connectivity.registerBestMatchingNetworkCallback(
                        request, callback, mainHandler
                    )
                }
                in 28 until 31 -> @TargetApi(28) {  // we want REQUEST here instead of LISTEN
                    SagerNet.connectivity.requestNetwork(request, callback, mainHandler)
                }
                in 26 until 28 -> @TargetApi(26) {
                    SagerNet.connectivity.registerDefaultNetworkCallback(callback, mainHandler)
                }
                in 24 until 26 -> @TargetApi(24) {
                    SagerNet.connectivity.registerDefaultNetworkCallback(callback)
                }
                else -> {
                    SagerNet.connectivity.requestNetwork(request, callback)
                    // known bug on API 23: https://stackoverflow.com/a/33509180/2245107
                }
            }
            return true
        } catch (e: Exception) {
            Logs.w(e)
            fallback = true
            registeredCallback = null
            return false
        }
    }

    private fun unregister() {
        val callback = registeredCallback ?: return
        registeredCallback = null
        SagerNet.connectivity.unregisterNetworkCallback(callback)
    }

    private fun physicalFallbackNetwork(): Network? {
        val connectivity = SagerNet.connectivity
        fun candidate(network: Network?): FallbackNetworkCandidate<Network>? {
            network ?: return null
            val capabilities = runCatching {
                connectivity.getNetworkCapabilities(network)
            }.getOrNull()
            return FallbackNetworkCandidate(
                network = network,
                physical = capabilities != null &&
                    !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            )
        }

        val activeNetwork = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching { connectivity.activeNetwork }.getOrNull()
        } else {
            null
        }
        val availableNetworks = runCatching { connectivity.allNetworks.asList() }
            .getOrDefault(emptyList())
        return selectPhysicalFallbackNetwork(
            existingUnderlying = candidate(SagerNet.underlyingNetwork),
            activeNetwork = candidate(activeNetwork),
            availableNetworks = availableNetworks.mapNotNull(::candidate),
        )
    }
}
