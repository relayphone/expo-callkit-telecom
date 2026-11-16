package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.*

/** Optional receive readiness after connect and identity validation. */
internal suspend fun warmNativeReceiver(
    remoteTrackReady: suspend () -> Unit,
    subscriberReady: suspend () -> Unit,
    playbackStarted: suspend () -> Unit,
    mutedOutput: suspend () -> Unit,
    decoded: suspend () -> Unit,
    emit: (NativeMediaStage, Map<String, Long>) -> Unit
) {
    val budget = currentCoroutineContext()[NativeMediaBudget]
    val warmupMs = minOf(2000L, (budget?.remainingMs()?.minus(250) ?: 2000L).coerceAtLeast(0))
    val completed = linkedMapOf("remoteTrackReady" to 0L, "subscriberReady" to 0L,
        "playbackStarted" to 0L, "mutedOutput" to 0L, "decoded" to 0L)
    var ready = false
    try {
        ready = withTimeoutOrNull(warmupMs) {
            remoteTrackReady(); completed["remoteTrackReady"] = 1L
            subscriberReady(); completed["subscriberReady"] = 1L
            playbackStarted(); completed["playbackStarted"] = 1L
            mutedOutput(); completed["mutedOutput"] = 1L
            decoded(); completed["decoded"] = 1L
            true
        } == true
    } catch (_: CancellationException) {
        // Retired readiness waits remain optional; caller cancellation still propagates.
        currentCoroutineContext().ensureActive()
    }
    currentCoroutineContext().ensureActive()
    if (!ready) emit(NativeMediaStage.PREWARM_RECEIVER_WARMUP_SKIPPED,
        completed + mapOf("warmupMs" to warmupMs, "remainingMs" to (budget?.remainingMs() ?: -1L)))
}
