package expo.modules.callkittelecom.conversations

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CallIdentityTest {
    @Test fun sharedSdkVectors() {
        val json = InstrumentationRegistry.getInstrumentation().context.assets
            .open("call-identity.json").bufferedReader().use { it.readText() }
        val contract = JSONObject(json)
        assertEquals("d460ee4a-efbc-5b0d-8035-0b915bf9551a", contract.getString("namespace"))
        val vectors = contract.getJSONArray("vectors")
        assertTrue(vectors.length() >= 11)
        for (i in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(i)
            assertEquals(vector.getString("name"), vector.getString("uuid"),
                CallIdentity.forServerCallId(vector.getString("serverCallId")).toString())
        }
    }
}
