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

// No macOS o WatchService do JDK verifica a cada ~10s, por isso os timeouts são generosos.
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
    fun `sem alteracoes o await acaba por timeout`() {
        ChangeWatcher().use { w ->
            w.watch(dir)
            assertFalse(w.awaitChange(300))
        }
    }

    @Test
    fun `detecta um ficheiro novo`() {
        ChangeWatcher().use { w ->
            w.watch(dir)
            (dir / "a.sav").writeText("1")
            assertTrue(w.awaitChange(30_000))
        }
    }

    @Test
    fun `detecta alteracoes em subpastas`() {
        val sub = (dir / "slot1").createDirectories()
        ChangeWatcher().use { w ->
            w.watch(dir)
            (sub / "a.sav").writeText("1")
            assertTrue(w.awaitChange(30_000))
        }
    }

    @Test
    fun `pasta que nao existe e ignorada`() {
        ChangeWatcher().use { w ->
            w.watch(dir / "nao-existe")
            assertFalse(w.awaitChange(100))
        }
    }

    private operator fun Path.div(name: String): Path = resolve(name)
}
