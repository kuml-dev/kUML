package dev.kuml.renderer.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Border/stroke measurements for a [KumlTheme].
 *
 * `cornerRadius` is the default rounding for node shapes.
 * State nodes override this with `12.dp` locally.
 *
 * Example:
 * ```kotlin
 * Box(modifier = Modifier.border(PlainTheme.borders.regular.dp, PlainTheme.colors.border))
 * ```
 *
 * @see KumlTheme
 */
public data class KumlBorders(
    /** Thin stroke — 1 dp. */
    val thin: Dp,
    /** Standard stroke — 1.5 dp. */
    val regular: Dp,
    /** Heavy stroke used for C4 SoftwareSystem borders — 2 dp. */
    val thick: Dp,
    /** Default corner radius for rounded rectangles. */
    val cornerRadius: Dp,
) {
    public companion object {
        /** Default plain-theme border values. */
        public val Plain: KumlBorders = KumlBorders(
            thin = 1.dp,
            regular = 1.5.dp,
            thick = 2.dp,
            cornerRadius = 4.dp,
        )
    }
}
