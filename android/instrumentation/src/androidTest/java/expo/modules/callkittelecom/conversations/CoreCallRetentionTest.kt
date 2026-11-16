package expo.modules.callkittelecom.conversations

import android.Manifest
import android.net.Uri
import android.telecom.DisconnectCause
import androidx.core.telecom.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises only supported Core-Telecom controls after the addCall lifetime has closed. */
@RunWith(AndroidJUnit4::class)
class CoreCallRetentionTest {
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

    @Test fun retainedScopeCanDisconnectAfterItsAdmissionJobIsCancelled() = runBlocking {
        withContext(Dispatchers.Main) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val manager = CallsManager(context).apply { registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE,
                backwardsCompatSdkLevel = CORE_LEGACY_THROUGH_SDK) }
            val ready = CompletableDeferred<CallControlScope>()
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val admission = owner.launch {
                manager.addCall(CallAttributesCompat("Retained control", Uri.parse("sip:retained"),
                    CallAttributesCompat.DIRECTION_OUTGOING), {}, {}, {}, {}) { ready.complete(this) }
            }
            var controls: CallControlScope? = null
            try {
                controls = withTimeout(6000) { ready.await() }
                assertTrue(controls.setActive() is CallControlResult.Success)
                assertTrue(TelecomEvidence.calls().any { it.contains("state=ACTIVE") })
                admission.cancelAndJoin()
                assertFalse(controls.isActive)
                assertTrue("The cancelled lifetime still has an ACTIVE platform call",
                    TelecomEvidence.calls().any { it.contains("state=ACTIVE") })
                val result = withTimeout(6000) { controls.disconnect(DisconnectCause(DisconnectCause.LOCAL)) }
                assertTrue("Retained public disconnect result: $result", result is CallControlResult.Success ||
                    result is CallControlResult.Error && result.errorCode == CallException.ERROR_CALL_IS_NOT_BEING_TRACKED)
                withTimeout(5000) { while (TelecomEvidence.calls().isNotEmpty()) delay(20) }
            } finally {
                withContext(NonCancellable) { controls?.disconnect(DisconnectCause(DisconnectCause.LOCAL)) }
                owner.cancel()
            }
        }
    }
}
