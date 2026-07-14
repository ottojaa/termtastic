/**
 * Enumeration of the addresses and names this machine is reachable at.
 *
 * Extracted from the settings dialog so every surface that needs the
 * machine's addresses — the Connections settings section and the pairing
 * dialog's QR candidate list — shares one implementation.
 *
 * @see se.soderbjorn.lunamux.ui.SettingsDialog
 * @see se.soderbjorn.lunamux.ui.PairingPanel
 */
package se.soderbjorn.lunamux.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * One way a phone can be told to reach this machine: either a literal IPv4
 * address, or a name that resolves to one.
 *
 * Encoded into the pairing QR as a candidate and listed in the pairing
 * dialog's picker, so it carries both the wire value ([host]) and enough
 * provenance for the UI to explain where it came from.
 *
 * @property host the candidate string — an IPv4 literal or a hostname.
 * @property kind where this endpoint came from; drives the picker's caption
 *   and how much the value can be trusted.
 * @property nic the interface the address lives on (`en0`), or `null` for a
 *   name that is not tied to one address.
 * @property rank reachability rank inherited from the address it describes;
 *   lower sorts first. See [LocalAddresses.interfaceRank].
 */
data class LocalEndpoint(
    val host: String,
    val kind: Kind,
    val nic: String?,
    val rank: Int,
) {
    /**
     * Where an endpoint's value came from — which is exactly how much it can
     * be trusted, so the picker can say so rather than presenting three very
     * different things as equivalent.
     */
    enum class Kind {
        /** An IPv4 literal read off a live interface. Reachable now; DHCP may move it. */
        IP,

        /**
         * A name a resolver returned for [host]'s address (a PTR record). The
         * mapping is confirmed by something other than this machine — a VPN's
         * resolver, typically, since consumer routers rarely publish reverse
         * DNS for the LAN.
         */
        RESOLVED_NAME,

        /**
         * The machine's own mDNS name (`foo.local`), read from local config.
         *
         * Nothing verifies this maps to any address: the machine simply
         * asserts it, and resolving it here returns loopback, so the server
         * cannot check its own claim. Peers must speak mDNS to use it —
         * macOS/iOS and Windows do, **Android does not**, and networks that
         * block multicast break it for everyone. Offered because it survives
         * DHCP churn, but never a safe default.
         */
        SELF_NAME,
    }
}

/**
 * Stateless helpers over [NetworkInterface] enumeration. Each call reads the
 * live interface list, so plugging in ethernet or joining a VPN is reflected
 * on the next query without any caching invalidation.
 */
object LocalAddresses {

    private val log = LoggerFactory.getLogger(LocalAddresses::class.java)

    /** Per-address budget for the reverse lookup in [endpoints]. */
    private const val REVERSE_DNS_TIMEOUT_MS = 2_000L

    // Endpoint ranks, lowest-first. This is not just display order: the QR
    // encodes candidates in this order and `PairingPayload.encode` trims from
    // the tail, so rank decides what gets dropped when the payload is full.
    // Ordered by "how likely is this to still work for a phone", which means
    // durable names outrank addresses that no phone can route to.

    /** A physical Wi-Fi/ethernet address: what a phone on this Wi-Fi uses. */
    private const val RANK_PHYSICAL_IP = 0

    /** A DNS-confirmed name: a resolver vouched for it, and it survives DHCP. */
    private const val RANK_RESOLVED_NAME = 1

    /** A VPN address: unreachable on this Wi-Fi, reachable from anywhere else. */
    private const val RANK_TUNNEL_IP = 2

    /** The `.local` self-name: survives DHCP, but unverified and iPhone-only. */
    private const val RANK_SELF_NAME = 3

    /** An unrecognised interface: no reason to assume either way. */
    private const val RANK_UNKNOWN_IP = 4

    /** A bridge/VM/container address: reachable from nothing off this box. */
    private const val RANK_VIRTUAL_IP = 5

    /**
     * All non-loopback, non-link-local IPv4 addresses on up interfaces —
     * the set of addresses a same-network peer can reach this machine at.
     *
     * Ordered most-reachable first (see [interfaceRank]). The order matters
     * because callers truncate: the pairing QR keeps only the leading
     * `PairingPayload.MAX_CANDIDATES` that fit, so a phone-reachable Wi-Fi
     * address must not lose its slot to a VM host-only adapter.
     *
     * Does no name resolution, so it never blocks on a resolver and is safe
     * to call from composition. Callers that want names use [endpoints].
     *
     * Called by the Connections settings section (display).
     *
     * @return distinct IPv4 address strings, most-reachable first.
     */
    fun ipv4(): List<String> = rankedIpv4().map { it.host }.distinct()

