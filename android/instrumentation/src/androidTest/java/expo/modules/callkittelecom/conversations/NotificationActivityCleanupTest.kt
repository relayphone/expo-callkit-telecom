package expo.modules.callkittelecom.conversations

import android.app.Activity
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationActivityCleanupTest {
    @Test fun aFailedBodyClosesEveryFixtureActivityIncludingThePreviousTask() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activities = mutableListOf<Activity>()
        try {
            for (type in listOf(NotificationLaunchActivity::class.java, NotificationAlternateActivity::class.java)) {
                activities += withContext(Dispatchers.IO) {
                    instrumentation.startActivitySync(Intent(instrumentation.targetContext, type)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
                }
            }
            withContext(Dispatchers.Main) {
                val failure = AssertionError("Deliberate fixture body failure")
                val observed = runCatching { withPresentationCleanup({ throw failure }, {
                    NotificationLaunchActivity.finishAll()
                    withTimeout(5000) { while (NotificationLaunchActivity.hasActivities) delay(10) }
                }) }.exceptionOrNull()
                assertSame("Cleanup must preserve the original failure", failure, observed)
                assertTrue("Every fixture Activity must be destroyed", activities.all { it.isDestroyed })
                assertFalse(NotificationLaunchActivity.hasActivities)
            }
        } finally {
            withContext(Dispatchers.Main) {
                activities.forEach { it.finish() }
                withTimeout(5000) { while (activities.any { !it.isDestroyed }) delay(10) }
            }
        }
    }
}
