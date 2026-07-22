/**
 * `/pty/{id}` WebSocket route. Each connection attaches to a single
 * [TerminalSession] and bidirectionally bridges PTY bytes ↔ WebSocket
 * frames. Resize control messages are routed through [handleControl].
 *
 * Lives next to [Application.module], which mounts it via [ptyRoutes]
 * inside its `routing { }` block.
 *
 * @see TerminalSession
 * @see DeviceAuth
 */
package se.soderbjorn.lunamux

import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import se.soderbjorn.lunamux.auth.DeviceAuth
import se.soderbjorn.lunamux.persistence.SettingsRepository
import se.soderbjorn.lunamux.pty.ClientPosture

/** Tolerant JSON used for parsing inbound /pty control messages. */
private val controlJson = Json { ignoreUnknownKeys = true }

/**
 * Mount the `/pty/{id}` WebSocket on this [Route]. The handler validates
 * the device-auth token, looks up the [TerminalSession] for the path id,
 * pushes the current PTY size and a snapshot of recent output, and then
 * pumps PTY bytes to the socket while forwarding inbound binary frames
 * (keystrokes) and text frames (resize control) back into the session.
 *
 * @param settingsRepo the SQLite-backed settings store, used by [DeviceAuth]
 */
internal fun Route.ptyRoutes(settingsRepo: SettingsRepository) {
    webSocket("/pty/{id}") {
        val token = call.readAuthToken()
        val info = call.readClientInfo()
        when (DeviceAuth.authorize(token, info, settingsRepo, call.readPairingToken())) {
            DeviceAuth.Decision.APPROVED -> Unit
            DeviceAuth.Decision.REJECTED -> {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "device not approved"))
                return@webSocket
            }
            DeviceAuth.Decision.HEADLESS -> {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "server cannot prompt (headless)"))
                return@webSocket
            }
        }
        val id = call.parameters["id"]
        val session = if (id != null) TerminalSessions.get(id) else null
        if (session == null) {
            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "unknown session id"))
            return@webSocket
        }

        val clientId = java.util.UUID.randomUUID().toString()
        // How this client participates in size governance (see [ClientPosture]).
        // A driver may seize the grid by typing/forcing; a viewer mirrors the
        // desktop until it explicitly takes over. The client declares it with a
        // `posture=viewer|driver` query param; absent that we sniff the reported
        // client type (a phone/tablet defaults to viewer, everything else to a
        // driver) so older clients still behave sensibly.
        val posture = call.readClientPosture(info.type)
        session.setClientPosture(clientId, posture)

        val (initialCols, initialRows) = session.sizeEvents.value
        send(
            Frame.Text(
                windowJson.encodeToString<PtyServerMessage>(
                    PtyServerMessage.Size(initialCols, initialRows, session.maxReplayCols())
                )
            )
        )

        val snapshot = session.snapshot()
        if (snapshot.isNotEmpty()) {
            send(Frame.Binary(true, snapshot))
        }

        // Single writer coroutine. PTY output and size changes come from two
        // independent upstreams — the PTY read loop (Dispatchers.IO) and the
        // size arbiter — so sending each from its own coroutine let their frames
        // interleave in a nondeterministic order: a Size could reach the wire
        // *after* the redraw bytes it must precede, mangling the client's grid,
        // and two coroutines calling send() concurrently on one socket is itself
        // unsafe. Merging both into one flow collected by one coroutine
        // serialises the sends and preserves arrival order (a client-initiated
        // resize sets the winsize before the program produces its repaint bytes,
        // so the Size still enqueues ahead of them).
        val outputFrames = session.output.map<ByteArray, Frame> { chunk ->
            Frame.Binary(true, chunk)
        }
        val sizeFrames = session.sizeEvents.drop(1).map<Pair<Int, Int>, Frame> { (cols, rows) ->
            Frame.Text(
                windowJson.encodeToString<PtyServerMessage>(
                    PtyServerMessage.Size(cols, rows, session.maxReplayCols())
                )
            )
        }
        val writerJob = launch {
            merge(outputFrames, sizeFrames).collect { frame -> send(frame) }
        }

        try {
            for (frame in incoming) {
                when (frame) {
                    is Frame.Binary -> {
                        // Input marks this client as the most recently active
                        // one for size arbitration (latest-active wins) —
                        // recorded here, not inside write(), because write()
                        // is also used by MCP tools with no client identity.
                        session.noteClientInput(clientId)
                        session.write(frame.readBytes())
                    }
                    is Frame.Text -> handleControl(session, clientId, frame.readText())
                    else -> Unit
                }
            }
        } finally {
            writerJob.cancel()
            session.removeClient(clientId)
        }
    }
}

