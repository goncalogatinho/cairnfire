package cairnfire

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
 * Vigia pastas (e subpastas) e avisa quando algo muda. Só diz "mudou alguma coisa": quem decide o que
 * copiar continua a ser o [SyncEngine], por isso eventos a mais ou a menos nunca estragam nada.
 */
class ChangeWatcher : AutoCloseable {
    private val service = FileSystems.getDefault().newWatchService()
    private val registered = mutableSetOf<Path>()

    /** Regista [root] e todas as subpastas. Pode ser chamado de novo para apanhar pastas criadas entretanto. */
    fun watch(root: Path) {
        if (!root.isDirectory()) return
        val dirs = Files.walk(root).use { stream -> stream.filter { it.isDirectory() }.toList() }
        for (dir in dirs) {
            if (!registered.add(dir)) continue
            try {
                dir.register(service, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
            } catch (e: IOException) {
                registered.remove(dir) // a pasta desapareceu entretanto; tenta-se de novo no próximo ciclo
            }
        }
    }

    /** Espera até [timeoutMillis] por uma alteração. Devolve true se houve alguma. */
    fun awaitChange(timeoutMillis: Long): Boolean {
        val key = service.poll(timeoutMillis, TimeUnit.MILLISECONDS) ?: return false
        handle(key)
        return true
    }

    /**
     * Espera que as alterações acalmem: só regressa depois de [quietMillis] sem eventos, ou ao fim de
     * [maxMillis]. Assim não se copia um save enquanto o jogo ainda o está a escrever.
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
