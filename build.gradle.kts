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

    // Interface gráfica (Compose Multiplatform Desktop)
    implementation(compose.desktop.currentOs)
    implementation(compose.foundation)

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("cairnfire.MainKt")
}

// O banner e o logo (gerados em assets/) vão dentro do jar para a interface os mostrar.
tasks.processResources {
    from("assets") {
        include("banner-ui.png", "logo.png")
        into("assets")
    }
}

tasks.test {
    useJUnitPlatform()
}

// Gera uma app com o runtime Java incluído (não precisa de JRE instalado). O jpackage não faz
// cross-compile: corre isto no Mac para a versão Mac e no Windows para a versão Windows.
tasks.register<Exec>("jpackage") {
    group = "distribution"
    description = "Empacota o Cairnfire com um runtime Java embutido em build/jpackage/"
    dependsOn(tasks.installDist)

    val isWindows = System.getProperty("os.name").lowercase().contains("win")
    val jpackage = File(System.getProperty("java.home"), "bin/jpackage" + if (isWindows) ".exe" else "")
    val dest = layout.buildDirectory.dir("jpackage").get().asFile
    val input = layout.buildDirectory.dir("install/cairnfire/lib").get().asFile

    doFirst {
        delete(dest)
        if (!jpackage.exists()) throw GradleException("jpackage não encontrado em $jpackage (precisas de um JDK, não de um JRE)")
    }
    commandLine(
        buildList {
            add(jpackage.path)
            addAll(listOf("--type", "app-image", "--name", "cairnfire", "--app-version", "1.0.0"))
            addAll(listOf("--input", input.path, "--main-jar", "cairnfire.jar", "--main-class", "cairnfire.MainKt"))
            addAll(listOf("--dest", dest.path))
            // Runtime mínimo: só o que a CLI e a janela (Compose/Skia + Swing) usam, em vez do JDK inteiro.
            addAll(listOf("--add-modules", "java.base,java.desktop,java.logging,java.management,java.naming,java.prefs,jdk.unsupported"))
            addAll(listOf("--jlink-options", "--strip-debug --no-man-pages --no-header-files --compress zip-9"))
            val icon = file("assets/" + if (isWindows) "icon.ico" else "icon.icns")
            if (icon.exists()) addAll(listOf("--icon", icon.path))
            if (isWindows) {
                // cairnfire.exe é a linha de comandos (precisa de consola); cairnfire-gui.exe abre a janela, sem consola.
                add("--win-console")
                val gui = layout.buildDirectory.file("jpackage-gui.properties").get().asFile
                gui.parentFile.mkdirs()
                gui.writeText("arguments=gui\nwin-console=false\n")
                addAll(listOf("--add-launcher", "cairnfire-gui=${gui.path}"))
            } else {
                // Sem argumentos (duplo clique na app) abre a janela; com argumentos é a linha de comandos.
                addAll(listOf("--arguments", "gui"))
            }
        },
    )
}
