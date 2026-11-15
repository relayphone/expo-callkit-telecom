package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Device prejoin and the fixture's receiver subscription share the existing prejoin budget. */
internal suspend fun awaitMediaReceiver(
    prejoined: () -> Boolean,
    read: suspend () -> JSONObject,
    timeoutMillis: Long = 10_000,
    onPrejoined: () -> Unit = {},
    pause: suspend () -> Unit = { delay(10) }
): JSONObject = withTimeout(timeoutMillis) {
    while (!prejoined()) pause()
    onPrejoined()
    var peer = read()
    while (peer.opt("running") != true) {
        check(peer.keys().asSequence().toSet() == setOf("running", "frames", "markers") &&
            peer.opt("running") == false && peer.opt("frames") == 0 && peer.opt("markers") == 0) {
            "Receiver stopped or returned malformed subscription evidence"
        }
        pause()
        peer = read()
    }
    peer
}
