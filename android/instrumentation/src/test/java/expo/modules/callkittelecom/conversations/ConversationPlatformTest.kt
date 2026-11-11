package expo.modules.callkittelecom.conversations

import org.junit.Assert.*
import org.junit.Test

class ConversationPlatformTest {
    private class State(
        override var sdkInt: Int = 35,
        override var ignoresBatteryOptimizations: Boolean = false,
        var restricted: Boolean = false,
    ) : BackgroundRestrictionState {
        override val backgroundRestricted: Boolean get() {
            check(sdkInt >= 28) { "Background restriction is unavailable before API 28" }
            return restricted
        }
    }

    @Test fun ordinaryAndExemptAppsReportTheirBatteryOptimizationPolicy() {
        for (sdk in listOf(26, 27, 28, 35)) {
            assertEquals(BackgroundRestriction.OPTIMIZED,
                ConversationPlatform.backgroundRestriction(State(sdk)))
            assertEquals(BackgroundRestriction.UNRESTRICTED,
                ConversationPlatform.backgroundRestriction(State(sdk, ignoresBatteryOptimizations = true)))
        }
    }

    @Test fun explicitRestrictionTakesPrecedenceEvenWhenTheAppIsExempt() {
        for (sdk in listOf(28, 35)) {
            for (exempt in listOf(false, true)) {
                assertEquals(BackgroundRestriction.RESTRICTED,
                    ConversationPlatform.backgroundRestriction(State(sdk, exempt, restricted = true)))
            }
        }
    }

    @Test fun android26And27NeverReadTheUnavailableRestrictionProperty() {
        for (sdk in listOf(26, 27)) {
            val state = State(sdk, restricted = true)
            assertEquals(BackgroundRestriction.OPTIMIZED, ConversationPlatform.backgroundRestriction(state))
            state.ignoresBatteryOptimizations = true
            assertEquals(BackgroundRestriction.UNRESTRICTED, ConversationPlatform.backgroundRestriction(state))
        }
    }

    @Test fun returningFromSettingsReadsTheChangedPolicyWithoutCaching() {
        val state = State()
        assertEquals(BackgroundRestriction.OPTIMIZED, ConversationPlatform.backgroundRestriction(state))
        state.ignoresBatteryOptimizations = true
        assertEquals(BackgroundRestriction.UNRESTRICTED, ConversationPlatform.backgroundRestriction(state))
        state.restricted = true
        assertEquals(BackgroundRestriction.RESTRICTED, ConversationPlatform.backgroundRestriction(state))
        state.ignoresBatteryOptimizations = false
        state.restricted = false
        assertEquals(BackgroundRestriction.OPTIMIZED, ConversationPlatform.backgroundRestriction(state))
    }
}
