package dev.kuml.renderer.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Built-in black-and-white Compose theme — the V1 default for all kUML renderers.
 *
 * This is the Compose-adapter variant of the framework-neutral
 * [dev.kuml.renderer.theme.core.PlainTheme]. Existing imports of
 * `dev.kuml.renderer.theme.PlainTheme` remain unchanged.
 *
 * Example:
 * ```kotlin
 * KumlKuiverRenderer.Render(diagram, layoutResult, theme = PlainTheme)
 * ```
 *
 * @see ComposeKumlTheme
 */
public object PlainTheme : ComposeKumlTheme {

    override val name: String = "Plain"

    override val colors: ComposeKumlColors = ComposeKumlColors(
        background = Color.White,
        foreground = Color.Black,
        border = Color.Black,
        muted = Color(0xFF666666),
        accent = Color.Black,
        edge = Color.Black,
        edgeMuted = Color(0xFF999999),
    )

    override val typography: ComposeKumlTypography = ComposeKumlTypography(
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

    override val borders: ComposeKumlBorders = ComposeKumlBorders(
        thin = 1.dp,
        regular = 1.5.dp,
        thick = 2.dp,
        cornerRadius = 4.dp,
    )
}
