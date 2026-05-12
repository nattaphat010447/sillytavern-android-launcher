package com.standroid.launcher.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object Network {

    /**
     * Returns true if the device currently has an active internet-capable network.
     * Uses the modern [NetworkCapabilities] API (API 23+; our minSdk is 33 so always safe).
     */
    fun isConnected(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
