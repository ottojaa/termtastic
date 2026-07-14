/**
 * Enumeration of the addresses this machine is reachable at.
 *
 * Extracted from the settings dialog so every surface that needs the
 * machine's addresses — the Connections settings section and the pairing
 * dialog's QR candidate list — shares one implementation.
 *
 * @see se.soderbjorn.lunamux.ui.SettingsDialog
 * @see se.soderbjorn.lunamux.ui.PairingDialog
 */
package se.soderbjorn.lunamux.net

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
     * Ordered most-reachable first (see [interfaceRank]). The order matters
     * because callers truncate: the pairing QR keeps only the leading
     * `PairingPayload.MAX_CANDIDATES` that fit, so a phone-reachable Wi-Fi
     * address must not lose its slot to a VM host-only adapter.
     *
     * Note [NetworkInterface.isVirtual] means *subinterface* (`en0:1`), not
     * "virtual adapter" — VPN, container, and VM NICs are real interfaces to
     * the JVM and survive that filter, which is exactly why the ranking below
     * exists. They are demoted rather than dropped: a VPN address is useless
     * for same-Wi-Fi pairing but is the whole point of the planned
     * reach-from-anywhere follow-up.
     *
     * Called by the Connections settings section (display) and the pairing
     * dialog (QR candidate list).
     *
     * @return distinct IPv4 address strings, most-reachable first.
     */
    fun ipv4(): List<String> {
        val result = mutableListOf<Pair<Int, String>>()
        runCatching {
            val nics = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (nic in nics) {
                if (!nic.isUp || nic.isLoopback || nic.isVirtual) continue
                val rank = interfaceRank(nic.name)
                for (addr in nic.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        result.add(rank to addr.hostAddress)
                    }
                }
            }
        }.onFailure { log.warn("Failed to enumerate network interfaces", it) }
        // sortedBy is stable, so enumeration order still breaks ties within a rank.
        return result.sortedBy { it.first }.map { it.second }.distinct()
    }

    /**
     * Interface-name prefixes that denote a tunnel, bridge, container, or
     * hypervisor adapter. Addresses on these are real but almost never how a
     * phone on the same Wi-Fi reaches this machine.
     */
    private val VIRTUAL_PREFIXES = listOf(
        "utun", "tun", "tap", "ppp",          // VPN / tunnels
        "bridge", "vmnet", "vboxnet", "veth", // VM + container bridges
        "docker", "awdl", "llw",              // Docker, Apple Wireless Direct Link
    )

    /**
     * Rank an interface by how likely a same-network peer is to reach us on
     * it — lower sorts first.
     *
     * @param name the interface name (`en0`, `utun3`, `vmnet1`, …).
     * @return 0 for physical ethernet/Wi-Fi (`en*`), 2 for known virtual or
     *   tunnel adapters, 1 for anything unrecognised.
     */
    private fun interfaceRank(name: String): Int = when {
        VIRTUAL_PREFIXES.any { name.startsWith(it) } -> 2
        name.startsWith("en") -> 0
        else -> 1
    }
}
