package cairnfire

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

data class FileInfo(val hash: String, val mtimeMillis: Long)

const val TMP_SUFFIX = ".cairnfire-tmp"
private val IGNORED_NAMES = setOf(".DS_Store", "Thumbs.db", "desktop.ini")

/** Expands "~", "%VAR%" and "${VAR}" and returns an absolute path. */
fun expandPath(raw: String): Path {
    var s = raw.trim()
    if (s == "~" || s.startsWith("~/") || s.startsWith("~\\")) {
        s = System.getProperty("user.home") + s.substring(1)
    }
    s = Regex("%([^%]+)%|[$][{]([^}]+)[}]").replace(s) { m ->
        val name = m.groupValues[1].ifEmpty { m.groupValues[2] }
        System.getenv(name) ?: m.value
    }
    return Path.of(s).toAbsolutePath().normalize()
}

fun Path.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** Lists every file under [root] (relative path with '/') with its hash and modification time. */
fun scan(root: Path): Map<String, FileInfo> {
    if (!root.isDirectory()) return emptyMap()
    val result = sortedMapOf<String, FileInfo>()
    Files.walk(root).use { stream ->
        stream.filter { it.isRegularFile() }.forEach { p ->
            val name = p.fileName.toString()
            if (name in IGNORED_NAMES || name.endsWith(TMP_SUFFIX)) return@forEach
            val rel = root.relativize(p).joinToString("/") { it.toString() }
            result[rel] = FileInfo(p.sha256(), p.getLastModifiedTime().toMillis())
        }
    }
    return result
}

/** Copies to a temporary file and only then replaces the destination, so a save is never left half-written. */
fun safeCopy(src: Path, dst: Path) {
    dst.parent.createDirectories()
    val tmp = dst.resolveSibling(dst.fileName.toString() + TMP_SUFFIX)
    Files.copy(src, tmp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
    try {
        Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (e: AtomicMoveNotSupportedException) {
        Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING)
    }
}
