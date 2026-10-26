package expo.modules.callkittelecom.conversations

import android.app.NotificationManager
import android.content.ContentProviderOperation
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.provider.ContactsContract
import android.telecom.DisconnectCause
import androidx.core.telecom.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Invoked once per adb-configured policy, independently of the library's filtering decision. */
@RunWith(AndroidJUnit4::class)
class DndMatrixTest {
    @Test fun platformAndTypedOutcome() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val case = args.getString("dndCase")
        assumeNotNull(case)
        val name = case!!
        val allowed = args.getString("allowed") == "true"
        val caller = args.getString("caller") ?: "unknown"
        val expectedMode = args.getString("mode")!!.toInt()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        val output = File(context.filesDir, "dnd-$name.json")
        output.delete()
        val phone = when (caller) { "contact" -> "+15550001001"; "starred" -> "+15550001002"; "repeat" -> "+15550001003"; else -> "+1555${name.hashCode().toUInt().toString().take(7)}" }
        if (caller in listOf("contact", "starred")) {
            val operations = arrayListOf(
                ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null).withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                    .withValue(ContactsContract.RawContacts.STARRED, if (caller == "starred") 1 else 0).build(),
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone).build())
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)
        }
        withTimeout(5000) { while (manager.currentInterruptionFilter != expectedMode || !DndObservationService.connected) delay(25) }
        val permitted = withContext(Dispatchers.IO) { manager.matchesCallFilter(Uri.fromParts("tel", phone, null)) }
        assertEquals("Platform caller filter", allowed, permitted)
        val audio = context.getSystemService(AudioManager::class.java)
        withTimeout(5000) { while (audio.activePlaybackConfigurations.any { it.audioAttributes.usage == AudioAttributes.USAGE_NOTIFICATION_RINGTONE }) delay(25) }
        val ringtoneObserved = AtomicBoolean(false)
        val playback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
                if (configs.any { it.audioAttributes.usage == AudioAttributes.USAGE_NOTIFICATION_RINGTONE }) ringtoneObserved.set(true)
            }
        }
        audio.registerAudioPlaybackCallback(playback, Handler(Looper.getMainLooper()))
        withContext(Dispatchers.Main) {
            val id = UUID.randomUUID().toString()
            val ready = CompletableDeferred<CallControlScope>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val telecom = CallsManager(context).apply { registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE) }
            DndObservationService.observations.remove(name)
            val raw = scope.launch {
                try { telecom.addCall(CallAttributesCompat(name, Uri.fromParts("tel", phone, null),
                    CallAttributesCompat.DIRECTION_INCOMING, CallAttributesCompat.CALL_TYPE_AUDIO_CALL),
                    onAnswer = {}, onDisconnect = {}, onSetActive = {}, onSetInactive = {}) {
                    ConversationService.show(context, id, mapOf("status" to "ringing", "members" to listOf(mapOf(
                        "id" to phone, "phoneNumber" to phone, "displayName" to name))))
                    ready.complete(this)
                } } catch (error: Exception) { ready.completeExceptionally(error) }
            }
            var control: CallControlScope? = null
            try {
                control = withTimeout(6000) { ready.await() }
                withTimeout(5000) { while (TelecomEvidence.calls().none { "state=RINGING" in it }) delay(25) }
                withTimeout(5000) { while (DndObservationService.observations[name] == null) delay(25) }
                // Allow asynchronous contact affinity/ranking updates to settle.
                delay(1000)
                val ranking = DndObservationService.observations[name]!!
                assertEquals("Actual CATEGORY_CALL ranking", allowed, ranking.matchesFilter)
                assertTrue(ranking.soundConfigured)
                assertEquals("call", ranking.category)
                val rawRingtoneObserved = ringtoneObserved.get()
                assertEquals("Actual ringtone playback activity", allowed, rawRingtoneObserved)
                if (allowed) { assertTrue(audio.getStreamVolume(AudioManager.STREAM_RING) > 0); assertEquals(AudioManager.RINGER_MODE_NORMAL, audio.ringerMode) }
                assertTrue(control.disconnect(DisconnectCause(DisconnectCause.REJECTED)) is CallControlResult.Success)
                ConversationService.remove(context, id)
                withTimeout(3000) { raw.join() }
                withTimeout(5000) { while (TelecomEvidence.calls().isNotEmpty()) delay(25) }

                val engine = ConversationEngine.get(context)
                val previous = engine.policy
                val observations = mutableListOf<NativeIncomingOutcome>()
                val observer = NativeIncomingObserver { observations += it }
                NativeIncomingOutcomes.observers += observer
                try {
                    engine.policy = JSONObject(mapOf("type" to "handler"))
                    val payload = mapOf("eventId" to "native-$name", "serverCallId" to "native-$name", "hasVideo" to false,
                        "caller" to mapOf("id" to phone, "phoneNumber" to phone, "displayName" to "Native $name"))
                    // The raw first call records repeat-call history. Both assertions above
                    // describe the first call; the library uses Android's current decision.
                    val nativeAllowed = withContext(Dispatchers.IO) { manager.matchesCallFilter(Uri.fromParts("tel", phone, null)) }
                    val outcome = engine.incoming(payload)
                    assertEquals(if (nativeAllowed) "reported" else "filtered", outcome["status"])
                    if (!nativeAllowed) { assertEquals("doNotDisturb", outcome["reason"]); assertFalse(outcome.containsKey("id")) }
                    assertEquals(outcome, engine.incoming(payload))
                    assertEquals(1, observations.count { it.serverCallId == "native-$name" })
                    if (nativeAllowed) engine.report(outcome["id"] as String, mapOf("type" to "ended", "reason" to "remoteEnded"))
                    withTimeout(5000) { while (TelecomEvidence.calls().isNotEmpty()) delay(25) }
                    output.writeText(JSONObject(mapOf("passed" to true, "case" to name, "interruptionFilter" to manager.currentInterruptionFilter,
                        "coreAccepted" to true, "callFilterAllowed" to permitted, "notificationAllowed" to ranking.matchesFilter,
                        "notificationCategory" to ranking.category, "soundConfigured" to ranking.soundConfigured,
                        "coreRingingObserved" to true, "coreCallsAfterCleanup" to 0, "ringtonePlaybackObserved" to rawRingtoneObserved,
                        "ringVolume" to audio.getStreamVolume(AudioManager.STREAM_RING), "ringerMode" to audio.ringerMode,
                        "nativeAllowedAfterRaw" to nativeAllowed, "outcome" to outcome, "nativeHookCount" to 1)).toString())
                } finally { NativeIncomingOutcomes.observers -= observer; engine.policy = previous }
            } finally {
                runCatching { control?.disconnect(DisconnectCause(DisconnectCause.REJECTED)) }
                ConversationService.remove(context, id); raw.cancel(); scope.cancel()
                audio.unregisterAudioPlaybackCallback(playback)
            }
        }
    }
}
