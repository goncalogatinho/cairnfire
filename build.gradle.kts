plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.github.ajalt.clikt:clikt:5.0.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("cairnfire.MainKt")
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
            // Runtime mínimo: a deteção automática arrastava o java.desktop (via JNA do Clikt), 100+ MB inúteis.
            addAll(listOf("--add-modules", "java.base,java.logging,jdk.unsupported"))
            addAll(listOf("--jlink-options", "--strip-debug --no-man-pages --no-header-files --compress zip-9"))
            val icon = file("assets/" + if (isWindows) "icon.ico" else "icon.icns")
            if (icon.exists()) addAll(listOf("--icon", icon.path))
            if (isWindows) add("--win-console") // é uma ferramenta de linha de comandos: precisa de consola
            else addAll(listOf("--java-options", "-Dapple.awt.UIElement=true")) // sem ícone na Dock (macOS)
        },
    )
}
