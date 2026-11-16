package expo.modules.callkittelecom.conversations

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Stable numeric steps for correlating ADM restarts with synchronous retirement calls. */
internal enum class NativeCaptureRetirementStep(val code: Long) {
    PUBLICATION_MUTE(1), UNPUBLISH(2), STOP_PREWARM(3), DISPOSE(4)
}

/** Recording permission belongs to the current capture generation, including after ADM restarts. */
internal class NativeCaptureAuthorization(
    private val recordingEnabled: (Boolean) -> Unit,
    private val publishRecordingState: () -> Unit,
) {
    private class Generation(val id: Long, val authorizedAtNanos: Long?) {
        val violationReported = AtomicBoolean(false)
    }
    private val lock = Any()
    private val current = AtomicReference(Generation(0, null))
    private var closed = false

    fun authorize(generation: Long, atNanos: Long): Boolean = synchronized(lock) {
        if (closed || generation <= current.get().id) return false
        current.set(Generation(generation, atNanos))
        true
    }

    fun enableRecording(generation: Long): Boolean = synchronized(lock) {
        if (closed || current.get().id != generation || current.get().authorizedAtNanos == null) return false
        recordingEnabled(true)
        publishRecordingState()
        true
    }

    fun revoke(generation: Long) = synchronized(lock) {
        if (generation > current.get().id) current.set(Generation(generation, null))
        recordingEnabled(false)
        publishRecordingState()
    }

    /** Runs on the new recording thread before its first loop can call AudioRecord.read. */
    fun enforceRecordingState() = synchronized(lock) {
        recordingEnabled(!closed && current.get().authorizedAtNanos != null)
    }

    fun allows(generation: Long, observedAtNanos: Long): Boolean {
        val state = current.get()
        return state.id == generation && state.authorizedAtNanos?.let { observedAtNanos >= it } == true
    }

    fun unauthorizedRead(generation: Long?, observedAtNanos: Long): Long? {
        val state = current.get()
        if (state.id == generation && state.authorizedAtNanos?.let { observedAtNanos >= it } == true) return null
        return state.id.takeIf { state.violationReported.compareAndSet(false, true) }
    }

    fun close(generation: Long) = synchronized(lock) {
        closed = true
        current.set(Generation(generation, null))
        recordingEnabled(false)
        publishRecordingState()
    }
}
