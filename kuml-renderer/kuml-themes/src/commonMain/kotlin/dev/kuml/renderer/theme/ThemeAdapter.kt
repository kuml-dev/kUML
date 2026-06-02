package dev.kuml.renderer.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.kuml.renderer.theme.core.KumlColor
import dev.kuml.renderer.theme.core.KumlFont
import dev.kuml.renderer.theme.core.KumlTheme as CoreKumlTheme

/**
 * Adaptiert ein framework-neutrales [CoreKumlTheme] in ein [ComposeKumlTheme].
 *
 * Wird von `PlainTheme` und zukünftigen Theme-Implementierungen genutzt, um
 * Core-Daten in Compose-Typen zu wandeln.
 *
 * Beispiel:
 * ```kotlin
 * val composeTheme: ComposeKumlTheme = dev.kuml.renderer.theme.core.PlainTheme().toCompose()
 * KumlKuiverRenderer.Render(diagram, layoutResult, theme = composeTheme)
 * ```
 */
public fun CoreKumlTheme.toCompose(): ComposeKumlTheme =
    object : ComposeKumlTheme {
        override val name: String = this@toCompose.name
        override val colors: ComposeKumlColors = this@toCompose.colors.toCompose()
        override val typography: ComposeKumlTypography = this@toCompose.typography.toCompose()
        override val borders: ComposeKumlBorders =
            ComposeKumlBorders(
                thin = this@toCompose.borders.thinPx.dp,
                regular = this@toCompose.borders.regularPx.dp,
                thick = this@toCompose.borders.thickPx.dp,
                cornerRadius = this@toCompose.borders.cornerRadiusPx.dp,
            )
    }

/**
 * Wandelt eine [KumlColor] (24-bit RGB) in eine Compose [Color].
 *
 * Alpha wird auf `0xFF` (voll opak) gesetzt, da V1 nur RGB ohne Alpha unterstützt.
 *
 * Beispiel:
 * ```kotlin
 * val composeColor: Color = KumlColor(0xFF0000).toCompose()
 * ```
 */
public fun KumlColor.toCompose(): Color = Color(rgb or (0xFF shl 24))

/**
 * Wandelt eine [KumlFont] in einen Compose [TextStyle].
 *
 * Beispiel:
 * ```kotlin
 * val style: TextStyle = KumlFont("system-ui, sans-serif", 14f, weight = 700).toCompose()
 * ```
 */
public fun KumlFont.toCompose(): TextStyle =
    TextStyle(
        fontSize = sizePt.sp,
        fontWeight = FontWeight(weight),
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
    )

// ── Private helpers ───────────────────────────────────────────────────────────

private fun dev.kuml.renderer.theme.core.KumlColors.toCompose(): ComposeKumlColors =
    ComposeKumlColors(
        background = background.toCompose(),
        foreground = foreground.toCompose(),
        border = border.toCompose(),
        muted = muted.toCompose(),
        accent = accent.toCompose(),
        edge = edge.toCompose(),
        edgeMuted = edgeMuted.toCompose(),
    )

private fun dev.kuml.renderer.theme.core.KumlTypography.toCompose(): ComposeKumlTypography =
    ComposeKumlTypography(
        title = title.toCompose(),
        subtitle = subtitle.toCompose(),
        body = body.toCompose(),
        small = small.toCompose(),
        stereotype = stereotype.toCompose(),
    )

private val Float.dp get() = androidx.compose.ui.unit.Dp(this)
