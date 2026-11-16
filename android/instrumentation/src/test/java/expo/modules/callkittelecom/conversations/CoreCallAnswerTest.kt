package expo.modules.callkittelecom.conversations

import android.os.ParcelUuid
import android.telecom.DisconnectCause
import androidx.core.telecom.*
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CoreCallAnswerTest {
    private class Control : CallControlScope {
        val answers = mutableListOf<Int>()
        var activations = 0
        override val coroutineContext: CoroutineContext = EmptyCoroutineContext
        override fun getCallId(): ParcelUuid = error("unused")
        override val currentCallEndpoint = emptyFlow<CallEndpointCompat>()
        override val availableEndpoints = emptyFlow<List<CallEndpointCompat>>()
        override val isMuted = emptyFlow<Boolean>()
        override suspend fun setActive(): CallControlResult { activations++; return CallControlResult.Success() }
        override suspend fun answer(callType: Int): CallControlResult { answers += callType; return CallControlResult.Success() }
        override suspend fun setInactive(): CallControlResult = error("unused")
        override suspend fun disconnect(cause: DisconnectCause): CallControlResult = error("unused")
        override suspend fun requestEndpointChange(endpoint: CallEndpointCompat): CallControlResult = error("unused")
    }
    @Test fun audioActivationAvoidsTheAffectedTransactionalAnswerArgument() = runBlocking {
        for (sdk in listOf(26, 33, 34, 35, 36, 37)) {
            val control = Control()
            assertTrue(answerCoreCall(control, video = false, sdkInt = sdk) is CallControlResult.Success)
            assertEquals(if (sdk in 35..36) 1 else 0, control.activations)
            assertEquals(if (sdk in 35..36) emptyList<Int>() else listOf(CallAttributesCompat.CALL_TYPE_AUDIO_CALL), control.answers)
        }
    }
    @Test fun videoStillUsesTheCoreVideoCallTypeOnEveryPath() = runBlocking {
        for (sdk in listOf(26, 33, 34, 35, 36, 37)) {
            val control = Control()
            assertTrue(answerCoreCall(control, video = true, sdkInt = sdk) is CallControlResult.Success)
            assertEquals(0, control.activations)
            assertEquals(listOf(CallAttributesCompat.CALL_TYPE_VIDEO_CALL), control.answers)
        }
    }
}
