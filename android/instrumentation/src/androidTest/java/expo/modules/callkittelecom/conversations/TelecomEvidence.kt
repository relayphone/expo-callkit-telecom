package expo.modules.callkittelecom.conversations

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** API35 CallsManager dump section; retain only counts/states, never the raw dump. */
internal object TelecomEvidence {
    suspend fun calls(): List<String> = withContext(Dispatchers.IO) {
        val command = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys -t 5 telecom")
        val lines = ParcelFileDescriptor.AutoCloseInputStream(command).bufferedReader().use { it.readLines() }
        val start = lines.indexOfFirst { it.trim() == "mCalls:" }
        check(start >= 0) { "Missing platform CallsManager evidence" }
        val indent = lines[start].takeWhile { it.isWhitespace() }.length
        lines.drop(start + 1).takeWhile { it.isBlank() || it.takeWhile { c -> c.isWhitespace() }.length > indent }
            .filter { it.trimStart().startsWith("[Call id=") }
    }
}
