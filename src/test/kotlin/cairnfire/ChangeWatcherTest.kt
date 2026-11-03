package cairnfire

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// On macOS the JDK WatchService polls every ~10s, so the timeouts are generous.
class ChangeWatcherTest {
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("cairnfire-watch")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `with no changes the await times out`() {
        ChangeWatcher().use { w ->
            w.watch(dir)
            assertFalse(w.awaitChange(300))
        }
    }

    @Test
    fun `detects a new file`() {
        ChangeWatcher().use { w ->
            w.watch(dir)
            (dir / "a.sav").writeText("1")
            assertTrue(w.awaitChange(30_000))
        }
    }

    @Test
    fun `detects changes in subfolders`() {
        val sub = (dir / "slot1").createDirectories()
        ChangeWatcher().use { w ->
            w.watch(dir)
            (sub / "a.sav").writeText("1")
            assertTrue(w.awaitChange(30_000))
        }
    }

    @Test
    fun `a folder that does not exist is ignored`() {
        ChangeWatcher().use { w ->
            w.watch(dir / "does-not-exist")
            assertFalse(w.awaitChange(100))
        }
    }

    private operator fun Path.div(name: String): Path = resolve(name)
}
