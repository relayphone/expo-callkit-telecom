package expo.modules.callkittelecom.conversations

import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

class MediaJourneyFailureTest {
    @Test fun persistsOriginalAndSamplesBeforeEnrichmentAndRethrowsTheSameInstance() = runBlocking {
        val directory = Files.createTempDirectory("media-first-failure").toFile()
        try {
            val file = directory.resolve("media-hook-results.json")
            val original = AssertionError("Recordingless source stopped")
            val progress = MediaSourceProgress()
            repeat(10) { progress.record(it * 100_000_000L, (it + 1) * 100_000_000L, it.toLong(), it.toLong()) }
            val evidence = JSONObject().put("captureRate", 48000).put("profile", "tones-v2").put("pipeline", "unobserved")
            val thrown = try {
                rethrowMediaJourneyFailure(file, evidence, original, "test", "muted publication", 123, true,
                    progress, JSONObject().put("clock", "android.elapsedRealtimeNanos")) {
                    val saved = JSONObject(file.readText())
                    assertEquals(original.message, saved.getJSONObject("originalFailure").getString("message"))
                    assertEquals("test", saved.getString("test"))
                    assertEquals("muted publication", saved.getString("step"))
                    val samples = saved.getJSONObject("sourceProgress").getJSONArray("samples")
                    assertEquals(8, samples.length())
                    assertEquals("200000000", samples.getJSONObject(0).getString("beforeNs"))
                    assertEquals("100000000", samples.getJSONObject(7).getString("elapsedNs"))
                    assertEquals(9L, samples.getJSONObject(7).getLong("beforeFrames"))
                    assertEquals(9L, samples.getJSONObject(7).getLong("afterFrames"))
                    throw IllegalStateException("optional enrichment failed")
                }
            } catch (error: AssertionError) { error }
            assertSame(original, thrown)
            assertEquals("optional enrichment failed", thrown.suppressed.single().message)
            assertEquals(original.message, JSONObject(file.readText()).getJSONArray("failure").getJSONObject(0).getString("message"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun aFailedEarlyWriteCannotReplaceTheOriginalOrPreventEnrichment() = runBlocking {
        val directory = Files.createTempDirectory("media-first-failure").toFile()
        try {
            val target = directory.resolve("result.json").apply { mkdir() }
            target.resolve("retained").writeText("existing")
            val original = AssertionError("original")
            var enriched = false
            val thrown = try {
                rethrowMediaJourneyFailure(target, JSONObject(), original, "test", "phase", 123, false,
                    MediaSourceProgress(), JSONObject()) { enriched = true }
            } catch (error: AssertionError) { error }
            assertSame(original, thrown)
            assertTrue(enriched)
            assertEquals(1, original.suppressed.size)
        } finally { directory.deleteRecursively() }
    }
}
