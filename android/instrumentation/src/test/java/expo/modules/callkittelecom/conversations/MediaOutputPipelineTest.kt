package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MediaOutputPipelineTest {
    private class Fixture {
        var now = 0L
        var captured = 0
        var drained = 0
        var active = 0
        var maximumActive = 0
        var maximumPending = 0
        var requests = 0
        val trace = OutputConsumerTrace { now }
        val waits = mutableListOf<Pair<Long, CompletableDeferred<Unit>>>()
        val received = mutableListOf<MediaOutputBatch>()
        suspend fun tick() {
            now += 100_000_000
            captured += 4800
            waits.filter { it.first <= now }.forEach { it.second.complete(Unit) }
            yield()
        }
        fun read(): MediaOutputChunk {
            trace.checkDrain(captured, drained)
            val first = drained
            val bytes = ByteArray((captured - drained) * 2) { (first / 4800).toByte() }
            drained = captured
            return MediaOutputChunk(first, bytes, JSONObject().put("fromFrame", first).put("frame", captured)
                .put("sample", captured / 4800))
        }
        suspend fun send(batch: MediaOutputBatch): Int {
            active++; maximumActive = maxOf(maximumActive, active); requests++
            received += batch
            val done = CompletableDeferred<Unit>()
            waits += (now + 479_000_000) to done
            try { done.await(); return requests }
            finally { active-- }
        }
        fun check(captured: Int, first: Int) {
            maximumPending = maxOf(maximumPending, captured - first)
            trace.checkDrain(captured, first)
        }
    }

    @Test fun a479MillisecondAckDoesNotBlockDrainsAndEveryPcmAndStatsSampleIsPreserved() = runBlocking {
        val fixture = Fixture()
        val result = withTimeout(1000) {
            pipelineMediaOutput(fixture::read, fixture::send, { it >= 3 }, fixture::check, fixture::tick)
        }
        assertEquals(3, result)
        assertEquals(1, fixture.maximumActive)
        assertEquals(0, fixture.active)
        assertTrue(fixture.maximumPending <= 24000)
        assertTrue(fixture.received.any { it.stats.length() > 1 })
        var next = 0
        val samples = mutableListOf<Int>()
        for (batch in fixture.received) {
            assertEquals(next, batch.firstFrame)
            assertTrue(batch.pcm.size <= 48000)
            batch.pcm.forEachIndexed { index, byte -> assertEquals(((batch.firstFrame * 2 + index) / 9600).toByte(), byte) }
            next += batch.pcm.size / 2
            repeat(batch.stats.length()) { samples += batch.stats.getJSONObject(it).getInt("sample") }
        }
        assertEquals((1..next / 4800).toList(), samples)
    }

    @Test fun anActualTapBacklogStillFailsAtOneFrameBeyond24000() = runBlocking {
        val fixture = Fixture()
        val error = runCatching {
            pipelineMediaOutput(fixture::read, fixture::send, { false }, fixture::check,
                pause = { fixture.captured = 24001 })
        }.exceptionOrNull()
        assertEquals("Output capture consumer fell behind", error?.message)
        assertEquals(24001, fixture.trace.snapshot().getJSONObject("guard").getInt("pendingFrames"))
        assertEquals(0, fixture.requests)
    }

    @Test fun aPermanentlyBlockedAckCannotCreateAnUnboundedPendingBatch() = runBlocking {
        val fixture = Fixture()
        var cancelled = false
        val error = runCatching {
            pipelineMediaOutput(fixture::read, {
                try { awaitCancellation() } finally { cancelled = true }
            }, { false }, fixture::check, fixture::tick)
        }.exceptionOrNull()
        assertEquals("Output capture consumer fell behind", error?.message)
        assertTrue(fixture.maximumPending > 24000)
        assertTrue(cancelled)
    }

    @Test fun aStalledSourceOrOutputCannotSatisfyTheExistingCallerDeadline() = runBlocking {
        for (sourceStopped in listOf(false, true)) {
            val error = runCatching {
                withTimeout(30) {
                    pipelineMediaOutput(read = {
                        if (sourceStopped) throw IllegalStateException("source stopped")
                        MediaOutputChunk(0, byteArrayOf(), JSONObject())
                    }, send = { false }, finished = { it }, checkPending = { _, _ -> }, pause = { delay(1) })
                }
            }.exceptionOrNull()
            if (sourceStopped) assertEquals("source stopped", error?.message)
            else assertTrue(error is TimeoutCancellationException)
        }
    }

    @Test fun failedAcknowledgementsKeepTheirFailureAndCancelTheDrain() = runBlocking {
        val failure = IllegalStateException("fixture health failure")
        val fixture = Fixture()
        val error = runCatching {
            pipelineMediaOutput(fixture::read, { throw failure }, { false }, fixture::check, fixture::tick)
        }.exceptionOrNull()
        assertTrue(generateSequence(error) { it.cause }.any { it === failure })
        assertEquals(4800, fixture.drained)
    }
}
