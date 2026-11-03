package cairnfire.gui

import androidx.compose.ui.graphics.Color

/**
 * The logo palette: the night and the cairn's stones in neutral tones, and the flame colours only as an accent
 * (orange = action/pending changes, red = conflict/error).
 */
object Px {
    // neutrals
    val Bg = Color(0xFF14111B)       // night
    val Panel = Color(0xFF1B1824)
    val PanelHi = Color(0xFF242232)
    val Border = Color(0xFF2F2E3D)   // dark stone
    val BorderHi = Color(0xFF5A596F) // mid stone
    val Muted = Color(0xFF8D8BA3)    // light stone
    val Text = Color(0xFFB9B7CC)
    val TextHi = Color(0xFFE4E2F0)

    // flame
    val Orange = Color(0xFFFF8A1F)
    val Yellow = Color(0xFFFFC933)
    val Red = Color(0xFFD0391F)
    val Warm = Color(0xFFD9A98A)
}
