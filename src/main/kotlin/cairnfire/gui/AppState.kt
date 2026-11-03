package cairnfire.gui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cairnfire.Action
import cairnfire.Config
import cairnfire.ConfigStore
import cairnfire.GameConfig
import cairnfire.GameReport
import cairnfire.Kind
import cairnfire.Platform
import cairnfire.expandPath
import cairnfire.requireSyncRoot
import cairnfire.syncGame
import cairnfire.validateGameName
import cairnfire.watchLoop
import com.github.ajalt.clikt.core.CliktError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.nio.file.Path
import java.time.LocalTime

/** O que a interface mostra de cada jogo. */
sealed interface GameStatus {
    data object Checking : GameStatus
    data object Synced : GameStatus
    data class Pending(val actions: List<Action>) : GameStatus
    data class Skipped(val reason: String) : GameStatus
    data class Failed(val message: String) : GameStatus
}

data class LogLine(val time: String, val text: String, val error: Boolean = false)

/** O formulário de adicionar/editar um jogo. */
data class GameDraft(
    val name: String = "",
    val windows: String = "",
    val mac: String = "",
    val editing: Boolean = false,
    val error: String? = null,
)

fun statusOf(report: GameReport): GameStatus = when {
    report.skipped != null -> GameStatus.Skipped(report.skipped)
    report.actions.isEmpty() -> GameStatus.Synced
    else -> GameStatus.Pending(report.actions)
}

