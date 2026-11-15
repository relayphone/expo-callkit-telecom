package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class MediaPermissionsTest {
    private class Access : MediaPermissionAccess {
        var foreground = true
        var reads = 0
        val asked = mutableListOf<List<MediaKind>>()
        val permissions = MediaKind.entries.associateWith {
            MediaPermissionResponse(MediaPermissionStatus.UNDETERMINED, true)
        }.toMutableMap()
        var onRead: () -> Unit = {}
        var onRequest: suspend () -> Unit = {}
        override fun isForeground() = foreground
        override suspend fun read(): Map<MediaKind, MediaPermissionResponse> {
            reads++; onRead(); return permissions.toMap()
        }
        override suspend fun request(kinds: List<MediaKind>) {
            asked += kinds; onRequest()
            kinds.forEach { permissions[it] = MediaPermissionResponse(MediaPermissionStatus.GRANTED, true) }
        }
    }

    @Test fun everyExpoStatusHasTheSharedPermissionResponseShape() = runBlocking {
        val access = Access()
        val api = MediaPermissions(access)
        for ((status, canAskAgain) in listOf(MediaPermissionStatus.UNDETERMINED to true,
            MediaPermissionStatus.GRANTED to true, MediaPermissionStatus.DENIED to true, MediaPermissionStatus.DENIED to false)) {
            for (kind in MediaKind.entries) access.permissions[kind] = MediaPermissionResponse.from(
                status == MediaPermissionStatus.GRANTED, status, canAskAgain)
            val expected = mapOf("status" to status.value, "granted" to (status == MediaPermissionStatus.GRANTED),
                "canAskAgain" to canAskAgain, "expires" to "never")
            assertEquals(mapOf("microphone" to expected, "camera" to expected), api.get())
        }
        assertTrue(access.asked.isEmpty())
        assertEquals(MediaPermissionResponse(MediaPermissionStatus.GRANTED, true),
            MediaPermissionResponse.from(true, MediaPermissionStatus.DENIED, false))
        val rejection = CommandRejection.from(ConversationFailure("not_foreground"))
        assertEquals(ConversationCommandCode.NOT_FOREGROUND,
            (rejection.outcome as ConversationCommandOutcome.Failed).code)
    }

    @Test fun requestsOnlyDistinctRequestedUngrantedsThatCanStillBeAskedAndReturnsBothKinds() = runBlocking {
        val access = Access()
        val api = MediaPermissions(access)
        access.permissions[MediaKind.CAMERA] = MediaPermissionResponse(MediaPermissionStatus.DENIED, false)
        val response = api.request(listOf("microphone", "microphone", "camera"))
        assertEquals(listOf(listOf(MediaKind.MICROPHONE)), access.asked)
        assertEquals(true, response.getValue("microphone")["granted"])
        assertEquals(false, response.getValue("camera")["canAskAgain"])
        api.request(listOf("microphone", "camera"))
        api.request(emptyList())
        assertEquals(1, access.asked.size)
        access.permissions[MediaKind.CAMERA] = MediaPermissionResponse(MediaPermissionStatus.DENIED, true)
        api.request(listOf("camera"))
        assertEquals(listOf(MediaKind.CAMERA), access.asked.last())
    }

    @Test fun requestsRejectOutsideForegroundEvenWhenNothingWouldPrompt() = runBlocking {
        val access = Access().apply { foreground = false }
        val api = MediaPermissions(access)
        for (kinds in listOf(emptyList(), listOf("microphone"), listOf("camera"))) {
            val error = runCatching { api.request(kinds) }.exceptionOrNull() as ConversationFailure
            assertEquals("not_foreground", error.code)
        }
        assertEquals(0, access.reads)
        assertTrue(access.asked.isEmpty())
        api.get() // Status reads remain available without an Activity.
        assertEquals(listOf("microphone"), api.readiness("audio") { false }["missing"])
    }

    @Test fun foregroundIsRecheckedImmediatelyBeforeRequestingAndInvalidKindsNeverPrompt() = runBlocking {
        val access = Access()
        val api = MediaPermissions(access)
        val invalid = runCatching { api.request(listOf("microphone", "location")) }.exceptionOrNull() as ConversationFailure
        assertEquals("invalid_arguments", invalid.code)
        access.onRead = { access.foreground = false }
        val background = runCatching { api.request(listOf("microphone")) }.exceptionOrNull() as ConversationFailure
        assertEquals("not_foreground", background.code)
        assertTrue(access.asked.isEmpty())
    }

    @Test fun concurrentRequestsReadFreshStatusAfterThePreviousPrompt() = runBlocking {
        val access = Access()
        val api = MediaPermissions(access)
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        access.onRequest = { entered.complete(Unit); finish.await() }
        val first = async { api.request(listOf("microphone")) }
        entered.await()
        val second = async { api.request(listOf("microphone")) }
        yield()
        assertEquals(1, access.asked.size)
        finish.complete(Unit)
        assertEquals(first.await(), second.await())
        assertEquals(1, access.asked.size)
    }

    @Test fun readinessUsesConversationVideoStateWithoutPromptingAndRejectsUnknownIds() = runBlocking {
        val access = Access().apply { foreground = false }
        val api = MediaPermissions(access)
        val conversations = mapOf("audio" to false, "video" to true)
        assertEquals(mapOf("required" to listOf("microphone"), "missing" to listOf("microphone")),
            api.readiness("audio", conversations::get))
        assertEquals(mapOf("required" to listOf("microphone", "camera"), "missing" to listOf("microphone", "camera")),
            api.readiness("video", conversations::get))
        access.permissions[MediaKind.MICROPHONE] = MediaPermissionResponse(MediaPermissionStatus.GRANTED, true)
        assertEquals(emptyList<String>(), api.readiness("audio", conversations::get)["missing"])
        assertEquals(listOf("camera"), api.readiness("video", conversations::get)["missing"])
        access.permissions[MediaKind.CAMERA] = MediaPermissionResponse(MediaPermissionStatus.GRANTED, true)
        assertEquals(emptyList<String>(), api.readiness("video", conversations::get)["missing"])
        val reads = access.reads
        val error = runCatching { api.readiness("unknown", conversations::get) }.exceptionOrNull() as ConversationFailure
        assertEquals("unknown_conversation", error.code)
        assertEquals(reads, access.reads)
        assertTrue(access.asked.isEmpty())
    }
}
