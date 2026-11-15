package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import org.junit.Assert.*
import org.junit.Test

class SystemCommandOutcomesTest {
    private val performed = ConversationCommandOutcome.Performed("call", ConversationCommandStatus.HELD)

    @Test fun hasNoReplayAndBroadcastsTheSameTypedSettlementToActiveCollectors() = runBlocking {
        val stream = SystemCommandOutcomes()
        stream.observe("old", SystemCommandAction.END, SystemCommandSource.NOTIFICATION, CompletableDeferred(performed))
        assertTrue(stream.flow.replayCache.isEmpty())
        val first = async(start = CoroutineStart.UNDISPATCHED) { stream.flow.first() }
        val second = async(start = CoroutineStart.UNDISPATCHED) { stream.flow.first() }
        assertFalse(first.isCompleted); assertFalse(second.isCompleted)
        stream.observe("call", SystemCommandAction.HOLD, SystemCommandSource.TELECOM, CompletableDeferred(performed))
        val expected = SystemCommandOutcome("call", SystemCommandAction.HOLD, SystemCommandSource.TELECOM, performed)
        assertEquals(expected, withTimeout(1000) { first.await() })
        assertEquals(expected, withTimeout(1000) { second.await() })
        assertTrue(stream.flow.replayCache.isEmpty())
    }

    @Test fun refusalAndFailureKeepTheirExactNormalizedOutcomes() = runBlocking {
        val stream = SystemCommandOutcomes()
        for (outcome in listOf(ConversationCommandOutcome.Refused("exact reason"),
            ConversationCommandOutcome.Failed(ConversationCommandCode.HANDLER_FAILED, "Conversation operation failed"))) {
            val observed = async(start = CoroutineStart.UNDISPATCHED) { stream.flow.first() }
            assertSame(outcome, stream.observe("call", SystemCommandAction.ANSWER, SystemCommandSource.NOTIFICATION,
                CompletableDeferred(outcome)))
            assertSame(outcome, withTimeout(1000) { observed.await() }.outcome)
        }
    }

    @Test fun slowCollectorsHaveABoundedBufferAndNeverBlockSystemSettlement() = runBlocking {
        val stream = SystemCommandOutcomes()
        val gate = CompletableDeferred<Unit>()
        val received = mutableListOf<String>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            stream.flow.take(SYSTEM_COMMAND_OUTCOME_BUFFER_CAPACITY + 1).collect {
                received += it.conversationId
                gate.await()
            }
        }
        try {
            stream.observe("0", SystemCommandAction.MUTE, SystemCommandSource.TELECOM, CompletableDeferred(performed))
            yield()
            assertEquals(listOf("0"), received)
            withTimeout(1000) {
                for (id in 1..SYSTEM_COMMAND_OUTCOME_BUFFER_CAPACITY + 3)
                    stream.observe("$id", SystemCommandAction.MUTE, SystemCommandSource.TELECOM, CompletableDeferred(performed))
            }
            gate.complete(Unit)
            withTimeout(1000) { collector.join() }
            assertEquals(listOf("0") + (4..SYSTEM_COMMAND_OUTCOME_BUFFER_CAPACITY + 3).map { "$it" }, received)
        } finally { gate.complete(Unit); collector.cancelAndJoin() }
    }

    @Test fun overallDeadlineEmitsOnceAndLeavesTheAcceptedCommandRunning() = runBlocking {
        val stream = SystemCommandOutcomes()
        val release = CompletableDeferred<Unit>()
        val accepted = async { release.await(); performed as ConversationCommandOutcome }
        val received = mutableListOf<SystemCommandOutcome>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { stream.flow.collect { received += it } }
        try {
            val result = stream.observe("call", SystemCommandAction.HOLD, SystemCommandSource.TELECOM, accepted, timeoutMillis = 10)
            yield()
            assertEquals(CommandRejection.deadlineExceeded().outcome, result)
            assertEquals(listOf(SystemCommandOutcome("call", SystemCommandAction.HOLD, SystemCommandSource.TELECOM, result)), received)
            assertTrue(accepted.isActive)
            release.complete(Unit); accepted.await(); yield()
            assertEquals(1, received.size)
        } finally { release.complete(Unit); collector.cancelAndJoin() }
    }
}
