/**
 * Tests for [DeviceAuth]'s QR-pairing approval path and the related
 * hardening: pairing-token trust (with the allow-remote auto-enable),
 * fall-through on spent/foreign pairing tokens, silent rejection of
 * token-less remote peers, and the append semantics of trust persistence
 * (a pairing or clean-slate approval must never wipe existing entries).
 *
 * Everything here goes through [DeviceAuth.checkFastPath], which never
 * shows a dialog — the interactive prompt path is exercised manually.
 */
package se.soderbjorn.termtastic.auth

import se.soderbjorn.termtastic.persistence.SettingsRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceAuthPairingTest {

    private fun tempRepo(): SettingsRepository {
        val dir = File.createTempFile("termtastic-pairing-auth", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        return SettingsRepository(File(dir, "test.db"))
    }

    private fun remoteClient(address: String = "192.168.1.50") = DeviceAuth.ClientInfo(
        type = "Android",
        hostname = "phone",
        selfReportedIp = address,
        remoteAddress = address,
    )

    private fun loopbackClient() = DeviceAuth.ClientInfo(
        type = "Web",
        hostname = null,
        selfReportedIp = null,
        remoteAddress = "127.0.0.1",
    )

    @Test
    fun `pairing token trusts a remote device and enables allow-remote`() {
        val repo = tempRepo()
        assertFalse(repo.isAllowRemoteConnections())
        val pairToken = PairingTokens.mint()

        val decision = DeviceAuth.checkFastPath("device-token-1", remoteClient(), repo, pairToken)

        assertEquals(DeviceAuth.Decision.APPROVED, decision)
        assertTrue(repo.isAllowRemoteConnections(), "pairing from non-loopback should enable allow-remote")
        assertEquals(1, DeviceAuth.listTrustedDevices(repo).size)

        // Reconnect re-sending the consumed pairing token: falls through to
        // the trusted-device lookup and passes.
        val again = DeviceAuth.checkFastPath("device-token-1", remoteClient(), repo, pairToken)
        assertEquals(DeviceAuth.Decision.APPROVED, again)
    }

    @Test
    fun `foreign pairing token falls through to the normal flow`() {
        val repo = tempRepo()
        // Allow-remote off: the network gate rejects the unknown remote device.
        assertEquals(
            DeviceAuth.Decision.REJECTED,
            DeviceAuth.checkFastPath("device-token-2", remoteClient(), repo, "not-a-minted-token"),
        )
        assertFalse(repo.isAllowRemoteConnections())
        assertTrue(DeviceAuth.listTrustedDevices(repo).isEmpty())

        // Allow-remote on: the unknown token needs the interactive prompt (null).
        repo.setAllowRemoteConnections(true)
        assertNull(DeviceAuth.checkFastPath("device-token-2", remoteClient(), repo, "not-a-minted-token"))
    }

    @Test
    fun `token-less remote peers are rejected without prompting`() {
        val repo = tempRepo()
        repo.setAllowRemoteConnections(true)
        assertEquals(
            DeviceAuth.Decision.REJECTED,
            DeviceAuth.checkFastPath(null, remoteClient(), repo),
        )
        assertEquals(
            DeviceAuth.Decision.REJECTED,
            DeviceAuth.checkFastPath("", remoteClient(), repo),
        )
    }

    @Test
    fun `token-less loopback keeps the prompt path`() {
        val repo = tempRepo()
        // null = "needs the interactive flow" — the loopback curl/dev
        // experience is unchanged by the remote hardening.
        assertNull(DeviceAuth.checkFastPath(null, loopbackClient(), repo))
    }

    @Test
    fun `pairing appends to the trusted list instead of replacing it`() {
        val repo = tempRepo()
        DeviceAuth.addTrustedToken(repo, "pre-existing", label = "Browser")
        val pairToken = PairingTokens.mint()

        val decision = DeviceAuth.checkFastPath("device-token-3", remoteClient("192.168.1.51"), repo, pairToken)

        assertEquals(DeviceAuth.Decision.APPROVED, decision)
        assertEquals(2, DeviceAuth.listTrustedDevices(repo).size)
    }

    @Test
    fun `clean-slate loopback auto-approve preserves MCP tokens`() {
        val repo = tempRepo()
        DeviceAuth.addTrustedToken(repo, "mcp-abc", DeviceAuth.MCP_LABEL, DeviceAuth.MCP_SCOPE_READ)

        // Only MCP tokens exist → the clean-slate loopback shortcut fires for
        // the first interactive localhost device…
        val decision = DeviceAuth.checkFastPath("device-token-4", loopbackClient(), repo)
        assertEquals(DeviceAuth.Decision.APPROVED, decision)

        // …and must not wipe the MCP token while persisting the new device.
        assertEquals(1, DeviceAuth.listMcpTokens(repo).size)
        assertEquals(2, DeviceAuth.listTrustedDevices(repo).size)
    }
}
