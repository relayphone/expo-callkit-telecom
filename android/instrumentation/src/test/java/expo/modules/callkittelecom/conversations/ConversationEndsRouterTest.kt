package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ConversationEndsRouterTest {
    @Test fun routesServerIdentityAndEverySupportedReasonToTheRemoteEndPath() {
        val payloads = mutableListOf<Map<String, Any?>>()
        val router = ConversationEndsRouter { payloads += it }
        val reasons = listOf("remoteEnded", "answeredElsewhere", "declinedElsewhere", "unanswered", "failed")
        reasons.forEach { router.report(" server-call ", it) }
        assertEquals(reasons, payloads.map { NativeCallEnded.wire(it)!!.reason })
        assertTrue(payloads.all { it["serverCallId"] == " server-call " })
        assertEquals(1, payloads.map { it["eventId"] }.distinct().size)
    }

    @Test fun unsupportedReportedReasonsBecomeFailedWithoutChangingPushDefaults() {
        val payloads = mutableListOf<Map<String, Any?>>()
        val router = ConversationEndsRouter { payloads += it }
        for (reason in listOf("", "local", "declined", "unknown")) router.report("server", reason)
        assertEquals(List(4) { "failed" }, payloads.map { NativeCallEnded.wire(it)!!.reason })
        assertEquals("remoteEnded", NativeCallEnded.wire(mapOf("serverCallId" to "server", "eventId" to "push"))!!.reason)
    }

    @Test fun reportAndPushEndsShareRetainedOwnershipInEitherOrder() = runBlocking {
        for (pushFirst in listOf(false, true)) {
            val intake = NativeEndedIntake { 0 }
            val entry = intake.reserve("server", "native-conversation")
            intake.admitted(entry); intake.settled(entry)
            val matched = mutableListOf<NativeEndedIntake.Entry>()
            fun consume(raw: Map<String, Any?>, onConsumed: () -> Unit) {
                NativeCallEnded.wire(raw)?.let { intake.accept(it, onConsumed) }?.let { matched += it }
            }
            val router = ConversationEndsRouter { consume(it) {} }
            val push = NativeCallPush({ error("No invitation") }, ::consume, { false }, { false }, {}, { _, _, _, _ -> })
            val end = DecodedCallPush.Ended(EndedCallPush("server", "push-end", "answeredElsewhere"))
            if (pushFirst) push.handle(end, true) else router.report("server", "answeredElsewhere")
            assertEquals("answeredElsewhere", entry.intent!!.payload.reason)
            intake.removed(entry)
            repeat(2) {
                router.report("server", "answeredElsewhere")
                push.handle(end, true)
            }
            assertEquals(2, matched.size) // One event identity from each source.
            assertTrue(matched.all { it === entry })
            assertFalse(entry.live); assertFalse(entry.pending)
            assertTrue(intake.current(entry))
            assertEquals("native-conversation", entry.id)
            assertEquals("answeredElsewhere", entry.intent!!.payload.reason)
        }
    }

    @Test fun reportsMatchPendingServerOwnershipAndUnknownReportsCannotClaimAFutureCall() {
        val intake = NativeEndedIntake { 0 }
        val router = ConversationEndsRouter { raw -> NativeCallEnded.wire(raw)?.let { intake.accept(it) } }
        router.report("unknown", "unanswered")
        assertNull(intake.reserve("unknown", "later-native").intent)
        val pending = intake.reserve("server", "native-conversation")
        router.report("server", "declinedElsewhere")
        assertTrue(pending.pending); assertFalse(pending.admitted)
        assertEquals("declinedElsewhere", pending.intent!!.payload.reason)
        intake.admitted(pending); intake.settled(pending)
        assertEquals("declinedElsewhere", pending.intent!!.payload.reason)
    }
}
