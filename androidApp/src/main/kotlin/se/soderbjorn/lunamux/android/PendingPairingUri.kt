/**
 * Hand-off point for `lunamux://pair` deep links between [MainActivity]
 * (which receives the intent) and the hosts screen (which performs the
 * pairing). Holds at most one pending URI.
 *
 * @see MainActivity
 * @see se.soderbjorn.lunamux.android.ui.HostsScreen
 */
package se.soderbjorn.lunamux.android

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * Process-wide single-slot mailbox for a pairing deep link. [MainActivity]
 * posts into it from `onCreate`/`onNewIntent`; the hosts screen observes
 * [uri] and calls [consume] exactly once per posted link, so a configuration
 * change or recomposition can never trigger a duplicate pairing.
 */
object PendingPairingUri {

    private val _uri = MutableStateFlow<String?>(null)

    /** The pending pairing URI, or `null` when none is waiting. */
    val uri: StateFlow<String?> = _uri.asStateFlow()

    /**
     * Post a freshly-received pairing link, replacing any unconsumed one
     * (the newest scan wins).
     *
     * @param value the full `lunamux://pair?...` URI from the intent.
     */
    fun post(value: String) {
        _uri.value = value
    }

    /**
     * Take the pending URI, clearing the slot.
     *
     * @return the URI, or `null` if it was already consumed.
     */
    fun consume(): String? = _uri.getAndUpdate { null }
}
