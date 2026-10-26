package expo.modules.callkittelecom

import android.content.pm.PackageManager
import expo.modules.callkittelecom.conversations.ConversationEngine
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.functions.Coroutine
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import kotlinx.coroutines.*
import org.json.JSONObject

class ExpoCallKitTelecomModule : Module() {
    private lateinit var engine: ConversationEngine
    private val owner = Any()
    private suspend fun <T> command(body: suspend () -> T): T = withContext(Dispatchers.Main) {
        try { check(engine.bridgeOwner === owner); body() }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            if (error is expo.modules.callkittelecom.conversations.ConversationFailure) {
                val details = JSONObject(mapOf("code" to error.code, "message" to "Conversation operation failed", "nativeDomain" to "androidx.core.telecom", "nativeCode" to error.nativeCode)).toString()
                throw CodedException(error.code, "expo-conversation:$details", null)
            }
            val code = error.message?.takeIf { it in setOf("invalid_arguments", "unknown_conversation", "unsupported") } ?: "conversation"
            throw CodedException(code, "Conversation operation failed", null)
        }
    }
    override fun definition() = ModuleDefinition {
        Name("ExpoCallKitTelecom")
        Events("conversationsChanged", "audioRoutesChanged", "pushTokenUpdated", "handlerAction", "actionCancelled", "conversationEvent")
        OnCreate {
            val context = appContext.reactContext ?: return@OnCreate
            engine = ConversationEngine.get(context)
            engine.jobs.launch {
                engine.bind(owner) { name, body -> sendEvent(name, body) }
                val metadata = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
                engine.policy = JSONObject(metadata?.getString("ExpoCallKitTelecomJoinPolicy") ?: "{}")
                runCatching { com.google.firebase.messaging.FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                    engine.jobs.launch { engine.updatePushToken(token) }
                } }
                engine.actions.acceptUnhandled = metadata?.getString("ExpoCallKitTelecomUnhandledAction") == "accept"
            }
        }
        OnDestroy { if (::engine.isInitialized) engine.jobs.launch { if (engine.bridgeOwner === owner) engine.bind(null) { _, _ -> } } }
        Property("snapshot") { engine.snapshot }
        Property("mediaAdapter") { engine.policy.optString("media", "webrtc") }
        AsyncFunction("getConversations") Coroutine { -> command { engine.snapshot } }
        AsyncFunction("getAudioRoutes") Coroutine { -> command { engine.audioRoutes() } }
        AsyncFunction("getPushToken") Coroutine { -> command { engine.pushTokenSnapshot() } }
        AsyncFunction("reportIncomingConversation") Coroutine { payload: Map<String, Any?> -> command { engine.incoming(payload) } }
        AsyncFunction("startConversation") Coroutine { options: Map<String, Any?> -> command { engine.start(options) } }
        AsyncFunction("joinConversation") Coroutine { id: String -> command { engine.join(id) } }
        AsyncFunction("endConversation") Coroutine { id: String -> command { engine.end(id) } }
        AsyncFunction("setHeld") Coroutine { id: String, value: Boolean -> command { engine.hold(id, value) } }
        AsyncFunction("setMuted") Coroutine { id: String, value: Boolean -> command { engine.mute(id, value) } }
        AsyncFunction("playTones") Coroutine { id: String, digits: String -> command { engine.tones(id, digits) } }
        AsyncFunction("mergeConversations") Coroutine { _: String, _: String -> command<Unit> { error("unsupported") } }
        AsyncFunction("reportConversation") Coroutine { id: String, report: Map<String, Any?> -> command { engine.report(id, report) } }
        AsyncFunction("selectAudioRoute") Coroutine { id: String -> command { engine.selectRoute(id) } }
        Function("setHandlerAttached") { attached: Boolean, generation: Int -> engine.jobs.launch { if (engine.bridgeOwner === owner) engine.actions.attach(attached, generation) }; Unit }
        Function("setDialRequestListenerAttached") { _: Boolean, _: Int -> Unit }
        Function("settleAction") { id: String, generation: Int, success: Boolean, _: Map<String, Any?>? ->
            engine.jobs.launch { if (engine.bridgeOwner === owner) engine.actions.settle(id, generation, success) }; Unit
        }
    }
}
