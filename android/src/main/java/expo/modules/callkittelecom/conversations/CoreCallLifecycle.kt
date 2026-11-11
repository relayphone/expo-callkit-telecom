package expo.modules.callkittelecom.conversations

import android.telecom.DisconnectCause
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

internal data class CoreCallCallbacks(
    val answer: suspend (Int) -> Unit,
    val disconnect: suspend (DisconnectCause) -> Unit,
    val activate: suspend () -> Unit,
    val deactivate: suspend () -> Unit,
)

/** The Core session lifetime, including its non-suspending admission callback. */
internal fun interface CoreCallAdmission {
    suspend fun add(attributes: CallAttributesCompat, callbacks: CoreCallCallbacks, admitted: CallControlScope.() -> Unit)
}

/** The first observed value establishes Core's baseline, not the app's desired mute. */
internal fun Flow<Boolean>.coreMuteChanges(): Flow<Boolean> = distinctUntilChanged().drop(1)

internal const val PLATFORM_REMOVAL_DEADLINE_MILLIS = 60_000L
internal enum class CoreDisconnectOutcome { REMOVED, DEADLINE_EXPIRED }

internal fun coreRemovalBackoffMillis(attempt: Int): Long = when (attempt) {
    1 -> 100L; 2 -> 250L; 3 -> 500L; 4 -> 1000L; 5 -> 2000L; else -> 5000L
}

/** Main-confined removal ownership outlives both logical end and individual callers. */
internal class CoreCallDisconnect(private val scope: CoroutineScope,
                                  private val deadlineMillis: Long = PLATFORM_REMOVAL_DEADLINE_MILLIS) {
    private var completion: Deferred<CoreDisconnectOutcome>? = null
    private val retry = Channel<Unit>(Channel.CONFLATED)
    val started: Boolean get() = completion != null
    val pending: Boolean get() = completion?.isCompleted == false
    var attempts: Int = 0
        private set
    var lastErrorCode: Int = CallException.ERROR_UNKNOWN
        private set

    suspend fun awaitCompletion() { completion?.await() }
    fun retryNow() { if (pending) retry.trySend(Unit) }

    fun start(request: suspend () -> CallControlResult): Deferred<CoreDisconnectOutcome> {
        completion?.let { return it }
        val pending = scope.async(start = CoroutineStart.LAZY) {
            withTimeoutOrNull(deadlineMillis) {
              while (true) {
                attempts++
                val result = try { request() }
                catch (_: CancellationException) { currentCoroutineContext().ensureActive(); null }
                catch (error: CallException) { CallControlResult.Error(error.code) }
                catch (_: Exception) { null }
                if (result is CallControlResult.Success || result is CallControlResult.Error &&
                    result.errorCode == CallException.ERROR_CALL_IS_NOT_BEING_TRACKED) {
                    return@withTimeoutOrNull CoreDisconnectOutcome.REMOVED
                }
                lastErrorCode = (result as? CallControlResult.Error)?.errorCode ?: CallException.ERROR_UNKNOWN
                withTimeoutOrNull(coreRemovalBackoffMillis(attempts)) { retry.receive() }
              }
              @Suppress("UNREACHABLE_CODE") CoreDisconnectOutcome.REMOVED
            } ?: CoreDisconnectOutcome.DEADLINE_EXPIRED
        }
        completion = pending
        pending.start()
        return pending
    }

    suspend fun remove(request: suspend () -> CallControlResult): CoreDisconnectOutcome = start(request).await()
}
