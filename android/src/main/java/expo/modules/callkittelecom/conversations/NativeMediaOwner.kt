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
    private val create: () -> NativeMediaController,
) {
    private var controller: NativeMediaController? = null
    private var closed = false
    private var transition = 0L

    fun prewarm() {
        check(!closed)
        if (selection == AndroidNativeMedia.NONE || controller != null) return
        val created = create()
        controller = created
        try { created.prewarm() }
        catch (error: Exception) {
            controller = null
            created.close("failed")
            throw error
        }
    }

    /** Called after a successful native answer; the app-managed path owns no native session. */
    fun answered() {
        check(!closed)
        transition++
        if (selection == AndroidNativeMedia.NONE) audio.coreActivated()
        else {
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

    suspend fun mute() { check(!closed); controller?.mute() }
    fun clearMute() { check(!closed); controller?.clearMute() }
    fun unmute() { check(!closed); controller?.unmute { audio.nativeActivated() } }

    fun close(reason: String) {
        if (closed) return
        closed = true
        transition++
        audio.close()
        controller?.close(reason)
    }
}
