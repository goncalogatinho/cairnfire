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

/** What autostart needs to know to launch `cairnfire watch`. */
data class AutostartSpec(
    val launcher: Path,
    val javaHome: Path,
    val cairnfireHome: Path?,
    val logFile: Path,
)

/**
 * Autostart of `watch` at login:
 *   macOS    ~/Library/LaunchAgents/com.cairnfire.watch.plist (launchd; restarts if it fails)
 *   Windows  a .vbs in the Startup folder (runs watch.cmd without a window)
 */
object Autostart {
    const val LABEL = "com.cairnfire.watch"

    /**
     * The executable that launches this installation: in the `jpackage` app it is the launcher itself (which
     * jpackage reports in `jpackage.app-path`); in the `installDist` version it is the `bin/cairnfire` script,
     * found from the jar's location.
     */
    fun launcher(platform: Platform): Path {
        System.getProperty("jpackage.app-path")?.let { return Path.of(it).toAbsolutePath().normalize() }
        val jar = Path.of(Autostart::class.java.protectionDomain.codeSource.location.toURI())
        val name = if (platform == Platform.WINDOWS) "cairnfire.bat" else "cairnfire"
        val script = jar.parent?.parent?.resolve("bin")?.resolve(name)
        if (script == null || !script.exists()) {
            throw CliktError("Could not find the installed executable. Run first: ./gradlew installDist (or jpackage)")
        }
        return script.toAbsolutePath().normalize()
    }

    fun spec(platform: Platform) = AutostartSpec(
        launcher = launcher(platform),
        // launchd and Windows at login do not have your shell: we pin the JDK you installed with.
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

    /** Runs the .cmd with the window hidden (0) and without waiting (False). */
    fun windowsVbs(cmd: Path): String =
        "CreateObject(\"WScript.Shell\").Run \"\"\"$cmd\"\"\", 0, False\r\n"

    private val macAgent: Path
        get() = Path.of(System.getProperty("user.home"), "Library", "LaunchAgents", "$LABEL.plist")

    private val windowsCmdFile: Path get() = ConfigStore.home / "watch.cmd"
    private val windowsVbsFile: Path
        get() = Path.of(
            System.getenv("APPDATA") ?: throw CliktError("The APPDATA variable is not set."),
            "Microsoft", "Windows", "Start Menu", "Programs", "Startup", "cairnfire-watch.vbs",
        )

    fun install(platform: Platform): String {
        when (platform) {
            Platform.MAC -> {
                val s = spec(platform)
                ConfigStore.home.createDirectories()
                macAgent.parent.createDirectories()
                macAgent.writeText(macPlist(s))
                run("launchctl", "bootout", macTarget()) // in case it was already installed; failing here is normal
                val (code, out) = run("launchctl", "bootstrap", macDomain(), macAgent.toString())
                if (code != 0) throw CliktError("launchctl failed ($code): $out")
                return "Installed at $macAgent and already running. Log: ${s.logFile}"
            }
            Platform.WINDOWS -> {
                val s = spec(platform)
                ConfigStore.home.createDirectories()
                windowsCmdFile.writeText(windowsCmd(s))
                windowsVbsFile.writeText(windowsVbs(windowsCmdFile))
                ProcessBuilder("wscript.exe", windowsVbsFile.toString()).start() // start now, without waiting for the next login
                return "Installed at $windowsVbsFile and already running. Log: ${s.logFile}"
            }
            Platform.LINUX -> throw unsupported()
        }
    }

    fun uninstall(platform: Platform): String {
        when (platform) {
            Platform.MAC -> {
                run("launchctl", "bootout", macTarget())
                val removed = macAgent.deleteIfExists()
                return if (removed) "Removed $macAgent" else "Autostart was not installed."
            }
            Platform.WINDOWS -> {
                // The process that is already running continues until you log out or end it in Task Manager.
                val removed = windowsVbsFile.deleteIfExists()
                windowsCmdFile.deleteIfExists()
                return if (removed) "Removed $windowsVbsFile (the current watch stops at the next logout)"
                else "Autostart was not installed."
            }
            Platform.LINUX -> throw unsupported()
        }
    }

    fun status(platform: Platform): String = when (platform) {
        Platform.MAC -> when {
            !macAgent.exists() -> "Not installed."
            run("launchctl", "print", macTarget()).first == 0 -> "Installed and loaded in launchd ($macAgent)."
            else -> "Installed ($macAgent), but not loaded. Run: cairnfire autostart install"
        }
        Platform.WINDOWS ->
            if (windowsVbsFile.exists()) "Installed ($windowsVbsFile)." else "Not installed."
        Platform.LINUX -> throw unsupported()
    }

    private fun unsupported() = CliktError("Autostart is only implemented for macOS and Windows.")

    private fun macDomain() = "gui/${run("id", "-u").second.trim()}"
    private fun macTarget() = "${macDomain()}/$LABEL"

    private fun run(vararg cmd: String): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        return p.waitFor() to out
    }
}

class AutostartCmd : CliktCommand(name = "autostart") {
    override fun help(context: Context) = "Makes 'watch' start by itself when you log in (macOS and Windows)."

    override fun run() = Unit
}

class AutostartInstallCmd : CliktCommand(name = "install") {
    override fun help(context: Context) = "Installs autostart and starts watch right away."

    override fun run() = echo(Autostart.install(Platform.current()))
}

class AutostartUninstallCmd : CliktCommand(name = "uninstall") {
    override fun help(context: Context) = "Removes autostart."

    override fun run() = echo(Autostart.uninstall(Platform.current()))
}

class AutostartStatusCmd : CliktCommand(name = "status") {
    override fun help(context: Context) = "Shows whether autostart is installed."

    override fun run() = echo(Autostart.status(Platform.current()))
}
