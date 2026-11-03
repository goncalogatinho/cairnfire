package cairnfire

import com.github.ajalt.clikt.core.CliktError
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.WatchKey
import java.util.concurrent.TimeUnit
import kotlin.io.path.isDirectory

/**
 * Watches folders (and subfolders) and reports when something changes. It only says "something changed":
 * what to copy is still decided by the [SyncEngine], so extra or missing events never break anything.
 */
class ChangeWatcher : AutoCloseable {
    private val service = FileSystems.getDefault().newWatchService()
    private val registered = mutableSetOf<Path>()

    /** Registers [root] and all its subfolders. Can be called again to pick up folders created in the meantime. */
    fun watch(root: Path) {
        if (!root.isDirectory()) return
        val dirs = Files.walk(root).use { stream -> stream.filter { it.isDirectory() }.toList() }
        for (dir in dirs) {
            if (!registered.add(dir)) continue
            try {
                dir.register(service, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
            } catch (e: IOException) {
                registered.remove(dir) // the folder disappeared in the meantime; tried again on the next cycle
            }
        }
    }

    /** Waits up to [timeoutMillis] for a change. Returns true if there was one. */
    fun awaitChange(timeoutMillis: Long): Boolean {
        val key = service.poll(timeoutMillis, TimeUnit.MILLISECONDS) ?: return false
        handle(key)
        return true
    }

    /**
     * Waits for changes to settle: only returns after [quietMillis] without events, or after
     * [maxMillis]. This way a save is not copied while the game is still writing it.
     */
    fun awaitQuiet(quietMillis: Long, maxMillis: Long) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxMillis)
        while (System.nanoTime() < deadline) {
            val key = service.poll(quietMillis, TimeUnit.MILLISECONDS) ?: return
            handle(key)
        }
    }

    private fun handle(key: WatchKey) {
        key.pollEvents()
        if (!key.reset()) registered.remove(key.watchable() as Path)
    }

    override fun close() = service.close()
}

/** The folders to watch: each game's local saves and its folder in the cloud. */
fun watchedDirs(config: Config = ConfigStore.load()): List<Path> {
    val platform = Platform.current()
    return config.games.flatMap { game ->
        listOfNotNull(
            game.paths[platform.key]?.let(::expandPath),
            Path.of(config.syncRoot).resolve(game.name),
        )
    }
}

/**
 * The automatic mode loop: runs [cycle] and then waits for changes (or [intervalSec] seconds) before
 * repeating. It never ends by itself: it stops when the thread is interrupted. Expected errors (cloud not
 * mounted, I/O) go to [onError] and the loop tries again later.
 */
fun watchLoop(intervalSec: Int, debounceSec: Int, onError: (String) -> Unit, cycle: () -> Unit) {
    ChangeWatcher().use { watcher ->
        while (true) {
            try {
                cycle()
                watchedDirs().forEach(watcher::watch)
            } catch (e: CliktError) {
                onError(e.message ?: "error") // for example the cloud is not mounted; tried again later
            } catch (e: IOException) {
                onError("I/O error: ${e.message}")
            }
            if (watcher.awaitChange(intervalSec * 1000L)) {
                watcher.awaitQuiet(debounceSec * 1000L, maxMillis = 60_000)
            }
        }
    }
}
