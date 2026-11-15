package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PushFaultControllerTest {
    @Test fun delayAndDropNeverReachIntakeBeforeTheControlledRelease() = runBlocking {
        for (drop in listOf(false, true)) {
            val entered = CompletableDeferred<Long>()
            val release = CompletableDeferred<Unit>()
            val events = mutableListOf<PushFaultEvent>()
            var now = 10L
            var deliveries = 0
            val control = PushFaultController(this, { now }, { events += it }, { deliveries++; true }, {
                entered.complete(it); release.await()
            })
            val request = PushFaultDelivery("delivery", "call", "same-event", 123, drop)
            val pending = control.enqueue(request)
            assertEquals(123L, entered.await())
            assertEquals(0, deliveries)
            assertEquals(listOf(PushFaultEvent.Delivery(request, PushFaultPhase.SCHEDULED, 10)), events)
            assertTrue(runCatching { control.reset() }.isFailure)
            now = 456
            release.complete(Unit); pending.join()
            assertEquals(if (drop) 0 else 1, deliveries)
            assertEquals(PushFaultEvent.Delivery(request,
                if (drop) PushFaultPhase.DROPPED else PushFaultPhase.RECOGNIZED, 456), events.last())
            control.reset()
        }
    }

    @Test fun duplicateMessagesKeepDistinctDeliveryIdsAndTheSameEventIdentity() = runBlocking {
        val received = mutableListOf<PushFaultDelivery>()
        val control = PushFaultController(this, { 1 }, {}, { received += it; true })
        val first = PushFaultDelivery("first", "call", "same-event")
        control.enqueue(first).join()
        control.enqueue(first.copy(deliveryId = "second")).join()
        assertEquals(listOf("first", "second"), received.map { it.deliveryId })
        assertEquals(listOf("same-event", "same-event"), received.map { it.eventId })
        assertTrue(runCatching { control.enqueue(first) }.isFailure)
        control.reset(); control.enqueue(first).join()
    }

    @Test fun cancelledDelaysNeverDeliverAndFailuresAreNotAcknowledgedAsRecognized() = runBlocking {
        val phases = mutableListOf<PushFaultPhase>()
        val started = CompletableDeferred<Unit>()
        val delayed = PushFaultController(this, { 1 }, { phases += (it as PushFaultEvent.Delivery).phase },
            { fail("Cancelled delivery reached intake"); true }, { started.complete(Unit); awaitCancellation() })
        val job = delayed.enqueue(PushFaultDelivery("cancel", "call", "event"))
        started.await(); job.cancelAndJoin(); delayed.reset()
        assertEquals(listOf(PushFaultPhase.SCHEDULED, PushFaultPhase.CANCELLED), phases)
        for (throws in listOf(true, false)) {
            phases.clear()
            val control = PushFaultController(this, { 1 }, { phases += (it as PushFaultEvent.Delivery).phase }, {
                if (throws) error("private detail") else false
            })
            control.enqueue(PushFaultDelivery("failed", "call", "event")).join()
            assertEquals(if (throws) PushFaultPhase.FAILED else PushFaultPhase.UNRECOGNIZED, phases.last())
        }
    }

    @Test fun retainedIdentityAndSchedulingAreBoundedUntilReset() = runBlocking {
        val control = PushFaultController(this, { 1 }, {}, { true })
        repeat(64) { control.enqueue(PushFaultDelivery("delivery-$it", "call", "event", drop = true)).join() }
        assertTrue(runCatching { control.enqueue(PushFaultDelivery("overflow", "call", "event")) }.isFailure)
        control.reset(); control.enqueue(PushFaultDelivery("overflow", "call", "event")).join()
        for (millis in listOf(-1L, 30_001L)) assertTrue(runCatching {
            PushFaultDelivery("delivery", "call", "event", millis)
        }.isFailure)
    }

    @Test fun jsonKeepsExactNanosecondsAndOnlyTokenPresenceAcrossRebind() {
        var output = ""
        val log = PushFaultLog(123) { output = it }
        log.record(PushFaultEvent.Delivery(PushFaultDelivery("delivery", "call", "event"),
            PushFaultPhase.RECOGNIZED, 9_007_199_254_740_993))
        log.record(PushFaultEvent.TokenRebind("rebind", false, true, true, 3, 3, true, 20))
        val root = JSONObject(output)
        assertEquals(1, root.getInt("schemaVersion")); assertEquals(123, root.getInt("processId"))
        val events = root.getJSONArray("events")
        assertEquals("9007199254740993", events.getJSONObject(0).getString("elapsedRealtimeNanos"))
        val token = events.getJSONObject(1)
        assertTrue(token.getBoolean("tokenEmpty")); assertFalse(token.has("token")); assertFalse(token.has("value"))
        log.clear(); assertEquals(0, JSONObject(output).getJSONArray("events").length())
    }
}
