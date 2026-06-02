package dev.kuml.renderer.theme

import androidx.compose.ui.graphics.Color

/**
 * Colour palette for a [KumlTheme].
 *
 * All colours are Compose [Color] values so they are usable on every platform
 * without depending on `java.awt.Color`.
 *
 * Example — access in a Composable:
 * ```kotlin
 * val colors = PlainTheme.colors
 * Box(modifier = Modifier.background(colors.background))
 * ```
 *
 * @see KumlTheme
 * @see PlainTheme
 */
public data class KumlColors(
    /** Canvas / node fill. */
    val background: Color,
    /** Primary text and icon colour. */
    val foreground: Color,
    /** Default stroke colour for node borders. */
    val border: Color,
    /** Secondary / subdued text (stereotypes, annotations). */
    val muted: Color,
    /** Accent highlight (currently same as foreground in PlainTheme). */
    val accent: Color,
    /** Default edge/arrow stroke colour. */
    val edge: Color,
    /** Subdued edge colour (dashed relationships). */
    val edgeMuted: Color,
)
