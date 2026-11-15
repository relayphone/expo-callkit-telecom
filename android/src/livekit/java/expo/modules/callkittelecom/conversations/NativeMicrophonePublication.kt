package expo.modules.callkittelecom.conversations

import io.livekit.android.room.participant.ParticipantPermission
import io.livekit.android.room.track.Track
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** One permission wait and one publish attempt; the caller owns the activation deadline. */
internal suspend fun publishNativeMicrophone(
    permissions: Flow<ParticipantPermission?>,
    permissionUnavailable: () -> Unit,
    publish: suspend () -> Unit,
) {
    try {
        permissions.first { permission -> permission?.canPublish == true &&
            (permission.canPublishSources.isEmpty() || Track.Source.MICROPHONE in permission.canPublishSources) }
    } catch (error: TimeoutCancellationException) {
        permissionUnavailable()
        throw error
    }
    publish()
}
