/**
 * Enumeration of the addresses this machine is reachable at.
 *
 * Extracted from the settings dialog so every surface that needs the
 * machine's addresses — the Connections settings section and the pairing
 * dialog's QR candidate list — shares one implementation.
 *
 * @see se.soderbjorn.termtastic.ui.SettingsDialog
 * @see se.soderbjorn.termtastic.ui.PairingDialog
 */
package se.soderbjorn.termtastic.net

import org.slf4j.LoggerFactory
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Stateless helpers over [NetworkInterface] enumeration. Each call reads the
 * live interface list, so plugging in ethernet or joining a VPN is reflected
 * on the next query without any caching invalidation.
 */
object LocalAddresses {

    private val log = LoggerFactory.getLogger(LocalAddresses::class.java)

    /**
     * All non-loopback, non-link-local IPv4 addresses on up interfaces —
     * the set of addresses a same-network peer can reach this machine at.
     *
     * Called by the Connections settings section (display) and the pairing
     * dialog (QR candidate list).
     *
     * @return distinct IPv4 address strings, in interface enumeration order.
     */
    fun ipv4(): List<String> {
        val result = mutableListOf<String>()
        runCatching {
            val nics = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (nic in nics) {
                if (!nic.isUp || nic.isLoopback || nic.isVirtual) continue
                for (addr in nic.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        result.add(addr.hostAddress)
                    }
                }
            }
        }.onFailure { log.warn("Failed to enumerate network interfaces", it) }
        return result.distinct()
    }
}
