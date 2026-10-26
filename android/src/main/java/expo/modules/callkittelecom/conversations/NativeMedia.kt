package expo.modules.callkittelecom.conversations

import android.content.Context
import io.livekit.android.*
import io.livekit.android.audio.NoAudioHandler
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.track.RemoteTrackPublication
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import org.json.JSONObject

/** Native application extension points. Register in Application.onCreate, before a push can arrive. */
interface NativeMediaSession {
    suspend fun prewarm(payload: Map<String, Any?>)
    suspend fun activate()
    suspend fun setMuted(muted: Boolean)
    suspend fun close(reason: String)
}
object NativeMediaRegistry {
    val fetchers = ConcurrentHashMap<String, suspend (Map<String, Any?>) -> Map<String, Any?>>()
    val factories = ConcurrentHashMap<String, (Context) -> NativeMediaSession>()
}

/** No microphone capture, remote subscription, focus or mode activation before Core-Telecom answers. */
class LiveKitNativeSession(private val context: Context, private val policy: JSONObject) : NativeMediaSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var room: io.livekit.android.room.Room? = null
    private var active = false
    private var muted = false
    private var closed = false

    @Suppress("UNCHECKED_CAST")
    override suspend fun prewarm(payload: Map<String, Any?>) {
        check(!closed)
        var credentials = if (policy.optString("credentials", "fromPush") == "fromPush")
            payload["media"] as? Map<String, Any?> else null
        val fresh = (credentials?.get("expiresAt") as? Number)?.toLong()?.let { it > System.currentTimeMillis() / 1000 } == true
        if (!fresh) {
            val source = policy.optJSONObject("credentials") ?: policy.optJSONObject("fallback")
            val fetcher = source?.optString("name")?.let { NativeMediaRegistry.fetchers[it] }
                ?: error("Native credentials fetcher unavailable")
            credentials = withTimeout(3000) { fetcher(payload) }
        }
        val media = credentials ?: error("Missing media credentials")
        require(media["provider"] == "livekit")
        require((media["expiresAt"] as? Number)?.toLong()?.let { it > System.currentTimeMillis() / 1000 } == true)
        val url = media["url"] as? String ?: error("Missing media URL")
        require(url.startsWith("wss://"))
        val token = media["token"] as? String ?: error("Missing media token")
        val candidate = LiveKit.create(context, overrides = LiveKitOverrides(audioOptions = AudioOptions(
            audioHandler = NoAudioHandler(), disableAudioPrewarming = true, disableCommunicationModeWorkaround = true)))
        try {
            candidate.connect(url, token, ConnectOptions(autoSubscribe = false, audio = false, video = false))
            check(!closed)
            require(candidate.name == media["room"])
            require(candidate.localParticipant.identity?.value == media["identity"])
            room = candidate
            scope.launch { candidate.events.collect { event ->
                if (event is RoomEvent.TrackPublished && active) (event.publication as? RemoteTrackPublication)?.setSubscribed(true)
            } }
            signal("prejoined")
        } catch (error: Throwable) { candidate.disconnect(); candidate.release(); throw error }
    }
    override suspend fun activate() {
        check(!closed)
        val connected = room ?: error("Native media not connected")
        active = true
        signal("answered")
        connected.remoteParticipants.values.forEach { participant ->
            participant.trackPublications.values.forEach { (it as? RemoteTrackPublication)?.setSubscribed(true) }
        }
        check(connected.localParticipant.setMicrophoneEnabled(!muted))
    }
    override suspend fun setMuted(muted: Boolean) {
        this.muted = muted
        if (active) check(room?.localParticipant?.setMicrophoneEnabled(!muted) == true)
    }
    private suspend fun signal(state: String) {
        val data = JSONObject(mapOf("state" to state)).toString()
        room?.localParticipant?.updateMetadata(data)
        room?.localParticipant?.publishData(data.toByteArray(Charsets.UTF_8), topic = "voip-sdk.call")?.getOrThrow()
    }
    override suspend fun close(reason: String) {
        if (closed) return
        closed = true
        active = false
        try { withTimeoutOrNull(1000) { signal(reason) } }
        finally { scope.cancel(); room?.disconnect(); room?.release(); room = null }
    }
}

/** One pre-join and at most one answer-time retry; cancellation always releases the native session. */
class NativeMediaController(context: Context, private val payload: Map<String, Any?>, private val policy: JSONObject,
                            private val scope: CoroutineScope, private val failed: () -> Unit) {
    private val session = NativeMediaRegistry.factories[policy.optString("media", "webrtc")]?.invoke(context)
        ?: if (policy.optString("media", "webrtc") == "livekit") LiveKitNativeSession(context, policy) else null
    private var preparation: Deferred<Unit>? = null
    private var closed = false
    fun prewarm() {
        if (policy.optBoolean("prewarm", true)) preparation = scope.async { session?.prewarm(payload) ?: error("Native media adapter unavailable") }
    }
    fun answered(audioReady: suspend () -> Unit) {
        scope.launch {
            try {
                try { preparation?.await() ?: error("No pre-warm") }
                catch (_: Exception) { check(!closed); session?.prewarm(payload) ?: error("Native media adapter unavailable") }
                check(!closed)
                audioReady()
                session?.activate() ?: error("Native media adapter unavailable")
            } catch (_: Exception) { if (!closed) failed() }
        }
    }
    suspend fun mute(value: Boolean) { session?.setMuted(value) }
    fun close(reason: String) {
        if (closed) return
        closed = true
        preparation?.cancel()
        scope.launch { withContext(NonCancellable) { session?.close(reason) } }
    }
}
