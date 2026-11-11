package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.*
import org.junit.Test

class NativeRoomConnectionTest {
    private class FakeRoom {
        val events = MutableSharedFlow<NativeRoomState>()
        var releases = 0
        val released = CompletableDeferred<Unit>()
        fun release() { releases++; released.complete(Unit) }
    }

    @Test fun roomRecoversWithoutFailureAndDuplicateReconnectingDoesNotExtendTheDeadline() = runBlocking {
        val room = FakeRoom()
        val signals = mutableListOf<NativeMediaStage>()
        val waits = mutableListOf<CompletableDeferred<Unit>>()
        val connection = NativeRoomConnection(this, signals::add, room::release) { millis ->
            assertEquals(30_000L, millis)
            CompletableDeferred<Unit>().also { waits += it }.await()
        }
        connection.observe(room.events)
        try {
            room.events.emit(NativeRoomState.RECONNECTING); yield()
            room.events.emit(NativeRoomState.RECONNECTING); yield()
            assertEquals(1, waits.size)
            assertEquals(listOf(NativeMediaStage.RECONNECTING), signals)
            assertEquals(0, room.releases)
            room.events.emit(NativeRoomState.RECONNECTED); yield()
            waits.single().complete(Unit); yield()
            assertEquals(0, room.releases)
            assertEquals(listOf(NativeMediaStage.RECONNECTING, NativeMediaStage.RECONNECTED), signals)
            room.events.emit(NativeRoomState.RECONNECTING); yield()
            assertEquals(2, waits.size)
            waits.last().complete(Unit)
            withTimeout(1000) { room.released.await() }
            assertEquals(1, room.releases)
        } finally { connection.close() }
    }

    @Test fun finalDisconnectReleasesOnceAndEndingCancelsEveryRoomObservationAndTimer() = runBlocking {
        for (end in listOf(false, true)) {
            val room = FakeRoom()
            val signals = mutableListOf<NativeMediaStage>()
            val gate = CompletableDeferred<Unit>()
            val waiting = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val connection = NativeRoomConnection(this, signals::add, room::release) {
                waiting.complete(Unit)
                try { gate.await() } finally { cancelled.complete(Unit) }
            }
            connection.observe(room.events)
            room.events.emit(NativeRoomState.RECONNECTING)
            withTimeout(1000) { waiting.await() }
            if (end) connection.close() else room.events.emit(NativeRoomState.DISCONNECTED)
            withTimeout(1000) { cancelled.await() }
            gate.complete(Unit)
            room.events.emit(NativeRoomState.RECONNECTED)
            room.events.emit(NativeRoomState.DISCONNECTED); yield()
            assertEquals(if (end) 0 else 1, room.releases)
            assertEquals(listOf(NativeMediaStage.RECONNECTING), signals)
            assertEquals(0, room.events.subscriptionCount.value)
            connection.close()
        }
    }
}
