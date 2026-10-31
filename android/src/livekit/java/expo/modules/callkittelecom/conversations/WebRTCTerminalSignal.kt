package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.*

/** Terminal signaling owns a bounded lifetime independent of local media disposal. */
internal object WebRTCTerminalSignal {
    fun send(serverId: String, url: String, token: String, values: Map<String, Any?>) {
        val snapshot = values.toMap()
        val lifetime = SupervisorJob()
        CoroutineScope(Dispatchers.IO + lifetime).launch {
            var delivered = 0L
            var httpErrorStatus = 0L
            var deadlineExpired = 0L
            try {
                withTimeout(3000) { NativeHttpCredentials().post(url, token, snapshot) }
                delivered = 1L
            } catch (_: TimeoutCancellationException) { deadlineExpired = 1L }
            catch (error: MediaCredentialsUnavailable) { httpErrorStatus = error.status.toLong() }
            catch (_: Exception) { /* Failure is reported numerically, without credentials. */ }
            finally {
                NativeMediaSignals.emit(serverId, NativeMediaStage.TERMINAL_SIGNAL_COMPLETED,
                    mapOf("delivered" to delivered, "httpErrorStatus" to httpErrorStatus, "deadlineExpired" to deadlineExpired))
                lifetime.cancel()
            }
        }
    }
}
