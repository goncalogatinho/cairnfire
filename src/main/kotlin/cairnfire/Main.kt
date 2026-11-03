package cairnfire

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import java.io.IOException
import java.nio.file.Path
import java.time.LocalTime
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

class Cairnfire : CliktCommand(name = "cairnfire") {
    override fun help(context: Context) =
        "Sincroniza saves de jogos entre o Windows e o Mac através de uma pasta na cloud."

    override fun run() = Unit
}

class InitCmd : CliktCommand(name = "init") {
    override fun help(context: Context) = "Define a pasta de sincronização (a pasta do Drive/Dropbox/OneDrive)."

    private val syncRoot by option("--sync-root", help = "Pasta partilhada na cloud").required()

    override fun run() {
        val root = expandPath(syncRoot)
        if (!root.isDirectory()) throw CliktError("A pasta '$root' não existe.")
        val existing = ConfigStore.loadOrNull()
        ConfigStore.save(Config(root.toString(), existing?.games ?: emptyList()))
        echo("Pasta de sincronização: $root")
    }
}

class AddCmd : CliktCommand(name = "add") {
    override fun help(context: Context) = "Regista um jogo (ou atualiza os caminhos de um jogo já registado)."

    private val name by argument(help = "Nome do jogo (usado como nome da pasta na cloud)")
    private val windows by option("--windows", help = "Pasta dos saves no Windows")
    private val mac by option("--mac", help = "Pasta dos saves no Mac")
    private val linux by option("--linux", help = "Pasta dos saves no Linux")

    override fun run() {
        validateGameName(name)
        val config = ConfigStore.load()
        val newPaths = listOfNotNull(
            windows?.let { Platform.WINDOWS.key to it },
            mac?.let { Platform.MAC.key to it },
            linux?.let { Platform.LINUX.key to it },
        ).toMap()
        if (newPaths.isEmpty()) throw CliktError("Indica pelo menos um caminho (--windows, --mac ou --linux).")

        val old = config.games.find { it.name == name }
        val game = GameConfig(name, (old?.paths ?: emptyMap()) + newPaths)
        ConfigStore.save(config.copy(games = config.games.filter { it.name != name } + game))
        echo("Jogo '$name' guardado (${game.paths.keys.joinToString(", ")}).")
    }
}

class ListCmd : CliktCommand(name = "list") {
    override fun help(context: Context) = "Lista os jogos registados."

    override fun run() {
        val config = ConfigStore.load()
        echo("Pasta de sincronização: ${config.syncRoot}")
        if (config.games.isEmpty()) echo("Sem jogos registados. Usa: cairnfire add <nome> --windows <pasta> --mac <pasta>")
        for (g in config.games) {
            echo("- ${g.name}")
            for ((os, path) in g.paths) echo("    $os: $path")
        }
    }
}

class StatusCmd : CliktCommand(name = "status") {
    override fun help(context: Context) = "Mostra o que seria sincronizado, sem alterar nada."

    private val game by argument(help = "Jogo (por omissão, todos)").optional()

    override fun run() = runGames(game, dryRun = true) { echo(it) }
}

class SyncCmd : CliktCommand(name = "sync") {
    override fun help(context: Context) = "Sincroniza os saves entre esta máquina e a pasta na cloud."

    private val game by argument(help = "Jogo (por omissão, todos)").optional()
    private val dryRun by option("--dry-run", help = "Só mostra o que faria").flag()

    override fun run() = runGames(game, dryRun) { echo(it) }
}

class WatchCmd : CliktCommand(name = "watch") {
    override fun help(context: Context) =
        "Fica a correr e sincroniza sozinho quando os saves (ou a cloud) mudam. Termina com Ctrl+C."

    private val interval by option("--interval", help = "Segundos entre verificações mesmo sem eventos (apanha a cloud)")
        .int().default(60)
    private val debounce by option("--debounce", help = "Segundos de sossego antes de sincronizar depois de uma alteração")
        .int().default(5)

