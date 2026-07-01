/**
 * Loopback HTTP endpoint that receives Claude Code hook callbacks and turns
 * them into [AgentEvent]s for the `AutoNamer`.
 *
 * The `claude` shim Termtastic installs (see [ClaudeAutoNameHooks]) registers
 * two `command` hooks that `curl` their JSON payload to
 * `POST /internal/agent-event`:
 *  - `UserPromptSubmit` (`?kind=prompt`) → [AgentEvent.Prompt] with the exact
 *    `user_prompt`;
 *  - `SessionStart` (`?kind=session`) → [AgentEvent.Reset] when `source` is
 *    `startup` or `clear` (a new run / an in-place `/clear`); `compact` and
 *    `resume` are ignored so a continuing task is not re-named.
 *
 * Correlation to a Termtastic pane is exact: the shim runs under a PTY
 * environment carrying `TERMTASTIC_SESSION` (the pane's `sN` id), which the
 * hook forwards as the `?session=` query parameter — no cwd guessing.
 *
 * Security: the endpoint is loopback-only and gated on a per-run token
 * (`TERMTASTIC_TOKEN`, injected into the same PTY env) so an arbitrary local
 * process can't spoof events to rename a user's terminals. The impact ceiling
 * is low regardless (it can only set a sanitized tab name), but the token stops
 * casual spoofing cheaply.
 *
 * Mounted from [Application.module]. The HTTP server is HTTPS-only with a
 * self-signed cert, so the hook `curl`s with `-k`.
 *
 * @see AgentEvent
 * @see ClaudeAutoNameHooks
 * @see installAutoNamer
 */
package se.soderbjorn.termtastic

import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

private val LOG = LoggerFactory.getLogger("AgentEventRoutes")

/** Lenient parser for hook JSON bodies — tolerate unknown/extra fields. */
private val hookJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * True for any loopback host Ktor can report (IPv4/IPv6 short and long forms).
 * Mirrors the check used by [adminRoutes].
 *
 * @param host the resolved remote host from the request origin.
 * @return whether [host] is a loopback address.
 */
private fun isLoopback(host: String?): Boolean =
    host == "127.0.0.1" ||
        host == "::1" ||
        host == "0:0:0:0:0:0:0:1" ||
        host.equals("localhost", ignoreCase = true)

/**
 * Mount `POST /internal/agent-event` on this [Route].
 *
 * Validates loopback origin + [token], then maps the Claude hook payload to an
 * [AgentEvent] published on [events]. Always responds `204 No Content` (even on
 * an ignored/unparseable payload) so the hook stays a cheap fire-and-forget and
 * the endpoint reveals nothing.
 *
 * Called by [Application.module].
 *
 * @param events the shared flow the `AutoNamer` collects.
 * @param token the per-run shared secret; requests must present it as `?token=`
 *   (empty disables the check — only in configurations that never set it).
 * @see AgentEvent
 */
internal fun Route.agentEventRoutes(events: MutableSharedFlow<AgentEvent>, token: String) {
    post("/internal/agent-event") {
        val origin = call.request.origin
        if (!isLoopback(origin.remoteHost)) {
            LOG.warn("Refusing agent-event from non-loopback origin: {}", origin.remoteHost)
            call.respond(HttpStatusCode.Forbidden)
            return@post
        }
        val q = call.request.queryParameters
        val tokenOk = token.isEmpty() || q["token"] == token
        LOG.info("agent-event: received kind={} session={} tokenOk={}", q["kind"], q["session"], tokenOk)
        if (!tokenOk) {
            call.respond(HttpStatusCode.Forbidden)
            return@post
        }
        val sid = q["session"]
        if (sid.isNullOrBlank()) {
            call.respond(HttpStatusCode.NoContent)
            return@post
        }

        val body = runCatching { call.receiveText() }.getOrNull().orEmpty()
        val obj = runCatching { hookJson.parseToJsonElement(body).jsonObject }.getOrNull()

        when (q["kind"]) {
            "prompt" -> {
                // Claude Code's UserPromptSubmit payload field is `prompt`
                // (verified on 2.1.197); accept `user_prompt` too in case a
                // version uses the name the docs describe.
                val prompt = (obj?.get("prompt") ?: obj?.get("user_prompt"))
                    ?.jsonPrimitive?.contentOrNull?.trim()
                if (!prompt.isNullOrBlank()) {
                    events.tryEmit(AgentEvent.Prompt(sid, prompt))
                }
            }
            "session" -> {
                // Only startup / clear re-arm naming; compact / resume continue
                // the same task and must NOT re-name.
                val source = obj?.get("source")?.jsonPrimitive?.contentOrNull
                if (source == "startup" || source == "clear") {
                    events.tryEmit(AgentEvent.Reset(sid, source))
                }
            }
        }
        call.respond(HttpStatusCode.NoContent)
    }
}
