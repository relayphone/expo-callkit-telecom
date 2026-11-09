package expo.modules.callkittelecom.conversations

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import livekit.org.webrtc.RTCStats
import livekit.org.webrtc.RTCStatsCollectorCallback
import livekit.org.webrtc.RTCStatsReport
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeTransportReadinessTest {
    private class Stats {
        private val requests = Channel<RTCStatsCollectorCallback>(Channel.UNLIMITED)
        fun request(callback: RTCStatsCollectorCallback) { check(requests.trySend(callback).isSuccess) }
        suspend fun next(): RTCStatsCollectorCallback = withTimeout(1000) { requests.receive() }
    }

    private fun stat(id: String, type: String, vararg members: Pair<String, Any>) =
        RTCStats(0, type, id, mapOf(*members))

    private fun transport(ice: String = "new", dtls: String = "new", pair: Any? = null): RTCStats {
        val members = mutableMapOf<String, Any>("iceState" to ice, "dtlsState" to dtls)
        pair?.let { members["selectedCandidatePairId"] = it }
        return RTCStats(0, "transport", "transport", members)
    }

    private fun report(vararg stats: RTCStats) = RTCStatsReport(0, stats.associateBy { it.id })
    private fun connected() = report(transport("connected", "connected"))

    @Test fun connectedCompletedAndSelectedSucceededPairReleaseReadiness() = runBlocking {
        for (sample in listOf(connected(), report(transport("completed", "connected")),
            report(transport(dtls = "connected", pair = "selected"), stat("selected", "candidate-pair", "state" to "succeeded")))) {
            val ready = CompletableDeferred<Unit>()
            val watch = NativeTransportReadiness(this).watch({ it.onStatsDelivered(sample) }, ready)
            try {
                withTimeout(1000) { ready.await(); watch.join() }
                assertFalse(ready.isCancelled)
            } finally { watch.cancelAndJoin() }
        }
    }

    @Test fun incompleteReportsAndUnselectedPairsCannotAccumulateReadiness() = runBlocking {
        val stats = Stats()
        val ready = CompletableDeferred<Unit>()
        val watch = NativeTransportReadiness(this).watch(stats::request, ready)
        try {
            var callback = stats.next()
            val incomplete = listOf(
                report(),
                report(stat("codec", "codec", "iceState" to "connected", "dtlsState" to "connected")),
                report(transport(ice = "connected")),
                report(transport(dtls = "connected")),
                report(transport(dtls = "connected", pair = "selected"), stat("selected", "candidate-pair", "state" to "in-progress")),
                report(transport(dtls = "connected", pair = "missing")),
                report(transport(dtls = "connected", pair = 42)),
                report(transport(dtls = "connected"), stat("unselected", "candidate-pair", "state" to "succeeded")),
            )
            for (sample in incomplete) {
                callback.onStatsDelivered(sample)
                callback = stats.next()
                assertFalse(ready.isCompleted)
            }
            callback.onStatsDelivered(connected())
            withTimeout(1000) { ready.await(); watch.join() }
        } finally { watch.cancelAndJoin() }
    }

    @Test fun publisherAndSubscriberHaveIndependentReadiness() = runBlocking {
        val readiness = NativeTransportReadiness(this)
        val publisher = Stats()
        val subscriber = Stats()
        val publisherReady = CompletableDeferred<Unit>()
        val subscriberReady = CompletableDeferred<Unit>()
        val publisherWatch = readiness.watch(publisher::request, publisherReady)
        val subscriberWatch = readiness.watch(subscriber::request, subscriberReady)
        try {
            val publisherCallback = publisher.next()
            val subscriberCallback = subscriber.next()
            publisherCallback.onStatsDelivered(connected())
            withTimeout(1000) { publisherReady.await(); publisherWatch.join() }
            assertFalse(subscriberReady.isCompleted)
            subscriberCallback.onStatsDelivered(report(transport(ice = "connected")))
            val next = subscriber.next()
            assertFalse(subscriberReady.isCompleted)
            next.onStatsDelivered(connected())
            withTimeout(1000) { subscriberReady.await(); subscriberWatch.join() }
        } finally { publisherWatch.cancelAndJoin(); subscriberWatch.cancelAndJoin() }
    }

    @Test fun missingCallbackExpiresAndItsLateReportIsIgnored() = runBlocking {
        val stats = Stats()
        val ready = CompletableDeferred<Unit>()
        var observations = 0
        val watch = NativeTransportReadiness(this).watch(stats::request, ready,
            { RTCStatsCollectorCallback { observations++ } })
        try {
            val expired = stats.next()
            val current = stats.next()
            expired.onStatsDelivered(connected())
            yield()
            assertFalse(ready.isCompleted)
            assertEquals(0, observations)
            current.onStatsDelivered(connected())
            withTimeout(1000) { ready.await(); watch.join() }
            assertEquals(1, observations)
        } finally { watch.cancelAndJoin() }
    }

    @Test fun duplicateReportsCannotReplaceTheFirstSample() = runBlocking {
        val stats = Stats()
        val ready = CompletableDeferred<Unit>()
        var observations = 0
        val watch = NativeTransportReadiness(this).watch(stats::request, ready,
            { RTCStatsCollectorCallback { observations++ } })
        try {
            val first = stats.next()
            first.onStatsDelivered(report(transport(ice = "connected")))
            first.onStatsDelivered(connected())
            val second = stats.next()
            assertFalse(ready.isCompleted)
            assertEquals(1, observations)
            second.onStatsDelivered(connected())
            withTimeout(1000) { ready.await(); watch.join() }
            assertEquals(2, observations)
        } finally { watch.cancelAndJoin() }
    }

    @Test fun failedStatsRequestRetriesWithoutOverwritingAnAlreadyDeliveredReport() = runBlocking {
        val ready = CompletableDeferred<Unit>()
        var attempts = 0
        val watch = NativeTransportReadiness(this).watch({ callback ->
            attempts++
            if (attempts > 1) callback.onStatsDelivered(connected())
            error("Stats request failed")
        }, ready)
        try {
            withTimeout(1000) { ready.await(); watch.join() }
            assertEquals(2, attempts)
        } finally { watch.cancelAndJoin() }
    }

    @Test fun observerCreationAndDeliveryFailuresDoNotOwnReadiness() = runBlocking {
        val observers = listOf<() -> RTCStatsCollectorCallback>(
            { error("Observer unavailable") },
            { RTCStatsCollectorCallback { error("Observer failed") } },
        )
        for (observer in observers) {
            val ready = CompletableDeferred<Unit>()
            val watch = NativeTransportReadiness(this).watch({ it.onStatsDelivered(connected()) }, ready, observer)
            try {
                withTimeout(1000) { ready.await(); watch.join() }
                assertFalse(ready.isCancelled)
            } finally { watch.cancelAndJoin() }
        }
    }

    @Test fun cancellationReleasesPendingWaitersAndRejectsLateReports() = runBlocking {
        val stats = Stats()
        val ready = CompletableDeferred<Unit>()
        var observations = 0
        val watch = NativeTransportReadiness(this).watch(stats::request, ready,
            { RTCStatsCollectorCallback { observations++ } })
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { runCatching { ready.await() } }
        try {
            val callback = stats.next()
            watch.cancelAndJoin()
            withTimeout(1000) { assertTrue(waiter.await().exceptionOrNull() is CancellationException) }
            callback.onStatsDelivered(connected())
            yield()
            assertTrue(ready.isCancelled)
            assertEquals(0, observations)
        } finally { watch.cancelAndJoin() }
    }

    @Test fun cancellationBeforeStartCancelsReadinessWithoutRequestingStats() = runBlocking {
        val ready = CompletableDeferred<Unit>()
        var requests = 0
        val watch = NativeTransportReadiness(this).watch({ requests++ }, ready)
        watch.cancelAndJoin()
        assertEquals(0, requests)
        assertTrue(ready.isCancelled)
    }

    @Test fun parentCancellationCancelsTheWatchAndReadiness() = runBlocking {
        val parent = SupervisorJob()
        val stats = Stats()
        val ready = CompletableDeferred<Unit>()
        val watch = NativeTransportReadiness(CoroutineScope(coroutineContext + parent)).watch(stats::request, ready)
        try {
            val callback = stats.next()
            parent.cancel()
            withTimeout(1000) { watch.join() }
            callback.onStatsDelivered(connected())
            assertTrue(ready.isCancelled)
        } finally { parent.cancel(); watch.cancelAndJoin() }
    }

    @Test fun measurementObserversKeepRoleAndDeduplicateConcurrentReports() = runBlocking {
        val signals = CopyOnWriteArrayList<NativeMediaSignal>()
        val observer = NativeMediaObserver { if (it.serverCallId == "transport-observer") signals += it }
        val probe = NativeTransportProbe("transport-observer", 2L)
        val sample = report(transport("connected", "connected"), stat("checking", "candidate-pair", "state" to "in-progress"))
        NativeMediaSignals.observers += observer
        try {
            coroutineScope {
                repeat(8) {
                    launch(Dispatchers.Default) { repeat(8) { probe.request().onStatsDelivered(sample) } }
                }
            }
            withContext(Dispatchers.Main) { }
            assertEquals(3, signals.size)
            assertEquals(setOf(NativeMediaStage.ICE_CHECKING, NativeMediaStage.ICE_CONNECTED, NativeMediaStage.DTLS_CONNECTED),
                signals.map { it.stage }.toSet())
            for (signal in signals) {
                assertEquals(2L, checkNotNull(signal.measurements["role"]))
                assertEquals(1L, checkNotNull(signal.measurements["sampled"]))
                assertTrue(signal.elapsedRealtimeNanos >= checkNotNull(signal.measurements["requestNanos"]))
            }
        } finally { NativeMediaSignals.observers -= observer }
    }
}
