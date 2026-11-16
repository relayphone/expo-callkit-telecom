@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package expo.modules.callkittelecom.conversations

import android.media.AudioDeviceInfo
import android.net.Uri
import android.os.Build
import android.os.ParcelUuid
import android.telecom.CallEndpoint
import android.telecom.DisconnectCause
import android.telecom.VideoProfile
import androidx.core.telecom.*
import androidx.core.telecom.internal.*
import androidx.core.telecom.internal.utils.EndpointUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Execute the dependency's actual implementations; no copied implementation or source inspection. */
@RunWith(AndroidJUnit4::class)
class CoreTelecomCompatibilityTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun attributes(video: Boolean = false) = CallAttributesCompat("Compatibility", Uri.parse("sip:compatibility"),
        CallAttributesCompat.DIRECTION_INCOMING, if (video) CallAttributesCompat.CALL_TYPE_VIDEO_CALL else CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
        CallAttributesCompat.SUPPORTS_SET_INACTIVE)
    private fun endpoint(name: String, type: Int) = CallEndpointCompat(name, type, ParcelUuid(UUID.randomUUID()))

    @Test fun legacyVoiceVideoAndAnswerAsAudioUseCorrectPlatformTypesAndMuteDeduplicates() = runBlocking {
        withContext(Dispatchers.Main) {
            for (video in listOf(false, true)) {
                val channels = CallChannels()
                val answered = Channel<Int>(Channel.UNLIMITED)
                val session = CallSessionLegacy(ParcelUuid(UUID.randomUUID()), context, attributes(video), channels,
                    coroutineContext, { answered.send(it) }, {}, {}, {}, { _, _ -> }, MutableSharedFlow(extraBufferCapacity = 32),
                    blockingSessionExecution = CompletableDeferred())
                try {
                    session.setRinging()
                    val type = if (video) CallAttributesCompat.CALL_TYPE_VIDEO_CALL else CallAttributesCompat.CALL_TYPE_AUDIO_CALL
                    session.answer(type)
                    assertEquals(if (video) VideoProfile.STATE_BIDIRECTIONAL else VideoProfile.STATE_AUDIO_ONLY, session.videoState)
                    session.requestVideoState(CallAttributesCompat.CALL_TYPE_VIDEO_CALL)
                    assertEquals(VideoProfile.STATE_BIDIRECTIONAL, session.videoState)
                    for ((platformType, coreType) in listOf(VideoProfile.STATE_BIDIRECTIONAL to CallAttributesCompat.CALL_TYPE_VIDEO_CALL,
                        VideoProfile.STATE_AUDIO_ONLY to CallAttributesCompat.CALL_TYPE_AUDIO_CALL)) {
                        session.onAnswer(platformType)
                        assertEquals(coreType, withTimeout(2000) { answered.receive() })
                        assertEquals(platformType, session.videoState)
                    }
                    session.onGlobalMuteStateChanged(true); session.onGlobalMuteStateChanged(true)
                    session.onGlobalMuteStateChanged(false); session.onGlobalMuteStateChanged(false)
                    assertEquals(true, channels.isMutedChannel.tryReceive().getOrThrow())
                    assertEquals(false, channels.isMutedChannel.tryReceive().getOrThrow())
                    assertTrue(channels.isMutedChannel.tryReceive().isFailure)
                    val cause = DisconnectCause(DisconnectCause.ERROR, "label", "description", "reason")
                    session.setConnectionDisconnect(cause)
                    assertEquals(if (Build.VERSION.SDK_INT < 38) DisconnectCause.LOCAL else DisconnectCause.ERROR, session.disconnectCause.code)
                    assertEquals("label", session.disconnectCause.label)
                    assertEquals("description", session.disconnectCause.description)
                    assertEquals("reason", session.disconnectCause.reason)
                } finally { session.close(); channels.closeAllChannels() }
            }
        }
    }

    @Test fun transactionalInitialRouteIsAsyncAndGenuineSystemChangesFollowTheApiPolicy() = runBlocking {
        if (Build.VERSION.SDK_INT < 35) return@runBlocking
        withContext(Dispatchers.Main) {
            val channels = CallChannels()
            val session = CallSession(object : BluetoothDeviceChecker {
                override fun hasAvailableNonWatchDevice(availableEndpoints: List<CallEndpointCompat>) = false
            }, coroutineContext, attributes(), {}, {}, {}, {}, channels, MutableSharedFlow(extraBufferCapacity = 32),
                { _, _ -> }, CompletableDeferred())
            try {
                assertFalse(session.getIsCurrentEndpointSet().isCompleted)
                val receiver = CallEndpoint("Receiver", CallEndpoint.TYPE_EARPIECE, ParcelUuid(UUID.randomUUID()))
                session.onCallEndpointChanged(receiver)
                assertTrue(session.getIsCurrentEndpointSet().isCompleted)
                assertEquals(CallEndpointCompat.TYPE_EARPIECE, channels.currentEndpointChannel.tryReceive().getOrThrow().type)
                assertEquals(CallAttributesCompat.CALL_TYPE_AUDIO_CALL, withTimeout(2000) { channels.callTypeChannel.receive() })
                session.onVideoStateChanged(CallAttributesCompat.CALL_TYPE_VIDEO_CALL); yield()
                if (Build.VERSION.SDK_INT in 35..36) assertTrue(channels.callTypeChannel.tryReceive().isFailure)
                else if (Build.VERSION.SDK_INT >= 37) assertEquals(CallAttributesCompat.CALL_TYPE_VIDEO_CALL, channels.callTypeChannel.tryReceive().getOrThrow())
                session.requestVideoState(CallAttributesCompat.CALL_TYPE_VIDEO_CALL)
                session.onVideoStateChanged(CallAttributesCompat.CALL_TYPE_AUDIO_CALL); yield()
                assertEquals(CallAttributesCompat.CALL_TYPE_AUDIO_CALL, channels.callTypeChannel.tryReceive().getOrThrow())
                for ((platformType, coreType) in listOf(VideoProfile.STATE_AUDIO_ONLY to CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
                    VideoProfile.STATE_BIDIRECTIONAL to CallAttributesCompat.CALL_TYPE_VIDEO_CALL)) {
                    val accepted = CompletableDeferred<Boolean>()
                    session.onAnswer(platformType) { accepted.complete(it) }
                    assertTrue(withTimeout(2000) { accepted.await() })
                    assertEquals(coreType, channels.callTypeChannel.tryReceive().getOrThrow())
                }
            } finally { session.close(); channels.closeAllChannels() }
        }
    }

    @Test fun endpointDiscoveryRejectsA2dpAndDeniedBluetoothUsesTheWatchNameHeuristic() {
        assertEquals(CallEndpointCompat.TYPE_UNKNOWN, EndpointUtils.remapAudioDeviceTypeToCallEndpointType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertEquals(CallEndpointCompat.TYPE_BLUETOOTH, EndpointUtils.remapAudioDeviceTypeToCallEndpointType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        val watch = endpoint("Pixel Watch", CallEndpointCompat.TYPE_BLUETOOTH)
        val car = endpoint("Honda Fit", CallEndpointCompat.TYPE_BLUETOOTH)
        val headphones = endpoint("Headphones", CallEndpointCompat.TYPE_BLUETOOTH)
        assertTrue(EndpointUtils.isPreferredStartingEndpoint(endpoint("Before", CallEndpointCompat.TYPE_EARPIECE),
            endpoint("After", CallEndpointCompat.TYPE_EARPIECE)))
        assertFalse(EndpointUtils.isPreferredStartingEndpoint(watch, headphones))
        assertFalse(EndpointUtils.isNonWearableDeviceByHeuristic(listOf(watch)))
        assertTrue(EndpointUtils.isNonWearableDeviceByHeuristic(listOf(watch, car)))
        val denied = object : android.content.ContextWrapper(context) {
            override fun checkSelfPermission(permission: String) = android.content.pm.PackageManager.PERMISSION_DENIED
        }
        assertFalse(EndpointUtils.hasAvailableNonWearableDevice(denied, listOf(watch)) { error("Permission denied: lookup must not run") })
        assertTrue(EndpointUtils.hasAvailableNonWearableDevice(denied, listOf(headphones)) { error("Permission denied: lookup must not run") })
    }

    @Test fun coreSpeakerFallbackPreservesWiredAndNonWatchBluetoothAndExistingRoutes() {
        val receiver = endpoint("Receiver", CallEndpointCompat.TYPE_EARPIECE)
        val speaker = endpoint("Speaker", CallEndpointCompat.TYPE_SPEAKER)
        val wired = endpoint("Wired", CallEndpointCompat.TYPE_WIRED_HEADSET)
        val bluetooth = endpoint("Headphones", CallEndpointCompat.TYPE_BLUETOOTH)
        var nonWatch = false
        val policy = VideoCallSpeakerManager(object : BluetoothDeviceChecker {
            override fun hasAvailableNonWatchDevice(availableEndpoints: List<CallEndpointCompat>) = nonWatch
        })
        assertTrue(policy.shouldSwitchToSpeaker(true, receiver, listOf(receiver, speaker)))
        assertFalse(policy.shouldSwitchToSpeaker(false, receiver, listOf(receiver, speaker)))
        assertFalse(policy.shouldSwitchToSpeaker(true, speaker, listOf(receiver, speaker)))
        assertFalse(policy.shouldSwitchToSpeaker(true, receiver, listOf(receiver)))
        assertFalse(policy.shouldSwitchToSpeaker(true, receiver, listOf(receiver, speaker, wired)))
        assertTrue(policy.shouldSwitchToSpeaker(true, receiver, listOf(receiver, speaker, bluetooth)))
        nonWatch = true
        assertFalse(policy.shouldSwitchToSpeaker(true, receiver, listOf(receiver, speaker, bluetooth)))
        assertFalse(policy.shouldSwitchToSpeaker(true, bluetooth, listOf(receiver, speaker, bluetooth)))
    }
}
