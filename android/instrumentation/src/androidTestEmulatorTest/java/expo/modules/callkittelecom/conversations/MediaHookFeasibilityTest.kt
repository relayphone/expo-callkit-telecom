package expo.modules.callkittelecom.conversations

import android.Manifest
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Bundle
import io.livekit.android.audio.AudioBufferCallback
import io.livekit.android.LiveKit
import io.livekit.android.room.participant.AudioTrackPublishOptions
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.LocalTrackPublication
import io.livekit.android.webrtc.peerconnection.overrideExecutorAndDispatcher
import livekit.LivekitModels
import livekit.org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaHookFeasibilityTest {
    private val failureTrace = MediaQualificationFailureRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(failureTrace).around(
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val settings by lazy { JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
        .open("media-ci.json").bufferedReader().readText()) }
    private val client = OkHttpClient.Builder().callTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build()

    @Test fun receiveOnlyPrejoinSwapsToClaimCredentialsAndPublishesOnce() = claimJourney(0)
    @Test fun aClaimTakingIts2800MillisecondBudgetStillAnswers() = claimJourney(2800)
    @Test fun aMutedClaimAnswerOffersAudioBeforeItIsUnmuted() = claimJourney(0, muteBeforeAnswer = true)
    @Test fun receiveOnlyAnswerWithoutAPublishGrantFailsWithTheMediaReason() = claimJourney(0, grant = false)

    private fun claimJourney(claimDelayMillis: Long, grant: Boolean = true, muteBeforeAnswer: Boolean = false) = runBlocking {
        withContext(Dispatchers.Main) {
            val engine = ConversationEngine.get(context)
            val previousMedia = engine.nativeMedia; val previousPolicy = engine.policy
            val previousHandler = ConversationActions.handler
            val previousFactory = NativeMediaRegistry.factories["livekit"]
            val id = "hooks-${System.nanoTime()}"
            var hooks = EmulatorMediaHooks(context, "$id:fromDevice")
            // Qualify both identical test sources before timing the production token swap.
            val answerHooks = EmulatorMediaHooks(context, "$id:fromDevice")
            val signals = CopyOnWriteArrayList<NativeMediaSignal>()
            val observer = NativeMediaObserver { if (it.serverCallId == id) signals += it }
            var creations = 0
            val claimTrace = JSONObject().put("clock", "android.elapsedRealtimeNanos").put("claimDelayMillis", claimDelayMillis)
                .put("grant", grant).put("muteBeforeAnswer", muteBeforeAnswer)
            val receiverWait = MediaReceiverTrace(android.os.SystemClock::elapsedRealtimeNanos)
            val mutedReceiverWait = MediaReceiverTrace(android.os.SystemClock::elapsedRealtimeNanos)
            fun receiverSnapshot() = (if (receiverWait.snapshot().has("startedNs")) receiverWait else mutedReceiverWait).snapshot()
            failureTrace.snapshot = { mediaQualificationTrace(receiver = receiverSnapshot(), claim = claimTrace) }
            var failure: Throwable? = null
            val permissionChecks = MediaPermissionObservation.microphoneChecks.get()
            NativeMediaRegistry.factories["livekit"] = {
                if (creations++ > 0) hooks = answerHooks
                LiveKitNativeSession(it, id, hooks)
            }
            fun events(stage: NativeMediaStage) = signals.filter { it.stage == stage }
            suspend fun stage(kind: NativeMediaStage, count: Int = 1) = withTimeout(10_000) {
                while (events(kind).size < count) delay(10)
                events(kind)[count - 1]
            }
            fun current() = (engine.snapshot["conversations"] as List<*>).map { it as Map<*, *> }.firstOrNull { it["id"] == id }
            fun present() = current() != null
            engine.nativeMedia = AndroidNativeMedia.LIVEKIT
            engine.policy = JSONObject(mapOf("media" to "livekit", "credentials" to "fromPush", "prewarm" to true))
            NativeMediaSignals.observers += observer
            try {
                withTimeout(7000) { while (TelecomEvidence.calls().isNotEmpty()) delay(20) }
                val invitation = fixture("/fixture/invitation/$id?media=1&publish=0").getJSONObject("media")
                val prejoin = invitation.keys().asSequence().associateWith { invitation.get(it) }
                ConversationActions.configure(ConversationActionHandler { action ->
                    if (action is ConversationAction.Answer && grant) {
                        check(action.conversationId == id && action.serverCallId == id)
                        val claimStarted = android.os.SystemClock.elapsedRealtimeNanos()
                        val claimed = fixture("/v1/calls/$id/media-credentials", body = JSONObject()
                            .put("eventId", "event-$id").put("deviceId", "fixture-device"))
                        val claimElapsedMillis = (android.os.SystemClock.elapsedRealtimeNanos() - claimStarted) / 1_000_000
                        if (claimDelayMillis > claimElapsedMillis) delay(claimDelayMillis - claimElapsedMillis)
                        check(claimed.getString("token") != invitation.getString("token"))
                        check(claimed.getString("identity") == invitation.getString("identity"))
                        check(claimed.getString("room") == invitation.getString("room"))
                        ConversationActionResult.Proceed(claimed.keys().asSequence().associateWith { claimed.get(it) })
                    } else ConversationActionResult.Proceed()
                })
                assertEquals("reported", engine.incoming(IncomingCallPush(id, "event-$id",
                    mapOf("handleType" to "generic", "value" to "peer", "displayName" to "Peer"), media = prejoin).payload())["status"])
                val joined = stage(NativeMediaStage.PREJOINED)
                assertEquals(0L, joined.measurements["localMicrophones"])
                assertEquals(0L, joined.measurements["localPublications"])
                delay(1500)
                assertTrue(present())
                assertEquals("ringing", current()?.get("status"))
                assertTrue(events(NativeMediaStage.PREWARM_LOST).isEmpty())
                val clients = fixture("/fixture/observations/$id").getInt("clients")
                claimTrace.put("clients", clients).put("clientObservationNs", android.os.SystemClock.elapsedRealtimeNanos().toString())
                assertEquals(1, clients)
                assertTrue(events(NativeMediaStage.MICROPHONE_PUBLISH_STARTED).isEmpty())
                assertTrue(events(NativeMediaStage.CAPTURE_STARTED).isEmpty())
                assertEquals(0, hooks.captureSampleRate)
                assertEquals(0L, hooks.injectedFrames.get())
                assertEquals(permissionChecks, MediaPermissionObservation.microphoneChecks.get())
                if (muteBeforeAnswer) engine.mute(id, true)
                val requested = android.os.SystemClock.elapsedRealtimeNanos()
                if (!grant) {
                    engine.join(id)
                    stage(NativeMediaStage.ANSWER_FAILED)
                    assertTrue(events(NativeMediaStage.ANSWER_FAILED).any { it.reason == "PUBLISH_PERMISSION_NOT_GRANTED" })
                    assertTrue(events(NativeMediaStage.MICROPHONE_PUBLISH_STARTED).isEmpty())
                    assertEquals(1, events(NativeMediaStage.MEDIA_CONNECT_STARTED).size)
                    assertEquals(permissionChecks, MediaPermissionObservation.microphoneChecks.get())
                    return@withContext
                }
                withTimeout(4500) { engine.join(id) }
                val actionCompleted = android.os.SystemClock.elapsedRealtimeNanos()
                withTimeout(5000) { stage(NativeMediaStage.CONNECTED) }
                assertEquals(2, creations)
                assertEquals(1, events(NativeMediaStage.CORE_ADD_REQUESTED).size)
                assertEquals(1, events(NativeMediaStage.REPORTED).size)
                assertTrue(present())
                val retired = stage(NativeMediaStage.CLOSED)
                val released = stage(NativeMediaStage.RELEASE_COMPLETED)
                val connect = stage(NativeMediaStage.MEDIA_CONNECT_STARTED, 2)
                val connected = stage(NativeMediaStage.MEDIA_CONNECT_COMPLETED, 2)
                assertTrue(retired.elapsedRealtimeNanos <= connect.elapsedRealtimeNanos)
                if (muteBeforeAnswer) {
                    val beforeUnmute = withTimeout(5000) {
                        val peer = awaitMediaReceiver({ true }, { phase -> fixture("/fixture/markers/$id", onPhase = phase) },
                            trace = mutedReceiverWait)
                        assertTrue(peer.getBoolean("muted"))
                        var snapshot: JSONObject
                        do {
                            delay(20)
                            snapshot = fixture("/fixture/timing/$id").also { claimTrace.put("signaling", it) }
                        } while (audioOffers(snapshot.getJSONArray("negotiations").getJSONObject(1)).isEmpty())
                        snapshot
                    }
                    assertEquals(1, beforeUnmute.getJSONArray("negotiations").getJSONObject(1).getInt("audioPublisherOffers"))
                    assertEquals(1, audioOffers(beforeUnmute.getJSONArray("negotiations").getJSONObject(1)).size)
                    assertTrue(hooks.captureGateClosed())
                    assertTrue(events(NativeMediaStage.CAPTURE_STARTED).isEmpty())
                    InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                        putString("claimMutedSignaling", beforeUnmute.toString())
                    })
                    engine.mute(id, false)
                }
                var first: Long? = null
                val receiving = async {
                    val receiver = awaitMediaReceiver({ true }, { phase -> fixture("/fixture/markers/$id", onPhase = phase) },
                        trace = receiverWait)
                    claimTrace.put("receiverGeneration", receiver.getLong("generation"))
                    peerAudio(id, receiver.getLong("generation")) { if (first == null) first = it }
                }
                assertTrue(stage(NativeMediaStage.REMOTE_SUBSCRIBED, 2).elapsedRealtimeNanos >= connect.elapsedRealtimeNanos)
                assertTrue(stage(NativeMediaStage.DECODER_FIRST_PCM, 2).elapsedRealtimeNanos >= connect.elapsedRealtimeNanos)
                assertEquals(1, events(NativeMediaStage.MICROPHONE_PUBLISH_STARTED).size)
                assertEquals(1, events(NativeMediaStage.MICROPHONE_PUBLISHED).size)
                assertTrue(MediaPermissionObservation.microphoneChecks.get() > permissionChecks)
                assertEquals(1L, events(NativeMediaStage.MICROPHONE_PUBLISHED).single().measurements["localPublications"])
                val uplink = receiving.await()
                assertTrue(uplink.getLong("cleanCyclesWithPostRoll") > 0)
                val observed = checkNotNull(first)
                val timing = JSONObject().put("clock", "android.elapsedRealtimeNanos").put("claimDelayMillis", claimDelayMillis)
                    .put("answerRequestedNs", requested.toString()).put("prejoinClosedNs", retired.elapsedRealtimeNanos.toString())
                    .put("prejoinReleaseCompletedNs", released.elapsedRealtimeNanos.toString())
                    .put("retirement", org.json.JSONArray(signals.filter { it.stage in NativeCloseStep.entries.map { step -> step.stage } ||
                        it.stage == NativeMediaStage.CLOSE_REQUESTED || it.stage == NativeMediaStage.RELEASE_COMPLETED }.map {
                        JSONObject().put("stage", it.stage.value).put("atNs", it.elapsedRealtimeNanos.toString())
                            .put("measurements", JSONObject(it.measurements)) }))
                    .put("newConnectStartedNs", connect.elapsedRealtimeNanos.toString())
                    .put("newConnectCompletedNs", connected.elapsedRealtimeNanos.toString()).put("firstMarkerObservedNs", observed.toString())
                    .put("publisherStartedNs", events(NativeMediaStage.MICROPHONE_PUBLISH_STARTED).single().elapsedRealtimeNanos.toString())
                    .put("publisherCompletedNs", events(NativeMediaStage.MICROPHONE_PUBLISHED).single().elapsedRealtimeNanos.toString())
                    .put("answerConnectedNs", events(NativeMediaStage.CONNECTED).single().elapsedRealtimeNanos.toString())
                    .put("answerActionCompletedNs", actionCompleted.toString())
                    .put("claimStartedNs", events(NativeMediaStage.HANDLER_STARTED)
                        .single { it.elapsedRealtimeNanos in requested..actionCompleted }.elapsedRealtimeNanos.toString())
                    .put("claimCompletedNs", events(NativeMediaStage.HANDLER_RESPONDED)
                        .single { it.elapsedRealtimeNanos in requested..actionCompleted }.elapsedRealtimeNanos.toString())
                    .put("answerToFirstMarkerObservedMs", (observed - requested) / 1_000_000.0)
                    .put("swapToFirstMarkerObservedMs", (observed - retired.elapsedRealtimeNanos) / 1_000_000.0)
                if (!muteBeforeAnswer) InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("claimUplinkTiming", timing.toString()) })
                val snapshot = (if (muteBeforeAnswer) fixture("/fixture/timing/$id") else fixture("/fixture/timing/$id", body = timing))
                    .also { claimTrace.put("signaling", it) }
                val negotiations = snapshot.getJSONArray("negotiations")
                assertEquals(2, negotiations.length())
                assertEquals(0, negotiations.getJSONObject(0).getInt("audioPublications"))
                assertEquals(0, negotiations.getJSONObject(0).getInt("audioPublisherOffers"))
                assertEquals(1, negotiations.getJSONObject(1).getInt("audioPublications"))
                assertEquals(1, negotiations.getJSONObject(1).getInt("audioPublisherOffers"))
                assertEquals(0, negotiations.getJSONObject(1).getInt("publisherIceRestarts"))
                assertTrue((0 until negotiations.length()).all { negotiations.getJSONObject(it).getInt("decodeErrors") == 0 &&
                    negotiations.getJSONObject(it).getInt("truncatedEvents") == 0 })
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putString("claimNegotiationCounts", negotiations.toString())
                    putString("claimReceiverTiming", snapshot.getJSONObject("receiver").toString())
                })
                val offers = audioOffers(negotiations.getJSONObject(1))
                assertEquals("Answer negotiates its audio sender exactly once", 1, offers.size)
                assertEquals(1, offers.single().getInt("audioSections"))
                assertFalse("Adding audio must not restart existing ICE", offers.single().getBoolean("iceChanged"))
                assertTrue(present())
                assertTrue(events(NativeMediaStage.ANSWER_FAILED).isEmpty())
                assertEquals("connected", current()?.get("status"))
            } catch (error: Throwable) {
                failure = error
                val timing = org.json.JSONArray(signals.takeLast(64).map { JSONObject().put("stage", it.stage.value)
                    .put("atNs", it.elapsedRealtimeNanos.toString()).put("measurements", JSONObject(it.measurements))
                    .put("reason", it.reason ?: JSONObject.NULL)
                    .put("permissionUnavailable", it.reason == "PUBLISH_PERMISSION_NOT_GRANTED") })
                claimTrace.put("creations", creations).put("signals", timing).put("truncatedEvents", maxOf(0, signals.size - 64))
                    .put("receiverWait", receiverSnapshot()).put("mutedReceiverWait", mutedReceiverWait.snapshot())
                    .put("capture", JSONObject().put("captureRate", hooks.captureSampleRate)
                        .put("injectedFrames", hooks.injectedFrames.get()).put("recordinglessCallbacks", hooks.recordinglessCallbacks.get())
                        .put("generatedWords", hooks.generatedWords.get()))
                retainMediaClaimCapture(context.filesDir.resolve("capture-trace.json"), error, id, claimDelayMillis) {
                    hooks.trace.snapshot().put("sourceStart", hooks.sourceReadiness.snapshot())
                        .put("receiverWait", receiverSnapshot()).put("capture", claimTrace.getJSONObject("capture"))
                        .put("postApm", JSONObject().put("observed", false))
                }
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("claimFailureTimeline", timing.toString()) })
                throw error
            } finally {
                try { withContext(NonCancellable) {
                    if (present()) engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded"))
                    withTimeout(7000) { while (present() || TelecomEvidence.calls().isNotEmpty()) delay(20) }
                    fixture("/fixture/close/$id", reset = true)
                } } catch (cleanup: Throwable) {
                    failure?.addSuppressed(cleanup) ?: throw cleanup
                } finally {
                answerHooks.close()
                NativeMediaSignals.observers -= observer
                ConversationActions.configure(previousHandler)
                if (previousFactory == null) NativeMediaRegistry.factories.remove("livekit")
                else NativeMediaRegistry.factories["livekit"] = previousFactory
                engine.nativeMedia = previousMedia; engine.policy = previousPolicy
                }
            }
        }
    }

    private fun audioOffers(connection: JSONObject): List<JSONObject> {
        val events = connection.getJSONArray("events")
        return (0 until events.length()).map { events.getJSONObject(it) }.filter {
            it.getInt("direction") == 0 && it.getInt("kind") == 1 && it.getInt("audioSections") > 0
        }
    }

    @Test fun captureCallbackGateReadDoesNotWaitForTheRtcExecutor() = runBlocking {
        withContext(Dispatchers.Main) {
            val room = LiveKit.create(context)
            val track = room.localParticipant.createAudioTrack("gate-probe")
            val info = Class.forName("livekit.LivekitModels\$TrackInfo")
                .getMethod("getDefaultInstance").invoke(null) as LivekitModels.TrackInfo
            val publication = LocalTrackPublication(info, track, room.localParticipant, AudioTrackPublishOptions())
            val utilities = Class.forName("io.livekit.android.webrtc.peerconnection.RTCThreadUtilsKt")
            fun field(name: String) = utilities.getDeclaredField(name).apply { isAccessible = true }.get(null)
            val previousExecutor = field("executor") as ExecutorService
            val previousDispatcher = field("rtcDispatcher") as CoroutineDispatcher
            val executor = Executors.newSingleThreadExecutor()
            val dispatcher = executor.asCoroutineDispatcher()
            val readers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val entered = CompletableDeferred<Unit>()
            val controlStarted = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            try {
                overrideExecutorAndDispatcher(executor, dispatcher)
                executor.submit { entered.complete(Unit); release.await() }
                withTimeout(1000) { entered.await() }
                val control = readers.async {
                    controlStarted.complete(Unit)
                    EmulatorGateProbe.captureClosed(publication)
                }
                withTimeout(1000) { controlStarted.await() }
                val callback = readers.async { EmulatorGateProbe.captureClosedAtCallback(publication) }
                assertFalse(withTimeout(1000) { callback.await() })
                assertFalse("The full track getter really waits for the paused RTC executor", control.isCompleted)
                release.countDown()
                assertFalse(withTimeout(1000) { control.await() })
            } finally {
                release.countDown()
                try {
                    withContext(NonCancellable) { readers.coroutineContext[Job]!!.cancelAndJoin() }
                } finally {
                    overrideExecutorAndDispatcher(previousExecutor, previousDispatcher)
                    dispatcher.close()
                    track.dispose(); room.release()
                }
            }
        }
    }

    private suspend fun fixture(path: String, reset: Boolean = false, body: JSONObject? = null,
        onPhase: (MediaReceiverHttpPhase) -> Unit = {}): JSONObject {
        val request = Request.Builder().url(settings.getString("baseUrl") + path)
            .header("Authorization", "Bearer " + settings.getString("authentication"))
        if (reset || body != null) request.post((body?.toString() ?: "{}").toRequestBody("application/json".toMediaType()))
        return readMediaFixture(client, request.build(), onPhase)
    }

    private fun healthy(peer: JSONObject, generation: Long) {
        assertTrue("A healthy subscribed receiver is required: $peer", peer.getBoolean("running"))
        assertTrue("The receiver failed: $peer", peer.isNull("error"))
        assertEquals("tones-v2", peer.getString("profile"))
        assertEquals(generation, peer.getLong("generation"))
    }

    private suspend fun peerAudio(id: String, generation: Long, within: Long = 5000,
        markerObserved: (Long) -> Unit = {}) = withTimeout(within) {
        var peer: JSONObject
        do {
            delay(40); peer = fixture("/fixture/markers/$id")
            healthy(peer, generation)
            if (peer.getLong("markers") > 0) markerObserved(android.os.SystemClock.elapsedRealtimeNanos())
        } while (peer.getLong("cleanCyclesWithPostRoll") < 1)
        peer
    }

    private suspend fun outputAudio(id: String, hooks: EmulatorMediaHooks, record: (JSONObject) -> Unit): JSONObject {
        val trace = hooks.outputConsumerTrace
        trace.begin()
        val baseline = trace.measure(OutputConsumerTrace.Stage.RTC_STATS) { withTimeout(2000) { hooks.outputStats(baseline = true) } }
        try {
            return withTimeout(5000) {
                trace.measure(OutputConsumerTrace.Stage.HTTP_ACK) {
                    fixture("/fixture/output/$id", body = JSONObject().put("reset", true).put("sampleRate", 48000)
                        .put("firstFrame", 0).put("pcm16", "").put("stats", baseline))
                }
                hooks.startOutputCapture()
                pipelineMediaOutput(read = {
                    val stats = trace.measure(OutputConsumerTrace.Stage.RTC_STATS) { hooks.outputStats() }
                    val (first, bytes) = trace.measure(OutputConsumerTrace.Stage.DRAIN) { hooks.drainOutput() }
                    MediaOutputChunk(first, bytes, stats)
                }, send = { batch ->
                    trace.measure(OutputConsumerTrace.Stage.HTTP_ACK) {
                        fixture("/fixture/output/$id", body = JSONObject().put("sampleRate", 48000)
                            .put("firstFrame", batch.firstFrame).put("pcm16", Base64.getEncoder().encodeToString(batch.pcm))
                            .put("stats", batch.stats))
                    }.also { peer ->
                        assertTrue(peer.isNull("error")); assertTrue(peer.getBoolean("running"))
                        record(peer)
                    }
                }, finished = { it.getLong("cleanCyclesWithPostRoll") >= 1 },
                    checkPending = trace::checkDrain,
                    pause = { trace.measure(OutputConsumerTrace.Stage.WAIT) { delay(100) } })
            }
        } finally { hooks.stopOutputCapture() }
    }

    private suspend fun silenceWithLiveDecodedAudio(hooks: EmulatorMediaHooks): EmulatorMediaHooks.Observation {
        var output = hooks.output()
        var decoded = hooks.decoded()
        repeat(5) {
            delay(100)
            val nextOutput = hooks.output()
            val nextDecoded = hooks.decoded()
            if (nextOutput.blocks <= output.blocks || nextOutput.frames <= output.frames)
                throw MediaHookUnavailable(MediaHookFailure.OUTPUT_TAP_UNAVAILABLE)
            assertEquals(0L, nextOutput.nonzero)
            assertTrue("Decoded tone must remain live throughout output silence",
                nextDecoded.frames > decoded.frames && nextDecoded.tones > decoded.tones)
            output = nextOutput; decoded = nextDecoded
        }
        return output
    }

    @Test fun aDisabledOutputTapFailsInsteadOfReportingSilence() {
        val error = assertThrows(MediaHookUnavailable::class.java) { EmulatorMediaHooks(context, "disabled", tapEnabled = false) }
        assertEquals(MediaHookFailure.OUTPUT_TAP_UNAVAILABLE, error.reason)
    }

    @Test fun captureSamplesMatchMarkerAndEmissionVectors() {
        for ((rate, emission, expected) in listOf(
            Triple(8000, false, "7305be4e1d4ba73d2f2f876869fa5cb27c12bb91450081452139f41262f7c003"),
            Triple(48000, false, "82f79efb9f1b24a5c839cd500f5c9ad291ef5969348c3d68b775fcef6f8fa3b4"),
            Triple(8000, true, "e339576ee90e3d72ef65c7a5e44ab4a74345be472fdcd39b8f25072448a5fbab"),
            Triple(48000, true, "0a79ca1399b4fc018584bcf8e90be7c476618fab47f903b9670ce1282eafbaa1"))) {
            val hooks = EmulatorMediaHooks(context, "parity-v2", emission = emission)
            try {
                val callback = hooks.capture(object : AudioBufferCallback {
                    override fun onBuffer(buffer: ByteBuffer, format: Int, channels: Int, rate: Int,
                        bytesRead: Int, timestamp: Long) = timestamp
                })
                val size = rate * (if (emission) 1020 else 720) / 1000 * 2
                val pcm = ByteBuffer.allocateDirect(size)
                assertEquals(123L, callback.onBuffer(pcm, AudioFormat.ENCODING_PCM_16BIT, 1, rate, size, 123L))
                val bytes = ByteArray(size).also { pcm.duplicate().apply { clear() }.get(it) }
                // SDK markerSamples(createMarker("parity-v2")), PCM16 little-endian, Math.round(value * 32767).
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                assertEquals("tones-v2 at $rate Hz", expected, digest)
            } finally { hooks.close() }
        }
    }

    @Test fun realRecordinglessCapturePublicationGateDecodedAndPostMuteOutput() = journey(48000)

    @Test fun aPhaseResetInsideAnInFlightPlaybackCallbackCannotReclassifyItsPcm() {
        val hooks = EmulatorMediaHooks(context, "phase-reset")
        try {
            hooks.beginPhase()
            val callback = hooks.playbackCallback { hooks.beginPhase() }
            callback.onWebRtcAudioTrackSamplesReady(playbackSamples(512))
            assertEquals(0L, hooks.output().blocks)
            assertEquals(0L, hooks.output().nonzero)
            hooks.playbackCallback { }.onWebRtcAudioTrackSamplesReady(playbackSamples(0))
            assertEquals(1L, hooks.output().blocks)
            assertEquals(0L, hooks.output().nonzero)
        } finally { hooks.close() }
    }

    @Test fun freshNonzeroPlaybackInTheNewPhaseStillFailsTheSilenceRequirement() {
        val hooks = EmulatorMediaHooks(context, "phase-nonzero")
        try {
            hooks.beginPhase()
            val callback = hooks.playbackCallback { }
            callback.onWebRtcAudioTrackSamplesReady(playbackSamples(0))
            callback.onWebRtcAudioTrackSamplesReady(playbackSamples(512))
            assertEquals(2L, hooks.output().blocks)
            assertEquals(1L, hooks.output().nonzero)
        } finally { hooks.close() }
    }

    private fun playbackSamples(value: Short) = JavaAudioDeviceModule.AudioSamples(
        AudioFormat.ENCODING_PCM_16BIT, 1, 48000,
        ByteBuffer.allocate(960).order(java.nio.ByteOrder.nativeOrder()).apply {
            repeat(480) { putShort(value) }
        }.array())

    private fun journey(rate: Int) = runBlocking {
        val forcedFailure = InstrumentationRegistry.getArguments().getString("mediaForceRecordinglessFailure") == "true"
        if (forcedFailure) for (name in listOf("media-hook-results.json", "capture-trace.json")) {
            val file = context.filesDir.resolve(name)
            check(!file.exists() || file.delete()) { "Could not clear stale control evidence" }
        }
        withContext(Dispatchers.Main) {
            val engine = ConversationEngine.get(context)
            val previousMedia = engine.nativeMedia; val previousPolicy = engine.policy
            val previousEmitter = engine.emit; val previousAccept = engine.actions.acceptUnhandled
            val previousControlObserver = engine.controlObserver
            val previousFactory = NativeMediaRegistry.factories["livekit"]
            val id = InstrumentationRegistry.getArguments().getString("mediaTestId") ?: "hooks-${System.nanoTime()}"
            require(id.matches(Regex("hooks-[0-9]+")))
            val hooks = EmulatorMediaHooks(context, "$id:fromDevice", requestedRate = rate)
            val signals = CopyOnWriteArrayList<NativeMediaSignal>()
            val observer = NativeMediaObserver {
                if (it.serverCallId == id) {
                    signals += it
                    if (it.stage == NativeMediaStage.MICROPHONE_PUBLISHED)
                        hooks.sourceReadiness.published(it.elapsedRealtimeNanos)
                    if (it.stage == NativeMediaStage.CAPTURE_PUMP_STARTED)
                        hooks.sourceReadiness.pumpStarted(it.elapsedRealtimeNanos)
                    if (it.stage == NativeMediaStage.CORE_MUTE_OBSERVED)
                        hooks.gateTrace.event(MediaGateTrace.Point.CORE_MUTE_OBSERVED, it.elapsedRealtimeNanos,
                            it.measurements["muted"] == 1L)
                }
            }
            var step = "prejoin"
            var pipeline = "unobserved"
            var caught: Throwable? = null
            var apmMonitor: Job? = null
            var heldRequested = false
            var muteDiagnosticDone = false
            var muteDiagnosticError: Throwable? = null
            val evidence = JSONObject().put("captureRate", rate).put("profile", "tones-v2").put("pipeline", "unobserved")
            val progress = MediaSourceProgress()
            var roomCleanupCompleted = false
            val receiverWait = MediaReceiverTrace(android.os.SystemClock::elapsedRealtimeNanos)
            fun receiverSnapshot() = receiverWait.snapshot().put("signals", org.json.JSONArray(
                signals.filter { it.stage in setOf(NativeMediaStage.MEDIA_CONNECT_STARTED,
                    NativeMediaStage.MEDIA_CONNECT_COMPLETED, NativeMediaStage.MICROPHONE_PUBLISH_STARTED,
                    NativeMediaStage.MICROPHONE_PUBLISHED, NativeMediaStage.PREJOINED,
                    NativeMediaStage.SUBSCRIPTION_REQUESTED, NativeMediaStage.REMOTE_SUBSCRIBED) }.map {
                    JSONObject().put("stage", it.stage.value).put("atNs", it.elapsedRealtimeNanos.toString())
                }))
            val publications = MediaEvidencePublications()
            failureTrace.snapshot = { mediaQualificationTrace(hooks.outputConsumerTrace.snapshot(), receiverSnapshot(),
                evidence.optJSONObject("outputServerTrace") ?: evidence.optJSONObject("toDevice")?.optJSONObject("outputConsumerTrace"),
                publications) }
            fun present() = (engine.snapshot["conversations"] as List<*>).any { (it as Map<*, *>)["id"] == id }
            suspend fun traceMuteTransition() {
                muteDiagnosticDone = true
                hooks.gateTrace.stop()
                try { withContext(NonCancellable) { withTimeout(3000) {
                    engine.hold(id, false)
                    while (hooks.captureGateClosed()) delay(10)
                    hooks.gateTrace.recording(MediaGateTrace.Phase.MUTED) {
                        hooks.trace.phase = "mute diagnostic"
                        engine.mute(id, true)
                        hooks.beginPhase()
                        delay(100)
                    }
                } } } catch (error: Throwable) {
                    muteDiagnosticError = error; throw error
                } finally { hooks.gateTrace.stop() }
            }
            suspend fun stage(kind: NativeMediaStage) = withTimeout(10_000) {
                while (signals.none { it.stage == kind }) delay(10)
            }
            engine.nativeMedia = AndroidNativeMedia.LIVEKIT
            engine.policy = JSONObject(mapOf("media" to "livekit", "credentials" to "fromPush", "prewarm" to true))
            engine.actions.resetBridge(); engine.actions.acceptUnhandled = true
            engine.emit = { name, _ -> assertNotEquals("handlerAction", name) }
            engine.controlObserver = NativeControlObserver { if (it.conversationId == id) hooks.gateTrace.control(it) }
            NativeMediaRegistry.factories["livekit"] = { LiveKitNativeSession(it, id, hooks) }
            NativeMediaSignals.observers += observer
            try {
                withTimeout(7000) { while (TelecomEvidence.calls().isNotEmpty()) delay(20) }
                val media = fixture("/fixture/invitation/$id?media=1").getJSONObject("media")
                val grant = media.keys().asSequence().associateWith { media.get(it) }
                assertEquals("reported", engine.incoming(IncomingCallPush(id, "event-$id",
                    mapOf("handleType" to "generic", "value" to "peer", "displayName" to "Peer"), media = grant).payload())["status"])
                step = "receive-only prejoin"
                stage(NativeMediaStage.PREJOINED)
                val prejoined = signals.first { it.stage == NativeMediaStage.PREJOINED }
                assertEquals(0L, prejoined.measurements["localMicrophones"])
                assertEquals(0L, prejoined.measurements["localPublications"])
                assertTrue(signals.none { it.stage == NativeMediaStage.MICROPHONE_PUBLISH_STARTED })
                hooks.beginPhase()
                withTimeout(3000) { while (hooks.decoded().tones < 5 || hooks.output().blocks < 10) delay(10) }
                silenceWithLiveDecodedAudio(hooks)
                assertEquals(0L, hooks.injectedFrames.get())
                engine.mute(id, true)
                step = "muted answer"
                engine.join(id); stage(NativeMediaStage.CONNECTED)
                step = "receiver subscription"
                awaitMediaReceiver({ true },
                    { phase -> fixture("/fixture/markers/$id", onPhase = phase) }, trace = receiverWait)
                step = "muted publication"
                assertTrue(hooks.directArrayVerified)
                val publication = hooks.publicationIdentity()
                val track = publication.track as LocalAudioTrack
                val options = track.options
                val manager = context.getSystemService(AudioManager::class.java)
                apmMonitor = launch {
                    var samples = 0
                    val fields = setOf("audioLevel", "totalAudioEnergy", "totalSamplesDuration", "echoReturnLoss",
                        "echoReturnLossEnhancement", "totalSamplesCaptured", "totalCaptureDelay", "droppedSamplesDuration",
                        "droppedSamplesEvents", "concealedSamples", "silentConcealedSamples", "concealmentEvents",
                        "totalSamplesReceived", "packetsReceived", "packetsLost", "jitterBufferEmittedCount",
                        "jitterBufferDelay", "insertedSamplesForDeceleration", "removedSamplesForAcceleration",
                        "packetsSent", "bytesSent", "active")
                    while (isActive) {
                        val current = track.options
                        hooks.trace.state(manager.mode, current.echoCancellation, current.autoGainControl,
                            current.noiseSuppression, track.enabled, publication.muted)
                        if (samples++ % 5 == 0) hooks.trace.stats(track.getRTCStats()?.statsMap?.values
                            ?.filter { it.type in setOf("media-source", "inbound-rtp", "outbound-rtp") }
                            ?.map { mapOf("type" to it.type, "values" to it.members.filterKeys { key -> key in fields }) }
                            ?: emptyList())
                        delay(50)
                    }
                }
                assertFalse(options.noiseSuppression)
                assertFalse(options.echoCancellation)
                assertTrue(options.autoGainControl && options.highPassFilter)
                pipeline = "NS=${options.noiseSuppression},AGC=${options.autoGainControl}," +
                    "AEC=${options.echoCancellation},highPass=${options.highPassFilter}"
                assertTrue(publication.muted)
                var peer = fixture("/fixture/markers/$id", reset = true)
                assertTrue("Muted answer requires a subscribed receiver: $peer", peer.getBoolean("running"))
                val generation = peer.getLong("generation")
                healthy(peer, generation)
                assertTrue(peer.getBoolean("muted"))
                step = "source readiness"
                hooks.sourceReadiness.awaitReady()
                evidence.put("sourceStart", hooks.sourceReadiness.snapshot())
                step = "muted publication"
                val wordsBefore = hooks.generatedWords.get()
                var injected = hooks.injectedFrames.get()
                var sampledAtNs = android.os.SystemClock.elapsedRealtimeNanos()
                var recordingless = hooks.recordinglessCallbacks.get()
                repeat(31) {
                    delay(100)
                    val next = hooks.injectedFrames.get()
                    val nextSampledAtNs = android.os.SystemClock.elapsedRealtimeNanos()
                    progress.record(sampledAtNs, nextSampledAtNs, injected, next)
                    if (forcedFailure && it == 0) fail("Recordingless source stopped")
                    assertTrue("Recordingless source stopped", next > injected)
                    assertTrue("The recordingless pacing callback must stay live", hooks.recordinglessCallbacks.get() > recordingless)
                    injected = next; sampledAtNs = nextSampledAtNs; recordingless = hooks.recordinglessCallbacks.get()
                    peer = fixture("/fixture/markers/$id")
                    healthy(peer, generation)
                    assertTrue(peer.getBoolean("muted")); assertTrue(publication.muted)
                    assertEquals(0L, peer.getLong("markers"))
                }
                val mutedWords = hooks.generatedWords.get() - wordsBefore
                assertEquals(rate, hooks.captureSampleRate)
                // Three completed boundaries guarantee at least two whole words inside the muted window.
                assertTrue("Muted source generated only $mutedWords boundaries", mutedWords >= 3)
                assertFalse(signals.any { it.stage == NativeMediaStage.CAPTURE_BEFORE_AUTHORITY ||
                    it.stage == NativeMediaStage.CAPTURE_STARTED || it.stage == NativeMediaStage.OUTPUT_BEFORE_AUTHORITY })
                peer = fixture("/fixture/markers/$id")
                healthy(peer, generation)
                assertTrue(peer.getBoolean("muted")); assertTrue(publication.muted)
                assertEquals("A muted publication must never leak a raw marker", 0L, peer.getLong("markers"))
                evidence.put("mutedPublication", peer)

                step = "unmute"
                val unmuteRequestedNs = android.os.SystemClock.elapsedRealtimeNanos()
                engine.mute(id, false); stage(NativeMediaStage.CAPTURE_STARTED)
                withTimeout(3000) {
                    var codec: Any?
                    do {
                        val stats = track.getRTCStats()?.statsMap
                        val outbound = stats?.values?.firstOrNull { it.type == "outbound-rtp" &&
                            (it.members["kind"] == "audio" || it.members["mediaType"] == "audio") }
                        codec = stats?.get(outbound?.members?.get("codecId"))?.members?.get("mimeType")
                        if (codec != "audio/opus") delay(30)
                    } while (codec != "audio/opus")
                }
                pipeline += ",codec=audio/opus"
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("pipeline", pipeline) })
                hooks.trace.phase = "answered"
                hooks.postApm.start()
                fixture("/fixture/markers/$id", reset = true)
                var firstUplinkObservedNs: Long? = null
                val answered = peerAudio(id, generation) { observed ->
                    if (firstUplinkObservedNs == null) firstUplinkObservedNs = observed
                }
                evidence.put("uplinkTiming", JSONObject().put("clock", "android.elapsedRealtimeNanos")
                    .put("unmuteRequestedNs", unmuteRequestedNs.toString())
                    .put("firstMarkerObservedNs", firstUplinkObservedNs?.toString())
                    .put("unmuteToFirstMarkerObservedMs", (checkNotNull(firstUplinkObservedNs) - unmuteRequestedNs) / 1_000_000.0))
                evidence.put("fromDevice", answered)
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putString("uplinkTiming", evidence.getJSONObject("uplinkTiming").toString())
                    putString("tonesV2", answered.put("captureRate", hooks.captureSampleRate).toString())
                })
                assertSame(publication, hooks.publicationIdentity()); assertFalse(publication.muted)
                step = "answered output"
                outputAudio(id, hooks) { evidence.put("toDevice", it) }
                hooks.postApm.stop()
                hooks.beginPhase()
                withTimeout(3000) { while (hooks.decoded().tones < 5 || hooks.output().tones < 5) delay(10) }

                step = "source controls"
                for (stimulus in listOf(EmulatorMediaHooks.Stimulus.ZERO, EmulatorMediaHooks.Stimulus.WRONG)) {
                    hooks.trace.phase = stimulus.name
                    hooks.stimulus = stimulus
                    delay(300)
                    fixture("/fixture/markers/$id", reset = true)
                    val rejected = runCatching { peerAudio(id, generation, 3100) }.exceptionOrNull()
                    assertTrue("$stimulus must fail the far-end audio assertion", rejected is TimeoutCancellationException)
                    val rejectedPeer = fixture("/fixture/markers/$id")
                    healthy(rejectedPeer, generation)
                    assertEquals("$stimulus must not produce a raw marker, even with impaired transport", 0L, rejectedPeer.getLong("markers"))
                    evidence.put(stimulus.name, rejectedPeer)
                }
                hooks.stimulus = EmulatorMediaHooks.Stimulus.MARKER
                hooks.trace.phase = "restored"
                fixture("/fixture/markers/$id", reset = true)
                peerAudio(id, generation)

                step = "held output"
                heldRequested = true
                hooks.gateTrace.arm(MediaGateTrace.Phase.HELD)
                hooks.trace.phase = "held"
                engine.hold(id, true); hooks.beginPhase()
                withTimeout(3000) { while (hooks.decoded().tones < 5 || hooks.output().blocks < 10) delay(10) }
                val held = silenceWithLiveDecodedAudio(hooks)
                hooks.gateTrace.stop()
                hooks.observeOutput(false)
                val stopped = runCatching { silenceWithLiveDecodedAudio(hooks) }.exceptionOrNull()
                assertTrue(stopped is MediaHookUnavailable)
                assertEquals(MediaHookFailure.OUTPUT_TAP_UNAVAILABLE, (stopped as MediaHookUnavailable).reason)
                hooks.observeOutput(true)
                engine.hold(id, false); hooks.beginPhase()
                withTimeout(3000) { while (hooks.output().tones < 5) delay(10) }
                assertFalse(signals.any { it.stage == NativeMediaStage.OUTPUT_OBSERVATION_UNAVAILABLE })
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putString("mediaHooks", "directArray=true,captureRate=${hooks.captureSampleRate}," +
                        "$pipeline," +
                        "generatedMutedWords=$mutedWords,receivedMutedMarkers=0,answeredMarkers=${answered.getLong("markers")}," +
                        "sameReceiver=true,wrongAndZeroRejected=true,heldOutputBlocks=${held.blocks},heldNonzero=${held.nonzero},stoppedTapRejected=true")
                })
                step = "mute diagnostic"
                traceMuteTransition()
                step = "end"
                engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded"))
                withTimeout(7000) { while (present() || TelecomEvidence.calls().isNotEmpty() ||
                    fixture("/fixture/observations/$id").getInt("clients") != 0) delay(30) }
                stage(NativeMediaStage.CLOSED)
            } catch (error: Throwable) {
                caught = error
                evidence.put("sourceStart", hooks.sourceReadiness.snapshot())
                rethrowMediaJourneyFailure(context.filesDir.resolve("media-hook-results.json"), evidence, error,
                    "realRecordinglessCapturePublicationGateDecodedAndPostMuteOutput", step, android.os.Process.myPid(),
                    forcedFailure, progress, receiverSnapshot()) {
                    if (step == "answered output") runCatching {
                        evidence.put("outputServerTrace", fixture("/fixture/output/$id"))
                    }.onFailure { evidence.put("outputServerTraceReadError", it.javaClass.simpleName) }
                    runCatching { evidence.put("fromDevice", fixture("/fixture/markers/$id")) }
                    InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                        putString("tonesV2Failure", runCatching { fixture("/fixture/markers/$id").put("captureRate", hooks.captureSampleRate).toString() }.getOrDefault("unavailable"))
                        putString("mediaHookFailure", "step=$step, error=$error, signals=${signals.map { it.stage }}")
                        putString("pipeline", pipeline)
                    })
                    evidence.put("failure", org.json.JSONArray(mediaFailureDetails(error).map {
                        JSONObject().put("type", it.type).put("message", it.message)
                    })).put("hookObservations", JSONObject().put("captureRate", hooks.captureSampleRate)
                        .put("injectedFrames", hooks.injectedFrames.get())
                        .put("decoded", runCatching { hooks.decoded().toString() }.getOrNull())
                        .put("output", runCatching { hooks.output().toString() }.getOrNull()))
                }
            } finally {
                val primary = caught
                if (heldRequested && !muteDiagnosticDone && present()) {
                    hooks.gateTrace.stop()
                    try { traceMuteTransition() } catch (error: Throwable) {
                        muteDiagnosticError = error
                        if (primary != null) primary.addSuppressed(error) else caught = error
                    } finally { hooks.gateTrace.stop() }
                }
                apmMonitor?.cancel()
                try {
                    publications.write(MediaEvidencePublications.File.RESULTS) {
                        writeMediaEvidence(context.filesDir.resolve("media-hook-results.json"), evidence.put("step", step)
                            .put("receiverWait", receiverSnapshot())
                            .put("outputConsumerTrace", hooks.outputConsumerTrace.snapshot())
                            .put("sourceStart", hooks.sourceReadiness.snapshot())
                            .put("passed", caught == null).put("pipeline", pipeline)
                            .put("muteTimings", org.json.JSONArray(signals.filter {
                                it.measurements.containsKey("operationId") && NativeMuteStep.entries.any { step ->
                                    it.stage == step.entered || it.stage == step.exited
                                }
                        }.takeLast(64).map {
                            JSONObject().put("operationId", it.measurements.getValue("operationId").toString())
                                .put("stage", it.stage.value).put("elapsedRealtimeNanos", it.elapsedRealtimeNanos.toString())
                                .put("completed", it.measurements["completed"] ?: JSONObject.NULL)
                        }))
                        .put("failure", org.json.JSONArray(caught?.let(::mediaFailureDetails).orEmpty().map {
                            JSONObject().put("type", it.type).put("message", it.message)
                        }))
                        .put("gateTrace", hooks.gateTrace.snapshot().put("muteDiagnosticError",
                            muteDiagnosticError?.javaClass?.simpleName ?: JSONObject.NULL)).toString())
                    }
                    publications.write(MediaEvidencePublications.File.CAPTURE) {
                        writeMediaCaptureEvidence(context.filesDir.resolve("capture-trace.json"), hooks.trace.snapshot()
                            .put("postApm", hooks.postApm.finish()))
                    }
                    InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                        putString("captureTraceFile", "capture-trace.json")
                    })
                } catch (diagnostic: Throwable) {
                    val primary = caught
                    if (primary == null) { caught = diagnostic; throw diagnostic }
                    else primary.addSuppressed(diagnostic)
                } finally {
                    withContext(NonCancellable) {
                        try {
                            try {
                                withTimeout(7000) {
                                    if (present()) engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded"))
                                    while (present() || TelecomEvidence.calls().isNotEmpty()) delay(20)
                                    fixture("/fixture/close/$id", reset = true)
                                    roomCleanupCompleted = true
                                }
                            } catch (cleanup: Throwable) {
                                val primary = caught
                                if (primary != null) primary.addSuppressed(cleanup) else throw cleanup
                            }
                        } finally {
                            try { hooks.close() } finally {
                                NativeMediaSignals.observers -= observer
                                engine.controlObserver = previousControlObserver
                                if (previousFactory == null) NativeMediaRegistry.factories.remove("livekit")
                                else NativeMediaRegistry.factories["livekit"] = previousFactory
                                engine.nativeMedia = previousMedia; engine.policy = previousPolicy
                                engine.actions.resetBridge(); engine.actions.acceptUnhandled = previousAccept; engine.emit = previousEmitter
                                if (caught != null) try {
                                    evidence.put("cleanup", JSONObject().put("roomLeft", roomCleanupCompleted &&
                                        signals.any { it.stage == NativeMediaStage.CLOSED })
                                        .put("captureStopped", hooks.isClosed))
                                    writeMediaEvidence(context.filesDir.resolve("media-hook-results.json"), evidence.toString())
                                } catch (diagnostic: Throwable) { caught?.addSuppressed(diagnostic) }
                            }
                        }
                    }
                }
                val tracingFailure = muteDiagnosticError
                if (primary == null && tracingFailure != null) throw tracingFailure
            }
        }
    }
}