private fun plural(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"

/** "2 enviados · 1 trazido · 1 conflito": uma linha para o registo de atividade. */
fun summary(actions: List<Action>): String {
    val pushed = actions.count { it.kind == Kind.PUSH }
    val pulled = actions.count { it.kind == Kind.PULL }
    val deleted = actions.count { it.kind == Kind.DELETE_LOCAL || it.kind == Kind.DELETE_REMOTE }
    val conflicts = actions.count { it.conflict }
    return listOfNotNull(
        pushed.takeIf { it > 0 }?.let { plural(it, "enviado", "enviados") },
        pulled.takeIf { it > 0 }?.let { plural(it, "trazido", "trazidos") },
        deleted.takeIf { it > 0 }?.let { plural(it, "apagado", "apagados") },
        conflicts.takeIf { it > 0 }?.let { plural(it, "conflito", "conflitos") },
    ).joinToString(" · ")
}

/**
 * Todo o estado da janela. Os comandos que mexem em ficheiros correm em threads de fundo; o estado em si é
 * "snapshot state" do Compose, por isso pode ser escrito de qualquer thread.
 */
class AppState(private val scope: CoroutineScope) {
    var config by mutableStateOf<Config?>(null)
    val statuses = mutableStateMapOf<String, GameStatus>()
    val log = mutableStateListOf<LogLine>()
    var busy by mutableStateOf(false)
    var auto by mutableStateOf(false)
    var expanded by mutableStateOf<String?>(null)
    var draft by mutableStateOf<GameDraft?>(null)

    private var autoJob: Job? = null
    private val lock = Any()

    val platform: Platform = Platform.current()

    fun localPath(game: GameConfig): String? = game.paths[platform.key]

    private fun say(text: String, error: Boolean = false) {
        log += LogLine(LocalTime.now().withNano(0).toString(), text, error)
        while (log.size > 200) log.removeAt(0)
    }

    // ---------- carregar e verificar ----------

    fun load() {
        config = ConfigStore.loadOrNull()
        refresh()
    }

    /** Vê o que cada jogo precisa (sem alterar nada). */
    fun refresh() {
        val cfg = config ?: return
        scope.launch(Dispatchers.IO) { check(cfg) }
    }

    private fun check(cfg: Config) = guarded {
        requireSyncRoot(cfg)
        for (game in cfg.games) {
            statuses[game.name] = GameStatus.Checking
            statuses[game.name] = plan(cfg, game)
        }
    }

    // ---------- sincronizar ----------

    fun syncAll() {
        scope.launch(Dispatchers.IO) { guarded { syncAllBlocking(quiet = false) } }
    }

    fun syncOne(name: String) {
        val cfg = config ?: return
        val game = cfg.games.find { it.name == name } ?: return
        scope.launch(Dispatchers.IO) {
            guarded {
                requireSyncRoot(cfg)
                synchronized(lock) {
                    busy = true
                    try { statuses[name] = apply(cfg, game, quiet = false) } finally { busy = false }
                }
            }
        }
    }

    /** Um ciclo completo (também usado pelo modo automático). Bloqueia até acabar. */
    fun syncAllBlocking(quiet: Boolean) {
        val cfg = ConfigStore.loadOrNull() ?: throw CliktError("Ainda não há pasta da cloud configurada.")
        config = cfg
        requireSyncRoot(cfg)
        synchronized(lock) {
            busy = true
            try {
                for (game in cfg.games) statuses[game.name] = apply(cfg, game, quiet)
            } finally {
                busy = false
            }
        }
    }

    private fun apply(cfg: Config, game: GameConfig, quiet: Boolean): GameStatus = try {
        val report = syncGame(cfg, game, dryRun = false)
        when {
            report.skipped != null -> say("[${game.name}] ${report.skipped}")
            report.actions.isNotEmpty() -> say("[${game.name}] ${summary(report.actions)}")
            !quiet -> say("[${game.name}] tudo sincronizado.")
        }
        statusOf(report)
    } catch (e: Exception) {
        say("[${game.name}] erro: ${e.message}", error = true)
        GameStatus.Failed(e.message ?: "erro")
    }

    private fun plan(cfg: Config, game: GameConfig): GameStatus = try {
        statusOf(syncGame(cfg, game, dryRun = true))
    } catch (e: Exception) {
        GameStatus.Failed(e.message ?: "erro")
    }

    /** Erros previsíveis (cloud não montada...) viram uma linha no registo em vez de rebentar a janela. */
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: CliktError) {
            say(e.message ?: "erro", error = true)
        }
    }

    // ---------- pasta da cloud ----------

    fun setSyncRoot(raw: String) {
        val root: Path = expandPath(raw)
        if (!java.nio.file.Files.isDirectory(root)) {
            say("A pasta '$root' não existe.", error = true)
            return
        }
        val updated = Config(root.toString(), config?.games ?: emptyList())
        ConfigStore.save(updated)
        config = updated
        say("Pasta da cloud: $root")
        refresh()
    }

    // ---------- jogos ----------

    fun newGame() { draft = GameDraft() }

    fun editGame(game: GameConfig) {
        draft = GameDraft(
            name = game.name,
            windows = game.paths[Platform.WINDOWS.key].orEmpty(),
            mac = game.paths[Platform.MAC.key].orEmpty(),
            editing = true,
        )
    }

    fun saveDraft() {
        val d = draft ?: return
        val cfg = config ?: return
        try {
            validateGameName(d.name.trim())
        } catch (e: CliktError) {
            draft = d.copy(error = e.message)
            return
        }
        val name = d.name.trim()
        if (!d.editing && cfg.games.any { it.name == name }) {
            draft = d.copy(error = "Já existe um jogo com este nome.")
            return
        }
        val old = cfg.games.find { it.name == name }
        val paths = (old?.paths ?: emptyMap()).toMutableMap()
        for ((os, value) in listOf(Platform.WINDOWS to d.windows.trim(), Platform.MAC to d.mac.trim())) {
            if (value.isEmpty()) paths.remove(os.key) else paths[os.key] = value
        }
        if (paths.isEmpty()) {
            draft = d.copy(error = "Indica pelo menos uma pasta de saves.")
            return
        }
        val updated = cfg.copy(games = cfg.games.filter { it.name != name } + GameConfig(name, paths))
        ConfigStore.save(updated)
        config = updated
        draft = null
        say("Jogo '$name' guardado.")
        refresh()
    }

    /** Tira o jogo da lista. Não apaga nenhum ficheiro de saves, nem na cloud nem neste computador. */
    fun removeGame(name: String) {
        val cfg = config ?: return
        val updated = cfg.copy(games = cfg.games.filter { it.name != name })
        ConfigStore.save(updated)
        ConfigStore.deleteState(name)
        config = updated
        statuses.remove(name)
        if (expanded == name) expanded = null
        say("Jogo '$name' removido da lista (os ficheiros não foram tocados).")
    }

    // ---------- modo automático ----------

    fun enableAuto(on: Boolean) {
        auto = on
        autoJob?.cancel()
        autoJob = null
        if (!on) {
            say("Sincronização automática desligada.")
            return
        }
        say("Sincronização automática ligada.")
        autoJob = scope.launch(Dispatchers.IO) {
            try {
                runInterruptible {
                    watchLoop(intervalSec = 60, debounceSec = 5, onError = { say(it, error = true) }) {
                        syncAllBlocking(quiet = true)
                    }
                }
            } finally {
                busy = false
            }
        }
    }

    fun stop() {
        autoJob?.cancel()
    }

    companion object {
        /** Dados de exemplo para a pré-visualização da documentação: não toca em ficheiros. */
        fun demo(scope: CoroutineScope, variant: String = "list"): AppState = AppState(scope).apply {
            config = Config(
                "~/Google Drive/Cairnfire",
                listOf(
                    GameConfig("Stardew Valley", mapOf("mac" to "~/.config/StardewValley/Saves", "windows" to "%APPDATA%/StardewValley/Saves")),
                    GameConfig("Hades", mapOf("mac" to "~/Library/Application Support/Supergiant Games/Hades/Profile1")),
                    GameConfig("Celeste", mapOf("mac" to "~/Library/Application Support/Celeste/Saves")),
                    GameConfig("Hollow Knight", mapOf("windows" to "%USERPROFILE%/AppData/LocalLow/Team Cherry/Hollow Knight")),
                ),
            )
            statuses["Stardew Valley"] = GameStatus.Pending(
                listOf(
                    Action(Kind.PUSH, "Farm_123456/Farm_123456"),
                    Action(Kind.PULL, "Farm_123456/SaveGameInfo", conflict = true, note = "alterado nos dois lados; a cloud é mais recente"),
                    Action(Kind.PULL, "Farm_123456/Farm_123456_old"),
                ),
            )
            statuses["Hades"] = GameStatus.Synced
            statuses["Celeste"] = GameStatus.Synced
            statuses["Hollow Knight"] = GameStatus.Skipped("sem caminho configurado para mac; ignorado.")
            when (variant) {
                "expanded" -> { expanded = "Stardew Valley"; config = config?.copy(games = config!!.games.take(2)) }
                "dialog" -> draft = GameDraft(name = "Stardew Valley", mac = "~/.config/StardewValley/Saves")
            }
            log += LogLine("21:14:02", "Pasta da cloud: ~/Google Drive/Cairnfire")
            log += LogLine("21:14:09", "[Hades] 1 enviado")
            log += LogLine("21:14:09", "[Celeste] tudo sincronizado.")
        }
    }
}
