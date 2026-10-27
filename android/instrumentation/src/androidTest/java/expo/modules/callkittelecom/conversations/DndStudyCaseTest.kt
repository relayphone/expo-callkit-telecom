package expo.modules.callkittelecom.conversations

import android.Manifest
import android.app.NotificationManager
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.ContactsContract
import android.telecom.DisconnectCause
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallsManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** One fresh caller, one policy, and two attempts. No media credentials or JS bridge. */
@RunWith(AndroidJUnit4::class)
class DndStudyCaseTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val audio get() = context.getSystemService(AudioManager::class.java)
    private var stage = "arguments"
    private var partialObservation: Map<String, Any?>? = null

    @Test fun typedCallerPolicyPair() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val laneArgument = args.getString("dndStudyLane")
        // This opt-in study must not expand the ordinary Gradle instrumentation suite.
        assumeNotNull(laneArgument)
        val lane = laneArgument!!
        val senders = args.getString("dndStudySenders")!!
        val repeat = args.getString("dndStudyRepeat")!!.let { require(it in listOf("true", "false")); it.toBoolean() }
        val callerClass = args.getString("dndStudyCaller")!!
        val sample = args.getString("dndStudySample")!!.toInt()
        val run = args.getString("dndStudyRun")!!
        require(lane in listOf("platform", "native"))
        require(senders in listOf("any", "contacts", "starred"))
        require(callerClass in listOf("starred", "contact", "noncontact"))
        require(sample in 1..3 && run.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        assertEquals("Pinned Android study platform", 35, Build.VERSION.SDK_INT)
        val key = "$lane-$senders-$repeat-$callerClass-$sample"
        val output = File(context.filesDir, "dnd-study-$key.json")
        output.delete()
        // Every lane/cell/sample changes the significant suffix, not just a country prefix.
        val digest = MessageDigest.getInstance("SHA-256").digest("$run/$key".toByteArray())
        val runPrefix = digest.take(4).fold(0L) { n, b -> (n * 256 + (b.toInt() and 255)) % 1000L }
        val cell = (((listOf("any", "contacts", "starred").indexOf(senders) * 2 + if (repeat) 1 else 0) * 3 +
            listOf("starred", "contact", "noncontact").indexOf(callerClass)) * 3 + sample - 1) +
            if (lane == "native") 54 else 0
        // A stable cell suffix guarantees distinct last-seven digits across all 108 pairs.
        // 202 is a real NANP area code; the 7-digit local part starts with 2..9.
        val prefix = (digest[4].toInt() and 255) % 8 + 2
        val phone = "+1202$prefix" + runPrefix.toString().padStart(3, '0') + cell.toString().padStart(3, '0')
        val hostId = "dnd-study-host-$key-$run"
        val caller = NativeCaller.parse(mapOf("handleType" to "phone", "value" to phone,
            "displayName" to "DND $key", "hostContactID" to hostId))!!
        assertEquals("tel", caller.address().scheme)
        assertEquals(phone, caller.address().schemeSpecificPart)
        assertEquals(caller.address(), caller.coreAddress()) // Study runs only on API35.
        val calls = mutableListOf<Map<String, Any?>>()
        val result = linkedMapOf<String, Any?>("schemaVersion" to 1, "passed" to false,
            "apiLevel" to Build.VERSION.SDK_INT, "targetSdk" to context.applicationInfo.targetSdkVersion,
            "lane" to lane, "senders" to senders, "repeat" to repeat, "caller" to callerClass,
            "sampleIndex" to sample, "run" to run, "callerUri" to caller.address().toString(),
            "callerE164" to phone, "hostContactId" to hostId, "pairWaitMs" to 2000, "calls" to calls)
        var ownedContact: Long? = null
        var failure: Throwable? = null
        try {
            stage = "permissions-and-idle"
            withTimeout(5000) { while (!DndObservationService.connected) delay(25) }
            val permissions = mapOf("readContacts" to granted(Manifest.permission.READ_CONTACTS),
                "writeContacts" to granted(Manifest.permission.WRITE_CONTACTS),
                "notificationListenerConnected" to DndObservationService.connected,
                "notificationPolicyAccess" to manager.isNotificationPolicyAccessGranted)
            result["permissions"] = permissions
            assertTrue(permissions.values.all { it })
            assertNull("Study must isolate Android DND from a host application filter", NativeIncomingFilters.evaluate)
            awaitIdle()
            stage = "contacts"
            if (callerClass != "noncontact") ownedContact = insertContact(phone, callerClass == "starred")
            withTimeout(5000) {
                while (phoneLookup(phone) != ((callerClass != "noncontact") to (callerClass == "starred"))) delay(50)
            }
            val membership = phoneLookup(phone)
            result["phoneLookup"] = mapOf("contact" to membership.first, "starred" to membership.second)

            stage = "fresh-repeat-preflight"
            val preflight = applyPolicy("any", calls = false, repeat = true)
            val preflightAllowed = matches(caller.address())
            result["preflight"] = mapOf("callFilterAllowed" to preflightAllowed, "policy" to preflight)
            assertFalse("Fresh identity already exists in Android repeat history", preflightAllowed)
            val allowedA = senders == "any" || callerClass == "starred" ||
                (senders == "contacts" && callerClass == "contact")
            stage = "target-policy-A"
            val policyA = applyPolicy(senders, calls = true, repeat = repeat)
            calls += observeCall(lane, caller, "A", allowedA, policyA, key, run)

            // Cancellation is what records notification repeat history in AOSP. Probe it
            // independently with calls disabled; this query itself does not record a call.
            stage = "repeat-history-after-A"
            applyPolicy("any", calls = false, repeat = true)
            withTimeout(5000) { while (!matches(caller.address())) delay(50) }
            result["repeatHistoryAfterA"] = true
            stage = "target-policy-B"
            val policyB = applyPolicy(senders, calls = true, repeat = repeat)
            delay(2000)
            calls += observeCall(lane, caller, "B", allowedA || repeat, policyB, key, run)
            result["pairGapMs"] = ((calls[1]["startedNanos"] as Long) - (calls[0]["startedNanos"] as Long)) / 1_000_000.0
            result["cleanupToSecondMs"] = ((calls[1]["startedNanos"] as Long) - (calls[0]["cleanupNanos"] as Long)) / 1_000_000.0
            result["passed"] = true
        } catch (error: Throwable) {
            failure = error
            result["failureStage"] = stage
            result["failureType"] = error.javaClass.simpleName
            result["partialObservation"] = partialObservation
        } finally {
            ownedContact?.let { id ->
                val owned = ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, id).buildUpon()
                    .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build()
                context.contentResolver.delete(owned, null, null)
            }
            val rowsRemaining = ownedContact?.let { id ->
                context.contentResolver.query(ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, id),
                    arrayOf(ContactsContract.RawContacts._ID), null, null, null)?.use { it.count } ?: 0
            } ?: 0
            result["contactCleanup"] = mapOf("ownedRowsRemaining" to rowsRemaining)
            if (rowsRemaining != 0) {
                result["passed"] = false
                if (failure == null) failure = AssertionError("Owned contact remains")
            }
            runCatching { shell("cmd notification set_dnd off") }
            output.writeText(jsonValue(result).toString())
        }
        failure?.let { throw it }
    }

    private suspend fun observeCall(lane: String, caller: NativeCaller, ordinal: String, expected: Boolean,
        policy: Map<String, Any?>, key: String, run: String): Map<String, Any?> = coroutineScope {
        stage = "$ordinal-prior-idle"
        awaitIdle()
        val effectivePolicy = policySnapshot()
        assertEquals("Effective policy changed before the attempt", policy, effectivePolicy)
        val permitted = matches(caller.address())
        assertEquals("Pre-attempt public matchesCallFilter", expected, permitted)
        val label = "DND $key $ordinal"
        val attemptCaller = NativeCaller.parse(caller.asPayload() + ("displayName" to label))!!
        DndObservationService.observations.remove(label)
        val ringtone = AtomicBoolean(false)
        val ringing = AtomicBoolean(false)
        val playback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
                if (configs.any { it.audioAttributes.usage == AudioAttributes.USAGE_NOTIFICATION_RINGTONE }) ringtone.set(true)
            }
        }
        audio.registerAudioPlaybackCallback(playback, Handler(Looper.getMainLooper()))
        val observationStart = SystemClock.elapsedRealtimeNanos()
        val poll = launch {
            while (isActive) {
                if (TelecomEvidence.calls().any { "state=RINGING" in it }) ringing.set(true)
                delay(50)
            }
        }
        val serverId = "dnd-study-$run-$key-$ordinal"
        val signals = CopyOnWriteArrayList<NativeMediaSignal>()
        val outcomes = CopyOnWriteArrayList<NativeIncomingOutcome>()
        val signalObserver = NativeMediaObserver { if (it.serverCallId == serverId) signals += it }
        val outcomeObserver = NativeIncomingObserver { if (it.serverCallId == serverId) outcomes += it }
        NativeMediaSignals.observers += signalObserver
        NativeIncomingOutcomes.observers += outcomeObserver
        var rawJob: Job? = null
        var rawScope: CoroutineScope? = null
        var control: CallControlScope? = null
        var id: String? = null
        val engine = ConversationEngine.get(context)
        val previousPolicy = engine.policy
        var nativeOutcome: Map<String, Any?>? = null
        var deduplicated: Boolean? = null
        var admitted = false
        val started = SystemClock.elapsedRealtimeNanos()
        var observedMs = 0L
        var ranking: DndObservationService.Companion.Observation? = null
        try {
            stage = "$ordinal-core-admission"
            withContext(Dispatchers.Main) {
                if (lane == "platform") {
                    val ready = CompletableDeferred<CallControlScope>()
                    id = UUID.randomUUID().toString()
                    rawScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                    val telecom = CallsManager(context).apply { registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE) }
                    rawJob = rawScope!!.launch {
                        try {
                            telecom.addCall(CallAttributesCompat(label, attemptCaller.coreAddress(),
                                CallAttributesCompat.DIRECTION_INCOMING, CallAttributesCompat.CALL_TYPE_AUDIO_CALL),
                                onAnswer = {}, onDisconnect = {}, onSetActive = {}, onSetInactive = {}) {
                                ConversationService.show(context, id!!, mapOf("status" to "ringing",
                                    "members" to listOf(attemptCaller.asParticipant()),
                                    "payload" to mapOf("caller" to attemptCaller.asPayload())))
                                ready.complete(this)
                            }
                        } catch (error: Exception) { ready.completeExceptionally(error) }
                    }
                    control = withTimeout(6000) { ready.await() }
                    admitted = true
                } else {
                    engine.policy = JSONObject(mapOf("type" to "handler"))
                    val payload = mapOf("eventId" to serverId, "serverCallId" to serverId, "hasVideo" to false,
                        "caller" to attemptCaller.asPayload())
                    val outcome = engine.incoming(payload)
                    id = outcome["id"] as? String
                    nativeOutcome = mapOf("status" to outcome["status"], "reason" to outcome["reason"])
                    assertEquals(if (expected) "reported" else "filtered", outcome["status"])
                    if (!expected) { assertEquals("doNotDisturb", outcome["reason"]); assertFalse(outcome.containsKey("id")) }
                    deduplicated = outcome == engine.incoming(payload)
                    assertTrue(deduplicated == true)
                }
            }
            if (lane == "native") {
                withTimeout(3000) { while (signals.none { it.stage == NativeMediaStage.CORE_ADMITTED } || outcomes.size != 1) delay(25) }
                admitted = true
                if (expected) assertTrue(outcomes.single().result is NativeIncomingResult.Reported)
                else {
                    val filtered = outcomes.single().result as? NativeIncomingResult.Filtered
                    assertEquals(NativeIncomingFilterReason.DO_NOT_DISTURB, filtered?.filter?.reason)
                }
            }
            stage = "$ordinal-platform-presentation"
            if (lane == "platform" || expected) {
                withTimeout(5000) { while (!ringing.get() || DndObservationService.observations[label] == null) delay(25) }
            }
            // Capture real playback/ranking, including asynchronous Contacts affinity updates.
            delay(1200)
            observedMs = (SystemClock.elapsedRealtimeNanos() - observationStart) / 1_000_000
            ranking = DndObservationService.observations[label]
            ranking?.let {
                assertEquals("Actual notification ranking", expected, it.matchesFilter)
                assertEquals("call", it.category)
                assertTrue(it.soundConfigured)
                assertEquals(attemptCaller.address().toString(), it.personUri)
                assertEquals(caller.hostContactID, it.personKey)
                assertEquals(false, it.channelBypass)
            }
            assertEquals("Actual ringtone playback within the observed window", expected, ringtone.get())
            if (expected) assertTrue(audio.getStreamVolume(AudioManager.STREAM_RING) > 0)
            val volume = audio.getStreamVolume(AudioManager.STREAM_RING)
            val ringerMode = audio.ringerMode
            val channel = checkNotNull(manager.getNotificationChannel(ConversationService.incomingChannelId(context))) {
                "Incoming notification channel was not created"
            }
            assertFalse("Study excludes user-enabled channel bypass", channel.canBypassDnd())
            stage = "$ordinal-cleanup"
            withContext(Dispatchers.Main) {
                if (lane == "platform") {
                    assertTrue(control!!.disconnect(DisconnectCause(DisconnectCause.REJECTED)) is CallControlResult.Success)
                    ConversationService.remove(context, id!!)
                } else if (id != null) engine.report(id!!, mapOf("type" to "ended", "reason" to "remoteEnded"))
            }
            rawJob?.let { withTimeout(5000) { it.join() } }
            awaitIdle()
            val cleanup = SystemClock.elapsedRealtimeNanos()
            mapOf("ordinal" to ordinal, "expectedAllowed" to expected, "callFilterAllowed" to permitted,
                "policy" to effectivePolicy, "coreAdmitted" to admitted,
                // A filtered native call may disappear before dumpsys can observe its state.
                "coreRingingObserved" to if (ringing.get()) true else null,
                "notificationRanking" to ranking?.let { mapOf("matchesFilter" to it.matchesFilter,
                    "soundConfigured" to it.soundConfigured, "category" to it.category,
                    "personUri" to it.personUri, "personKey" to it.personKey,
                    "channelBypass" to it.channelBypass, "importance" to it.importance) },
                "ringtonePlaybackObserved" to ringtone.get(), "playbackObservationMs" to observedMs,
                "ringVolume" to volume, "ringerMode" to ringerMode,
                "channelBypass" to channel.canBypassDnd(), "channelImportance" to channel.importance,
                "channelSoundConfigured" to (channel.sound != null),
                "nativeOutcome" to nativeOutcome, "nativeHookCount" to if (lane == "native") outcomes.size else null,
                "deduplicated" to deduplicated, "coreCallsAfterCleanup" to TelecomEvidence.calls().size,
                "engineCallsAfterCleanup" to engineCallCount(), "ownNotificationsAfterCleanup" to manager.activeNotifications.size,
                "startedNanos" to started, "cleanupNanos" to cleanup)
        } finally {
            withContext(NonCancellable) {
                // A failed assertion still leaves useful, scalar-only experiment evidence.
                val lastRanking = DndObservationService.observations[label]
                partialObservation = mapOf("ordinal" to ordinal, "callFilterAllowed" to permitted,
                    "policy" to effectivePolicy, "coreAdmitted" to if (admitted || signals.any { it.stage == NativeMediaStage.CORE_ADMITTED }) true else null,
                    "coreRingingObserved" to if (ringing.get()) true else null,
                    "notificationAllowed" to lastRanking?.matchesFilter,
                    "notificationPersonUri" to lastRanking?.personUri, "notificationPersonKey" to lastRanking?.personKey,
                    "ringtonePlaybackObserved" to ringtone.get(), "nativeOutcome" to nativeOutcome,
                    "playbackObservationMs" to (SystemClock.elapsedRealtimeNanos() - observationStart) / 1_000_000,
                    "nativeHookCount" to if (lane == "native") outcomes.size else null,
                    "listenerConnected" to DndObservationService.connected,
                    "ringVolume" to audio.getStreamVolume(AudioManager.STREAM_RING), "ringerMode" to audio.ringerMode)
                poll.cancelAndJoin()
                withContext(Dispatchers.Main) {
                    if (lane == "platform") {
                        runCatching { control?.disconnect(DisconnectCause(DisconnectCause.REJECTED)) }
                        id?.let { ConversationService.remove(context, it) }
                    } else id?.let { runCatching { engine.report(it, mapOf("type" to "ended", "reason" to "remoteEnded")) } }
                    rawJob?.cancel(); rawScope?.cancel(); engine.policy = previousPolicy
                }
                NativeMediaSignals.observers -= signalObserver
                NativeIncomingOutcomes.observers -= outcomeObserver
                audio.unregisterAudioPlaybackCallback(playback)
            }
        }
    }

    private suspend fun awaitIdle() {
        withTimeout(7000) {
            while (TelecomEvidence.calls().isNotEmpty() || manager.activeNotifications.isNotEmpty() || engineCallCount() != 0 ||
                audio.activePlaybackConfigurations.any { it.audioAttributes.usage == AudioAttributes.USAGE_NOTIFICATION_RINGTONE }) delay(50)
        }
    }
    private fun engineCallCount(): Int = (ConversationEngine.get(context).snapshot["conversations"] as? List<*>)?.size ?: 0
    /** Preserve unavailable evidence as explicit JSON null, including inside nested maps. */
    private fun jsonValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().apply { value.forEach { (key, item) -> put(key as String, jsonValue(item)) } }
        is Iterable<*> -> JSONArray().apply { value.forEach { put(jsonValue(it)) } }
        is String, is Boolean, is Number -> value
        else -> error("Unsupported study observation")
    }
    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private suspend fun matches(uri: Uri): Boolean = withContext(Dispatchers.IO) { manager.matchesCallFilter(uri) }
    private suspend fun shell(command: String): String = withContext(Dispatchers.IO) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
    }
    private suspend fun applyPolicy(senders: String, calls: Boolean, repeat: Boolean): Map<String, Any?> {
        shell("am broadcast -a expo.callkittelecom.SET_DND_POLICY -n expo.modules.callkittelecom.dndpolicy/.PolicyReceiver --es senders $senders --ez calls $calls --ez repeat $repeat")
        shell("cmd notification set_dnd priority")
        val expectedSenders = when (senders) {
            "starred" -> NotificationManager.Policy.PRIORITY_SENDERS_STARRED
            "contacts" -> NotificationManager.Policy.PRIORITY_SENDERS_CONTACTS
            else -> NotificationManager.Policy.PRIORITY_SENDERS_ANY
        }
        var snapshot: Map<String, Any?> = emptyMap()
        withTimeout(5000) {
            while (true) {
                val policy = manager.consolidatedNotificationPolicy
                val hasCalls = policy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_CALLS != 0
                val hasRepeat = policy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_REPEAT_CALLERS != 0
                snapshot = policySnapshot()
                if (manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY &&
                    hasCalls == calls && hasRepeat == repeat && policy.priorityCallSenders == expectedSenders) break
                delay(50)
            }
        }
        return snapshot
    }
    private fun policySnapshot(): Map<String, Any?> {
        val policy = manager.consolidatedNotificationPolicy
        return mapOf("interruptionFilter" to manager.currentInterruptionFilter,
            "priorityCategories" to policy.priorityCategories, "priorityCallSenders" to policy.priorityCallSenders,
            "calls" to (policy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_CALLS != 0),
            "repeat" to (policy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_REPEAT_CALLERS != 0))
    }
    private fun insertContact(phone: String, starred: Boolean): Long {
        val operations = arrayListOf(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null).withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .withValue(ContactsContract.RawContacts.STARRED, if (starred) 1 else 0).build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, "DND study fixture").build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone).build())
        return ContentUris.parseId(context.contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)[0].uri!!)
    }
    private fun phoneLookup(phone: String): Pair<Boolean, Boolean> {
        var contact = false
        var starred = false
        context.contentResolver.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phone)),
            arrayOf(ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.STARRED), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) { contact = true; starred = starred || cursor.getInt(1) != 0 }
        }
        return contact to starred
    }
}
