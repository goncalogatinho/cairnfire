package cairnfire

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.writeText

/** O que o arranque automático precisa de saber para lançar `cairnfire watch`. */
data class AutostartSpec(
    val launcher: Path,
    val javaHome: Path,
    val cairnfireHome: Path?,
    val logFile: Path,
)

/**
 * Arranque automático do `watch` no login:
 *   macOS    ~/Library/LaunchAgents/com.cairnfire.watch.plist (launchd; reinicia se falhar)
 *   Windows  um .vbs na pasta Arranque (corre o watch.cmd sem janela)
 */
object Autostart {
    const val LABEL = "com.cairnfire.watch"

    /**
     * O executável que lança esta instalação: na app do `jpackage` é o próprio launcher (que o jpackage indica
     * em `jpackage.app-path`); na versão do `installDist` é o script `bin/cairnfire`, a partir do local do jar.
     */
    fun launcher(platform: Platform): Path {
        System.getProperty("jpackage.app-path")?.let { return Path.of(it).toAbsolutePath().normalize() }
        val jar = Path.of(Autostart::class.java.protectionDomain.codeSource.location.toURI())
        val name = if (platform == Platform.WINDOWS) "cairnfire.bat" else "cairnfire"
        val script = jar.parent?.parent?.resolve("bin")?.resolve(name)
        if (script == null || !script.exists()) {
            throw CliktError("Não encontrei o executável instalado. Corre primeiro: ./gradlew installDist (ou jpackage)")
        }
        return script.toAbsolutePath().normalize()
    }

    fun spec(platform: Platform) = AutostartSpec(
        launcher = launcher(platform),
        // O launchd e o Windows no login não têm o teu shell: fixamos o JDK com que instalaste.
        javaHome = Path.of(System.getProperty("java.home")),
        cairnfireHome = System.getenv("CAIRNFIRE_HOME")?.let { Path.of(it).toAbsolutePath() },
        logFile = ConfigStore.home / "watch.log",
    )

    fun macPlist(s: AutostartSpec): String {
        fun x(v: Any) = v.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val env = buildString {
            append("        <key>JAVA_HOME</key><string>${x(s.javaHome)}</string>\n")
            s.cairnfireHome?.let { append("        <key>CAIRNFIRE_HOME</key><string>${x(it)}</string>\n") }
        }
        return """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            |<plist version="1.0">
            |<dict>
            |    <key>Label</key><string>$LABEL</string>
            |    <key>ProgramArguments</key>
            |    <array>
            |        <string>${x(s.launcher)}</string>
            |        <string>watch</string>
            |    </array>
            |    <key>EnvironmentVariables</key>
            |    <dict>
            |$env    </dict>
            |    <key>RunAtLoad</key><true/>
            |    <key>KeepAlive</key><true/>
            |    <key>ThrottleInterval</key><integer>30</integer>
            |    <key>StandardOutPath</key><string>${x(s.logFile)}</string>
            |    <key>StandardErrorPath</key><string>${x(s.logFile)}</string>
            |</dict>
            |</plist>
            |""".trimMargin()
    }

    fun windowsCmd(s: AutostartSpec): String = buildString {
        append("@echo off\r\n")
        append("set \"JAVA_HOME=${s.javaHome}\"\r\n")
        s.cairnfireHome?.let { append("set \"CAIRNFIRE_HOME=$it\"\r\n") }
        append("\"${s.launcher}\" watch >> \"${s.logFile}\" 2>&1\r\n")
    }

    /** Corre o .cmd com a janela escondida (0) e sem esperar (False). */
    fun windowsVbs(cmd: Path): String =
        "CreateObject(\"WScript.Shell\").Run \"\"\"$cmd\"\"\", 0, False\r\n"

    private val macAgent: Path
        get() = Path.of(System.getProperty("user.home"), "Library", "LaunchAgents", "$LABEL.plist")

