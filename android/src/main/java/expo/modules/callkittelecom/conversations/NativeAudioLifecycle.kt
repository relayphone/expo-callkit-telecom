package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class NativeAudioEvent { ACTIVATED, DEACTIVATED, ACTIVATION_MISSING }

/** Main-thread audio ownership; handler settlement and transport readiness do not activate audio. */
internal class NativeAudioLifecycle(
    private val isCurrentActive: () -> Boolean,
    private val mode: () -> Int,
    private val emit: (NativeAudioEvent) -> Unit,
) {
    private var generation = 0L
    private var active = false
    private var closed = false

    /** Used only after Core-Telecom successfully activates an app-managed call. */
    fun coreActivated() {
        check(!closed && isCurrentActive())
        activate()
    }

    /** Native capture waits for authority; endpoint observations are not authorization. */
    suspend fun nativeActivated() = withContext(Dispatchers.Main.immediate) {
        val expected = generation
        fun current() = !closed && generation == expected && isCurrentActive()
        NativeAudioAuthority.await(::current, mode) {
            if (current()) emit(NativeAudioEvent.ACTIVATION_MISSING)
        }
        check(current())
        activate()
    }

    private fun activate() {
        if (active) return
        active = true
        emit(NativeAudioEvent.ACTIVATED)
    }

    /** Invalidates outstanding authority waits before another call can reuse the audio mode. */
    fun inactive() {
        generation++
        if (!active) return
        active = false
        emit(NativeAudioEvent.DEACTIVATED)
    }

    fun close() {
        if (closed) return
        closed = true
        inactive()
    }
}
