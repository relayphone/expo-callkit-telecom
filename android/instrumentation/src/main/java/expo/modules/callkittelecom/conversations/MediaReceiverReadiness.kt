package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Device prejoin and the fixture's receiver subscription share the existing prejoin budget. */
internal suspend fun awaitMediaReceiver(
    prejoined: () -> Boolean,
    read: suspend ((MediaReceiverHttpPhase) -> Unit) -> JSONObject,
    timeoutMillis: Long = 10_000,
    onPrejoined: () -> Unit = {},
    pause: suspend () -> Unit = { delay(10) },
    trace: MediaReceiverTrace? = null,
): JSONObject = withTimeout(timeoutMillis) {
    trace?.started(timeoutMillis)
    try {
        while (!prejoined()) pause()
        onPrejoined()
        trace?.prejoined()
        suspend fun poll() = trace?.read(read) ?: read({})
        var peer = poll()
        while (peer.opt("running") != true) {
            check(peer.keys().asSequence().toSet() == setOf("running", "frames", "markers") &&
                peer.opt("running") == false && peer.opt("frames") == 0 && peer.opt("markers") == 0) {
                "Receiver stopped or returned malformed subscription evidence"
            }
            pause()
            peer = poll()
        }
        trace?.completed()
        peer
    } catch (error: Throwable) { trace?.failed(); throw error }
}
