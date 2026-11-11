package expo.modules.callkittelecom.conversations

import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class NativeMediaOwnerTest {
    @Test fun builtInProviderContainsOnlyLiveKit() = runBlocking {
        withContext(Dispatchers.Main) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            for (adapter in listOf("webrtc", "none", "unknown"))
                assertNull(BuiltInMedia.session(context, adapter, "provider-selection"))
            val session = checkNotNull(BuiltInMedia.session(context, "livekit", "provider-selection"))
            try {
                assertTrue(session is LiveKitNativeSession)
                assertFalse(session.isPrepared)
            } finally { session.close("ended") }
        }
    }

    @Test fun selectionIsIndependentOfAnswerPolicyAndDefaultsToNone() {
        assertEquals(AndroidNativeMedia.NONE, AndroidNativeMedia.from(null))
        assertEquals(AndroidNativeMedia.NONE, AndroidNativeMedia.from("none"))
        assertEquals(AndroidNativeMedia.LIVEKIT, AndroidNativeMedia.from("livekit"))
        assertTrue(runCatching { AndroidNativeMedia.from("webrtc") }.isFailure)
        assertTrue(runCatching { AndroidNativeMedia.from("native") }.isFailure)
    }

    @Test fun noProviderNeverCreatesOrWarmsANativeSession() = runBlocking {
        withContext(Dispatchers.Main) {
            var active = true
            val events = mutableListOf<NativeAudioEvent>()
            val audio = NativeAudioLifecycle({ active }, { AudioManager.MODE_NORMAL }, events::add)
            val owner = NativeMediaOwner(AndroidNativeMedia.NONE, audio) { error("Unexpected native session") }
            owner.prewarm(); owner.answered(); owner.resume(); owner.mute(); owner.unmute()
            assertEquals(listOf(NativeAudioEvent.ACTIVATED), events)
            active = false; owner.pause()
            assertEquals(listOf(NativeAudioEvent.ACTIVATED, NativeAudioEvent.DEACTIVATED), events)
            active = true; owner.resume()
            owner.close("ended"); owner.close("ended")
            assertEquals(listOf(NativeAudioEvent.ACTIVATED, NativeAudioEvent.DEACTIVATED,
                NativeAudioEvent.ACTIVATED, NativeAudioEvent.DEACTIVATED), events)
            assertTrue(runCatching { owner.answered() }.isFailure)
            assertTrue(runCatching { owner.prewarm() }.isFailure)
        }
    }

    @Test fun aPrewarmedSessionIgnoresTheAnswerGrant() = fixture {
        val owner = owner()
        owner.prewarm()
        withTimeout(1000) { while (!owner.isPrewarmed) delay(10) }
        owner.answered(mapOf("provider" to "livekit", "token" to "unused"))
        withTimeout(1000) { activationEntered.await() }
        assertEquals(1, creations)
        assertEquals(1, invitations.size)
        assertNull((invitations.single()["media"] as Map<*, *>)["token"])
    }

    @Test fun nativeActivationComesFromAudioAuthorityBeforeTransportAndCannotDuplicateOnUnmute() = fixture {
        mode = AudioManager.MODE_NORMAL
        val media = owner()
        media.prewarm(); media.prewarm()
        withTimeout(1000) { prepared.await() }
        assertTrue(events.isEmpty())
        media.answered()
        withTimeout(1000) { while (modeReads.get() == 0) delay(10) }
        assertTrue(events.isEmpty()); assertEquals(0, activations.get())
        mode = AudioManager.MODE_IN_COMMUNICATION
        withTimeout(1000) { activationEntered.await() }
        // The native session has not finished activating yet. Authority already succeeded.
        assertEquals(listOf(NativeAudioEvent.ACTIVATED), events)
        assertFalse(connected.isCompleted)
        releaseActivation.complete(Unit)
        withTimeout(1000) { connected.await() }
        media.mute(); assertTrue(muted); media.unmute()
        withTimeout(1000) { while (muted) delay(10) }
        assertEquals(listOf(NativeAudioEvent.ACTIVATED), events)
        assertEquals(1, creations); assertEquals(1, activations.get())
        active = false; media.pause()
        assertFalse(playing)
        assertEquals(NativeAudioEvent.DEACTIVATED, events.last())
        active = true; media.resume()
        withTimeout(1000) { while (!playing) delay(10) }
        assertEquals(listOf(NativeAudioEvent.ACTIVATED, NativeAudioEvent.DEACTIVATED,
            NativeAudioEvent.ACTIVATED), events)
        media.close("ended")
        withTimeout(1000) { closed.await() }
        assertEquals(NativeAudioEvent.DEACTIVATED, events.last())
    }

    @Test fun authorityTimeoutEmitsMissingWithoutActivatingMedia() = fixture {
        mode = AudioManager.MODE_NORMAL
        val media = owner()
        media.answered()
        withTimeout(3000) { failed.await() }
        assertEquals(listOf(NativeAudioEvent.ACTIVATION_MISSING), events)
        assertEquals(0, activations.get())
        media.close("failed")
        withTimeout(1000) { closed.await() }
    }

    @Test fun aResumedAuthoritySurvivesCompletionOfAnOlderSuspendedPause() = fixture {
        releaseActivation.complete(Unit)
        val media = owner()
        media.answered()
        withTimeout(3000) { connected.await() }
        releasePause = CompletableDeferred()
        active = false
        val pause = scope.async { media.pause() }
        withTimeout(3000) { pauseEntered.await() }
        val previousReads = modeReads.get()
        active = true; media.resume()
        withTimeout(3000) { while (modeReads.get() == previousReads) delay(10) }
        assertEquals(listOf(NativeAudioEvent.ACTIVATED, NativeAudioEvent.DEACTIVATED,
            NativeAudioEvent.ACTIVATED), events)
        releasePause.complete(Unit)
        withTimeout(3000) { pause.await(); while (!playing) delay(10) }
        assertEquals(NativeAudioEvent.ACTIVATED, events.last())
        assertEquals(3, events.size)
    }

    @Test fun holdInvalidatesAnAuthorityWaitEvenIfCommunicationModeReturns() = fixture {
        mode = AudioManager.MODE_NORMAL
        val media = owner()
        media.answered()
        withTimeout(1000) { while (modeReads.get() == 0) delay(10) }
        active = false; media.pause()
        mode = AudioManager.MODE_IN_COMMUNICATION
        delay(30)
        assertTrue(events.isEmpty()); assertEquals(0, activations.get())
        assertFalse(failed.isCompleted)
        active = true; releaseActivation.complete(Unit); media.resume()
        withTimeout(1000) { connected.await() }
        assertEquals(listOf(NativeAudioEvent.ACTIVATED), events)
    }

    @Test fun aReentrantResumePreventsTheOlderPauseFromSuspendingMedia() = fixture {
        releaseActivation.complete(Unit)
        val media = owner()
        media.answered()
        withTimeout(3000) { connected.await() }
        val previousReads = modeReads.get()
        onAudioEvent = { event ->
            if (event == NativeAudioEvent.DEACTIVATED) {
                onAudioEvent = {}
                active = true
                media.resume()
            }
        }
        active = false
        media.pause()
        withTimeout(3000) { while (modeReads.get() == previousReads) delay(10) }
        assertTrue(playing); assertEquals(0, pauses.get())
        assertEquals(listOf(NativeAudioEvent.ACTIVATED, NativeAudioEvent.DEACTIVATED,
            NativeAudioEvent.ACTIVATED), events)
    }

    @Test fun parentCancellationClosesPreparedMediaExactlyOnceWithoutLateActivation() = fixture {
        mode = AudioManager.MODE_NORMAL
        val media = owner()
        media.answered()
        withTimeout(1000) { while (modeReads.get() == 0) delay(10) }
        scope.cancel()
        withTimeout(1000) { closed.await() }
        mode = AudioManager.MODE_IN_COMMUNICATION
        media.close("ended")
        delay(30)
        assertEquals(1, closes.get()); assertEquals(0, activations.get())
        assertTrue(events.isEmpty()); assertFalse(failed.isCompleted)
    }

    @Test fun parentCancellationReleasesAPartiallyPreparedSession() = fixture {
        releasePreparation = CompletableDeferred()
        val media = owner()
        media.prewarm()
        withTimeout(1000) { preparationEntered.await() }
        scope.cancel()
        withTimeout(1000) { closed.await() }
        releasePreparation.complete(Unit)
        assertEquals(1, closes.get()); assertFalse(prepared.isCompleted)
        assertEquals(0, activations.get()); assertTrue(events.isEmpty())
    }

    @Test fun aShorterCallerDeadlineDoesNotEmitAnAuthorityTimeout() = runBlocking {
        withContext(Dispatchers.Main) {
            val events = mutableListOf<NativeAudioEvent>()
            val audio = NativeAudioLifecycle({ true }, { AudioManager.MODE_NORMAL }, events::add)
            val error = runCatching { withTimeout(40) { audio.nativeActivated() } }.exceptionOrNull()
            assertTrue(error is TimeoutCancellationException)
            assertTrue(events.isEmpty())
            audio.close()
        }
    }

    private fun fixture(body: suspend Fixture.() -> Unit) = runBlocking {
        withContext(Dispatchers.Main) {
            val fixture = Fixture()
            val previous = NativeMediaRegistry.factories.put("livekit") { fixture.session }
            try { fixture.body() }
            finally {
                fixture.media?.close("ended")
                fixture.scope.cancel()
                if (fixture.creations > 0) withTimeout(3000) { fixture.closed.await() }
                if (previous == null) NativeMediaRegistry.factories.remove("livekit")
                else NativeMediaRegistry.factories["livekit"] = previous
            }
        }
    }

    private class Fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val prepared = CompletableDeferred<Unit>()
        val preparationEntered = CompletableDeferred<Unit>()
        var releasePreparation = CompletableDeferred(Unit)
        val activationEntered = CompletableDeferred<Unit>()
        val releaseActivation = CompletableDeferred<Unit>()
        val connected = CompletableDeferred<Unit>()
        val failed = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val pauseEntered = CompletableDeferred<Unit>()
        var releasePause = CompletableDeferred(Unit)
        val activations = AtomicInteger()
        val closes = AtomicInteger()
        val pauses = AtomicInteger()
        val modeReads = AtomicInteger()
        val events = mutableListOf<NativeAudioEvent>()
        val invitations = java.util.concurrent.CopyOnWriteArrayList<Map<String, Any?>>()
        var onAudioEvent: (NativeAudioEvent) -> Unit = {}
        var creations = 0
        var active = true
        var mode = AudioManager.MODE_IN_COMMUNICATION
        @Volatile var playing = false
        @Volatile var muted = false
        var media: NativeMediaOwner? = null
        val session = object : NativeMediaSession {
            override suspend fun prewarm(payload: Map<String, Any?>) {
                invitations += payload
                preparationEntered.complete(Unit); releasePreparation.await(); prepared.complete(Unit)
            }
            override suspend fun activate() {
                activations.incrementAndGet(); activationEntered.complete(Unit)
                releaseActivation.await(); playing = true
            }
            override suspend fun setHeld(held: Boolean) {
                if (held) { pauses.incrementAndGet(); pauseEntered.complete(Unit); releasePause.await() }
                playing = activations.get() > 0 && !held
            }
            override suspend fun setMuted(muted: Boolean) { this@Fixture.muted = muted }
            override suspend fun close(reason: String) { playing = false; closes.incrementAndGet(); closed.complete(Unit) }
        }
        fun owner(): NativeMediaOwner {
            val audio = NativeAudioLifecycle({ active }, { modeReads.incrementAndGet(); mode }) {
                events.add(it); onAudioEvent(it)
            }
            return NativeMediaOwner(AndroidNativeMedia.LIVEKIT, audio) { answerMedia ->
                creations++
                NativeMediaController(InstrumentationRegistry.getInstrumentation().targetContext,
                    mapOf("serverCallId" to "owner-test", "media" to (answerMedia ?: mapOf("provider" to "livekit",
                        "expiresAt" to System.currentTimeMillis() / 1000 + 120))),
                    JSONObject(mapOf("media" to "livekit")), scope,
                    { failed.complete(Unit) }, { connected.complete(Unit) })
            }.also { media = it }
        }
    }
}
