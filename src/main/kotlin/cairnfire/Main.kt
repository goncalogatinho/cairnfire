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
        "Syncs game saves between Windows and Mac through a cloud folder."

    override fun run() = Unit
}

class InitCmd : CliktCommand(name = "init") {
    override fun help(context: Context) = "Sets the sync folder (the Drive/Dropbox/OneDrive folder)."

    private val syncRoot by option("--sync-root", help = "Shared cloud folder").required()

    override fun run() {
        val root = expandPath(syncRoot)
        if (!root.isDirectory()) throw CliktError("The folder '$root' does not exist.")
        val existing = ConfigStore.loadOrNull()
        ConfigStore.save(Config(root.toString(), existing?.games ?: emptyList()))
        echo("Sync folder: $root")
    }
}

class AddCmd : CliktCommand(name = "add") {
    override fun help(context: Context) = "Registers a game (or updates the paths of an already registered game)."

    private val name by argument(help = "Game name (also used as the folder name in the cloud)")
    private val windows by option("--windows", help = "Saves folder on Windows")
    private val mac by option("--mac", help = "Saves folder on Mac")
    private val linux by option("--linux", help = "Saves folder on Linux")

    override fun run() {
        validateGameName(name)
        val config = ConfigStore.load()
        val newPaths = listOfNotNull(
            windows?.let { Platform.WINDOWS.key to it },
            mac?.let { Platform.MAC.key to it },
            linux?.let { Platform.LINUX.key to it },
        ).toMap()
        if (newPaths.isEmpty()) throw CliktError("Give at least one path (--windows, --mac or --linux).")

        val old = config.games.find { it.name == name }
        val game = GameConfig(name, (old?.paths ?: emptyMap()) + newPaths)
        ConfigStore.save(config.copy(games = config.games.filter { it.name != name } + game))
        echo("Game '$name' saved (${game.paths.keys.joinToString(", ")}).")
    }
}

class ListCmd : CliktCommand(name = "list") {
    override fun help(context: Context) = "Lists the registered games."

    override fun run() {
        val config = ConfigStore.load()
        echo("Sync folder: ${config.syncRoot}")
        if (config.games.isEmpty()) echo("No games registered. Use: cairnfire add <name> --windows <folder> --mac <folder>")
        for (g in config.games) {
            echo("- ${g.name}")
            for ((os, path) in g.paths) echo("    $os: $path")
        }
    }
}

class StatusCmd : CliktCommand(name = "status") {
    override fun help(context: Context) = "Shows what would be synced, without changing anything."

    private val game by argument(help = "Game (default: all)").optional()

    override fun run() = runGames(game, dryRun = true) { echo(it) }
}

class SyncCmd : CliktCommand(name = "sync") {
    override fun help(context: Context) = "Syncs the saves between this machine and the cloud folder."

    private val game by argument(help = "Game (default: all)").optional()
    private val dryRun by option("--dry-run", help = "Only shows what it would do").flag()

    override fun run() = runGames(game, dryRun) { echo(it) }
}

class GuiCmd : CliktCommand(name = "gui") {
    override fun help(context: Context) = "Opens the Cairnfire window."

    private val preview by option("--preview", hidden = true, help = "Renders the window with sample data to a PNG")

    private val variant by option("--preview-variant", hidden = true, help = "list, expanded or dialog").default("list")

    override fun run() {
        val out = preview
        if (out != null) cairnfire.gui.renderPreview(Path.of(out), variant) else cairnfire.gui.launchGui()
    }
}

class WatchCmd : CliktCommand(name = "watch") {
    override fun help(context: Context) =
        "Keeps running and syncs by itself when the saves (or the cloud) change. Stop with Ctrl+C."

    private val interval by option("--interval", help = "Seconds between checks even without events (catches cloud changes)")
        .int().default(60)
    private val debounce by option("--debounce", help = "Seconds of quiet before syncing after a change")
        .int().default(5)

    override fun run() {
        if (interval < 1 || debounce < 1) throw CliktError("--interval and --debounce must be at least 1.")
        val say = { line: String -> echo("${LocalTime.now().withNano(0)}  $line") }
        say("Watching the saves (every ${interval}s and on every change). Ctrl+C to stop.")

        watchLoop(interval, debounce, onError = { say("Error: $it") }) {
            runGames(null, dryRun = false, quiet = true, echo = say)
        }
    }
}

private fun runGames(only: String?, dryRun: Boolean, quiet: Boolean = false, echo: (String) -> Unit) {
    val config = ConfigStore.load()
    requireSyncRoot(config)

    val games = if (only == null) config.games else listOf(
        config.games.find { it.name == only } ?: throw CliktError("Game '$only' is not registered."),
    )
    if (games.isEmpty()) {
        echo("No games registered. Use: cairnfire add <name> --windows <folder> --mac <folder>")
        return
    }

    val engine = SyncEngine(ConfigStore.backupRoot)
    for (game in games) {
        val report = syncGame(config, game, dryRun, engine)
        if (report.skipped != null) {
            echo("[${game.name}] ${report.skipped}")
        } else if (report.actions.isEmpty()) {
            if (!quiet) echo("[${game.name}] everything is in sync.")
        } else {
            echo("[${game.name}] ${if (dryRun) "to do" else "done"}:")
            for (a in report.actions) {
                val flag = if (a.conflict) "  !" else "   "
                echo("$flag ${a.kind.label.padEnd(16)} ${a.path}${a.note?.let { "  ($it)" } ?: ""}")
            }
            val backupDir = ConfigStore.backupRoot.resolve(game.name)
            if (!dryRun && backupDir.exists()) {
                echo("    (replaced or deleted versions are kept in $backupDir)")
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
