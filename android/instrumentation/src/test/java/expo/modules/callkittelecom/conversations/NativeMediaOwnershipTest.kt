package expo.modules.callkittelecom.conversations

import org.junit.Assert.*
import org.junit.Test

class NativeMediaOwnershipTest {
    private class Session : NativeMediaControl {
        override var isPrewarmed = false
        override val hasSession get() = isPrewarmed
        val closes = mutableListOf<String>()
        var answers = 0
        var muted = false
        override fun prewarm() { isPrewarmed = true }
        override fun answered(audioReady: suspend () -> Unit) { answers++ }
        override suspend fun pause() {}
        override fun resume(audioReady: suspend () -> Unit) {}
        override suspend fun mute() { muted = true }
        override fun clearMute() { muted = false }
        override fun unmute(audioReady: suspend () -> Unit) { muted = false }
        override fun setDesiredMuted(muted: Boolean) { this.muted = muted }
        override fun close(reason: String) { isPrewarmed = false; closes += reason }
    }

    @Test fun lostPrejoinClosesOnceAndAnswerCreatesFreshMediaWithoutClosingAudioAuthority() {
        for (answerMedia in listOf(null, mapOf("token" to "answer"), mapOf("token" to "provided"))) {
            val sessions = mutableListOf<Session>()
            val credentials = mutableListOf<Map<String, Any?>?>()
            val audioEvents = mutableListOf<NativeAudioEvent>()
            val audio = NativeAudioLifecycle({ true }, { 0 }, audioEvents::add)
            val owner = NativeMediaOwner(AndroidNativeMedia.LIVEKIT, audio, initiallyMuted = true) { media ->
                credentials += media
                Session().also { sessions += it }
            }
            owner.prewarm()
            assertTrue(owner.hasSession)
            assertTrue(owner.prewarmLost())
            assertFalse(owner.prewarmLost())
            assertFalse(owner.isPrewarmed); assertFalse(owner.hasSession)
            assertEquals(listOf("prewarmLost"), sessions.single().closes)
            assertTrue(audioEvents.isEmpty())
            owner.answered(answerMedia)
            assertEquals(listOf(null, answerMedia), credentials)
            assertEquals(2, sessions.size)
            assertEquals(1, sessions.last().answers)
            assertTrue(sessions.last().muted)
            // Losing the optional session must leave the same audio lifecycle usable.
            audio.coreActivated()
            assertEquals(listOf(NativeAudioEvent.ACTIVATED), audioEvents)
            owner.close("ended")
            assertEquals(listOf("ended"), sessions.last().closes)
            assertFalse(owner.prewarmLost())
            assertEquals(listOf("prewarmLost"), sessions.first().closes)
        }
    }
}
