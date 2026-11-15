package expo.modules.callkittelecom.conversations

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Publish complete fixture evidence; a reader sees the previous file or the completed replacement. */
internal fun writeMediaEvidence(file: File, contents: String) {
    val staged = File(file.parentFile, ".${file.name}.tmp")
    try {
        FileOutputStream(staged).use { stream ->
            stream.write(contents.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        Files.move(staged.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    } finally { Files.deleteIfExists(staged.toPath()) }
}
