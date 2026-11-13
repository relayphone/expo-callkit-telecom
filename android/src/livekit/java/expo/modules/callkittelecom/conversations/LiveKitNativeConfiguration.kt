package expo.modules.callkittelecom.conversations

import io.livekit.android.AudioOptions
import io.livekit.android.LiveKitOverrides
import livekit.org.webrtc.PeerConnectionFactory

/** Capture app options at session creation and retain LiveKit's defaults unless explicitly overridden. */
internal class LiveKitNativeConfiguration(private val options: NativeMediaOptions = ConversationMedia.options) {
    fun overrides(audio: AudioOptions? = null) = LiveKitOverrides(
        audioOptions = audio,
        peerConnectionFactoryOptions = if (options.disableNetworkMonitor) PeerConnectionFactory.Options().apply {
            disableNetworkMonitor = true
        } else null,
    )
}
