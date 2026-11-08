package expo.modules.callkittelecom.conversations

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeActionReceiptTest {
    @Test fun eachSupportedActionEmitsItsExactPayloadAfterTheSnapshot() = runBlocking {
        val actions = listOf(
            NativePerformedAction.Answer to mapOf("kind" to "answer"),
            NativePerformedAction.Start to mapOf("kind" to "start"),
            NativePerformedAction.End to mapOf("kind" to "end"),
            NativePerformedAction.Hold(true) to mapOf("kind" to "hold", "held" to true),
            NativePerformedAction.Mute(false) to mapOf("kind" to "mute", "muted" to false),
        )
        for ((origin, expectedOrigin) in listOf(NativeActionOrigin.APP to "app", NativeActionOrigin.SYSTEM to "system")) for ((action, expected) in actions) {
            val order = mutableListOf<String>()
            val receipt = NativeActionReceipt("raw/電話", origin, action)
            receipt.perform({ order += "effect" }, { order += "snapshot" }) { name, body ->
                order += "event"
                assertEquals("conversationEvent", name)
                assertEquals(mapOf("type" to "actionPerformed", "payload" to mapOf(
                    "conversationId" to "raw/電話", "origin" to expectedOrigin, "action" to expected)), body)
            }
            assertEquals(listOf("effect", "snapshot", "event"), order)
        }
    }

    @Test fun aReentrantDuplicateCannotRepeatAnEffectOrEmitTwice() = runBlocking {
        val order = mutableListOf<String>()
        val receipt = NativeActionReceipt("call", NativeActionOrigin.APP, NativePerformedAction.End)
        receipt.perform({ order += "effect" }, {
            order += "snapshot"
            runBlocking { receipt.perform({ fail("Duplicate effect") }, { fail("Duplicate snapshot") }) { _, _ -> fail("Duplicate event") } }
        }) { _, _ -> order += "event" }
        receipt.perform({ fail("Repeated effect") }, { fail("Repeated snapshot") }) { _, _ -> fail("Repeated event") }
        assertEquals(listOf("effect", "snapshot", "event"), order)
    }

    @Test fun failureOrCancellationNeverPublishesAnActionThatWasNotPerformed() = runBlocking {
        val failure = IllegalStateException("native failure")
        val receipt = NativeActionReceipt("failed", NativeActionOrigin.SYSTEM, NativePerformedAction.Answer)
        assertSame(failure, runCatching {
            receipt.perform({ throw failure }, { fail("Failed snapshot") }) { _, _ -> fail("Failed event") }
        }.exceptionOrNull())
        val entered = CompletableDeferred<Unit>()
        val cancelled = NativeActionReceipt("cancelled", NativeActionOrigin.APP, NativePerformedAction.Hold(true))
        val pending = launch(start = CoroutineStart.UNDISPATCHED) {
            cancelled.perform({ entered.complete(Unit); awaitCancellation() }, { fail("Cancelled snapshot") }) { _, _ -> fail("Cancelled event") }
        }
        entered.await(); pending.cancelAndJoin()
        val observerCancelled = NativeActionReceipt("observer", NativeActionOrigin.APP, NativePerformedAction.Mute(true))
        val publication = launch(start = CoroutineStart.UNDISPATCHED) {
            observerCancelled.perform({}, { cancel() }) { _, _ -> fail("Event after cancelled publication") }
        }
        publication.join()
    }
}
