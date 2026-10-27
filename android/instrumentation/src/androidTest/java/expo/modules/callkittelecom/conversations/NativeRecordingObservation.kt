package expo.modules.callkittelecom.conversations

import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Isolated-emulator delta evidence, not UID attribution. Session identities never leave memory. */
internal class NativeRecordingObservation(private val audio: AudioManager) : AudioManager.AudioRecordingCallback() {
    private val idle = audio.activeRecordingConfigurations.map { it.clientAudioSessionId }.toSet()
    private val seen = mutableSetOf<Int>()
    var firstNewObservedNanos = 0L
        private set
    init { audio.registerAudioRecordingCallback(this, Handler(Looper.getMainLooper())) }

    override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) { observe(configs) }
    private fun observe(configs: List<AudioRecordingConfiguration>) {
        val added = configs.map { it.clientAudioSessionId }.filterNot { it in idle }
        if (added.isNotEmpty() && firstNewObservedNanos == 0L) firstNewObservedNanos = SystemClock.elapsedRealtimeNanos()
        seen += added
    }
    fun activeNewCount(): Int = audio.activeRecordingConfigurations.let { configs ->
        observe(configs); configs.count { it.clientAudioSessionId !in idle }
    }
    fun snapshot(): Map<String, Any> = audio.activeRecordingConfigurations.let { configs ->
        observe(configs)
        mapOf("idleDeviceCount" to idle.size, "deviceCount" to configs.size,
            "newObservedCount" to seen.size, "newActiveCount" to configs.count { it.clientAudioSessionId !in idle },
            "firstNewObservedNanos" to firstNewObservedNanos,
            "silencedCount" to configs.count { it.isClientSilenced },
            "sources" to configs.map { it.clientAudioSource }.distinct().sorted())
    }
    fun close() { audio.unregisterAudioRecordingCallback(this) }
}
