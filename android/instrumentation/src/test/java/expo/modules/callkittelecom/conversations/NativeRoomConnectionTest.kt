package expo.modules.callkittelecom.conversations

import io.livekit.android.events.DisconnectReason
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.*
import org.junit.Test

class NativeRoomConnectionTest {
    private class FakeRoom {
        val events = MutableSharedFlow<NativeRoomState>()
        var releases = 0
        val released = CompletableDeferred<Unit>()
        var reason: DisconnectReason? = null
        fun release(reason: DisconnectReason?) { this.reason = reason; releases++; released.complete(Unit) }
    }

    @Test fun roomRecoversWithoutFailureAndDuplicateReconnectingDoesNotExtendTheDeadline() = runBlocking {
        val room = FakeRoom()
        val signals = mutableListOf<Pair<NativeMediaStage, String?>>()
        val waits = mutableListOf<CompletableDeferred<Unit>>()
        val connection = NativeRoomConnection(this, { stage, reason -> signals.add(stage to reason) }, room::release) { millis ->
            assertEquals(30_000L, millis)
            CompletableDeferred<Unit>().also { waits += it }.await()
        }
        connection.observe(room.events)
        try {
            room.events.emit(NativeRoomState.RECONNECTING); yield()
            room.events.emit(NativeRoomState.RECONNECTING); yield()
            assertEquals(1, waits.size)
            assertEquals(listOf(NativeMediaStage.RECONNECTING to null), signals)
            assertEquals(0, room.releases)
            room.events.emit(NativeRoomState.RECONNECTED); yield()
            waits.single().complete(Unit); yield()
            assertEquals(0, room.releases)
            assertEquals(listOf(NativeMediaStage.RECONNECTING to null, NativeMediaStage.RECONNECTED to null), signals)
            room.events.emit(NativeRoomState.RECONNECTING); yield()
            assertEquals(2, waits.size)
            waits.last().complete(Unit)
            withTimeout(1000) { room.released.await() }
            assertEquals(1, room.releases)
            assertNull(room.reason)
            assertEquals(NativeMediaStage.ROOM_DISCONNECTED to "RECONNECT_TIMEOUT", signals.last())
        } finally { connection.close() }
    }

    @Test fun finalDisconnectReleasesOnceAndEndingCancelsEveryRoomObservationAndTimer() = runBlocking {
        for (end in listOf(false, true)) {
            val room = FakeRoom()
            val signals = mutableListOf<NativeMediaStage>()
            val gate = CompletableDeferred<Unit>()
            val waiting = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val connection = NativeRoomConnection(this, { stage, _ -> signals.add(stage) }, room::release) {
                waiting.complete(Unit)
                try { gate.await() } finally { cancelled.complete(Unit) }
            }
            connection.observe(room.events)
            room.events.emit(NativeRoomState.RECONNECTING)
            withTimeout(1000) { waiting.await() }
            if (end) connection.close() else room.events.emit(NativeRoomState.DISCONNECTED(DisconnectReason.UNKNOWN_REASON))
            withTimeout(1000) { cancelled.await() }
            gate.complete(Unit)
            room.events.emit(NativeRoomState.RECONNECTED)
            room.events.emit(NativeRoomState.DISCONNECTED(DisconnectReason.UNKNOWN_REASON)); yield()
            assertEquals(if (end) 0 else 1, room.releases)
            assertEquals(if (end) listOf(NativeMediaStage.RECONNECTING) else
                listOf(NativeMediaStage.RECONNECTING, NativeMediaStage.ROOM_DISCONNECTED), signals)
            assertEquals(0, room.events.subscriptionCount.value)
            connection.close()
        }
    }

    @Test fun everyLiveKitDisconnectReasonIsRetainedAndClassifiedOnce() = runBlocking {
        for (reason in DisconnectReason.entries) {
            val room = FakeRoom()
            val signals = mutableListOf<Pair<NativeMediaStage, String?>>()
            val connection = NativeRoomConnection(this, { stage, name -> signals += stage to name }, room::release)
            try {
                connection.observe(room.events)
                room.events.emit(NativeRoomState.DISCONNECTED(reason)); yield()
                room.events.emit(NativeRoomState.DISCONNECTED(reason)); yield()
                assertEquals(reason.name, 1, room.releases)
                assertEquals(reason, room.reason)
                val expected = when (reason) {
                    DisconnectReason.ROOM_DELETED, DisconnectReason.PARTICIPANT_REMOVED -> NativeMediaDisconnection.REMOTE_ENDED
                    DisconnectReason.CLIENT_INITIATED -> NativeMediaDisconnection.CLIENT_INITIATED
                    else -> NativeMediaDisconnection.FAILURE
                }
                assertEquals(reason.name, expected, room.reason!!.disconnection())
                assertEquals(listOf(NativeMediaStage.ROOM_DISCONNECTED to reason.name), signals)
            } finally { connection.close() }
        }
    }

    @Test fun localReleaseReportsItsReasonOnceWithoutAnotherLogicalEnd() = runBlocking {
        val room = FakeRoom()
        val signals = mutableListOf<Pair<NativeMediaStage, String?>>()
        val connection = NativeRoomConnection(this, { stage, reason -> signals += stage to reason }, room::release)
        connection.observe(room.events)
        connection.close(DisconnectReason.CLIENT_INITIATED)
        connection.close(DisconnectReason.CLIENT_INITIATED)
        room.events.emit(NativeRoomState.DISCONNECTED(DisconnectReason.CLIENT_INITIATED)); yield()
        assertEquals(0, room.releases)
        assertEquals(listOf(NativeMediaStage.ROOM_DISCONNECTED to "CLIENT_INITIATED"), signals)
    }

    @Test fun cancellationCleanupRetainsAnAlreadyEmittedReasonOrRecordsItsOwnRelease() = runBlocking {
        for (reason in listOf(null, DisconnectReason.JOIN_FAILURE, DisconnectReason.ROOM_DELETED)) {
            val room = FakeRoom()
            val signals = mutableListOf<Pair<NativeMediaStage, String?>>()
            val connection = NativeRoomConnection(this, { stage, name -> signals += stage to name }, room::release)
            connection.observe(room.events)
            // emit hands the event to the room collector, whose continuation is still queued.
            val emission = reason?.let { launch(start = CoroutineStart.UNDISPATCHED) {
                room.events.emit(NativeRoomState.DISCONNECTED(it))
            } }
            val cleanup = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { connection.closeForRelease() }
            }
            cleanup.cancelAndJoin(); emission?.join()
            connection.closeForRelease()
            assertEquals(listOf(NativeMediaStage.ROOM_DISCONNECTED to (reason ?: DisconnectReason.CLIENT_INITIATED).name), signals)
            assertEquals(if (reason == null) 0 else 1, room.releases)
        }
    }
}
