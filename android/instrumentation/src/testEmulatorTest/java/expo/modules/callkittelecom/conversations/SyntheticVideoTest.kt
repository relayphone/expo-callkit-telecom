package expo.modules.callkittelecom.conversations

import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import livekit.org.webrtc.JavaI420Buffer
import livekit.org.webrtc.VideoFrame
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyntheticVideoTest {
    private val fixture = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")
    private fun golden(direction: Int = 0) = SyntheticVideoMarker(fixture, direction, 7, 0xffffffffL, 9007199254740991L)
    @Test fun sharedTypeScriptPixelsDecodeAndKotlinPixelsExportInBothDirections() {
        for (direction in 0..1) {
            val marker = golden(direction)
            val input = File("src/androidTest/assets/video-interop-$direction.y").readBytes()
            assertEquals(marker, SyntheticVideoMarker.decode(input, 160, 120))
            val output = marker.luma()
            assertArrayEquals(input, output)
            if (direction == 0) assertEquals("94ba28480beb3d98dcb027a5bfca34428cdd0715b3a0a8c43ee54555e913f51a",
                MessageDigest.getInstance("SHA-256").digest(output).joinToString("") { "%02x".format(it) })
            File("build/video-interop").mkdirs()
            File("build/video-interop/kotlin-$direction.y").writeBytes(output)
        }
    }
    @Test fun paddedLossyPixelsAndInvalidMarkersExerciseTheRealDecoder() {
        val original = golden().luma()
        val padded = ByteArray(176 * 120) { 99 }
        repeat(120) { row -> repeat(160) { x ->
            val value = original[row * 160 + x].toInt() and 255
            padded[row * 176 + x] = (if (value < 100) value + 20 else if (value > 160) value - 20 else value).toByte()
        } }
        assertEquals(golden(), SyntheticVideoMarker.decode(padded, 160, 120, 176))
        assertThrows(IllegalArgumentException::class.java) { SyntheticVideoMarker.decode(ByteArray(19200) { 128.toByte() }, 160, 120) }
        val corrupt = original.copyOf(); repeat(6) { row -> corrupt.fill(235.toByte(), row * 160 + 8, row * 160 + 14) }
        assertThrows(IllegalArgumentException::class.java) { SyntheticVideoMarker.decode(corrupt, 160, 120) }
        assertThrows(IllegalArgumentException::class.java) { golden().copy(sourceTimeUs = 9007199254740992L) }
        assertThrows(IllegalArgumentException::class.java) { SyntheticVideoMarker.decode(ByteArray(2), 160, 120) }
    }
    private fun inspect(width: Int = 8, height: Int = 8, y: ByteArray = ByteArray(width * height) { 16 },
        u: ByteArray = ByteArray((width + 1) / 2 * ((height + 1) / 2)) { 128.toByte() }, v: ByteArray = u.copyOf()) =
        VideoImageClassifier.inspect(width, height, ByteBuffer.wrap(y), width,
            ByteBuffer.wrap(u), (width + 1) / 2, ByteBuffer.wrap(v), (width + 1) / 2)
    @Test fun contentFreeRequiresFullPlaneUniformityAndNonSourceGeometry() {
        assertEquals(VideoImageKind.CONTENT_FREE, inspect().kind)
        assertEquals(VideoImageKind.CONTENT_FREE, inspect(y = ByteArray(64) { if (it == 63) 20 else 16 }).kind)
        assertEquals(VideoImageKind.AMBIGUOUS, inspect(y = ByteArray(64) { if (it == 63) 21 else 16 }).kind)
        for (chroma in 0..1) {
            val changed = ByteArray(16) { if (it == 15) 150.toByte() else 128.toByte() }
            assertEquals(VideoImageKind.AMBIGUOUS, (if (chroma == 0) inspect(u = changed) else inspect(v = changed)).kind)
        }
        assertEquals(VideoImageKind.AMBIGUOUS, inspect(v = ByteArray(15)).kind)
        for ((w, h) in listOf(160 to 120, 320 to 240)) assertEquals(VideoImageKind.AMBIGUOUS, inspect(w, h).kind)
        assertEquals(golden(), inspect(160, 120, golden().luma()).marker)
        val real = golden().luma(); real.fill(128.toByte(), 0, 160 * 8)
        assertEquals(VideoImageKind.AMBIGUOUS, inspect(160, 120, real).kind)
    }
    @Test fun visibleCropPositionAndStrideExcludePaddingButNotImagePixels() {
        val y = ByteBuffer.wrap(ByteArray(12 * 9) { 99 }).apply { position(12) }
        repeat(8) { row -> repeat(8) { x -> y.put(12 + row * 12 + x, 16) } }
        val c = ByteBuffer.wrap(ByteArray(16) { 128.toByte() })
        assertEquals(VideoImageKind.CONTENT_FREE, VideoImageClassifier.inspect(8, 8, y, 12, c, 4, c, 4).kind)
        y.put(12 + 7 * 12 + 7, 21)
        assertEquals(VideoImageKind.AMBIGUOUS, VideoImageClassifier.inspect(8, 8, y, 12, c, 4, c, 4).kind)
        assertEquals(VideoImageKind.AMBIGUOUS, VideoImageClassifier.inspect(8, 8, y, 12, c, 4, c, 4, 90).kind)
    }
    @Test fun sinkReleasesConversionReferencesAndBoundsActualCallbacks() {
        var releases = 0
        fun direct(size: Int, value: Int) = ByteBuffer.allocateDirect(size).apply { repeat(size) { put(value.toByte()) }; flip() }
        val buffer = JavaI420Buffer.wrap(8, 8, direct(64, 16), 8, direct(16, 128), 4, direct(16, 128), 4) { releases++ }
        val frame = VideoFrame(buffer, 0, 123)
        val sink = EmulatorVideoSink { 456 }
        repeat(4097) { sink.onFrame(frame) }
        assertEquals(0, releases)
        frame.release()
        assertEquals(1, releases)
        assertEquals(4096, sink.snapshot().size); assertTrue(sink.overflowed())
        assertEquals(VideoImageKind.CONTENT_FREE, sink.snapshot().first().image.kind)
    }
    @Test fun encoderReferencesPreventPoolReuseAndSubmissionFailureReleases() {
        val pool = VideoFramePool(); val retained = mutableListOf<VideoFrame>()
        try {
            repeat(4) { counter -> pool.push(golden().copy(counter = counter.toLong())) { it.retain(); retained += it } }
            assertThrows(IllegalStateException::class.java) { pool.push(golden()) { fail("Pool must remain bounded") } }
            retained.forEachIndexed { index, frame ->
                val buffer = frame.buffer.toI420()
                try {
                    val pixels = ByteArray(19200); buffer.dataY.get(pixels)
                    assertEquals(index.toLong(), SyntheticVideoMarker.decode(pixels, 160, 120).counter)
                } finally { buffer.release() }
            }
            retained.removeAt(0).release()
            repeat(6) { assertThrows(IllegalStateException::class.java) { pool.push(golden()) { error("Rejected") } } }
            pool.push(golden()) { }
        } finally { retained.forEach { it.release() } }
    }
    @Test fun pacingSkipsMissedTicksAndStopFreezesTheOriginalReceipt() = runTest {
        val pushed = mutableListOf<SyntheticVideoMarker>()
        val source = EmulatorVideoSource(fixture, 0, 7, { testScheduler.currentTime * 1000 }, submit = { pushed += it })
        source.start(backgroundScope); runCurrent(); advanceTimeBy(250); runCurrent()
        val stopped = source.stop()
        assertTrue(stopped.pacingTaskJoined); assertEquals(3, pushed.size)
        assertEquals(pushed.last().counter, stopped.finalCounter); assertEquals(7L, stopped.finalEpoch)
        assertTrue(runCatching { source.capture() }.exceptionOrNull() is IllegalStateException)
        advanceTimeBy(1000); runCurrent()
        val final = source.snapshot()
        assertEquals(stopped.generated, final.generated); assertEquals(stopped.stoppedAtUs, final.stoppedAtUs)
        assertEquals(1L, final.postStopCaptureRejects); assertFalse(final.failed)
        var time = 0L
        val slow = EmulatorVideoSource(fixture, 0, 1, { time }, pause = { time += 450_000; delay(100) }, submit = {})
        slow.start(backgroundScope); runCurrent(); advanceTimeBy(100); runCurrent()
        val skipped = slow.stop()
        assertEquals(2, skipped.generated.size); assertTrue(skipped.skippedTicks >= 3)
        assertTrue(skipped.generated.last().counter > skipped.generated.first().counter + 1)
    }
    @Test fun blockedPushFinishesBeforeStopAndNoLaterPushIsAdmitted() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val time = AtomicLong(100)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val source = EmulatorVideoSource(fixture, 0, 1, time::get, submit = {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
        })
        try {
            source.start(scope); assertTrue(entered.await(5, TimeUnit.SECONDS))
            val stop = async(Dispatchers.Default) { source.stop() }
            yield(); assertFalse(stop.isCompleted); time.set(200); release.countDown()
            val receipt = withTimeout(5000) { stop.await() }
            assertTrue(receipt.pacingTaskJoined); assertEquals(1, receipt.generated.size)
            assertTrue(runCatching { source.capture() }.isFailure)
            assertEquals(receipt.finalCounter, source.snapshot().finalCounter)
        } finally { release.countDown(); source.stop(); scope.cancel() }
    }
    @Test fun stoppedBeforeStartAndCancelledOwnersStillJoinAndReject() = runTest {
        val source = EmulatorVideoSource(fixture, 1, 3, { 123L }, submit = { error("Unexpected push") })
        val receipt = source.stop()
        assertNull(receipt.finalCounter); assertEquals(3L, receipt.finalEpoch); assertTrue(receipt.pacingTaskJoined)
        assertTrue(runCatching { source.capture() }.isFailure)
        val cancelled = CoroutineScope(Job().apply { cancel() })
        val neverStarted = EmulatorVideoSource(fixture, 1, 3, { 123L }, submit = {})
        neverStarted.start(cancelled); assertTrue(neverStarted.stop().pacingTaskJoined)
    }
    @Test fun physicalBuildFactsAreRejectedBeforeCapture() {
        MediaTestRuntime.validate(true, true, true, "ranchu", "sdk_gphone64_x86_64")
        for ((hardware, product) in listOf("qcom" to "phone", "ranchu" to "physical", "qcom" to "sdk_phone")) {
            val error = assertThrows(MediaHookUnavailable::class.java) { MediaTestRuntime.validate(true, true, true, hardware, product) }
            assertEquals(MediaHookFailure.NOT_EMULATOR, error.reason)
        }
        for (flags in listOf(listOf(false, true, true), listOf(true, false, true), listOf(true, true, false)))
            assertEquals(MediaHookFailure.UNSUPPORTED_BUILD, assertThrows(MediaHookUnavailable::class.java) {
                MediaTestRuntime.validate(flags[0], flags[1], flags[2], "ranchu", "sdk_phone")
            }.reason)
    }
}
