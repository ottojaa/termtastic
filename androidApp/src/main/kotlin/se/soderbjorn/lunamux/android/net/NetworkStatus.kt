/**
 * Coarse connectivity classification for connection-failure messaging.
 *
 * When a connect attempt fails, the hosts screen uses these helpers to tell
 * the user *why* it probably failed ("you're on mobile data") instead of
 * showing a generic timeout. Requires only the normal-level
 * `ACCESS_NETWORK_STATE` permission — no runtime prompt.
 *
 * @see se.soderbjorn.lunamux.android.ui.HostsScreen
 */
package se.soderbjorn.lunamux.android.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Snapshot queries over the active network's transport. Each call reads the
 * live [ConnectivityManager] state; failures (missing service, stale
 * network handle) degrade to `false` so callers fall back to generic copy.
 */
object NetworkStatus {

    /**
     * Whether the phone's active network rides Wi-Fi.
     *
     * @param context any context; the application context is used internally.
     * @return `true` when the active network has the Wi-Fi transport.
     */
    fun isOnWifi(context: Context): Boolean =
        hasTransport(context, NetworkCapabilities.TRANSPORT_WIFI)

    /**
     * Whether the phone's active network rides mobile data.
     *
     * @param context any context; the application context is used internally.
     * @return `true` when the active network has the cellular transport.
     */
    fun isOnCellular(context: Context): Boolean =
        hasTransport(context, NetworkCapabilities.TRANSPORT_CELLULAR)

    /** Shared active-network transport probe backing the public queries. */
    private fun hasTransport(context: Context, transport: Int): Boolean = runCatching {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        cm.getNetworkCapabilities(network)?.hasTransport(transport) == true
    }.getOrDefault(false)
}
