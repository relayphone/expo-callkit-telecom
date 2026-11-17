package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Supplies the Android main dispatcher for JVM fixtures that construct main-bound scopes. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule : TestWatcher() {
    override fun starting(description: Description) { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    override fun finished(description: Description) { Dispatchers.resetMain() }
}
