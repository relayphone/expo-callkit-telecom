package expo.modules.callkittelecom.conversations

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DndWarmCleanupTest {
    @Test fun policyChangeClosesPrewarmedMedia() = runBlocking {
        assumeNotNull(InstrumentationRegistry.getArguments().getString("dndWarm"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val settings = JSONObject(instrumentation.context.assets.open("media-ci.json").bufferedReader().readText())
        val client = OkHttpClient.Builder().callTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build()
        suspend fun fixture(path: String): JSONObject = withContext(Dispatchers.IO) {
            client.newCall(Request.Builder().url(settings.getString("baseUrl") + path)
                .header("Authorization", "Bearer " + settings.getString("authentication")).build()).execute().use {
                check(it.isSuccessful); JSONObject(it.body!!.string())
            }
        }
        suspend fun dnd(mode: String) = withContext(Dispatchers.IO) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cmd notification set_dnd $mode")).use { it.readBytes() }
        }
        withContext(Dispatchers.Main) {
            val engine = ConversationEngine.get(context)
            val previous = engine.policy
            val previousEmitter = engine.emit
            val oldFilter = NativeIncomingFilters.evaluate
            val signals = CopyOnWriteArrayList<NativeMediaSignal>()
            val native = CopyOnWriteArrayList<NativeIncomingOutcome>()
            val js = mutableListOf<Map<String, Any?>>()
            val mediaObserver = NativeMediaObserver { signals += it }
            val outcomeObserver = NativeIncomingObserver { native += it }
            NativeMediaSignals.observers += mediaObserver
            NativeIncomingOutcomes.observers += outcomeObserver
            val output = File(context.filesDir, "dnd-warm-cleanup.json")
            output.delete()
            NativeMediaAuthenticationProviders.providers["dnd-fixture"] = {
                NativeMediaAuthentication(settings.getString("authentication"), "fixture-device")
            }
            engine.emit = { name, body -> if (name == "conversationEvent" && body["type"] == "incomingConversationOutcome") js += body }
            engine.policy = JSONObject(mapOf("type" to "native", "media" to "livekit", "credentials" to "fromPush",
                "fallback" to mapOf("type" to "http", "baseUrl" to settings.getString("baseUrl"), "authentication" to "dnd-fixture")))
            val serverId = "dnd-warm-${android.os.SystemClock.elapsedRealtime()}"
            var id: String? = null
            try {
                dnd("off")
                val payload = fixture("/fixture/invitation/$serverId?media=1")
                val outcome = NativeCallPush(engine) {}.handle(mapOf("messageType" to "incomingCall", "incomingCall" to payload.toString()), true)!!
                assertEquals("reported", outcome["status"])
                id = outcome["id"] as String
                withTimeout(10_000) { while (signals.none { it.serverCallId == serverId && it.stage == NativeMediaStage.PREJOINED }) delay(20) }
                assertTrue(TelecomEvidence.calls().any { "state=RINGING" in it })
                dnd("on")
                withTimeout(7000) { while (native.none { it.serverCallId == serverId && it.result is NativeIncomingResult.Filtered } ||
                    signals.none { it.serverCallId == serverId && it.stage == NativeMediaStage.CLOSED }) delay(20) }
                val result = (native.last { it.serverCallId == serverId }.result as NativeIncomingResult.Filtered).filter
                assertEquals(NativeIncomingFilterReason.DO_NOT_DISTURB, result.reason)
                assertFalse((engine.snapshot["conversations"] as List<*>).any { (it as Map<*, *>)["id"] == id })
                withTimeout(5000) { while (TelecomEvidence.calls().isNotEmpty()) delay(25) }
                assertTrue(js.any { ((it["payload"] as? Map<*, *>)?.get("outcome") as? Map<*, *>)?.get("reason") == "doNotDisturb" })
                val state = withTimeout(7000) {
                    var value = fixture("/fixture/observations/$serverId")
                    while (value.getInt("clients") != 0) { delay(30); value = fixture("/fixture/observations/$serverId") }
                    value
                }
                assertFalse(signals.any { it.serverCallId == serverId && it.stage == NativeMediaStage.CAPTURE_STARTED })
                dnd("off")
                val appReasons = mutableListOf<String>()
                for (reason in listOf(NativeIncomingFilterReason.BLOCK_LIST, NativeIncomingFilterReason.OTHER)) {
                    val entered = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    NativeIncomingFilters.evaluate = { value -> assertFalse(value.containsKey("media")); entered.complete(Unit); release.await(); NativeIncomingFilter(reason, "test.nativePolicy") }
                    val key = "app-${reason.value}-${android.os.SystemClock.elapsedRealtime()}"
                    val request = mapOf("eventId" to key, "serverCallId" to key, "caller" to mapOf("id" to "caller", "displayName" to key), "hasVideo" to false)
                    val reporting = async { engine.incoming(request) }
                    withTimeout(5000) { entered.await() }
                    val earlyAnswer = async { runCatching { engine.join(CallIdentity.forServerCallId(key).toString()) } }
                    yield()
                    assertFalse(earlyAnswer.isCompleted)
                    assertFalse(context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.any {
                        it.notification.extras.getParcelable(android.app.Notification.EXTRA_CALL_PERSON, android.app.Person::class.java)?.name?.toString() == key })
                    release.complete(Unit)
                    val filtered = reporting.await()
                    assertTrue(earlyAnswer.await().isFailure)
                    assertEquals("filtered", filtered["status"]); assertEquals(reason.value, filtered["reason"])
                    assertFalse(filtered.containsKey("id"))
                    assertEquals(filtered, engine.incoming(request))
                    assertEquals(1, native.count { it.serverCallId == key })
                    assertTrue(native.last().result is NativeIncomingResult.Filtered)
                    withTimeout(5000) { while (TelecomEvidence.calls().isNotEmpty()) delay(25) }
                    appReasons += reason.value
                }
                output.writeText(JSONObject(mapOf("passed" to true, "prejoinedBeforeFilter" to true,
                    "filteredReason" to result.reason.value, "serverClients" to state.getInt("clients"),
                    "captureStarted" to false, "nativeClosed" to true, "coreCallsAfterCleanup" to 0,
                    "delayedRejectPreventsAnswerAndNotification" to true, "appReasons" to appReasons)).toString())
            } finally {
                dnd("off")
                NativeIncomingFilters.evaluate = oldFilter
                id?.let { value -> runCatching { engine.report(value, mapOf("type" to "ended", "reason" to "remoteEnded")) } }
                NativeMediaSignals.observers -= mediaObserver; NativeIncomingOutcomes.observers -= outcomeObserver
                NativeMediaAuthenticationProviders.providers.remove("dnd-fixture")
                engine.policy = previous; engine.emit = previousEmitter
            }
        }
    }
}
