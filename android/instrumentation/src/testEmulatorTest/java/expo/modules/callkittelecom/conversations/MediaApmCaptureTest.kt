package expo.modules.callkittelecom.conversations

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class MediaApmCaptureTest {
    private fun tap() = MediaApmCapture("parity-v2") { 123L }.apply {
        initializeAudioProcessing(48000, 1); start()
    }
    private fun source() = ByteBuffer.allocateDirect(960).order(ByteOrder.nativeOrder()).apply {
        repeat(480) { putShort((it - 240).toShort()) }; position(7); limit(900)
    }
    private fun post() = ByteBuffer.allocateDirect(1920).order(ByteOrder.nativeOrder()).apply {
        repeat(480) { putFloat((it - 240) / 2f) }; position(5); limit(1900)
    }

    @Test fun copiesPairedPcmWithoutChangingNativeBuffersOrTheirCursors() {
        val tap = tap(); val source = source(); val post = post()
        tap.returned(48960, 480, 48000, 1, source)
        tap.processAudio(3, 480, post)
        assertEquals(7, source.position()); assertEquals(900, source.limit())
        assertEquals(5, post.position()); assertEquals(1900, post.limit())
        assertEquals((-240).toShort(), source.getShort(0)); assertEquals(-120f, post.getFloat(0), 0f)
        source.putShort(0, 0); post.putFloat(0, 0f)
        val saved = tap.finish()
        assertTrue(saved.isNull("error")); assertEquals(48960L, saved.getLong("firstSourceFrame"))
        val before = ByteBuffer.wrap(Base64.getDecoder().decode(saved.getString("sourcePcm"))).order(ByteOrder.LITTLE_ENDIAN)
        val after = ByteBuffer.wrap(Base64.getDecoder().decode(saved.getString("postPcm"))).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals((-240).toShort(), before.short); assertEquals(-120f, after.float, 0f)
        assertEquals(480, saved.getInt("frames"))
        assertEquals(48960L, saved.getJSONArray("blocks").getJSONArray(0).getLong(0))
    }

    @Test fun rejectsMissingRepeatedAndNoncontiguousSourceInsteadOfStitching() {
        for (kind in listOf("missing", "repeated", "gap")) {
            val tap = tap()
            tap.returned(0, 480, 48000, 1, source()); tap.processAudio(3, 480, post())
            when (kind) {
                "missing" -> tap.processAudio(3, 480, post())
                "repeated" -> { tap.returned(480, 480, 48000, 1, source()); tap.returned(960, 480, 48000, 1, source()) }
                "gap" -> { tap.returned(960, 480, 48000, 1, source()); tap.processAudio(3, 480, post()) }
            }
            val saved = tap.finish()
            assertFalse(kind, saved.isNull("error")); assertEquals(480, saved.getInt("frames"))
        }
    }

    @Test fun rejectsCrossThreadOrUnsupportedPostBuffers() {
        val crossThread = tap()
        crossThread.returned(0, 480, 48000, 1, source())
        Thread { crossThread.processAudio(3, 480, post()) }.apply { start(); join(2000); assertFalse(isAlive) }
        assertEquals("THREAD_MISMATCH", crossThread.finish().getString("error"))
        for (kind in listOf("rate", "stereo", "bands", "capacity", "nonfinite")) {
            val tap = tap()
            if (kind == "rate") tap.resetAudioProcessing(16000)
            if (kind == "stereo") tap.initializeAudioProcessing(48000, 2)
            tap.returned(0, 480, 48000, 1, source())
            val buffer = if (kind == "capacity") ByteBuffer.allocate(960) else post()
            if (kind == "nonfinite") buffer.putFloat(0, Float.NaN)
            tap.processAudio(if (kind == "bands") 1 else 3, 480, buffer)
            assertFalse(kind, tap.finish().isNull("error"))
        }
    }

    @Test fun ignoresUnpairedWorkBeforeFirstSourceAndStopsAtThreeSeconds() {
        val tap = tap()
        tap.processAudio(3, 480, post())
        repeat(320) { index ->
            tap.returned(index * 480L, 480, 48000, 1, source()); tap.processAudio(3, 480, post())
        }
        val saved = tap.finish()
        assertTrue(saved.isNull("error")); assertEquals(144000, saved.getInt("frames"))
        assertEquals(300, saved.getJSONArray("blocks").length())
        assertEquals(288000, Base64.getDecoder().decode(saved.getString("sourcePcm")).size)
        assertEquals(576000, Base64.getDecoder().decode(saved.getString("postPcm")).size)
        assertTrue(saved.toString().length < 1400000)
    }

    @Test fun stoppingBetweenSourceAndPostKeepsOnlyTheCompletePairedPrefix() {
        val tap = tap()
        tap.returned(0, 480, 48000, 1, source()); tap.processAudio(3, 480, post())
        tap.returned(480, 480, 48000, 1, source()); tap.stop()
        tap.processAudio(3, 480, post())
        val saved = tap.finish()
        assertTrue(saved.isNull("error")); assertEquals(480, saved.getInt("frames"))
        assertTrue(saved.getBoolean("discardedTrailingSource"))
    }
}
