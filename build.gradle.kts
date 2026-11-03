plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    kotlin("plugin.compose") version "2.1.0"
    id("org.jetbrains.compose") version "1.7.3"
    application
}

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation("com.github.ajalt.clikt:clikt:5.0.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Graphical interface (Compose Multiplatform Desktop)
    implementation(compose.desktop.currentOs)
    implementation(compose.foundation)

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("cairnfire.MainKt")
    // Newer JDKs warn on every run about JNA (used by the terminal detection) calling native code.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// The banner and logo (generated in assets/) go inside the jar so the window can show them.
tasks.processResources {
    from("assets") {
        include("banner-ui.png", "logo.png")
        into("assets")
    }
}

tasks.test {
    useJUnitPlatform()
}

// Builds an app with the Java runtime included (no JRE needed). jpackage does not cross-compile:
// run this on the Mac for the Mac version and on Windows for the Windows version.
tasks.register<Exec>("jpackage") {
    group = "distribution"
    description = "Packages Cairnfire with an embedded Java runtime into build/jpackage/"
    dependsOn(tasks.installDist)

    val isWindows = System.getProperty("os.name").lowercase().contains("win")
    val jpackage = File(System.getProperty("java.home"), "bin/jpackage" + if (isWindows) ".exe" else "")
    val dest = layout.buildDirectory.dir("jpackage").get().asFile
    val input = layout.buildDirectory.dir("install/cairnfire/lib").get().asFile

    doFirst {
        delete(dest)
        if (!jpackage.exists()) throw GradleException("jpackage not found at $jpackage (you need a JDK, not a JRE)")
    }
    commandLine(
        buildList {
            add(jpackage.path)
            addAll(listOf("--type", "app-image", "--name", "cairnfire", "--app-version", "1.0.0"))
            addAll(listOf("--input", input.path, "--main-jar", "cairnfire.jar", "--main-class", "cairnfire.MainKt"))
            addAll(listOf("--dest", dest.path))
            // Minimal runtime: only what the CLI and the window (Compose/Skia + Swing) use, instead of the whole JDK.
            addAll(listOf("--add-modules", "java.base,java.desktop,java.logging,java.management,java.naming,java.prefs,jdk.unsupported"))
            addAll(listOf("--jlink-options", "--strip-debug --no-man-pages --no-header-files --compress zip-9"))
            addAll(listOf("--java-options", "--enable-native-access=ALL-UNNAMED"))
            val icon = file("assets/" + if (isWindows) "icon.ico" else "icon.icns")
            if (icon.exists()) addAll(listOf("--icon", icon.path))
            if (isWindows) {
                // cairnfire.exe is the command line (needs a console); cairnfire-gui.exe opens the window, without a console.
                add("--win-console")
                val gui = layout.buildDirectory.file("jpackage-gui.properties").get().asFile
                gui.parentFile.mkdirs()
                gui.writeText("arguments=gui\nwin-console=false\n")
                addAll(listOf("--add-launcher", "cairnfire-gui=${gui.path}"))
            } else {
                // With no arguments (double-clicking the app) it opens the window; with arguments it is the command line.
                addAll(listOf("--arguments", "gui"))
            }
        },
    )
}
