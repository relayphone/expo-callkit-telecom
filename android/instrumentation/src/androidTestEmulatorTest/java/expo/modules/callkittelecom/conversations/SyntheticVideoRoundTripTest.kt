package expo.modules.callkittelecom.conversations

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.AudioOptions
import io.livekit.android.audio.NoAudioHandler
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.RoomOptions
import io.livekit.android.room.participant.VideoTrackPublishOptions
import io.livekit.android.room.track.RemoteVideoTrack
import io.livekit.android.room.track.Track
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyntheticVideoRoundTripTest {
    @Test fun cameraPermissionSpyFailsWhenTheCameraPathIsInvoked() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            MediaPermissionObservation.forbidCamera.set(true)
            assertThrows(IllegalStateException::class.java) { context.checkSelfPermission(Manifest.permission.CAMERA) }
        } finally { MediaPermissionObservation.forbidCamera.set(false) }
    }
    @Test fun realVp8CameraTrackDecodesBothDirectionsWithoutCameraPermission() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        MediaTestRuntime.activate(context); assertEquals(35, Build.VERSION.SDK_INT)
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.CAMERA))
        val settings = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
            .open("video-fixture.json").bufferedReader().readText())
        val cameraChecks = MediaPermissionObservation.cameraChecks.get()
        val room = LiveKit.create(context, options = RoomOptions(adaptiveStream = false, dynacast = false),
            overrides = LiveKitOverrides(audioOptions = AudioOptions(audioHandler = NoAudioHandler(),
                disableAudioPrewarming = true, disableCommunicationModeWorkaround = true)))
        val sink = EmulatorVideoSink(SystemClock::elapsedRealtimeNanos)
        var remote: RemoteVideoTrack? = null; var codec = ""
        var video: EmulatorVideoFixture? = null; var published = false
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { room.events.collect { event ->
            if (event is RoomEvent.TrackSubscribed && event.track is RemoteVideoTrack) {
                assertEquals("video-peer", event.participant.identity?.value)
                assertEquals(Track.Source.CAMERA, event.publication.source)
                check(remote == null); codec = checkNotNull(event.publication.mimeType)
                remote = event.track as RemoteVideoTrack; remote!!.addRenderer(sink)
            }
        } }
        try {
            MediaPermissionObservation.forbidCamera.set(true)
            withTimeout(10_000) { room.connect(settings.getString("url"), settings.getString("token")) }
            val fixture = EmulatorVideoFixture(context, room, UUID.fromString(settings.getString("fixture")), 0, 1)
            video = fixture
            fixture.source.capture()
            withTimeout(10_000) {
                assertTrue(room.localParticipant.publishVideoTrack(fixture.track,
                    VideoTrackPublishOptions(videoCodec = "vp8", simulcast = false, source = Track.Source.CAMERA)))
            }
            published = true; fixture.source.start(this)
            withTimeout(10_000) {
                while (sink.snapshot().count { it.image.kind == VideoImageKind.SOURCE } < 3) delay(20)
            }
            fixture.stop()
            assertTrue(runCatching { fixture.source.capture() }.isFailure)
            val final = fixture.source.snapshot()
            assertTrue(final.pacingTaskJoined); assertFalse(final.failed); assertTrue(final.postStopCaptureRejects > 0)
            val arrivals = sink.snapshot()
            assertFalse(sink.overflowed()); assertEquals("video/vp8", codec.lowercase())
            val markers = arrivals.mapNotNull { it.image.marker }
            assertTrue(markers.all { it.fixture.toString() == settings.getString("fixture") && it.direction == 1 && it.epoch == 1L })
            assertEquals(cameraChecks, MediaPermissionObservation.cameraChecks.get())
            fun marker(value: SyntheticVideoMarker) = JSONObject().put("fixture", value.fixture.toString())
                .put("direction", value.direction).put("epoch", value.epoch).put("counter", value.counter).put("sourceTimeUs", value.sourceTimeUs)
            writeMediaEvidence(File(context.filesDir, "video-round-trip.json"), JSONObject()
                .put("source", JSONObject().put("generated", JSONArray(final.generated.map(::marker)))
                    .put("finalCounter", final.finalCounter ?: JSONObject.NULL).put("finalEpoch", final.finalEpoch)
                    .put("pacingTaskJoined", final.pacingTaskJoined).put("postStopCaptureRejects", final.postStopCaptureRejects))
                .put("received", JSONArray(markers.map(::marker))).put("codec", codec)
                .put("ambiguous", arrivals.count { it.image.kind == VideoImageKind.AMBIGUOUS })
                .put("overflow", sink.overflowed()).toString())
        } finally {
            withContext(NonCancellable) {
                try {
                    try { video?.stop(); if (published) video?.track?.let { room.localParticipant.unpublishTrack(it) } }
                    finally {
                        remote?.removeRenderer(sink); collector.cancelAndJoin()
                        video?.track?.dispose(); room.disconnect(); room.release()
                    }
                } finally {
                    MediaPermissionObservation.forbidCamera.set(false)
                    assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.CAMERA))
                }
            }
        }
    }
}
