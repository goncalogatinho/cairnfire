package cairnfire.gui

import androidx.compose.ui.graphics.Color

/**
 * A paleta do logo: a noite e as pedras do cairn em tons neutros, e as cores da chama só como acento
 * (laranja = ação/alterações pendentes, vermelho = conflito/erro).
 */
object Px {
    // neutros
    val Bg = Color(0xFF14111B)       // noite
    val Panel = Color(0xFF1B1824)
    val PanelHi = Color(0xFF242232)
    val Border = Color(0xFF2F2E3D)   // pedra escura
    val BorderHi = Color(0xFF5A596F) // pedra média
    val Muted = Color(0xFF8D8BA3)    // pedra clara
    val Text = Color(0xFFB9B7CC)
    val TextHi = Color(0xFFE4E2F0)

    // chama
    val Orange = Color(0xFFFF8A1F)
    val Yellow = Color(0xFFFFC933)
    val Red = Color(0xFFD0391F)
    val Warm = Color(0xFFD9A98A)
}
