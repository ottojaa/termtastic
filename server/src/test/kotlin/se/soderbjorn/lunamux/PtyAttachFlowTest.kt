/**
 * Unit tests for [streamAttach] — the `/pty` attach ordering and seq gating —
 * driven against a controllable fake [TermSession] so the frame order is asserted
 * without a real WebSocket or PTY:
 *  - a declared grid (?cols/?rows) is registered via setClientSize before attach;
 *  - the very first frames are the attach Size then the redraw binary;
 *  - live events already folded into the attach payload (seq ≤ attach.seq) are
 *    dropped, and only later events (Output and Size) are forwarded.
 */
package se.soderbjorn.lunamux

import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunamux.pty.ClientPosture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PtyAttachFlowTest {

    /** A [TermSession] whose event stream and attach payload the test drives directly. */
    private class FakeSession(private var attach: AttachPayload) : TermSession {
        private val _events = MutableSharedFlow<SessionEvent>(replay = 0, extraBufferCapacity = 64)
        override val events: SharedFlow<SessionEvent> = _events.asSharedFlow()
        override val output: SharedFlow<ByteArray> = MutableSharedFlow<ByteArray>().asSharedFlow()
        override val cwd: StateFlow<String?> = MutableStateFlow(null).asStateFlow()
        override val programTitle: StateFlow<String?> = MutableStateFlow(null).asStateFlow()
        override val sizeEvents: StateFlow<Pair<Int, Int>> = MutableStateFlow(80 to 24).asStateFlow()

        val setClientSizeCalls = mutableListOf<Triple<String, Int, Int>>()

        suspend fun emit(ev: SessionEvent) = _events.emit(ev)

        override fun attachPayload(): AttachPayload = attach
        override fun bytesWritten(): Long = 0
        override fun write(bytes: ByteArray) {}
        override fun resetTerminalModes() {}
        override fun shutdown() {}
        override fun setClientSize(clientId: String, cols: Int, rows: Int, priority: SizePriority) {
            setClientSizeCalls.add(Triple(clientId, cols, rows))
        }
        override fun forceClientSize(clientId: String, cols: Int, rows: Int, priority: SizePriority) {}
        override fun removeClient(clientId: String) {}
        override fun detectState(): SessionState? = null
        override fun transcriptText(): String = ""
        override fun persistSnapshot(): ByteArray = ByteArray(0)
        override fun screenText(): String = ""
        override fun isProcessAlive(): Boolean = true
    }

    private fun sizeOf(frame: Frame): Pair<Int, Int> {
        val msg = windowJson.decodeFromString<PtyServerMessage>((frame as Frame.Text).readText())
        val size = msg as PtyServerMessage.Size
        return size.cols to size.rows
    }

    @Test
    fun `attach sends Size then redraw, then gates live events`() = runBlocking(Dispatchers.Default) {
        val fake = FakeSession(AttachPayload(seq = 5, cols = 80, rows = 24, bytes = "REDRAW".toByteArray()))
        val frames = mutableListOf<Frame>()

        val job = launch { fake.streamAttach("c1", qCols = 80, qRows = 24) { frames.add(it) } }
        delay(120) // let the subscription register and onSubscription send the attach frames

        // The declared grid was registered before attach.
        assertEquals(listOf(Triple("c1", 80, 24)), fake.setClientSizeCalls)

        // First two frames: attach Size, then the redraw binary.
        assertTrue(frames.size >= 2, "expected attach Size + redraw, got ${frames.size}")
        assertEquals(80 to 24, sizeOf(frames[0]))
        assertEquals("REDRAW", (frames[1] as Frame.Binary).readBytes().toString(Charsets.UTF_8))

        // Live events: one already covered by the attach (skipped), then newer ones.
        fake.emit(SessionEvent.Output(seq = 4, bytes = "stale".toByteArray()))   // ≤ 5 → dropped
        fake.emit(SessionEvent.Output(seq = 6, bytes = "fresh".toByteArray()))   // > 5 → sent
        fake.emit(SessionEvent.Size(seq = 7, cols = 100, rows = 30))             // > 5 → sent
        delay(120)

        val live = frames.drop(2)
        assertEquals(2, live.size, "stale event must be gated out")
        assertEquals("fresh", (live[0] as Frame.Binary).readBytes().toString(Charsets.UTF_8))
        assertEquals(100 to 30, sizeOf(live[1]))

        job.cancel()
    }

    @Test
    fun `no declared grid means no pre-attach vote`() = runBlocking(Dispatchers.Default) {
        val fake = FakeSession(AttachPayload(seq = 0, cols = 120, rows = 32, bytes = ByteArray(0)))
        val frames = mutableListOf<Frame>()

        val job = launch { fake.streamAttach("c2", qCols = null, qRows = null) { frames.add(it) } }
        delay(120)

        assertTrue(fake.setClientSizeCalls.isEmpty(), "absent ?cols/?rows must not vote")
        // Empty redraw → only the Size frame is sent.
        assertEquals(1, frames.size)
        assertEquals(120 to 32, sizeOf(frames[0]))

        job.cancel()
    }
}
