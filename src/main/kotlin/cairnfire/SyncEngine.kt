package cairnfire

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.div
import kotlin.io.path.exists

enum class Kind(val label: String) {
    PUSH("local  -> cloud"),
    PULL("cloud  -> local"),
    DELETE_REMOTE("apagar na cloud"),
    DELETE_LOCAL("apagar local")
}

data class Action(val kind: Kind, val path: String, val conflict: Boolean = false, val note: String? = null)

data class SyncResult(val actions: List<Action>, val newBase: Map<String, String>)

/**
 * Merge a 3 vias entre a pasta local, a pasta na cloud e a "base" (o que ficou da última sincronização).
 *
 *   só o local mudou    -> PUSH
 *   só a cloud mudou    -> PULL
 *   os dois mudaram     -> conflito: ganha o mais recente (data de modificação) e o outro vai para backup
 *
 * Nada é sobrescrito ou apagado sem antes ficar guardado em [backupRoot].
 */
class SyncEngine(
    private val backupRoot: Path,
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
) {
    fun sync(game: String, localDir: Path, remoteDir: Path, base: Map<String, String>, dryRun: Boolean): SyncResult {
        val local = scan(localDir)
        val remote = scan(remoteDir)
        val actions = plan(local, remote, base)
        if (!dryRun) execute(game, localDir, remoteDir, actions)
        return SyncResult(actions, nextBase(local, remote, actions))
    }

    fun plan(local: Map<String, FileInfo>, remote: Map<String, FileInfo>, base: Map<String, String>): List<Action> {
        val actions = mutableListOf<Action>()
        for (path in (local.keys + remote.keys + base.keys).toSortedSet()) {
            val l = local[path]
            val r = remote[path]
            val b = base[path]
            if (l?.hash == r?.hash) continue // iguais (ou ausentes dos dois lados)

            val action = if (l != null && r != null) {
                when {
                    b == r.hash -> Action(Kind.PUSH, path)
                    b == l.hash -> Action(Kind.PULL, path)
                    l.mtimeMillis > r.mtimeMillis ->
                        Action(Kind.PUSH, path, conflict = true, note = "alterado nos dois lados; o local é mais recente")
                    else ->
                        Action(Kind.PULL, path, conflict = true, note = "alterado nos dois lados; a cloud é mais recente")
                }
            } else if (l != null) {
                when (b) {
                    null -> Action(Kind.PUSH, path)
                    l.hash -> Action(Kind.DELETE_LOCAL, path, note = "apagado noutra máquina")
                    else -> Action(Kind.PUSH, path, conflict = true, note = "alterado aqui mas apagado na cloud; mantém-se")
                }
            } else {
                val remoteInfo = checkNotNull(r)
                when (b) {
                    null -> Action(Kind.PULL, path)
                    remoteInfo.hash -> Action(Kind.DELETE_REMOTE, path, note = "apagado nesta máquina")
                    else -> Action(Kind.PULL, path, conflict = true, note = "apagado aqui mas alterado na cloud; mantém-se")
                }
            }
            actions += action
        }
        return actions
    }

    fun execute(game: String, localDir: Path, remoteDir: Path, actions: List<Action>) {
        if (actions.isEmpty()) return
        val stamp = now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
        val backupDir = backupRoot / game / stamp
        for (a in actions) {
            val l = localDir.resolve(a.path)
            val r = remoteDir.resolve(a.path)
            when (a.kind) {
                Kind.PUSH -> {
                    backup(r, backupDir.resolve("cloud").resolve(a.path))
                    safeCopy(l, r)
                }
                Kind.PULL -> {
                    backup(l, backupDir.resolve("local").resolve(a.path))
                    safeCopy(r, l)
                }
                Kind.DELETE_REMOTE -> {
                    backup(r, backupDir.resolve("cloud").resolve(a.path))
                    r.deleteExisting()
                }
                Kind.DELETE_LOCAL -> {
                    backup(l, backupDir.resolve("local").resolve(a.path))
                    l.deleteExisting()
                }
            }
        }
    }

    /** O novo estado base: o hash de cada ficheiro depois de aplicadas as ações. */
    fun nextBase(local: Map<String, FileInfo>, remote: Map<String, FileInfo>, actions: List<Action>): Map<String, String> {
        val byPath = actions.associateBy { it.path }
        val result = sortedMapOf<String, String>()
        for (path in local.keys + remote.keys) {
            val hash = when (byPath[path]?.kind) {
                Kind.PUSH -> local[path]?.hash
                Kind.PULL -> remote[path]?.hash
                Kind.DELETE_LOCAL, Kind.DELETE_REMOTE -> null
                null -> local[path]?.hash
            }
            if (hash != null) result[path] = hash
        }
        return result
    }

    private fun backup(src: Path, dst: Path) {
        if (!src.exists()) return
        dst.parent.createDirectories()
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
    }
}