    override fun run() {
        if (interval < 1 || debounce < 1) throw CliktError("--interval e --debounce têm de ser pelo menos 1.")
        val say = { line: String -> echo("${LocalTime.now().withNano(0)}  $line") }
        say("A vigiar os saves (a cada ${interval}s e a cada alteração). Ctrl+C para parar.")

        ChangeWatcher().use { watcher ->
            while (true) {
                try {
                    runGames(null, dryRun = false, quiet = true, echo = say)
                    watchedDirs().forEach(watcher::watch)
                } catch (e: CliktError) {
                    say("Erro: ${e.message}") // por exemplo a cloud não está montada; tenta-se de novo mais tarde
                } catch (e: IOException) {
                    say("Erro de E/S: ${e.message}")
                }
                if (watcher.awaitChange(interval * 1000L)) {
                    watcher.awaitQuiet(debounce * 1000L, maxMillis = 60_000)
                }
            }
        }
    }
}

/** As pastas a vigiar: os saves locais de cada jogo e a respetiva pasta na cloud. */
private fun watchedDirs(): List<Path> {
    val config = ConfigStore.load()
    val platform = Platform.current()
    return config.games.flatMap { game ->
        listOfNotNull(
            game.paths[platform.key]?.let(::expandPath),
            Path.of(config.syncRoot).resolve(game.name),
        )
    }
}

private fun runGames(only: String?, dryRun: Boolean, quiet: Boolean = false, echo: (String) -> Unit) {
    val config = ConfigStore.load()
    val syncRoot = Path.of(config.syncRoot)
    if (!syncRoot.isDirectory()) {
        throw CliktError("A pasta de sincronização '$syncRoot' não existe (a cloud está montada?). Nada foi alterado.")
    }

    val games = if (only == null) config.games else listOf(
        config.games.find { it.name == only } ?: throw CliktError("Jogo '$only' não registado."),
    )
    if (games.isEmpty()) {
        echo("Sem jogos registados. Usa: cairnfire add <nome> --windows <pasta> --mac <pasta>")
        return
    }

    val platform = Platform.current()
    val engine = SyncEngine(ConfigStore.backupRoot)

    for (game in games) {
        val rawLocal = game.paths[platform.key]
        if (rawLocal == null) {
            echo("[${game.name}] sem caminho configurado para ${platform.key}; ignorado.")
            continue
        }
        val localDir = expandPath(rawLocal)
        val remoteDir = syncRoot.resolve(game.name)
        val base = ConfigStore.loadState(game.name)

        // Proteção: se uma das pastas desapareceu mas já sincronizámos antes, isto parece um erro
        // (caminho errado, disco desmontado) e não uma decisão de apagar tudo.
        if (base.isNotEmpty() && !localDir.exists()) {
            echo("[${game.name}] a pasta local '$localDir' não existe; ignorado para não apagar nada na cloud.")
            continue
        }
        if (base.isNotEmpty() && !remoteDir.exists()) {
            echo("[${game.name}] a pasta '$remoteDir' desapareceu da cloud; ignorado para não apagar nada local.")
            continue
        }

        val result = engine.sync(game.name, localDir, remoteDir, base, dryRun)
        if (result.actions.isEmpty()) {
            if (!quiet) echo("[${game.name}] tudo sincronizado.")
        } else {
            echo("[${game.name}] ${if (dryRun) "a fazer" else "feito"}:")
            for (a in result.actions) {
                val flag = if (a.conflict) "  !" else "   "
                echo("$flag ${a.kind.label.padEnd(16)} ${a.path}${a.note?.let { "  ($it)" } ?: ""}")
            }
            val backupDir = ConfigStore.backupRoot.resolve(game.name)
            if (!dryRun && backupDir.exists()) {
                echo("    (versões substituídas ou apagadas ficam em $backupDir)")
            }
        }
        if (!dryRun) ConfigStore.saveState(game.name, result.newBase)
    }
}

fun main(args: Array<String>) =
    Cairnfire()
        .subcommands(
            InitCmd(), AddCmd(), ListCmd(), StatusCmd(), SyncCmd(), WatchCmd(),
            AutostartCmd().subcommands(AutostartInstallCmd(), AutostartUninstallCmd(), AutostartStatusCmd()),
        )
        .main(args)
