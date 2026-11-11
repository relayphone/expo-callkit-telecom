package expo.modules.callkittelecom.conversations

internal enum class AndroidNativeMedia {
    NONE, LIVEKIT;

    companion object {
        fun from(value: String?): AndroidNativeMedia = when (value) {
            null, "none" -> NONE
            "livekit" -> LIVEKIT
            else -> error("androidNativeMedia must be livekit or none")
        }
    }
}

/** Main-thread call ownership. Only an explicitly selected native provider creates a controller. */
internal class NativeMediaOwner(
    private val selection: AndroidNativeMedia,
    private val audio: NativeAudioLifecycle,
    initiallyMuted: Boolean = false,
    private val create: (Map<String, Any?>?) -> NativeMediaController,
) {
    private var controller: NativeMediaController? = null
    private var closed = false
    private var transition = 0L
    private var desiredMuted = initiallyMuted
    internal val isPrewarmed: Boolean get() = controller?.isPrewarmed == true
    internal val hasSession: Boolean get() = controller?.hasSession == true

    fun prewarm() {
        check(!closed)
        if (selection == AndroidNativeMedia.NONE || controller != null) return
        val created = createController(null)
        controller = created
        try { created.prewarm() }
        catch (error: Exception) {
            controller = null
            created.close("failed")
            throw error
        }
    }

    /** Called after a successful native answer; the app-managed path owns no native session. */
    fun answered(media: Map<String, Any?>? = null) {
        check(!closed)
        transition++
        if (selection == AndroidNativeMedia.NONE) audio.coreActivated()
        else {
            if (media != null && !isPrewarmed) {
                controller?.close("replaced")
                controller = createController(media).also { it.prewarm() }
            }
            prewarm()
            controller!!.answered { audio.nativeActivated() }
        }
    }

    /** Suspension completes before the caller acknowledges a successful inactive transition. */
    suspend fun pause() {
        check(!closed)
        val expected = ++transition
        audio.inactive()
        if (closed || transition != expected) return
        controller?.pause()
    }

    fun resume() {
        check(!closed)
        transition++
        if (selection == AndroidNativeMedia.NONE) audio.coreActivated()
        else {
            prewarm()
            controller!!.resume { audio.nativeActivated() }
        }
    }

    private fun createController(media: Map<String, Any?>?) = create(media).also { it.setDesiredMuted(desiredMuted) }
    suspend fun mute() { check(!closed); desiredMuted = true; controller?.mute() }
    fun clearMute() { check(!closed); desiredMuted = false; controller?.clearMute() }
    fun unmute() { check(!closed); desiredMuted = false; controller?.unmute { audio.nativeActivated() } }

    fun close(reason: String) {
        if (closed) return
        closed = true
        transition++
        audio.close()
        controller?.close(reason)
    }
}
