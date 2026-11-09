package com.codefluence.sdk

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * Default [CodefluenceNetworkMonitor] backed by ConnectivityManager's
 * NetworkCallback (A3). Fires on each network that becomes available so the SDK
 * can resend an accepted-but-unfinished confirmation when connectivity returns.
 */
internal class SystemNetworkMonitor(context: Context) : CodefluenceNetworkMonitor {
    private val connectivity = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private var callback: ConnectivityManager.NetworkCallback? = null

    override fun start(onAvailable: () -> Unit) {
        val manager = connectivity ?: return
        if (callback != null) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { onAvailable() }
        }
        callback = networkCallback
        runCatching { manager.registerNetworkCallback(request, networkCallback) }
    }

    override fun cancel() {
        val manager = connectivity ?: return
        callback?.let { runCatching { manager.unregisterNetworkCallback(it) } }
        callback = null
    }
}
