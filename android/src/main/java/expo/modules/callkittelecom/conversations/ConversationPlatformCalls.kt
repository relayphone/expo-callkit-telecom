package expo.modules.callkittelecom.conversations

import android.content.Context
import java.util.UUID
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** A missing process-owned removal handle is not proof that Telecom has removed a call. */
enum class PlatformCallReconcileOutcome { REMOVED, STILL_REMAINING, NO_SUCH_CALL }

/** Confirms removal after the initial deadline. The platform UUID distinguishes reused app IDs. */
data class PlatformCallRemoved(val conversationId: String, val platformCallId: UUID, val endedAt: Instant?)

/** Supported Core-Telecom cleanup for logically ended calls, retained while this process lives. */
object ConversationPlatformCalls {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val updates = MutableSharedFlow<PlatformCallRemoved>(extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Collect before ending a call. This event stream does not replay historical removals. */
    val removed: SharedFlow<PlatformCallRemoved> = updates.asSharedFlow()

    /**
     * Retry an ended call's platform removal, including an end still in flight. Safe from any
     * thread. Concurrent requests share work; repeated failures are limited to one manual attempt
     * per ten seconds. Waiting takes at most five seconds and never cancels accepted cleanup.
     * A live replacement with the same app ID is never disconnected. After process restart,
     * Core-Telecom 1.0.1 cannot restore the old control handle: this returns [PlatformCallReconcileOutcome.NO_SUCH_CALL].
     */
    @JvmStatic suspend fun reconcile(context: Context, conversationId: String): PlatformCallReconcileOutcome {
        val application = context.applicationContext
        return scope.async { ConversationEngine.get(application).reconcilePlatformCall(conversationId) }.await()
    }

    internal fun didRemove(update: PlatformCallRemoved) { updates.tryEmit(update) }
}
