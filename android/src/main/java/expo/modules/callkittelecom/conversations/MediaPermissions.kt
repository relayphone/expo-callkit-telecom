package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class MediaKind(val value: String) {
    MICROPHONE("microphone"), CAMERA("camera");

    companion object {
        fun parse(value: String): MediaKind = entries.firstOrNull { it.value == value }
            ?: throw ConversationFailure("invalid_arguments")
    }
}

internal enum class MediaPermissionStatus(val value: String) {
    GRANTED("granted"), DENIED("denied"), UNDETERMINED("undetermined")
}

/** Expo PermissionResponse semantics, including a permission that can be requested again. */
internal data class MediaPermissionResponse(val status: MediaPermissionStatus, val canAskAgain: Boolean) {
    companion object {
        fun from(granted: Boolean, expoStatus: MediaPermissionStatus, canAskAgain: Boolean): MediaPermissionResponse =
            MediaPermissionResponse(if (granted) MediaPermissionStatus.GRANTED
                else if (expoStatus == MediaPermissionStatus.UNDETERMINED) MediaPermissionStatus.UNDETERMINED
                else MediaPermissionStatus.DENIED,
                if (granted || expoStatus == MediaPermissionStatus.UNDETERMINED) true else canAskAgain)
    }
    val granted: Boolean get() = status == MediaPermissionStatus.GRANTED
    fun wire(): Map<String, Any> = mapOf("status" to status.value, "granted" to granted,
        "canAskAgain" to canAskAgain, "expires" to "never")
}

internal interface MediaPermissionAccess {
    fun isForeground(): Boolean
    suspend fun read(): Map<MediaKind, MediaPermissionResponse>
    suspend fun request(kinds: List<MediaKind>)
}

/** Explicit permission commands only; incoming calls and media activation never invoke requests. */
internal class MediaPermissions(private val access: MediaPermissionAccess) {
    private val requests = Mutex()
    suspend fun get(): Map<String, Map<String, Any>> = access.read().wire()

    suspend fun request(kinds: List<String>): Map<String, Map<String, Any>> = requests.withLock {
        requireForeground()
        val requested = kinds.map(MediaKind::parse).distinct()
        val current = access.read()
        val ask = requested.filter { !current.getValue(it).granted && current.getValue(it).canAskAgain }
        if (ask.isNotEmpty()) {
            requireForeground()
            access.request(ask)
        }
        access.read().wire()
    }

    suspend fun readiness(id: String, isVideo: (String) -> Boolean?): Map<String, List<String>> {
        val video = isVideo(id) ?: throw ConversationFailure("unknown_conversation")
        val required = if (video) MediaKind.entries else listOf(MediaKind.MICROPHONE)
        val current = access.read()
        return mapOf("required" to required.map { it.value },
            "missing" to required.filter { !current.getValue(it).granted }.map { it.value })
    }

    private fun requireForeground() {
        if (!access.isForeground()) throw ConversationFailure("not_foreground")
    }
    private fun Map<MediaKind, MediaPermissionResponse>.wire() = MediaKind.entries.associate { it.value to getValue(it).wire() }
}
