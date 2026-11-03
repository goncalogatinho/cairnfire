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

class GuiCmd : CliktCommand(name = "gui") {
    override fun help(context: Context) = "Abre a janela do Cairnfire."

    private val preview by option("--preview", hidden = true, help = "Desenha a janela com dados de exemplo para um PNG")

    private val variant by option("--preview-variant", hidden = true, help = "list, expanded ou dialog").default("list")

    override fun run() {
        val out = preview
        if (out != null) cairnfire.gui.renderPreview(Path.of(out), variant) else cairnfire.gui.launchGui()
    }
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

        watchLoop(interval, debounce, onError = { say("Erro: $it") }) {
            runGames(null, dryRun = false, quiet = true, echo = say)
        }
    }
}

private fun runGames(only: String?, dryRun: Boolean, quiet: Boolean = false, echo: (String) -> Unit) {
    val config = ConfigStore.load()
    requireSyncRoot(config)

    val games = if (only == null) config.games else listOf(
        config.games.find { it.name == only } ?: throw CliktError("Jogo '$only' não registado."),
    )
    if (games.isEmpty()) {
        echo("Sem jogos registados. Usa: cairnfire add <nome> --windows <pasta> --mac <pasta>")
        return
    }

    val engine = SyncEngine(ConfigStore.backupRoot)
    for (game in games) {
        val report = syncGame(config, game, dryRun, engine)
        if (report.skipped != null) {
            echo("[${game.name}] ${report.skipped}")
        } else if (report.actions.isEmpty()) {
            if (!quiet) echo("[${game.name}] tudo sincronizado.")
        } else {
            echo("[${game.name}] ${if (dryRun) "a fazer" else "feito"}:")
            for (a in report.actions) {
                val flag = if (a.conflict) "  !" else "   "
                echo("$flag ${a.kind.label.padEnd(16)} ${a.path}${a.note?.let { "  ($it)" } ?: ""}")
            }
            val backupDir = ConfigStore.backupRoot.resolve(game.name)
            if (!dryRun && backupDir.exists()) {
                echo("    (versões substituídas ou apagadas ficam em $backupDir)")
            }
        }
    }
}

fun main(args: Array<String>) =
    Cairnfire()
        .subcommands(
            InitCmd(), AddCmd(), ListCmd(), StatusCmd(), SyncCmd(), WatchCmd(), GuiCmd(),
            AutostartCmd().subcommands(AutostartInstallCmd(), AutostartUninstallCmd(), AutostartStatusCmd()),
        )
        .main(args)
