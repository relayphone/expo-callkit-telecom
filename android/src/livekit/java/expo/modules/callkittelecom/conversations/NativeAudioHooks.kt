package expo.modules.callkittelecom.conversations

import io.livekit.android.audio.AudioBufferCallback
import io.livekit.android.audio.AudioProcessorInterface
import io.livekit.android.room.track.LocalAudioTrackOptions
import io.livekit.android.room.track.LocalTrackPublication
import io.livekit.android.room.track.RemoteAudioTrack
import livekit.org.webrtc.AudioTrackSink
import livekit.org.webrtc.audio.JavaAudioDeviceModule

/** Optional internal audio instrumentation; the library supplies no implementation. */
internal interface NativeAudioHooks {
    val decodedSink: AudioTrackSink
    val capturePostProcessor: AudioProcessorInterface? get() = null
    fun captureOptions(defaults: LocalAudioTrackOptions): LocalAudioTrackOptions = defaults
    fun audioDeviceCreated(device: JavaAudioDeviceModule) {}
    fun capture(pacing: AudioBufferCallback): AudioBufferCallback
    fun configureOutput(builder: JavaAudioDeviceModule.Builder, observer: JavaAudioDeviceModule.PlaybackSamplesReadyCallback)
    fun published(publication: LocalTrackPublication)
    fun subscribed(track: RemoteAudioTrack)
    fun close()
}
