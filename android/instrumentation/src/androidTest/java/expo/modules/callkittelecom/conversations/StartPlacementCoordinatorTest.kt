package expo.modules.callkittelecom.conversations

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.milliseconds
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartPlacementCoordinatorTest {
    @Test fun theDefaultHandlerDeadlineReservesOneSecondFromPlacement(): Unit = runBlocking {
        var now = 0L
        val deliveries = mutableListOf<Map<String, Any?>>()
        val coordinator = StartPlacementCoordinator({ name, body -> if (name == "handlerAction") deliveries += body }, {},
            monotonicNow = { now }, wallNow = { Instant.parse("2026-11-08T12:00:00Z") })
        coordinator.attach(true, 1, coordinator.epoch)
        val pending = async(start = CoroutineStart.UNDISPATCHED) { coordinator.place("slow", mapOf("id" to "slow")) }
        assertEquals("2026-11-08T12:00:09Z", deliveries.single()["deadline"])
        now = 8999
        coordinator.settle(deliveries.single()["actionID"] as String, 1, true, coordinator.epoch)
        assertNull(pending.await())
    }

    @Test fun missingAppHandlerFailsImmediatelyOrExplicitlyAcceptsWithoutCreatingPendingWork(): Unit = runBlocking {
        val coordinator = StartPlacementCoordinator({ _, _ -> fail("Unexpected delivery") }, {})
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { coordinator.place("raw", mapOf("id" to "raw")) }
        }
        assertTrue(pending.isCompleted)
        val failure = pending.await().exceptionOrNull()
        assertEquals("handler_failed", (failure as ConversationFailure).code)
        assertNull(coordinator.pending("raw"))
        coordinator.acceptUnhandled = true
        assertNull(coordinator.place("raw", mapOf("id" to "raw")))
        assertNull(coordinator.pending("raw"))
    }

    @Test fun onlyTheCurrentGenerationAndEpochCanSettleAndReportsWaitForPlacement(): Unit = runBlocking {
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        val coordinator = StartPlacementCoordinator({ name, body -> events += name to body }, {},
            wallNow = { Instant.parse("2026-11-08T12:00:00Z") })
        coordinator.attach(true, 1, coordinator.epoch)
        val placement = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.place("raw/電話", mapOf("id" to "raw/電話"))
        }
        assertEquals(mapOf("id" to events.first().second["actionID"], "kind" to "start",
            "origin" to "app", "deadline" to "2026-11-08T12:00:09Z"), coordinator.pending("raw/電話"))
        assertEquals("start", events.first().second["kind"])
        assertTrue(coordinator.deferReport("raw/電話", PlacementConnectionReport.CONNECTED))
        assertTrue(coordinator.deferReport("raw/電話", PlacementConnectionReport.CONNECTING))
        val id = events.first().second["actionID"] as String
        val oldEpoch = coordinator.epoch
        coordinator.resetBridge()
        coordinator.attach(true, 1, coordinator.epoch)
        coordinator.settle(id, 1, true, oldEpoch)
        assertFalse(placement.isCompleted)
        coordinator.attach(true, 2, coordinator.epoch)
        coordinator.settle(id, 1, false, coordinator.epoch)
        assertFalse(placement.isCompleted)
        coordinator.settle(id, 2, true, coordinator.epoch)
        assertEquals(PlacementConnectionReport.CONNECTED, placement.await())
        assertNull(coordinator.pending("raw/電話"))
        assertFalse(coordinator.deferReport("raw/電話", PlacementConnectionReport.CONNECTED))
    }

    @Test fun rejectedCancelledAndExpiredPlacementsDiscardReportsAndRejectLateSettlement(): Unit = runBlocking {
        val deliveries = mutableListOf<Map<String, Any?>>()
        var now = 0L
        val coordinator = StartPlacementCoordinator({ name, body -> if (name == "handlerAction") deliveries += body }, {},
            timeout = 40.milliseconds, handlerMargin = kotlin.time.Duration.ZERO, monotonicNow = { now })
        coordinator.attach(true, 1, coordinator.epoch)
        suspend fun rejected(id: String, expire: Boolean, cancel: Boolean) {
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { coordinator.place(id, mapOf("id" to id)) }
            }
            coordinator.deferReport(id, PlacementConnectionReport.CONNECTED)
            val action = deliveries.last()["actionID"] as String
            if (expire) now += 40
            if (cancel) coordinator.cancel(id) else coordinator.settle(action, 1, false, coordinator.epoch)
            val failure = withTimeout(3000) { pending.await().exceptionOrNull() }
            assertEquals(if (expire) "deadline_exceeded" else "handler_failed", (failure as ConversationFailure).code)
            coordinator.settle(action, 1, true, coordinator.epoch)
            assertNull(coordinator.pending(id))
            assertFalse(coordinator.deferReport(id, PlacementConnectionReport.CONNECTED))
        }
        rejected("rejected", expire = false, cancel = false)
        rejected("cancelled", expire = false, cancel = true)
        rejected("expired", expire = true, cancel = false)
    }

    @Test fun reentrantHandlerReplacementCannotRedeliverAnOlderGeneration(): Unit = runBlocking {
        val generations = mutableListOf<Int>()
        lateinit var coordinator: StartPlacementCoordinator
        var replace = true
        coordinator = StartPlacementCoordinator({ name, body ->
            if (name == "handlerAction") generations += body["generation"] as Int
            if (name == "actionCancelled" && replace) {
                replace = false
                coordinator.attach(true, 3, coordinator.epoch)
            }
        }, {})
        coordinator.attach(true, 1, coordinator.epoch)
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { coordinator.place("reentrant", mapOf("id" to "reentrant")) }
        }
        coordinator.attach(true, 2, coordinator.epoch)
        assertEquals(listOf(1, 3), generations)
        coordinator.cancel("reentrant")
        pending.await()
    }

    @Test fun callerCancellationWithdrawsDeliveryAndCannotBeSettledLater(): Unit = runBlocking {
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        val coordinator = StartPlacementCoordinator({ name, body -> events += name to body }, {})
        coordinator.attach(true, 1, coordinator.epoch)
        val pending = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.place("cancelled", mapOf("id" to "cancelled"))
        }
        val id = events.first().second["actionID"] as String
        pending.cancelAndJoin()
        assertEquals("actionCancelled", events.last().first)
        assertEquals(mapOf("actionID" to id, "generation" to 1, "reason" to "cancelled"), events.last().second)
        assertNull(coordinator.pending("cancelled"))
        coordinator.settle(id, 1, true, coordinator.epoch)
        assertTrue(pending.isCancelled)
    }

    @Test fun aSystemStartCanWaitForHandlerAttachmentWithoutExtendingItsDeadline(): Unit = runBlocking {
        val deliveries = mutableListOf<Map<String, Any?>>()
        val coordinator = StartPlacementCoordinator({ name, body -> if (name == "handlerAction") deliveries += body }, {})
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.place("system", mapOf("id" to "system"), NativeActionOrigin.SYSTEM)
        }
        val deadline = coordinator.pending("system")?.get("deadline")
        assertFalse(pending.isCompleted)
        coordinator.attach(true, 1, coordinator.epoch)
        assertEquals(deadline, deliveries.single()["deadline"])
        assertEquals("system", deliveries.single()["origin"])
        coordinator.settle(deliveries.single()["actionID"] as String, 1, true, coordinator.epoch)
        assertNull(pending.await())
    }

    @Test fun attachingDuringPendingPublicationDeliversTheGenerationOnlyOnce(): Unit = runBlocking {
        val deliveries = mutableListOf<Map<String, Any?>>()
        lateinit var coordinator: StartPlacementCoordinator
        coordinator = StartPlacementCoordinator({ name, body -> if (name == "handlerAction") deliveries += body }, {
            coordinator.attach(true, 1, coordinator.epoch)
        })
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.place("published", mapOf("id" to "published"), NativeActionOrigin.SYSTEM)
        }
        assertEquals(1, deliveries.size)
        coordinator.settle(deliveries.single()["actionID"] as String, 1, true, coordinator.epoch)
        pending.await()
    }

    @Test fun replacementDuringBatchDeliveryCannotDeliverItsLaterEntriesTwice(): Unit = runBlocking {
        val deliveries = mutableListOf<Map<String, Any?>>()
        lateinit var coordinator: StartPlacementCoordinator
        var replace = true
        coordinator = StartPlacementCoordinator({ name, body ->
            if (name == "handlerAction") {
                deliveries += body
                if (replace) {
                    replace = false
                    coordinator.attach(true, 2, coordinator.epoch)
                }
            }
        }, {})
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.place("first", mapOf("id" to "first"), NativeActionOrigin.SYSTEM)
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.place("second", mapOf("id" to "second"), NativeActionOrigin.SYSTEM)
        }
        coordinator.attach(true, 1, coordinator.epoch)
        assertEquals(listOf("first" to 1, "first" to 2, "second" to 2), deliveries.map {
            (it["conversation"] as Map<*, *>)["id"] to it["generation"]
        })
        coordinator.settle(deliveries.first()["actionID"] as String, 2, true, coordinator.epoch)
        coordinator.settle(deliveries.last()["actionID"] as String, 2, true, coordinator.epoch)
        first.await(); second.await()
    }
}
