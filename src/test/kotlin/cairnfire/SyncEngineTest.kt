package cairnfire

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.stream.Collectors
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.setLastModifiedTime
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncEngineTest {
    private lateinit var root: Path
    private lateinit var local: Path
    private lateinit var remote: Path
    private lateinit var backups: Path
    private lateinit var engine: SyncEngine
    private var base: Map<String, String> = emptyMap()

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("cairnfire-test")
        local = root.resolve("local").also { it.createDirectories() }
        remote = root.resolve("remote").also { it.createDirectories() }
        backups = root.resolve("backups")
        engine = SyncEngine(backups)
        base = emptyMap()
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    private fun sync(dryRun: Boolean = false): List<Action> {
        val result = engine.sync("game", local, remote, base, dryRun)
        if (!dryRun) base = result.newBase
        return result.actions
    }

    private fun write(dir: Path, rel: String, text: String, mtime: Long = System.currentTimeMillis()) {
        val f = dir.resolve(rel)
        f.parent.createDirectories()
        f.writeText(text)
        f.setLastModifiedTime(FileTime.fromMillis(mtime))
    }

    private fun backedUp(): List<Path> =
        if (!backups.exists()) emptyList() else Files.walk(backups).use { s -> s.filter { it.isRegularFile() }.collect(Collectors.toList()) }

    @Test
    fun `novo ficheiro local vai para a cloud`() {
        write(local, "slot1/save.dat", "A")
        val actions = sync()
        assertEquals(listOf(Action(Kind.PUSH, "slot1/save.dat")), actions)
        assertEquals("A", remote.resolve("slot1/save.dat").readText())
    }

    @Test
    fun `novo ficheiro na cloud vem para o local`() {
        write(remote, "save.dat", "B")
        val actions = sync()
        assertEquals(listOf(Action(Kind.PULL, "save.dat")), actions)
        assertEquals("B", local.resolve("save.dat").readText())
    }

    @Test
    fun `segunda sincronizacao sem alteracoes nao faz nada`() {
        write(local, "save.dat", "A")
        sync()
        assertTrue(sync().isEmpty())
    }

    @Test
    fun `dry run nao altera nada`() {
        write(local, "save.dat", "A")
        val actions = sync(dryRun = true)
        assertEquals(1, actions.size)
        assertFalse(remote.resolve("save.dat").exists())
        assertTrue(base.isEmpty())
    }

    @Test
    fun `alteracao local e enviada e a versao antiga fica em backup`() {
        write(local, "save.dat", "v1")
        sync()
        write(local, "save.dat", "v2", mtime = System.currentTimeMillis() + 5_000)
        val actions = sync()
        assertEquals(listOf(Action(Kind.PUSH, "save.dat")), actions)
        assertEquals("v2", remote.resolve("save.dat").readText())
        assertEquals(listOf("v1"), backedUp().map { it.readText() })
    }

    @Test
    fun `conflito ganha o mais recente e o perdedor fica em backup`() {
        val t = System.currentTimeMillis()
        write(local, "save.dat", "base", mtime = t)
        sync()

        write(local, "save.dat", "do-windows", mtime = t + 1_000)
        write(remote, "save.dat", "do-mac", mtime = t + 2_000)

        val actions = sync()
        assertEquals(1, actions.size)
        assertEquals(Kind.PULL, actions[0].kind)
        assertTrue(actions[0].conflict)
        assertEquals("do-mac", local.resolve("save.dat").readText())
        assertTrue(backedUp().any { it.readText() == "do-windows" })
    }

    @Test
    fun `apagar localmente apaga na cloud com backup`() {
        write(local, "save.dat", "A")
        sync()
        local.resolve("save.dat").deleteExisting()

        val actions = sync()
        assertEquals(listOf(Kind.DELETE_REMOTE), actions.map { it.kind })
        assertFalse(remote.resolve("save.dat").exists())
        assertEquals(listOf("A"), backedUp().map { it.readText() })
        assertTrue(base.isEmpty())
    }

    @Test
    fun `apagado na cloud mas alterado localmente mantem o ficheiro`() {
        val t = System.currentTimeMillis()
        write(local, "save.dat", "A", mtime = t)
        sync()
        remote.resolve("save.dat").deleteExisting()
        write(local, "save.dat", "A2", mtime = t + 1_000)

        val actions = sync()
        assertEquals(Kind.PUSH, actions.single().kind)
        assertTrue(actions.single().conflict)
        assertEquals("A2", remote.resolve("save.dat").readText())
    }

    @Test
    fun `ficheiros de lixo do sistema sao ignorados`() {
        write(local, ".DS_Store", "x")
        write(local, "Thumbs.db", "x")
        assertTrue(sync().isEmpty())
    }
}
