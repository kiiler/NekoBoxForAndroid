package moe.matsuri.nb4a

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Build.VERSION_CODES
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import io.nekohasekai.sagernet.utils.PackageCache
import libcore.BoxPlatformInterface
import libcore.InterfaceUpdateListener
import libcore.Libcore
import libcore.NB4AInterface
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.NetworkInterface
import org.json.JSONArray
import org.json.JSONObject

class NativeInterface : BoxPlatformInterface, NB4AInterface {

    private val networkMonitorLock = Any()
    private val platformUnderlyingNetworks = linkedMapOf<Long, Network?>()

    //  libbox interface

    override fun autoDetectInterfaceControl(fd: Int) {
        DataStore.vpnService?.protect(fd)
    }

    override fun openTun(singTunOptionsJson: String, tunPlatformOptionsJson: String): Long {
        if (DataStore.vpnService == null) {
            throw Exception("no VpnService")
        }
        return DataStore.vpnService!!.startVpn(singTunOptionsJson, tunPlatformOptionsJson).toLong()
    }

    override fun useProcFS(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun findConnectionOwner(
        ipProto: Int, srcIp: String, srcPort: Int, destIp: String, destPort: Int
    ): Int {
        return SagerNet.connectivity.getConnectionOwnerUid(
            ipProto, InetSocketAddress(srcIp, srcPort), InetSocketAddress(destIp, destPort)
        )
    }

    override fun packageNameByUid(uid: Int): String {
        PackageCache.awaitLoadSync()

        if (uid <= 1000L) {
            return "android"
        }

        val packageNames = PackageCache.uidMap[uid]
        if (!packageNames.isNullOrEmpty()) for (packageName in packageNames) {
            return packageName
        }

        error("unknown uid $uid")
    }

    override fun uidByPackageName(packageName: String): Int {
        PackageCache.awaitLoadSync()
        return PackageCache[packageName] ?: 0
    }

    // TODO: 'getter for connectionInfo: WifiInfo!' is deprecated
    override fun wifiState(): String {
        val wifiManager =
            app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val connectionInfo = wifiManager.connectionInfo
        return "${connectionInfo.ssid},${connectionInfo.bssid}"
    }

    override fun networkInterfaces(): String {
        val result = JSONArray()
        val network = synchronized(networkMonitorLock) {
            if (platformUnderlyingNetworks.isNotEmpty()) {
                platformUnderlyingNetworks.values.firstOrNull { it != null }
            }
            else SagerNet.underlyingNetwork
        }
        val underlying = underlyingNetworkState(network) ?: return result.toString()
        result.put(underlying.toJson())
        return result.toString()
    }

    override fun startDefaultInterfaceMonitor(
        monitorID: Long,
        listener: InterfaceUpdateListener,
    ) = runBlocking {
        synchronized(networkMonitorLock) {
            platformUnderlyingNetworks[monitorID] = SagerNet.underlyingNetwork
        }
        DefaultNetworkListener.startWithEvents(monitorID) { network, change ->
            synchronized(networkMonitorLock) {
                if (platformUnderlyingNetworks.containsKey(monitorID)) {
                    platformUnderlyingNetworks[monitorID] = network
                }
            }
            val underlying = underlyingNetworkState(network)
            if (underlying == null) {
                listener.updateDefaultInterface(
                    "",
                    -1,
                    false,
                    false,
                    change.forcePlatformRefresh,
                )
            } else {
                listener.updateDefaultInterface(
                    underlying.networkInterface.name,
                    underlying.networkInterface.index,
                    underlying.expensive,
                    false,
                    change.forcePlatformRefresh,
                )
            }
        }
    }

    override fun closeDefaultInterfaceMonitor(monitorID: Long) = runBlocking {
        try {
            DefaultNetworkListener.stop(monitorID)
        } finally {
            synchronized(networkMonitorLock) {
                platformUnderlyingNetworks.remove(monitorID)
            }
        }
    }

    private fun underlyingNetworkState(network: Network?): UnderlyingNetworkState? {
        network ?: return null
        val capabilities = SagerNet.connectivity.getNetworkCapabilities(network) ?: return null
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null
        val linkProperties = SagerNet.connectivity.getLinkProperties(network) ?: return null
        val interfaceName = linkProperties.interfaceName?.takeIf(String::isNotBlank) ?: return null
        val networkInterface = runCatching { NetworkInterface.getByName(interfaceName) }.getOrNull()
            ?: return null
        val up = runCatching { networkInterface.isUp }.getOrDefault(false)
        val loopback = runCatching { networkInterface.isLoopback }.getOrDefault(false)
        if (!up || loopback || isNekoTunnel(networkInterface)) return null

        val type = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 0
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 2
            else -> 3
        }
        val dnsServers = linkProperties.dnsServers.mapNotNull { address ->
            address.hostAddress?.substringBefore('%')
        }
        return UnderlyingNetworkState(
            networkInterface = networkInterface,
            type = type,
            dnsServers = dnsServers,
            expensive = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        )
    }