/**
 * Handle a JSON control message received on a `/pty/{id}` WebSocket.
 *
 * Deserialises the text as a [PtyControl] and dispatches resize commands
 * to the [TerminalSession]. Unknown or malformed messages are silently
 * dropped.
 */
internal fun handleControl(session: TermSession, clientId: String, text: String) {
    val control = runCatching {
        controlJson.decodeFromString<PtyControl>(text)
    }.getOrNull() ?: return
    when (control) {
        // Every client keeps the tier it sent (NORMAL, or THREE_D when the 3D
        // world asserts a Pane.grid3d override). A phone no longer gets a
        // special tier — it governs by posture/take-over, not by winning min().
        is PtyControl.Resize ->
            session.setClientSize(clientId, control.cols, control.rows, control.priority)
        is PtyControl.ForceResize ->
            session.forceClientSize(clientId, control.cols, control.rows, control.priority)
        is PtyControl.ResetModes -> session.resetTerminalModes()
    }
}

/**
 * Decide the connecting client's [ClientPosture] for size governance.
 *
 * Prefers the client's own declaration — a `posture=viewer|driver` query param
 * on the `/pty` URL (the web has no request-header channel, so a query param is
 * the portable way for it to declare intent). When absent or unrecognised it
 * falls back to sniffing the reported client [type] via [isMobileClientType]:
 * a phone/tablet defaults to [ClientPosture.VIEWER] (it mirrors the desktop
 * until the user takes over), everything else to [ClientPosture.DRIVER]. This
 * keeps older clients that send no `posture` behaving sensibly.
 *
 * @param type the self-reported client type (from [readClientInfo]).
 * @return the posture to register with the session for this connection.
 * @see ClientSizeArbiter.setPosture
 */
internal fun io.ktor.server.application.ApplicationCall.readClientPosture(
    type: String,
): ClientPosture = when (request.queryParameters["posture"]?.lowercase()) {
    "viewer" -> ClientPosture.VIEWER
    "driver" -> ClientPosture.DRIVER
    else -> if (isMobileClientType(type)) ClientPosture.VIEWER else ClientPosture.DRIVER
}

/**
 * Classify a reported client [type] (the `X-Termtastic-Client-Type` /
 * `clientType` value — e.g. `"Web"`, `"Computer"`, `"Android"`, `"iOS"`) as a
 * phone/tablet. Used by [readClientPosture] as the *fallback* posture signal
 * when a client sends no explicit `posture=` declaration: a mobile client
 * defaults to a viewer that mirrors the desktop. Matched leniently (substring,
 * case-insensitive) so future mobile type strings (`"iPhone"`, `"iPad"`, …) are
 * covered without another code change.
 *
 * @param type the self-reported client type, possibly `"Unknown"`.
 * @return `true` if [type] denotes a mobile client.
 */
internal fun isMobileClientType(type: String): Boolean {
    val t = type.lowercase()
    return t.contains("android") ||
        t.contains("ios") ||
        t.contains("iphone") ||
        t.contains("ipad") ||
        t.contains("phone") ||
        t.contains("mobile") ||
        t.contains("tablet")
}
