package dev.kuml.renderer.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Built-in black-and-white theme — the V1 default for all kUML renderers.
 *
 * Uses the platform's default system font at classic UML diagram sizes.
 * No additional font resources are required.
 *
 * Example:
 * ```kotlin
 * KumlKuiverRenderer.Render(diagram, layoutResult, theme = PlainTheme)
 * ```
 *
 * @see KumlTheme
 */
public object PlainTheme : KumlTheme {

    override val name: String = "Plain"

    override val colors: KumlColors = KumlColors(
        background = Color.White,
        foreground = Color.Black,
        border = Color.Black,
        muted = Color(0xFF666666),
        accent = Color.Black,
        edge = Color.Black,
        edgeMuted = Color(0xFF999999),
    )

    override val typography: KumlTypography = KumlTypography(
        title = TextStyle(
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        ),
        subtitle = TextStyle(
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        body = TextStyle(
            fontSize = 11.sp,
            fontWeight = FontWeight.Normal,
        ),
        small = TextStyle(
            fontSize = 9.sp,
            fontWeight = FontWeight.Normal,
        ),
        stereotype = TextStyle(
            fontSize = 10.sp,
            fontWeight = FontWeight.Normal,
            fontStyle = FontStyle.Italic,
        ),
    )

    override val borders: KumlBorders = KumlBorders(
        thin = 1.dp,
        regular = 1.5.dp,
        thick = 2.dp,
        cornerRadius = 4.dp,
    )
}
