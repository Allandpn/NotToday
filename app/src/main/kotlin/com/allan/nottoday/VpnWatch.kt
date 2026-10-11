package com.allan.nottoday

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * Observa a VPN do aparelho (o Tailscale). O aviso chega por PendingIntent, então acorda o app mesmo que o
 * processo tenha morrido: a VPN caiu → os apps marcados são suspensos na hora.
 */
object VpnWatch {
    @Suppress("DEPRECATION")
    fun isVpnUp(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }

    fun register(context: Context) {
        try {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            context.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(request, pending(context))
        } catch (e: Exception) {
            Diag.lastError = "VpnWatch: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    fun unregister(context: Context) {
        try {
            context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(pending(context))
        } catch (_: Exception) {
        }
    }

    // FLAG_MUTABLE: o sistema acrescenta extras (rede, tipo de evento) ao intent.
    private fun pending(context: Context) = PendingIntent.getBroadcast(
        context, 1, Intent(context, VpnStateReceiver::class.java),
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** VPN subiu ou caiu: reavalia o filtro e os apps marcados na hora. */
class VpnStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        runAsync(onDone = pending::finish) {
            val policy = PolicyManager(context)
            if (!policy.isDeviceOwner()) return@runAsync
            if (VpnWatch.isVpnUp(context)) Health.check(context) else Health.invalidate(context)
            policy.quickSync()
        }
    }
}