    private fun isNekoTunnel(networkInterface: NetworkInterface): Boolean = runCatching {
        networkInterface.inetAddresses.toList().any { address ->
            when (address.hostAddress?.substringBefore('%')) {
                "172.19.0.1", "fdfe:dcba:9876::1" -> true
                else -> false
            }
        }
    }.getOrDefault(false)

    private data class UnderlyingNetworkState(
        val networkInterface: NetworkInterface,
        val type: Int,
        val dnsServers: List<String>,
        val expensive: Boolean,
    ) {
        fun toJson(): JSONObject {
            val loopback = runCatching { networkInterface.isLoopback }.getOrDefault(false)
            val pointToPoint = runCatching { networkInterface.isPointToPoint }.getOrDefault(false)
            val up = runCatching { networkInterface.isUp }.getOrDefault(false)
            val addresses = JSONArray()
            runCatching {
                networkInterface.interfaceAddresses.forEach { interfaceAddress ->
                    val hostAddress = interfaceAddress.address?.hostAddress
                        ?.substringBefore('%')
                        ?: return@forEach
                    addresses.put("$hostAddress/${interfaceAddress.networkPrefixLength.toInt()}")
                }
            }
            val hardwareAddress = runCatching {
                networkInterface.hardwareAddress?.joinToString(":") {
                    "%02x".format(it.toInt() and 0xff)
                }.orEmpty()
            }.getOrDefault("")
            return JSONObject()
                .put("index", networkInterface.index)
                .put("mtu", runCatching { networkInterface.mtu }.getOrDefault(0))
                .put("name", networkInterface.name.orEmpty())
                .put("hardware_address", hardwareAddress)
                .put("addresses", addresses)
                .put("up", up)
                .put("running", up)
                .put("broadcast", !loopback && !pointToPoint)
                .put("loopback", loopback)
                .put("point_to_point", pointToPoint)
                .put("multicast", runCatching { networkInterface.supportsMulticast() }.getOrDefault(false))
                .put("type", type)
                .put("dns_servers", JSONArray(dnsServers))
                .put("expensive", expensive)
                .put("constrained", false)
                .put("underlying", true)
                .put("vpn", false)
        }
    }

    override fun sendNotification(
        identifier: String,
        title: String,
        body: String,
        openURL: String,
    ) {
        val contentIntent = openURL.takeIf { it.isNotBlank() }?.let { url ->
            PendingIntent.getActivity(
                app,
                identifier.hashCode(),
                Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val notification = NotificationCompat.Builder(app, "tailscale-authentication")
            .setSmallIcon(R.drawable.ic_service_active)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(app).notify(identifier.hashCode(), notification)
    }

    // nb4a interface

    override fun useOfficialAssets(): Boolean {
        return DataStore.rulesProvider == 0
    }

    override fun selector_OnProxySelected(selectorTag: String, tag: String) {
        if (selectorTag != "proxy") {
            Logs.d("other selector: $selectorTag")
            return
        }
        Libcore.resetAllConnections(true)
        DataStore.baseService?.apply {
            runOnDefaultDispatcher {
                val id = data.proxy!!.config.profileTagMap
                    .filterValues { it == tag }.keys.firstOrNull() ?: -1
                val ent = SagerDatabase.proxyDao.getById(id) ?: return@runOnDefaultDispatcher
                // traffic & title
                data.proxy?.apply {
                    looper?.selectMain(id)
                    displayProfileName = ServiceNotification.genTitle(ent)
                    data.notification?.postNotificationTitle(displayProfileName)
                }
                // post binder
                data.binder.broadcast { b ->
                    b.cbSelectorUpdate(id)
                }
            }
        }
    }

}
