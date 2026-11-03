package cairnfire

import com.github.ajalt.clikt.core.CliktError
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * The result of syncing (or just planning) a game. [skipped] explains why the game was skipped
 * without touching anything; in that case [actions] is empty.
 */
data class GameReport(val game: String, val actions: List<Action> = emptyList(), val skipped: String? = null)

/** The cloud folder must exist (otherwise the drive may not be mounted); if it does not, nothing is touched. */
fun requireSyncRoot(config: Config): Path {
    val syncRoot = Path.of(config.syncRoot)
    if (!syncRoot.isDirectory()) {
        throw CliktError("The sync folder '$syncRoot' does not exist (is the cloud mounted?). Nothing was changed.")
    }
    return syncRoot
}

/**
 * Syncs a game between this machine and the cloud (or, with [dryRun], only works out what it would do).
 * Used by both the command line and the window.
 */
fun syncGame(
    config: Config,
    game: GameConfig,
    dryRun: Boolean,
    engine: SyncEngine = SyncEngine(ConfigStore.backupRoot),
    platform: Platform = Platform.current(),
): GameReport {
    val rawLocal = game.paths[platform.key]
        ?: return GameReport(game.name, skipped = "no path configured for ${platform.key}; skipped.")
    val localDir = expandPath(rawLocal)
    val remoteDir = Path.of(config.syncRoot).resolve(game.name)
    val base = ConfigStore.loadState(game.name)

    // Safety: if one of the folders disappeared but we have synced before, this looks like a mistake
    // (wrong path, unmounted drive) and not a decision to delete everything.
    if (base.isNotEmpty() && !localDir.exists()) {
        return GameReport(game.name, skipped = "the local folder '$localDir' does not exist; skipped so nothing is deleted in the cloud.")
    }
    if (base.isNotEmpty() && !remoteDir.exists()) {
        return GameReport(game.name, skipped = "the folder '$remoteDir' disappeared from the cloud; skipped so nothing is deleted locally.")
    }

    val result = engine.sync(game.name, localDir, remoteDir, base, dryRun)
    if (!dryRun) ConfigStore.saveState(game.name, result.newBase)
    return GameReport(game.name, result.actions)
}
