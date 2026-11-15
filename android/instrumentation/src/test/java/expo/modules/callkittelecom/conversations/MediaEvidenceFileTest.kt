package expo.modules.callkittelecom.conversations

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class MediaEvidenceFileTest {
    @Test fun replacementIsCompleteAndPreservesUtf8() {
        val directory = Files.createTempDirectory("media-evidence").toFile()
        try {
            val file = directory.resolve("result.json")
            writeMediaEvidence(file, "{\"value\":\"before\"}")
            writeMediaEvidence(file, "{\"value\":\"åfter\"}")
            assertEquals("{\"value\":\"åfter\"}", file.readText())
            assertEquals(listOf("result.json"), directory.list()!!.toList())
        } finally { directory.deleteRecursively() }
    }

    @Test fun concurrentReadersOnlySeeCompleteReplacements() = runBlocking {
        val directory = Files.createTempDirectory("media-evidence").toFile()
        val done = AtomicBoolean(false)
        try {
            val file = directory.resolve("result.json")
            val before = "{\"value\":\"${"a".repeat(65536)}\"}"
            val after = "{\"value\":\"${"b".repeat(65536)}\"}"
            writeMediaEvidence(file, before)
            val started = CompletableDeferred<Unit>()
            val reading = async(Dispatchers.IO) {
                var reads = 0
                while (!done.get()) {
                    val value = file.readText()
                    assertTrue(value == before || value == after)
                    reads++
                    started.complete(Unit)
                }
                reads
            }
            try {
                withTimeout(3000) { started.await() }
                repeat(20) { writeMediaEvidence(file, if (it % 2 == 0) after else before) }
            } finally { done.set(true) }
            assertTrue(withTimeout(3000) { reading.await() } > 0)
        } finally { done.set(true); directory.deleteRecursively() }
    }

    @Test fun failedPublicationLeavesTheDestinationAndRemovesTheStagedFile() {
        val directory = Files.createTempDirectory("media-evidence").toFile()
        try {
            val target = directory.resolve("result.json").apply { mkdir() }
            target.resolve("retained").writeText("previous evidence")
            assertTrue(runCatching { writeMediaEvidence(target, "{}") }.isFailure)
            assertEquals("previous evidence", target.resolve("retained").readText())
            assertFalse(directory.resolve(".result.json.tmp").exists())
        } finally { directory.deleteRecursively() }
    }
}
