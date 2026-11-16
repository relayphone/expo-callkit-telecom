package expo.modules.callkittelecom.conversations

import android.Manifest
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import androidx.core.telecom.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationVideoCoreJourneyTest {
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun actualRegistrationSupportsAppAndServerVideoChanges() = runBlocking {
        withContext(Dispatchers.Main) {
            val engine = ConversationEngine.get(context)
            val previousMedia = engine.nativeMedia
            val id = "video-unsupported-${System.nanoTime()}"
            try {
                engine.nativeMedia = AndroidNativeMedia.NONE
                assertEquals("reported", engine.incoming(IncomingCallPush(id, "$id-event",
                    mapOf("handleType" to "generic", "value" to "peer", "displayName" to "Video support")).payload())["status"])
                val state = (engine.snapshot["conversations"] as List<*>).filterIsInstance<Map<*, *>>().first { it["id"] == id }
                assertEquals(true, (state["capabilities"] as Map<*, *>)["video"])
                engine.setVideo(id, true)
                assertEquals(true, (engine.snapshot["conversations"] as List<*>).filterIsInstance<Map<*, *>>().first { it["id"] == id }["isVideo"])
                engine.report(id, mapOf("type" to "updated", "video" to false))
                assertEquals(false, (engine.snapshot["conversations"] as List<*>).filterIsInstance<Map<*, *>>().first { it["id"] == id }["isVideo"])
            } finally {
                engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded"))
                withTimeout(5000) { while (TelecomEvidence.calls().isNotEmpty()) delay(20) }
                engine.nativeMedia = previousMedia
            }
        }
    }

    @Test fun realCoreVideoChangesKeepAudioModeAndRenderedFramesRunning() = runBlocking {
        withContext(Dispatchers.Main) {
            val engine = ConversationEngine.get(context)
            val oldMedia = engine.nativeMedia
            val oldEmit = engine.emit
            val events = mutableListOf<String>()
            val id = "video-render-${System.nanoTime()}"
            val audio = context.getSystemService(AudioManager::class.java)
            val running = AtomicBoolean(false)
            var writer: Job? = null
            var track: AudioTrack? = null
            try {
                engine.nativeMedia = AndroidNativeMedia.NONE
                engine.emit = { name, value -> if (name == "conversationEvent") events += value["type"] as String }
                assertEquals("reported", engine.incoming(IncomingCallPush(id, "$id-event",
                    mapOf("handleType" to "generic", "value" to "peer", "displayName" to "Video audio")).payload())["status"])
                engine.join(id)
                withTimeout(4000) { while (audio.mode != AudioManager.MODE_IN_COMMUNICATION) delay(10) }
                val output = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(9600, AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)))
                    .setTransferMode(AudioTrack.MODE_STREAM).build()
                track = output
                output.play()
                running.set(true)
                writer = launch(Dispatchers.IO) {
                    val tone = ShortArray(480) { (kotlin.math.sin(it * 2.0 * Math.PI * 1000 / 48000) * 1000).toInt().toShort() }
                    while (running.get()) {
                        val written = output.write(tone, 0, tone.size, AudioTrack.WRITE_BLOCKING)
                        check(written > 0 || !running.get())
                    }
                }
                withTimeout(3000) { while (output.playbackHeadPosition == 0) delay(10) }
                val deactivations = events.count { it == "audioDeactivated" }
                var lastFrame = output.playbackHeadPosition
                var lastProgress = SystemClock.elapsedRealtimeNanos()
                var maxGap = 0L
                var samples = 0
                val monitor = launch {
                    while (running.get()) {
                        delay(10)
                        val now = SystemClock.elapsedRealtimeNanos()
                        val frames = output.playbackHeadPosition
                        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audio.mode)
                        if (frames > lastFrame) { maxGap = maxOf(maxGap, now - lastProgress); lastProgress = now; lastFrame = frames }
                        samples++
                    }
                }
                try {
                    delay(300)
                    engine.setVideo(id, true)
                    delay(300)
                    engine.report(id, mapOf("type" to "updated", "video" to false))
                    delay(300)
                } finally { running.set(false); monitor.join() }
                assertTrue("No rendered frame progress", lastFrame > 0 && samples > 0)
                assertEquals(deactivations, events.count { it == "audioDeactivated" })
                assertEquals(2, events.count { it == "videoChanged" })
                println("videoAudio renderedFrames=$lastFrame maxObservedProgressGapMs=${maxGap / 1_000_000.0} samples=$samples mode=${audio.mode}; rendered-frame observation, not an acoustic recording")
            } finally {
                running.set(false)
                track?.stop(); writer?.cancelAndJoin(); track?.release()
                runCatching { engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded")) }
                withTimeout(5000) { while (TelecomEvidence.calls().isNotEmpty()) delay(20) }
                engine.nativeMedia = oldMedia; engine.emit = oldEmit
            }
        }
    }
}
