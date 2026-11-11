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

    @Test fun retriesAfterFailureAndSharesOverlappingRequests() = runBlocking {
        val retry = CompletableDeferred<Unit>()
        val releaseRetry = CompletableDeferred<Unit>()
        val disconnect = CoreCallDisconnect(this)
        var requests = 0
        val request: suspend () -> CallControlResult = {
            if (++requests == 1) { retry.complete(Unit); releaseRetry.await(); CallControlResult.Error(CallException.ERROR_UNKNOWN) }
            else CallControlResult.Success()
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

    @Test fun retriesThrownAndReturnedFailuresUntilConfirmedRemoval() = runBlocking {
        var requests = 0
        val disconnect = CoreCallDisconnect(this)
        assertEquals(CoreDisconnectOutcome.REMOVED, disconnect.remove {
            requests++
            if (requests == 1) error("Core operation failed")
            if (requests == 2) CallControlResult.Error(CallException.ERROR_UNKNOWN) else CallControlResult.Success()
        })
        assertEquals(3, requests)
    }

    @Test fun boundedBackoffKeepsRetryingAtFiveSeconds() {
        assertEquals(listOf(100L, 250L, 500L, 1000L, 2000L, 5000L, 5000L, 5000L),
            (1..8).map(::coreRemovalBackoffMillis))
        assertEquals(60_000L, PLATFORM_REMOVAL_DEADLINE_MILLIS)
    }

    @Test fun deadlineAlsoBoundsASuspendedRequestAndRetainsDiagnostics() = runBlocking {
        val disconnect = CoreCallDisconnect(this, deadlineMillis = 30)
        assertEquals(CoreDisconnectOutcome.DEADLINE_EXPIRED, disconnect.remove { awaitCancellation() })
        assertEquals(1, disconnect.attempts)
        assertEquals(CallException.ERROR_UNKNOWN, disconnect.lastErrorCode)
        assertFalse(disconnect.pending)
    }

    @Test fun deadlineRetainsTheLastCoreErrorCode() = runBlocking {
        val disconnect = CoreCallDisconnect(this, deadlineMillis = 30)
        assertEquals(CoreDisconnectOutcome.DEADLINE_EXPIRED, disconnect.remove { CallControlResult.Error(73) })
        assertEquals(1, disconnect.attempts)
        assertEquals(73, disconnect.lastErrorCode)
    }

    @Test fun notTrackedIsAlreadyRemovedWithoutRetry() = runBlocking {
        for (throws in listOf(false, true)) {
            var requests = 0
            val disconnect = CoreCallDisconnect(this)
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
