package cairnfire.gui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.awt.Dimension
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Path
import javax.swing.JFileChooser
import kotlin.io.path.writeBytes

const val WINDOW_WIDTH = 780
const val WINDOW_HEIGHT = 680

/** Abre a janela do Cairnfire. */
fun launchGui() = application {
    val scope = rememberCoroutineScope()
    val state = remember { AppState(scope).also { it.load() } }
    val logo = remember { BitmapPainter(useResource("assets/logo.png", ::loadImageBitmap)) }

    Window(
        onCloseRequest = { state.stop(); exitApplication() },
        title = "Cairnfire",
        icon = logo,
        state = rememberWindowState(width = WINDOW_WIDTH.dp, height = WINDOW_HEIGHT.dp),
    ) {
        window.minimumSize = Dimension(700, 560)
        App(state)
    }
}

/** Desenha a janela com dados de exemplo para um PNG (para a documentação). Não precisa de ecrã. */
@OptIn(ExperimentalComposeUiApi::class)
fun renderPreview(out: Path, variant: String = "list", scale: Float = 2f) {
    val state = AppState.demo(kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined), variant)
    val scene = ImageComposeScene(
        width = (WINDOW_WIDTH * scale).toInt(),
        height = (WINDOW_HEIGHT * scale).toInt(),
        density = Density(scale),
    ) { App(state) }
    try {
        scene.render()
        val png = scene.render(16_000_000L).encodeToData(EncodedImageFormat.PNG) ?: error("não consegui codificar o PNG")
        out.writeBytes(png.bytes)
    } finally {
        scene.close()
    }
}

/** Escolhe uma pasta. No macOS usa o diálogo nativo; nos outros sistemas, o do Swing. */
fun pickFolder(title: String): String? {
    if (System.getProperty("os.name").lowercase().contains("mac")) {
        System.setProperty("apple.awt.fileDialogForDirectories", "true")
        try {
            val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
            dialog.isVisible = true
            val dir = dialog.directory ?: return null
            val file = dialog.file ?: return null
            return File(dir, file).path
        } finally {
            System.setProperty("apple.awt.fileDialogForDirectories", "false")
        }
    }
    val chooser = JFileChooser().apply {
        dialogTitle = title
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.path else null
}
