package expo.modules.callkittelecom.conversations

import io.livekit.android.room.track.LocalTrackPublication
import java.lang.reflect.Modifier
import livekit.org.webrtc.audio.JavaAudioDeviceModule

/** Read the pinned SDK's actual speaker flag; do not infer it from a requested state. */
internal class EmulatorGateProbe(device: JavaAudioDeviceModule) {
    private val output = device.javaClass.getField("audioOutput").get(device)
    private val speakerMute = output.javaClass.getDeclaredField("speakerMute").apply {
        check(type == Boolean::class.javaPrimitiveType && Modifier.isVolatile(modifiers))
        isAccessible = true
    }
    fun playbackClosed(): Boolean = speakerMute.getBoolean(output)
    companion object {
        // This is the publication gate, not the physical recordingless-pump flag.
        fun captureClosed(publication: LocalTrackPublication?): Boolean =
            publication == null || publication.muted || publication.track?.enabled != true
    }
}
