package cairnfire

import com.github.ajalt.clikt.core.CliktError
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * O resultado de sincronizar (ou só planear) um jogo. [skipped] explica porque é que o jogo foi saltado
 * sem tocar em nada; nesse caso [actions] está vazio.
 */
data class GameReport(val game: String, val actions: List<Action> = emptyList(), val skipped: String? = null)

/** A pasta da cloud tem de existir (senão o disco pode não estar montado); caso contrário não se mexe em nada. */
fun requireSyncRoot(config: Config): Path {
    val syncRoot = Path.of(config.syncRoot)
    if (!syncRoot.isDirectory()) {
        throw CliktError("A pasta de sincronização '$syncRoot' não existe (a cloud está montada?). Nada foi alterado.")
    }
    return syncRoot
}

/**
 * Sincroniza um jogo entre esta máquina e a cloud (ou, com [dryRun], só calcula o que faria).
 * É usado tanto pela linha de comandos como pela interface gráfica.
 */
fun syncGame(
    config: Config,
    game: GameConfig,
    dryRun: Boolean,
    engine: SyncEngine = SyncEngine(ConfigStore.backupRoot),
    platform: Platform = Platform.current(),
): GameReport {
    val rawLocal = game.paths[platform.key]
        ?: return GameReport(game.name, skipped = "sem caminho configurado para ${platform.key}; ignorado.")
    val localDir = expandPath(rawLocal)
    val remoteDir = Path.of(config.syncRoot).resolve(game.name)
    val base = ConfigStore.loadState(game.name)

    // Proteção: se uma das pastas desapareceu mas já sincronizámos antes, isto parece um erro
    // (caminho errado, disco desmontado) e não uma decisão de apagar tudo.
    if (base.isNotEmpty() && !localDir.exists()) {
        return GameReport(game.name, skipped = "a pasta local '$localDir' não existe; ignorado para não apagar nada na cloud.")
    }
    if (base.isNotEmpty() && !remoteDir.exists()) {
        return GameReport(game.name, skipped = "a pasta '$remoteDir' desapareceu da cloud; ignorado para não apagar nada local.")
    }

    val result = engine.sync(game.name, localDir, remoteDir, base, dryRun)
    if (!dryRun) ConfigStore.saveState(game.name, result.newBase)
    return GameReport(game.name, result.actions)
}
