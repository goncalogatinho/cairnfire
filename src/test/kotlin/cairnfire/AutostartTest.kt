package cairnfire

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutostartTest {
    private val spec = AutostartSpec(
        launcher = Path.of("/opt/cairnfire & co/bin/cairnfire"),
        javaHome = Path.of("/jdk/21"),
        cairnfireHome = null,
        logFile = Path.of("/home/u/.cairnfire/watch.log"),
    )

    @Test
    fun `plist lanca o watch no login e reinicia se falhar`() {
        val plist = Autostart.macPlist(spec)
        assertContains(plist, "<key>Label</key><string>com.cairnfire.watch</string>")
        assertContains(plist, "<string>watch</string>")
        assertContains(plist, "<key>RunAtLoad</key><true/>")
        assertContains(plist, "<key>KeepAlive</key><true/>")
        assertContains(plist, "<key>JAVA_HOME</key><string>/jdk/21</string>")
        assertFalse("CAIRNFIRE_HOME" in plist)
    }

    @Test
    fun `plist escapa caracteres XML e inclui CAIRNFIRE_HOME quando definido`() {
        val plist = Autostart.macPlist(spec.copy(cairnfireHome = Path.of("/tmp/cf")))
        assertContains(plist, "/opt/cairnfire &amp; co/bin/cairnfire")
        assertContains(plist, "<key>CAIRNFIRE_HOME</key><string>/tmp/cf</string>")
    }

    @Test
    fun `cmd do Windows fixa o JDK e escreve o log`() {
        val cmd = Autostart.windowsCmd(spec)
        assertContains(cmd, "set \"JAVA_HOME=/jdk/21\"")
        assertContains(cmd, "\" watch >> \"")
        assertTrue(cmd.endsWith("2>&1\r\n"))
    }

    @Test
    fun `vbs corre o cmd escondido`() {
        val vbs = Autostart.windowsVbs(Path.of("C:/Users/u/.cairnfire/watch.cmd"))
        assertContains(vbs, "\"\"\"")
        assertContains(vbs, ", 0, False")
    }
}
