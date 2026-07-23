/**
 * The ordered outbound event stream a [TermSession] broadcasts to attached
 * `/pty` clients, plus the attach snapshot a newly-connected client is seeded
 * with. This file defines [SessionEvent] and [AttachPayload].
 *
 * Every output chunk and every size change is assigned a strictly increasing
 * [SessionEvent.seq] under the session's outbound monitor, and both ride one
 * ordered stream ([TermSession.events]). That makes attach exact rather than
 * timing-dependent: [AttachPayload.seq] is the last event folded into the attach
 * redraw, so a client applies the redraw and then every live event with a
 * greater seq — no gap, no double-apply. This replaces the previous
 * wall-clock-ordered `merge{}` of the output and size flows.
 *
 * @see TermSession.events
 * @see TermSession.attachPayload
 */
package se.soderbjorn.lunamux

/**
 * One ordered outbound event. [seq] is monotonic within a session and gates
 * attach replay: a client skips events whose seq is already covered by its
 * [AttachPayload].
 */
sealed interface SessionEvent {
    /** Strictly increasing sequence number, assigned under the session's outbound monitor. */
    val seq: Long

    /**
     * A chunk of session output — live PTY bytes, a mode-reset broadcast, or a
     * synthesized resync redraw. Delivered to the client as a binary frame.
     */
    class Output(override val seq: Long, val bytes: ByteArray) : SessionEvent

    /**
     * An effective grid size change. Delivered as a `Size` control frame so the
     * client resizes its local grid before any subsequent output paints at the
     * new width.
     */
    class Size(override val seq: Long, val cols: Int, val rows: Int) : SessionEvent
}

/**
 * The seed a client receives on attach: the grid dimensions to adopt and a
 * self-contained redraw ([bytes]) that reconstructs the current screen at those
 * dimensions, tagged with the [seq] it reflects.
 *
 * The client adopts [cols]×[rows], applies [bytes], then processes live
 * [SessionEvent]s with `seq > this.seq`.
 *
 * @property seq the last [SessionEvent.seq] folded into [bytes].
 * @property cols grid width the redraw is authored for.
 * @property rows grid height the redraw is authored for.
 * @property bytes the synthesized redraw (empty only for a brand-new session).
 */
class AttachPayload(val seq: Long, val cols: Int, val rows: Int, val bytes: ByteArray)
