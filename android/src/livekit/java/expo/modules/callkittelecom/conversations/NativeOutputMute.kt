package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Serializes mute application with the playback callback after its current device write. */
internal class NativeOutputMute {
    private class Request(val owner: Job?, val applyMute: () -> Unit) {
        val applied = CompletableDeferred<Unit>()
    }
    private val lock = Any()
    private var pending: Request? = null
    private var closed = false
    private var playing = false

    fun playbackStarted() = synchronized(lock) { if (!closed) playing = true }
    fun playbackStopped() = synchronized(lock) {
        playing = false
        applyPending()
    }

    suspend fun atPlaybackBoundary(applyMute: () -> Unit) {
        val context = currentCoroutineContext()
        context.ensureActive()
        val request = Request(context[Job], applyMute)
        synchronized(lock) {
            check(!closed)
            check(pending == null) { "Output mute already pending" }
            if (playing) pending = request else apply(request)
        }
        try { request.applied.await() }
        finally {
            synchronized(lock) {
                if (pending === request) pending = null
                request.applied.cancel()
            }
        }
    }

    fun callbackCompleted() = synchronized(lock) { applyPending() }

    private fun applyPending() {
        val request = pending ?: return
        pending = null
        apply(request)
    }

    private fun apply(request: Request) {
        if (!closed && request.owner?.isActive != false && request.applied.isActive) {
            try {
                request.applyMute()
                request.applied.complete(Unit)
            } catch (error: Exception) { request.applied.completeExceptionally(error) }
        } else request.applied.cancel()
    }

    fun cancelPending() {
        synchronized(lock) {
            val request = pending
            pending = null
            request?.applied?.cancel()
        }
    }

    fun close() = synchronized(lock) {
        closed = true
        cancelPending()
    }
}