    private val windowsCmdFile: Path get() = ConfigStore.home / "watch.cmd"
    private val windowsVbsFile: Path
        get() = Path.of(
            System.getenv("APPDATA") ?: throw CliktError("A variável APPDATA não está definida."),
            "Microsoft", "Windows", "Start Menu", "Programs", "Startup", "cairnfire-watch.vbs",
        )

    fun install(platform: Platform): String {
        when (platform) {
            Platform.MAC -> {
                val s = spec(platform)
                ConfigStore.home.createDirectories()
                macAgent.parent.createDirectories()
                macAgent.writeText(macPlist(s))
                run("launchctl", "bootout", macTarget()) // se já estava instalado; falhar aqui é normal
                val (code, out) = run("launchctl", "bootstrap", macDomain(), macAgent.toString())
                if (code != 0) throw CliktError("O launchctl falhou ($code): $out")
                return "Instalado em $macAgent e já a correr. Log: ${s.logFile}"
            }
            Platform.WINDOWS -> {
                val s = spec(platform)
                ConfigStore.home.createDirectories()
                windowsCmdFile.writeText(windowsCmd(s))
                windowsVbsFile.writeText(windowsVbs(windowsCmdFile))
                ProcessBuilder("wscript.exe", windowsVbsFile.toString()).start() // arranca já, sem esperar pelo login
                return "Instalado em $windowsVbsFile e já a correr. Log: ${s.logFile}"
            }
            Platform.LINUX -> throw unsupported()
        }
    }

    fun uninstall(platform: Platform): String {
        when (platform) {
            Platform.MAC -> {
                run("launchctl", "bootout", macTarget())
                val removed = macAgent.deleteIfExists()
                return if (removed) "Removido $macAgent" else "O arranque automático não estava instalado."
            }
            Platform.WINDOWS -> {
                // O processo que já está a correr continua até fechar a sessão ou ser terminado no Gestor de Tarefas.
                val removed = windowsVbsFile.deleteIfExists()
                windowsCmdFile.deleteIfExists()
                return if (removed) "Removido $windowsVbsFile (o watch atual pára no próximo logout)"
                else "O arranque automático não estava instalado."
            }
            Platform.LINUX -> throw unsupported()
        }
    }

    fun status(platform: Platform): String = when (platform) {
        Platform.MAC -> when {
            !macAgent.exists() -> "Não instalado."
            run("launchctl", "print", macTarget()).first == 0 -> "Instalado e carregado no launchd ($macAgent)."
            else -> "Instalado ($macAgent), mas não carregado. Corre: cairnfire autostart install"
        }
        Platform.WINDOWS ->
            if (windowsVbsFile.exists()) "Instalado ($windowsVbsFile)." else "Não instalado."
        Platform.LINUX -> throw unsupported()
    }

    private fun unsupported() = CliktError("O arranque automático só está implementado para macOS e Windows.")

    private fun macDomain() = "gui/${run("id", "-u").second.trim()}"
    private fun macTarget() = "${macDomain()}/$LABEL"

    private fun run(vararg cmd: String): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        return p.waitFor() to out
    }
}

class AutostartCmd : CliktCommand(name = "autostart") {
    override fun help(context: Context) = "Faz o 'watch' arrancar sozinho quando inicias sessão (macOS e Windows)."

    override fun run() = Unit
}

class AutostartInstallCmd : CliktCommand(name = "install") {
    override fun help(context: Context) = "Instala o arranque automático e põe o watch a correr já."

    override fun run() = echo(Autostart.install(Platform.current()))
}

class AutostartUninstallCmd : CliktCommand(name = "uninstall") {
    override fun help(context: Context) = "Remove o arranque automático."

    override fun run() = echo(Autostart.uninstall(Platform.current()))
}

class AutostartStatusCmd : CliktCommand(name = "status") {
    override fun help(context: Context) = "Mostra se o arranque automático está instalado."

    override fun run() = echo(Autostart.status(Platform.current()))
}
