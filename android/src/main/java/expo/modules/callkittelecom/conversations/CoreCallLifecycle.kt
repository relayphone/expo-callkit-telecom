package expo.modules.callkittelecom.conversations

import android.telecom.DisconnectCause
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallException
import kotlinx.coroutines.*
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

internal enum class CoreDisconnectOutcome { REMOVED, GAVE_UP }

/** One shared teardown per call. A failed request gets one delayed retry. */
internal class CoreCallDisconnect(private val scope: CoroutineScope, private val onGiveUp: () -> Unit = {},
                                  private val retryDelay: suspend () -> Unit = { delay(100) }) {
    private var completion: Deferred<CoreDisconnectOutcome>? = null
    val started: Boolean get() = completion != null

    suspend fun awaitCompletion() { completion?.await() }

    suspend fun remove(request: suspend () -> CallControlResult): CoreDisconnectOutcome {
        completion?.let { return it.await() }
        val pending = scope.async(start = CoroutineStart.LAZY) {
            repeat(2) { attempt ->
                val result = try { request() }
                catch (_: CancellationException) { currentCoroutineContext().ensureActive(); null }
                catch (error: CallException) { CallControlResult.Error(error.code) }
                catch (_: Exception) { null }
                if (result is CallControlResult.Success || result is CallControlResult.Error &&
                    result.errorCode == CallException.ERROR_CALL_IS_NOT_BEING_TRACKED) {
                    return@async CoreDisconnectOutcome.REMOVED
                }
                if (attempt == 0) retryDelay()
            }
            onGiveUp()
            CoreDisconnectOutcome.GAVE_UP
        }
        completion = pending
        pending.start()
        return pending.await()
    }
}
