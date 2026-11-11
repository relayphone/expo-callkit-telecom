package expo.modules.callkittelecom.conversations

import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.*
import org.junit.Test

class CoreCallLifecycleTest {
    @Test fun firstMuteAndRepeatedBaselineAreNotChanges() = runBlocking {
        val updates = MutableSharedFlow<Boolean>()
        val observed = mutableListOf<Boolean>()
        val collection = launch(start = CoroutineStart.UNDISPATCHED) { updates.coreMuteChanges().collect { observed += it } }
        try {
            for (value in listOf(false, false, true, true, false)) { updates.emit(value); yield() }
            assertEquals(listOf(true, false), observed)
        } finally { collection.cancelAndJoin() }
    }

    @Test fun retriesOnceAfterDelayAndSharesOverlappingRequests() = runBlocking {
        val retry = CompletableDeferred<Unit>()
        val releaseRetry = CompletableDeferred<Unit>()
        val disconnect = CoreCallDisconnect(this) { retry.complete(Unit); releaseRetry.await() }
        var requests = 0
        val request: suspend () -> CallControlResult = {
            if (++requests == 1) CallControlResult.Error(CallException.ERROR_UNKNOWN) else CallControlResult.Success()
        }
        val first = async(start = CoroutineStart.UNDISPATCHED) { disconnect.remove(request) }
        retry.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { disconnect.remove(request) }
        assertEquals(1, requests)
        releaseRetry.complete(Unit)
        assertEquals(listOf(CoreDisconnectOutcome.REMOVED, CoreDisconnectOutcome.REMOVED), awaitAll(first, second))
        assertEquals(CoreDisconnectOutcome.REMOVED, disconnect.remove { error("Already removed") })
        assertEquals(2, requests)
    }

    @Test fun givesUpAfterTwoFailuresIncludingThrownErrors() = runBlocking {
        var requests = 0
        var delays = 0
        val disconnect = CoreCallDisconnect(this) { delays++ }
        assertEquals(CoreDisconnectOutcome.GAVE_UP, disconnect.remove {
            requests++
            if (requests == 1) error("Core operation failed")
            CallControlResult.Error(CallException.ERROR_UNKNOWN)
        })
        assertEquals(2, requests); assertEquals(1, delays)
    }

    @Test fun notTrackedIsAlreadyRemovedWithoutRetry() = runBlocking {
        for (throws in listOf(false, true)) {
            var requests = 0
            val disconnect = CoreCallDisconnect(this) { fail("Already removed must not wait or retry") }
            assertEquals(CoreDisconnectOutcome.REMOVED, disconnect.remove {
                requests++
                if (throws) throw CallException(CallException.ERROR_CALL_IS_NOT_BEING_TRACKED)
                CallControlResult.Error(CallException.ERROR_CALL_IS_NOT_BEING_TRACKED)
            })
            assertEquals(1, requests)
        }
    }

    @Test fun cancellingOneCallerDoesNotCancelSharedTeardownOrSendAnotherRequest() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val disconnect = CoreCallDisconnect(this)
        var requests = 0
        val request: suspend () -> CallControlResult = { requests++; entered.complete(Unit); release.await(); CallControlResult.Success() }
        val first = async(start = CoroutineStart.UNDISPATCHED) { disconnect.remove(request) }
        entered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { disconnect.remove(request) }
        first.cancelAndJoin()
        assertFalse(second.isCompleted)
        release.complete(Unit)
        assertEquals(CoreDisconnectOutcome.REMOVED, second.await())
        assertEquals(CoreDisconnectOutcome.REMOVED, disconnect.remove(request))
        assertEquals(1, requests)
    }
}
