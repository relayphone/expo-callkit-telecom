package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal enum class ColdPushStage(val value: String) {
    PROCESS_STARTED("processStarted"), INTAKE("intake"), RINGING("ringing"),
    PRESENTED("presented"), ANSWERED("answered"), CLEANED("cleaned"), FAILED("failed")
}

/** Drives the real intake and notification controls; injected steps also qualify fatal controls on the JVM. */
internal class ColdPushJourney(private val nowNanos: () -> Long,
    private val record: (PushFaultEvent.ColdStart) -> Unit) {
    suspend fun run(request: PushFaultDelivery, processStartedNanos: Long,
        intake: () -> Boolean, ringing: suspend () -> Unit, presented: suspend () -> Unit,
        answer: suspend () -> Unit, cleanup: suspend () -> Unit) {
        fun stage(value: ColdPushStage, at: Long = nowNanos()) =
            record(PushFaultEvent.ColdStart(request, value, at))
        var failed = false
        try {
            stage(ColdPushStage.PROCESS_STARTED, processStartedNanos)
            stage(ColdPushStage.INTAKE)
            check(intake()) { "Cold push was not recognized" }
            ringing(); stage(ColdPushStage.RINGING)
            presented(); stage(ColdPushStage.PRESENTED)
            answer(); stage(ColdPushStage.ANSWERED)
        } catch (error: Throwable) {
            failed = true
            stage(ColdPushStage.FAILED)
            throw error
        } finally {
            withContext(NonCancellable) {
                try { cleanup(); stage(ColdPushStage.CLEANED) }
                catch (error: Throwable) {
                    stage(ColdPushStage.FAILED)
                    if (!failed) throw error
                }
            }
        }
    }
}