    /**
     * Every endpoint this machine can offer a phone: each discovered IPv4
     * address, the name a resolver gives for it (where one answers), and the
     * machine's own mDNS name.
     *
     * Suspending and **not** safe to call from composition: a reverse lookup
     * against a resolver with no answer blocks, with no cancellable variant in
     * the JDK. The lookups therefore run concurrently on [Dispatchers.IO] with
     * a per-address [REVERSE_DNS_TIMEOUT_MS] budget, so the caller waits once
     * for the slowest rather than once per address, and a black-holed resolver
     * costs one timeout instead of stalling the dialog. A lookup that overruns
     * is abandoned, not cancelled — its thread finishes in the background.
     *
     * Deliberately vendor-neutral: it asks the system resolver and takes
     * whatever answers. A VPN that publishes reverse DNS shows up here for
     * free, with nothing in this file knowing that vendor exists.
     *
     * Called by the pairing dialog to populate its picker.
     *
     * @return endpoints most-reachable first; IPs and the names derived from
     *   them stay adjacent, with unverifiable self-names last.
     */
    suspend fun endpoints(): List<LocalEndpoint> {
        val addresses = withContext(Dispatchers.IO) { rankedIpv4() }
        val resolved = coroutineScope {
            addresses.map { address ->
                async(Dispatchers.IO) { address to reverseLookup(address.host) }
            }.awaitAll()
        }
        val result = mutableListOf<LocalEndpoint>()
        for ((address, name) in resolved) {
            result.add(address)
            if (name != null) {
                // A name is ranked on its own merit, not its interface's: a
                // resolver answering for it is the strongest evidence we have
                // that it works, and unlike the address it survives DHCP.
                result.add(
                    address.copy(
                        host = name,
                        kind = LocalEndpoint.Kind.RESOLVED_NAME,
                        rank = RANK_RESOLVED_NAME,
                    ),
                )
            }
        }
        selfName()?.let {
            result.add(LocalEndpoint(it, LocalEndpoint.Kind.SELF_NAME, nic = null, rank = RANK_SELF_NAME))
        }
        // sortedBy is stable, so ties keep discovery order.
        return result.distinctBy { it.host }.sortedBy { it.rank }
    }

    /**
     * The name a resolver returns for [ip], or `null` when none does.
     *
     * @param ip an IPv4 literal from [rankedIpv4].
     * @return the resolved name, or `null` when the resolver echoed the
     *   address back (the JDK's "no PTR" signal), timed out, or failed.
     */
    private suspend fun reverseLookup(ip: String): String? =
        withTimeoutOrNull(REVERSE_DNS_TIMEOUT_MS) {
            runCatching { InetAddress.getByName(ip).canonicalHostName }
                .getOrNull()
                ?.takeIf { it != ip && it.isNotBlank() }
        }

    /**
     * This machine's own hostname, when it is one a peer could actually look
     * up.
     *
     * A bare label (`my-macbook`, common on Linux) means nothing off-box, so
     * only a dotted name — `foo.local` or a real FQDN — is offered.
     *
     * @return the self-asserted hostname, or `null` when there isn't a usable
     *   one. See [LocalEndpoint.Kind.SELF_NAME] for why it is unverified.
     */
    private fun selfName(): String? =
        runCatching { InetAddress.getLocalHost().hostName }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && it.contains('.') }

    /**
     * Shared NIC walk behind [ipv4] and [endpoints].
     *
     * Note [NetworkInterface.isVirtual] means *subinterface* (`en0:1`), not
     * "virtual adapter" — VPN, container, and VM NICs are real interfaces to
     * the JVM and survive that filter, which is exactly why the ranking below
     * exists. They are demoted rather than dropped: a VPN address is useless
     * for same-Wi-Fi pairing, but it is the one that still works when the
     * phone is somewhere else entirely.
     *
     * @return one [LocalEndpoint] of kind [LocalEndpoint.Kind.IP] per address,
     *   most-reachable first.
     */
    private fun rankedIpv4(): List<LocalEndpoint> {
        val result = mutableListOf<LocalEndpoint>()
        runCatching {
            val nics = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (nic in nics) {
                if (!nic.isUp || nic.isLoopback || nic.isVirtual) continue
                val rank = interfaceRank(nic.name)
                for (addr in nic.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        result.add(
                            LocalEndpoint(addr.hostAddress, LocalEndpoint.Kind.IP, nic.name, rank),
                        )
                    }
                }
            }
        }.onFailure { log.warn("Failed to enumerate network interfaces", it) }
        // sortedBy is stable, so enumeration order still breaks ties within a rank.
        return result.sortedBy { it.rank }.distinctBy { it.host }
    }

    /**
     * VPN / tunnel interface prefixes. An address here is not reachable from
     * the same Wi-Fi, but it *is* reachable from anywhere the tunnel reaches —
     * which is the whole point when the phone is not on the LAN. Ranked below
     * physical adapters, but well above [VIRTUAL_PREFIXES].
     */
    private val TUNNEL_PREFIXES = listOf("utun", "tun", "tap", "ppp")

    /**
     * Bridge, container, and hypervisor adapter prefixes. Unlike a tunnel,
     * nothing off this machine can reach these: a Docker bridge address is
     * meaningful only to containers on this host. They are kept solely so the
     * picker can show the truth about what exists, and rank last so they are
     * the first thing dropped when the payload is full.
     */
    private val VIRTUAL_PREFIXES = listOf(
        "bridge", "vmnet", "vboxnet", "veth", // VM + container bridges
        "docker", "awdl", "llw",              // Docker, Apple Wireless Direct Link
    )

    /**
     * Rank an interface by how useful its address is to a phone — lower sorts
     * first, and the tail is what gets dropped when the QR is full.
     *
     * Tunnels used to share a rank with Docker bridges. That conflated two
     * opposite things: a VPN address reaches this machine from anywhere, a
     * bridge address reaches it from nothing. With names in the payload the
     * distinction started to matter, because the trim was throwing away a
     * working VPN endpoint to keep a container bridge no phone can route to.
     *
     * @param name the interface name (`en0`, `utun3`, `vmnet1`, …).
     * @return [RANK_PHYSICAL_IP] for `en*`, [RANK_TUNNEL_IP] for VPNs,
     *   [RANK_VIRTUAL_IP] for bridge/VM adapters, [RANK_UNKNOWN_IP] otherwise.
     */
    private fun interfaceRank(name: String): Int = when {
        VIRTUAL_PREFIXES.any { name.startsWith(it) } -> RANK_VIRTUAL_IP
        TUNNEL_PREFIXES.any { name.startsWith(it) } -> RANK_TUNNEL_IP
        name.startsWith("en") -> RANK_PHYSICAL_IP
        else -> RANK_UNKNOWN_IP
    }
}
