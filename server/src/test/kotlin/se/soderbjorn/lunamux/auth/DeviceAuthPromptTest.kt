/**
 * Tests for how [DeviceAuth] de-duplicates approval prompts across the several
 * connections one device opens at once.
 *
 * One phone reaching the server on two interfaces got a dialog per connection
 * and left a denied entry per dialog. The suppression that should have stopped
 * that ([DeviceAuth.recentDecisions]) was stamped with the time the *dialog
 * opened*, so any dialog left up longer than its TTL wrote an already-expired
 * entry and every queued connection re-prompted.
 *
 * The prompt is driven through [DeviceAuth.approvalPrompt], the clock through
 * [DeviceAuth.clock], and [DeviceAuth.headlessCheck] is forced false — the real
 * dialog can't be clicked headlessly.
 *
 * @see DeviceAuthApprovalChoiceTest for approve/deny/dismiss semantics.
 * @see DeviceAuthPairingTest for the non-interactive fast-path coverage.
 */
package se.soderbjorn.lunamux.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.awt.GraphicsEnvironment
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceAuthPromptTest {

    private fun tempRepo(): SettingsRepository {
        val dir = File.createTempFile("lunamux-prompt-auth", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        return SettingsRepository(File(dir, "test.db"))
    }

    /**
     * The incident this suite exists for had one iPhone arrive on two
     * link-local addresses at once, so the remote address is a parameter.
     */
    private fun iosClient(address: String) = DeviceAuth.ClientInfo(
        type = "iOS",
        hostname = "southphone.local",
        selfReportedIp = "10.0.4.106",
        remoteAddress = address,
    )

    @BeforeTest
    fun setUp() {
        DeviceAuth.headlessCheck = { false }
        DeviceAuth.clearTransientSuppressions()
    }

    @AfterTest
    fun tearDown() {
        DeviceAuth.approvalPrompt = DeviceAuth.defaultApprovalPrompt
        DeviceAuth.headlessCheck = { GraphicsEnvironment.isHeadless() }
        DeviceAuth.clock = { System.currentTimeMillis() }
        DeviceAuth.clearTransientSuppressions()
    }



    /**
     * The reported incident: one iPhone opened /window from two link-local
     * addresses with the same device token. Both got a dialog and both denials
     * were appended, leaving two identical rows in the settings list.
     */
    @Test
    fun `concurrent connections from one device prompt once and persist one denial`() {
        val repo = tempRepo()
        repo.setAllowRemoteConnections(true)
        val prompts = AtomicInteger()
        // The real dialog stood open ~22s with the second connection arriving
        // partway through. Hold the first prompt open until the second request
        // is actually in flight, or the first would finish before the second
        // starts and the race under test would never happen.
        val firstPromptReached = CompletableDeferred<Unit>()
        val releaseFirstPrompt = CompletableDeferred<Unit>()
        DeviceAuth.approvalPrompt = {
            if (prompts.incrementAndGet() == 1) {
                firstPromptReached.complete(Unit)
                releaseFirstPrompt.await()
            }
            DeviceAuth.ApprovalChoice.DENY
        }

        val decisions = runBlocking {
            val first = async(Dispatchers.IO) {
                DeviceAuth.authorize("device-token-1", iosClient("fe80::ce8:afff:fe4e:1400%35"), repo)
            }
            firstPromptReached.await()
            val second = async(Dispatchers.IO) {
                DeviceAuth.authorize("device-token-1", iosClient("fe80::40f:7ccb:f92c:1908%14"), repo)
            }
            // Give the second request time to reach (and block on) the gate.
            delay(200)
            releaseFirstPrompt.complete(Unit)
            listOf(first, second).awaitAll()
        }

        assertEquals(listOf(DeviceAuth.Decision.REJECTED, DeviceAuth.Decision.REJECTED), decisions)
        assertEquals(1, prompts.get(), "the second connection should reuse the first decision")
        assertEquals(
            1,
            DeviceAuth.listDeniedDevices(repo).size,
            "one device denied once must yield exactly one denied entry",
        )
    }

    /**
     * The reported incident. The first dialog stood open ~22s — longer than
     * [DeviceAuth.RECENT_DECISION_TTL_MS] — so a decision cached against the
     * time the dialog *opened* was already expired when it was written, and
     * the queued second connection re-prompted and appended a second denial.
     */
    @Test
    fun `a decision on a long-open dialog still suppresses the queued connection`() {
        val repo = tempRepo()
        repo.setAllowRemoteConnections(true)
        val prompts = AtomicInteger()
        // A hand-cranked clock: it jumps 22 seconds while the first dialog is
        // open, spanning the suppression TTL without the test sleeping.
        val nowMs = AtomicLong(1_000_000)
        DeviceAuth.clock = { nowMs.get() }
        val firstPromptReached = CompletableDeferred<Unit>()
        val releaseFirstPrompt = CompletableDeferred<Unit>()
        DeviceAuth.approvalPrompt = {
            if (prompts.incrementAndGet() == 1) {
                firstPromptReached.complete(Unit)
                releaseFirstPrompt.await()
            }
            DeviceAuth.ApprovalChoice.DENY
        }

        val decisions = runBlocking {
            val first = async(Dispatchers.IO) {
                DeviceAuth.authorize("device-token-1", iosClient("fe80::ce8:afff:fe4e:1400%35"), repo)
            }
            firstPromptReached.await()
            val second = async(Dispatchers.IO) {
                DeviceAuth.authorize("device-token-1", iosClient("fe80::40f:7ccb:f92c:1908%14"), repo)
            }
            delay(200)
            // The user stares at the dialog for 22 seconds, then clicks Deny.
            nowMs.addAndGet(22_000)
            releaseFirstPrompt.complete(Unit)
            listOf(first, second).awaitAll()
        }

        assertEquals(listOf(DeviceAuth.Decision.REJECTED, DeviceAuth.Decision.REJECTED), decisions)
        assertEquals(
            1,
            prompts.get(),
            "a slow decision must still suppress the queued connection, not re-prompt it",
        )
        assertEquals(
            1,
            DeviceAuth.listDeniedDevices(repo).size,
            "one device denied once must yield exactly one denied entry",
        )
    }

}
