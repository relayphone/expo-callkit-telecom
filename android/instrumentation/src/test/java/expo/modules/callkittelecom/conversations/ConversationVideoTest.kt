package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ConversationVideoTest {
    private class Fixture(initial: Boolean = false) {
        var live = true
        var supported = true
        val events = mutableListOf<Pair<Boolean, VideoChangeSource>>()
        val video = ConversationVideo(initial, { check(live) { "invalid_arguments" } },
            { check(supported) { "unsupported" } }) { value, source -> events += value to source }
        init { video.observed(initial) }
        suspend fun set(value: Boolean, source: VideoChangeSource = VideoChangeSource.APP) =
            video.set(value, source, { video.observed(value); Unit }, {})
    }

    @Test fun capabilityMatchesTheActualRegistrationBound() {
        val normal = ConversationTelecomRegistration()
        assertEquals(34, normal.backwardsCompatSdkLevel)
        for (sdk in 26..38) assertTrue(normal.supportsVideo(sdk))
        val transactionalOn34 = ConversationTelecomRegistration(33)
        for (sdk in 26..38) assertEquals(sdk != 34, transactionalOn34.supportsVideo(sdk))
        assertTrue(ConversationTelecomRegistration(34).supportsVideo(34))
        assertFalse(ConversationTelecomRegistration(26).supportsVideo(34))
        assertFalse(ConversationCapabilities(supportsVideo = false).snapshot().getValue("video"))
        assertEquals("unsupported", runCatching { ConversationCapabilities(supportsVideo = false).requireVideo() }.exceptionOrNull()?.message)
    }

    @Test fun initialAndSameValuesAreSilentAndEverySourceIsRetained() = runBlocking {
        val f = Fixture()
        f.set(false)
        assertFalse(f.video.observed(false))
        assertTrue(f.events.isEmpty())
        f.set(true)
        f.set(true, VideoChangeSource.SERVER)
        f.set(false, VideoChangeSource.SERVER)
        f.video.observed(true)
        f.video.observed(false)
        assertEquals(listOf(true to VideoChangeSource.APP, false to VideoChangeSource.SERVER,
            true to VideoChangeSource.SYSTEM, false to VideoChangeSource.SYSTEM), f.events)
    }

    @Test fun requestWaitsForBothCoreSuccessAndTheMatchingFlowAndSerializesOtherCommands() = runBlocking {
        val f = Fixture()
        val returned = CompletableDeferred<Unit>()
        val requests = mutableListOf<Boolean>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            f.video.set(true, VideoChangeSource.APP, { requests += true; returned.await() }, {})
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            f.video.set(false, VideoChangeSource.SERVER, { requests += false; f.video.observed(false); Unit }, {})
        }
        f.video.observed(true)
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        assertEquals(listOf(true), requests)
        returned.complete(Unit)
        first.await(); second.await()
        assertEquals(listOf(true, false), requests)
        assertEquals(listOf(true to VideoChangeSource.APP, false to VideoChangeSource.SERVER), f.events)
    }

    @Test fun successfulRequestAloneDoesNotChangeTheSnapshot() = runBlocking {
        val f = Fixture()
        val change = async(start = CoroutineStart.UNDISPATCHED) {
            f.video.set(true, VideoChangeSource.SERVER, {}, {})
        }
        assertFalse(f.video.isVideo)
        assertTrue(f.events.isEmpty())
        assertFalse(change.isCompleted)
        f.video.observed(true)
        change.await()
        assertTrue(f.video.isVideo)
        assertEquals(VideoChangeSource.SERVER, f.events.single().second)
    }

    @Test fun matchedRequestDoesNotClaimLaterSystemTransitionsWhileCoreReturnIsDelayed() = runBlocking {
        val f = Fixture()
        val returned = CompletableDeferred<Unit>()
        val change = async(start = CoroutineStart.UNDISPATCHED) {
            f.video.set(true, VideoChangeSource.APP, { returned.await() }, {})
        }
        f.video.observed(true)
        f.video.observed(false)
        f.video.observed(true)
        returned.complete(Unit); change.await()
        assertEquals(listOf(true to VideoChangeSource.APP, false to VideoChangeSource.SYSTEM,
            true to VideoChangeSource.SYSTEM), f.events)
    }

    @Test fun unknownCoreFailureLeavesStateAndLaterSystemAttributionIntact() = runBlocking {
        val f = Fixture()
        val failure = runCatching { f.video.set(true, VideoChangeSource.APP, { error("conversation") }, {}) }.exceptionOrNull()
        assertEquals("conversation", failure?.message)
        assertFalse(f.video.isVideo)
        f.video.observed(true)
        assertEquals(listOf(true to VideoChangeSource.SYSTEM), f.events)
    }

    @Test fun unsupportedAndEndedCallsRejectEvenSameValueWithoutCallingCore() = runBlocking {
        val f = Fixture()
        f.supported = false
        assertEquals("unsupported", runCatching { f.set(false) }.exceptionOrNull()?.message)
        f.live = false
        assertEquals("invalid_arguments", runCatching { f.set(false) }.exceptionOrNull()?.message)
        assertTrue(f.events.isEmpty())
    }

    @Test fun endingWakesPendingCommandWithoutPublishingSuccess() = runBlocking {
        val f = Fixture()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { f.video.set(true, VideoChangeSource.APP, {}, {}) }.exceptionOrNull()
        }
        f.live = false; f.video.close()
        assertEquals("invalid_arguments", result.await()?.message)
        assertTrue(f.events.isEmpty())
    }

    @Test fun lateAutomaticSpeakerMoveRestoresOnlyOnTheFollowingDowngrade() = runBlocking {
        val f = Fixture()
        val receiver = VideoUpgradeRoute.Endpoint("receiver", VideoUpgradeRoute.Kind.RECEIVER)
        val speaker = VideoUpgradeRoute.Endpoint("speaker", VideoUpgradeRoute.Kind.SPEAKER)
        f.video.route.observed(receiver)
        f.set(true)
        f.video.route.observed(speaker)
        var restored = 0
        f.video.set(false, VideoChangeSource.APP, { f.video.observed(false); Unit }, { restored++ })
        assertEquals(1, restored)
        assertFalse(f.video.route.takeRestore())
    }

    @Test fun routeChangesSelectionsAndPrivateEndpointsDisarmAutomaticRestoration() {
        val receiver = VideoUpgradeRoute.Endpoint("receiver", VideoUpgradeRoute.Kind.RECEIVER)
        val speaker = VideoUpgradeRoute.Endpoint("speaker", VideoUpgradeRoute.Kind.SPEAKER)
        for (kind in VideoUpgradeRoute.Kind.entries) {
            val route = VideoUpgradeRoute()
            route.observed(receiver); route.upgrading(); route.observed(speaker)
            route.observed(VideoUpgradeRoute.Endpoint("changed", kind))
            assertFalse(route.takeRestore())
        }
        val selected = VideoUpgradeRoute()
        selected.observed(receiver); selected.upgrading(); selected.observed(speaker); selected.selected()
        assertFalse(selected.takeRestore())
        val alreadySpeaker = VideoUpgradeRoute()
        alreadySpeaker.observed(speaker); alreadySpeaker.upgrading()
        assertFalse(alreadySpeaker.takeRestore())
        val privateRoute = VideoUpgradeRoute()
        privateRoute.observed(VideoUpgradeRoute.Endpoint("headphones", VideoUpgradeRoute.Kind.PRIVATE))
        privateRoute.upgrading(); privateRoute.observed(speaker)
        assertFalse(privateRoute.takeRestore())
    }

    @Test fun speakerCallbackAfterDowngradeStillRestoresUnlessAnotherRouteIntervened() = runBlocking {
        for (intervened in listOf(false, true)) {
            val f = Fixture()
            f.video.route.observed(VideoUpgradeRoute.Endpoint("receiver", VideoUpgradeRoute.Kind.RECEIVER))
            f.set(true)
            f.set(false)
            if (intervened) f.video.route.observed(VideoUpgradeRoute.Endpoint("wired", VideoUpgradeRoute.Kind.PRIVATE))
            val moved = f.video.route.observed(VideoUpgradeRoute.Endpoint("speaker", VideoUpgradeRoute.Kind.SPEAKER))
            assertEquals(!intervened, moved)
            var restored = 0
            if (moved) f.video.restoreFromSystem { restored++ }
            assertEquals(if (intervened) 0 else 1, restored)
        }
    }

    @Test fun duplicateEndpointEchoDoesNotDisarmRestorationAndSystemRestoreIsSerialized() = runBlocking {
        val f = Fixture()
        f.video.route.observed(VideoUpgradeRoute.Endpoint("receiver", VideoUpgradeRoute.Kind.RECEIVER))
        f.video.observed(true)
        val speaker = VideoUpgradeRoute.Endpoint("speaker", VideoUpgradeRoute.Kind.SPEAKER)
        f.video.route.observed(speaker); f.video.route.observed(speaker)
        assertTrue(f.video.observed(false))
        var restored = 0
        f.video.restoreFromSystem { restored++ }
        f.video.restoreFromSystem { restored++ }
        assertEquals(1, restored)
    }

    @Test fun endpointDiscoveryAfterUpgradeTracksOnlyAnObservedReceiverToSpeakerMove() {
        val receiver = VideoUpgradeRoute.Endpoint("receiver", VideoUpgradeRoute.Kind.RECEIVER)
        val speaker = VideoUpgradeRoute.Endpoint("speaker", VideoUpgradeRoute.Kind.SPEAKER)
        val late = VideoUpgradeRoute()
        late.upgrading(); late.observed(receiver); late.observed(speaker)
        assertTrue(late.takeRestore())
        for (kind in listOf(VideoUpgradeRoute.Kind.PRIVATE, VideoUpgradeRoute.Kind.SPEAKER, VideoUpgradeRoute.Kind.OTHER)) {
            val route = VideoUpgradeRoute()
            route.upgrading(); route.observed(VideoUpgradeRoute.Endpoint("initial", kind)); route.observed(speaker)
            assertFalse(route.takeRestore())
        }
        val selected = VideoUpgradeRoute()
        selected.upgrading(); selected.selected(); selected.observed(receiver); selected.observed(speaker)
        assertFalse(selected.takeRestore())
    }

    @Test fun closeCancelsSuspendedSystemRestorationAndReleasesQueuedCommands() = runBlocking {
        val f = Fixture()
        f.video.route.observed(VideoUpgradeRoute.Endpoint("receiver", VideoUpgradeRoute.Kind.RECEIVER))
        f.video.observed(true)
        f.video.route.observed(VideoUpgradeRoute.Endpoint("speaker", VideoUpgradeRoute.Kind.SPEAKER))
        f.video.observed(false)
        val restoreStarted = CompletableDeferred<Unit>()
        val restore = launch(start = CoroutineStart.UNDISPATCHED) {
            f.video.restoreFromSystem { restoreStarted.complete(Unit); awaitCancellation() }
        }
        restoreStarted.await()
        val command = async(start = CoroutineStart.UNDISPATCHED) { runCatching { f.set(true) }.exceptionOrNull() }
        assertFalse(command.isCompleted)
        f.live = false; f.video.close()
        withTimeout(1000) {
            restore.join()
            assertTrue(restore.isCancelled)
            assertEquals("invalid_arguments", command.await()?.message)
        }
    }
}
