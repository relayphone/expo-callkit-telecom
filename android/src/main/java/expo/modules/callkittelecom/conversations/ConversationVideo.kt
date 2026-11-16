package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Mirrors the compatibility bound passed to registerAppWithTelecom, never internal session types. */
internal data class ConversationTelecomRegistration(val backwardsCompatSdkLevel: Int = CORE_LEGACY_THROUGH_SDK) {
    fun supportsVideo(sdk: Int): Boolean = sdk <= backwardsCompatSdkLevel.coerceIn(33, sdk.coerceAtLeast(33)) || sdk >= 35
}

internal enum class VideoChangeSource(val wire: String) { APP("app"), SERVER("server"), SYSTEM("system") }

/** Main-confined state; one Core collector observes while app/server mutations share a mutex. */
internal class ConversationVideo(
    initialVideo: Boolean,
    private val requireLive: () -> Unit,
    private val requireSupported: () -> Unit,
    private val changed: (Boolean, VideoChangeSource) -> Unit,
) {
    private data class Request(val enabled: Boolean, val source: VideoChangeSource,
                               val applied: CompletableDeferred<Unit> = CompletableDeferred())
    private val mutations = Mutex()
    private val initial = CompletableDeferred<Unit>()
    private var pending: Request? = null
    private val restorations = mutableSetOf<Job>()
    var isVideo: Boolean = initialVideo
        private set
    val route = VideoUpgradeRoute()

    suspend fun set(enabled: Boolean, source: VideoChangeSource, request: suspend () -> Unit,
                    restoreReceiver: suspend () -> Unit) = mutations.withLock {
        requireLive()
        requireSupported()
        withTimeout(5000) {
            initial.await()
            requireLive()
            if (isVideo == enabled) return@withTimeout
            val current = Request(enabled, source)
            pending = current
            if (enabled) route.upgrading()
            var applied = false
            try {
                request()
                current.applied.await()
                requireLive()
                applied = true
                if (!enabled && route.takeRestore()) restoreReceiver()
            } finally {
                if (pending === current) pending = null
                if (!applied) route.cancelUpgrade()
            }
        }
    }

    suspend fun restoreFromSystem(restoreReceiver: suspend () -> Unit) = coroutineScope {
        val restoration = coroutineContext.job
        restorations += restoration
        try {
            mutations.withLock {
                requireLive()
                if (!isVideo && route.takeRestore()) restoreReceiver()
            }
        } finally { restorations -= restoration }
    }

    /** Initial and same-value emissions carry no change event. Matching echoes retain their source. */
    fun observed(enabled: Boolean): Boolean {
        if (!initial.isCompleted) { initial.complete(Unit); return false }
        if (isVideo == enabled) return false
        val request = pending?.takeIf { it.enabled == enabled && !it.applied.isCompleted }
        if (enabled && request == null) route.upgrading()
        isVideo = enabled
        changed(enabled, request?.source ?: VideoChangeSource.SYSTEM)
        request?.applied?.complete(Unit)
        return !enabled && request == null
    }

    fun close() {
        val error = ConversationFailure("invalid_arguments")
        initial.completeExceptionally(error)
        pending?.applied?.completeExceptionally(error)
        restorations.toList().forEach { it.cancel() }
        route.selected()
    }
}

/** Only an observed receiver-to-speaker transition belonging to an upgrade permits restoration. */
internal class VideoUpgradeRoute {
    enum class Kind { RECEIVER, SPEAKER, PRIVATE, OTHER }
    data class Endpoint(val id: String, val kind: Kind)
    private var endpoint: Endpoint? = null
    private var awaitingSpeaker = false
    private var automaticSpeaker: Endpoint? = null
    fun upgrading() { awaitingSpeaker = endpoint == null || endpoint?.kind == Kind.RECEIVER; automaticSpeaker = null }
    fun cancelUpgrade() { awaitingSpeaker = false }
    fun selected() { awaitingSpeaker = false; automaticSpeaker = null }
    fun observed(value: Endpoint): Boolean {
        if (value == endpoint) return false
        if (awaitingSpeaker && endpoint == null && value.kind == Kind.RECEIVER) {
            endpoint = value
            return false
        }
        automaticSpeaker = value.takeIf { awaitingSpeaker && endpoint?.kind == Kind.RECEIVER && it.kind == Kind.SPEAKER }
        awaitingSpeaker = false
        endpoint = value
        return automaticSpeaker != null
    }
    fun takeRestore(): Boolean {
        val restore = automaticSpeaker != null && automaticSpeaker == endpoint
        automaticSpeaker = null
        // Core's upgrade endpoint callback can arrive after the type has already downgraded.
        // Keep that correlation until a route change or explicit selection settles it.
        if (restore) awaitingSpeaker = false
        return restore
    }
}
