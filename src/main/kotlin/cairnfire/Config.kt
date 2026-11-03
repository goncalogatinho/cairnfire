package cairnfire

import com.github.ajalt.clikt.core.CliktError
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

enum class Platform(val key: String) {
    WINDOWS("windows"),
    MAC("mac"),
    LINUX("linux");

    companion object {
        fun current(): Platform {
            val os = System.getProperty("os.name").lowercase()
            return when {
                "mac" in os -> MAC
                "win" in os -> WINDOWS
                else -> LINUX
            }
        }
    }
}

/** Um jogo e a pasta dos saves em cada sistema operativo. */
@Serializable
data class GameConfig(val name: String, val paths: Map<String, String>)

@Serializable
data class Config(val syncRoot: String, val games: List<GameConfig> = emptyList())

/** Estado da última sincronização deste dispositivo: caminho relativo -> hash. */
@Serializable
data class GameState(val files: Map<String, String> = emptyMap())

private val GAME_NAME = Regex("[A-Za-z0-9._ -]+")

fun validateGameName(name: String) {
    if (!GAME_NAME.matches(name) || name.startsWith(".")) {
        throw CliktError("Nome de jogo inválido: '$name'. Usa letras, números, espaços, '.', '_' ou '-'.")
    }
}

/**
 * Tudo fica em ~/.cairnfire (ou em $CAIRNFIRE_HOME):
 *   config.json        jogos e pasta de sincronização
 *   state/<jogo>.json  hashes da última sincronização (base do merge a 3 vias)
 *   backups/<jogo>/    cópias de tudo o que foi sobrescrito ou apagado
 */
object ConfigStore {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    val home: Path
        get() = System.getenv("CAIRNFIRE_HOME")?.let { Path.of(it) }
            ?: Path.of(System.getProperty("user.home"), ".cairnfire")

    val backupRoot: Path get() = home / "backups"
    private val configFile: Path get() = home / "config.json"
    private fun stateFile(game: String): Path = home / "state" / "$game.json"

    fun loadOrNull(): Config? =
        if (configFile.exists()) json.decodeFromString(Config.serializer(), configFile.readText()) else null

    fun load(): Config = loadOrNull()
        ?: throw CliktError("Ainda não há configuração. Corre primeiro: cairnfire init --sync-root <pasta>")

    fun save(config: Config) {
        home.createDirectories()
        configFile.writeText(json.encodeToString(Config.serializer(), config))
    }

    fun loadState(game: String): Map<String, String> {
        val f = stateFile(game)
        return if (f.exists()) json.decodeFromString(GameState.serializer(), f.readText()).files else emptyMap()
    }

    /** Esquece a última sincronização de um jogo (ao removê-lo), para não herdar uma "base" velha se voltar. */
    fun deleteState(game: String) {
        stateFile(game).deleteIfExists()
    }

    fun saveState(game: String, files: Map<String, String>) {
        val f = stateFile(game)
        f.parent.createDirectories()
        f.writeText(json.encodeToString(GameState.serializer(), GameState(files)))
    }
}
