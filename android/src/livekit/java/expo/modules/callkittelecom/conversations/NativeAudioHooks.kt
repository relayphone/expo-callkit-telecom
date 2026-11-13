package expo.modules.callkittelecom.conversations

import io.livekit.android.audio.AudioBufferCallback
import io.livekit.android.room.track.LocalTrackPublication
import livekit.org.webrtc.AudioTrackSink
import livekit.org.webrtc.audio.JavaAudioDeviceModule

/** Optional internal audio instrumentation; the library supplies no implementation. */
internal interface NativeAudioHooks {
    val decodedSink: AudioTrackSink
    fun capture(pacing: AudioBufferCallback): AudioBufferCallback
    fun configureOutput(builder: JavaAudioDeviceModule.Builder, observer: JavaAudioDeviceModule.PlaybackSamplesReadyCallback)
    fun published(publication: LocalTrackPublication)
    fun close()
}
