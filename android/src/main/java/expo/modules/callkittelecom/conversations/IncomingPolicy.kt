package expo.modules.callkittelecom.conversations

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class NativeIncomingFilterReason(val value: String) {
    DO_NOT_DISTURB("doNotDisturb"), BLOCK_LIST("blockList"), OTHER("other")
}
data class NativeIncomingFilter(val reason: NativeIncomingFilterReason, val nativeReason: String? = null)

/** Register a local native block list/other policy during Application.onCreate; never wait for JS. */
object NativeIncomingFilters {
    @Volatile var evaluate: (suspend (Map<String, Any?>) -> NativeIncomingFilter?)? = null
}

sealed interface NativeIncomingResult {
    data class Reported(val id: String) : NativeIncomingResult
    data class Filtered(val filter: NativeIncomingFilter) : NativeIncomingResult
    data object AtCapacity : NativeIncomingResult
    data class Failed(val code: String, val nativeCode: String?) : NativeIncomingResult
}
data class NativeIncomingOutcome(val eventId: String, val serverCallId: String, val result: NativeIncomingResult)
fun interface NativeIncomingObserver { fun onOutcome(outcome: NativeIncomingOutcome) }

/** Main-thread native hook; backend decline/AI screening does not depend on a live JS bridge. */
object NativeIncomingOutcomes {
    val observers = CopyOnWriteArraySet<NativeIncomingObserver>()
    internal fun emit(payload: Map<String, Any?>, outcome: Map<String, Any?>) {
        val eventId = payload["eventId"] as? String ?: return
        val serverId = payload["serverCallId"] as? String ?: return
        val result = when (outcome["status"]) {
            "reported" -> NativeIncomingResult.Reported(outcome["id"] as String)
            "filtered" -> NativeIncomingResult.Filtered(NativeIncomingFilter(
                NativeIncomingFilterReason.entries.firstOrNull { it.value == outcome["reason"] } ?: NativeIncomingFilterReason.OTHER,
                outcome["nativeReason"] as? String))
            "atCapacity" -> NativeIncomingResult.AtCapacity
            else -> {
                val error = outcome["error"] as? Map<*, *>
                NativeIncomingResult.Failed(error?.get("code") as? String ?: "conversation", error?.get("nativeCode") as? String)
            }
        }
        val value = NativeIncomingOutcome(eventId, serverId, result)
        observers.forEach { observer -> runCatching { observer.onOutcome(value) } }
    }
}

internal class IncomingPolicy(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    suspend fun custom(payload: Map<String, Any?>): NativeIncomingFilter? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(750) { runCatching { NativeIncomingFilters.evaluate?.invoke(payload.filterKeys { it != "media" }) }.getOrNull() }
    }
    suspend fun evaluate(payload: Map<String, Any?>): NativeIncomingFilter? = custom(payload) ?: system(payload)
    @Suppress("UNCHECKED_CAST")
    suspend fun system(payload: Map<String, Any?>): NativeIncomingFilter? = withContext(Dispatchers.IO) {
        val mode = manager.currentInterruptionFilter
        if (mode == NotificationManager.INTERRUPTION_FILTER_NONE || mode == NotificationManager.INTERRUPTION_FILTER_ALARMS)
            return@withContext NativeIncomingFilter(NativeIncomingFilterReason.DO_NOT_DISTURB, "android.interruptionFilter.$mode")
        if (mode != NotificationManager.INTERRUPTION_FILTER_PRIORITY || Build.VERSION.SDK_INT < 33) return@withContext null
        // A user-enabled bypass channel may ring even when the caller filter says no.
        if (manager.getNotificationChannel(ConversationService.incomingChannelId(context))?.canBypassDnd() == true)
            return@withContext null
        val caller = payload["caller"] as? Map<String, Any?> ?: return@withContext null
        val uri = callerAddress(context, caller) ?: return@withContext null
        // Public API requires READ_CONTACTS or notification-listener access. When unavailable,
        // leave presentation to Android rather than inventing a filtered outcome.
        val permitted = try { manager.matchesCallFilter(uri) } catch (_: SecurityException) { return@withContext null }
        if (permitted) null else NativeIncomingFilter(NativeIncomingFilterReason.DO_NOT_DISTURB, "android.matchesCallFilter")
    }
}
