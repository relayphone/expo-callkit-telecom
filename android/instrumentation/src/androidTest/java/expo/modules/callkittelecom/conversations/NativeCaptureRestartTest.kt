package expo.modules.callkittelecom.conversations

import android.Manifest
import android.media.AudioManager
import io.livekit.android.audio.AudioBufferCallback
import io.livekit.android.room.track.LocalTrackPublication
import io.livekit.android.room.track.RemoteAudioTrack
import livekit.org.webrtc.AudioTrackSink
import livekit.org.webrtc.audio.JavaAudioDeviceModule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class NativeCaptureRestartTest {
    @get:Rule val microphone = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)
    @Test fun holdRetirementKeepsEveryRestartRecordingless() = runBlocking {
        withSession { session, signals ->
            session.setHeld(true)
            delay(1000)
            val stopped = signals.first { it.stage == NativeMediaStage.CAPTURE_STOPPED }
            assertTrue("No positive read may follow the held capture stop", signals.none {
                it.stage == NativeMediaStage.CAPTURE_STARTED && it.elapsedRealtimeNanos > stopped.elapsedRealtimeNanos
            })
        }
    }

    @Test fun aHoldCancelledAtTheOutputBoundaryCanResumeCaptureWithFreshAuthority() = runBlocking {
        val playback = PausedPlayback()
        withSession(playback) { session, signals ->
            playback.blocked = true
            withTimeout(2000) { playback.blockedObserved.await() }
            val hold = launch(start = CoroutineStart.UNDISPATCHED) { session.setHeld(true) }
            assertFalse("Hold must be suspended at the controlled playback boundary", hold.isCompleted)
            hold.cancelAndJoin()
            withTimeout(3000) { while (signals.none { it.stage == NativeMediaStage.CAPTURE_STOPPED }) delay(10) }
            playback.blocked = false
            session.setHeld(false)
            withTimeout(3000) { while (signals.count { it.stage == NativeMediaStage.CAPTURE_STARTED } < 2) delay(10) }
            assertEquals("Cancelled retirement must not create another publication", 1,
                signals.count { it.stage == NativeMediaStage.MICROPHONE_PUBLISHED })
        }
    }

    private class PausedPlayback : NativeAudioHooks {
        @Volatile var blocked = false
        val blockedObserved = CompletableDeferred<Unit>()
        override val decodedSink = AudioTrackSink { _, _, _, _, _, _ -> }
        override fun capture(pacing: AudioBufferCallback) = pacing
        override fun configureOutput(builder: JavaAudioDeviceModule.Builder,
            observer: JavaAudioDeviceModule.PlaybackSamplesReadyCallback) {
            builder.setPlaybackSamplesReadyCallback { samples ->
                if (blocked) blockedObserved.complete(Unit) else observer.onWebRtcAudioTrackSamplesReady(samples)
            }
        }
        override fun published(publication: LocalTrackPublication) {}
        override fun subscribed(track: RemoteAudioTrack) {}
        override fun close() { blocked = false }
    }

    private suspend fun withSession(hooks: NativeAudioHooks? = null,
        block: suspend CoroutineScope.(LiveKitNativeSession, CopyOnWriteArrayList<NativeMediaSignal>) -> Unit
    ) = withContext(Dispatchers.Main) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val settings = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("media-ci.json").bufferedReader().use { it.readText() })
            val id = "hold-restart-" + System.nanoTime()
            val client = OkHttpClient()
            val media = withContext(Dispatchers.IO) {
                client.newCall(Request.Builder().url(settings.getString("baseUrl") + "/fixture/invitation/" + id + "?media=1")
                    .header("Authorization", "Bearer " + settings.getString("authentication")).build()).execute().use {
                    check(it.isSuccessful)
                    JSONObject(it.body!!.string()).getJSONObject("media")
                }
            }
            val audio = context.getSystemService(AudioManager::class.java)
            val previousMode = audio.mode
            val signals = CopyOnWriteArrayList<NativeMediaSignal>()
            val observer = NativeMediaObserver { if (it.serverCallId == id) signals += it }
            NativeMediaSignals.observers += observer
            val session = LiveKitNativeSession(context, id, hooks)
            var failure: Throwable? = null
            try {
                audio.mode = AudioManager.MODE_IN_COMMUNICATION
                session.prewarm(mapOf("serverCallId" to id, "media" to media.keys().asSequence().associateWith { media.get(it) }))
                session.activate()
                withTimeout(3000) { while (signals.none { it.stage == NativeMediaStage.CAPTURE_STARTED }) delay(10) }
                block(session, signals)
            } catch (error: Throwable) { failure = error; throw error }
            finally {
                val cleanup = runCatching { withContext(NonCancellable) {
                    try { session.close("ended") }
                    finally {
                        NativeMediaSignals.observers -= observer
                        audio.mode = previousMode
                        client.dispatcher.executorService.shutdown()
                        withContext(Dispatchers.IO) { client.connectionPool.evictAll() }
                    }
                } }.exceptionOrNull()
                if (cleanup != null) failure?.addSuppressed(cleanup) ?: throw cleanup
            }
    }
}
