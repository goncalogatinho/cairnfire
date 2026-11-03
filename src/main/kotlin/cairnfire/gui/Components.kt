package cairnfire.gui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Texto monoespaçado: simples, neutro e combina com o pixel art. */
@Composable
fun PxText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Px.Text,
    size: TextUnit = 13.sp,
    bold: Boolean = false,
    spacing: TextUnit = TextUnit.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(
        text,
        modifier,
        style = TextStyle(
            color = color,
            fontSize = size,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            letterSpacing = spacing,
        ),
        overflow = TextOverflow.Ellipsis,
        maxLines = maxLines,
    )
}

/** Etiqueta de secção: pequena, em maiúsculas e espaçada. */
@Composable
fun Label(text: String, modifier: Modifier = Modifier) =
    PxText(text.uppercase(), modifier, color = Px.Muted, size = 10.sp, bold = true, spacing = 1.6.sp)

/** Caixa de cantos retos com borda de 2 dp, como um "pixel" ampliado. */
fun Modifier.panel(border: Color = Px.Border, fill: Color = Px.Panel): Modifier =
    this.background(fill).border(2.dp, border)

/** Botão chunky: cantos retos, borda de 2 dp e uma "sombra" de 2 dp por baixo que desaparece ao carregar. */
@Composable
fun PixelButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()

    val face = when {
        !enabled -> Px.Panel
        primary -> if (hovered) Px.Yellow else Px.Orange
        else -> if (hovered) Px.PanelHi else Px.Panel
    }
    val edge = when {
        !enabled -> Px.Border
        primary -> Px.Red
        else -> if (hovered) Px.BorderHi else Px.Border
    }
    val fg = when {
        !enabled -> Px.BorderHi
        primary -> Px.Bg
        else -> Px.TextHi
    }

    Box(
        modifier
            .hoverable(source)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
            .background(edge)
            .padding(bottom = 3.dp),
    ) {
        Box(
            Modifier
                .offset(y = if (pressed && enabled) 3.dp else 0.dp)
                .background(face)
                .border(2.dp, edge)
                .padding(horizontal = 14.dp, vertical = 7.dp),
            contentAlignment = Alignment.Center,
        ) {
            PxText(text, color = fg, size = 12.sp, bold = true, maxLines = 1)
        }
    }
}

/** Interruptor quadrado: o quadrado acende a laranja quando está ligado. */
@Composable
fun PixelToggle(checked: Boolean, label: String, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    androidx.compose.foundation.layout.Row(
        modifier
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PxText(label, color = if (hovered) Px.TextHi else Px.Text, size = 12.sp)
        androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
        Box(Modifier.size(width = 36.dp, height = 18.dp).background(Px.Bg).border(2.dp, if (checked) Px.Orange else Px.BorderHi)) {
            Box(
                Modifier
                    .offset(x = if (checked) 18.dp else 0.dp)
                    .size(14.dp)
                    .background(if (checked) Px.Orange else Px.BorderHi),
            )
        }
    }
}

/** Campo de texto de uma linha com a mesma borda; fica laranja com o foco. */
@Composable
fun PixelField(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value,
        onChange,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .background(Px.Bg)
            .border(2.dp, if (focused) Px.Orange else Px.Border)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        enabled = enabled,
        singleLine = true,
        textStyle = TextStyle(
            color = if (enabled) Px.TextHi else Px.Muted,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
        ),
        cursorBrush = SolidColor(Px.Orange),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth()) {
                if (value.isEmpty()) PxText(placeholder, color = Px.BorderHi)
                inner()
            }
        },
    )
}

/** Quadradinho de estado (8 dp). */
@Composable
fun StatusSquare(color: Color, modifier: Modifier = Modifier) =
    Box(modifier.size(10.dp).background(color))
