package dev.kuml.renderer.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp

/**
 * Compose-spezifisches Theme-Interface für den Kuiver-Renderer.
 *
 * Wraps framework-neutrale [dev.kuml.renderer.theme.core.KumlTheme]-Daten in
 * Compose-Typen ([Color], [TextStyle], [Dp]). Wird durch [KumlTheme]-Typaliase
 * rückwärtskompatibel exportiert.
 *
 * Beispiel:
 * ```kotlin
 * val composeTheme: ComposeKumlTheme = dev.kuml.renderer.theme.core.PlainTheme().toCompose()
 * KumlKuiverRenderer.Render(diagram, layoutResult, theme = composeTheme)
 * ```
 *
 * @see dev.kuml.renderer.theme.core.KumlTheme
 * @see PlainTheme
 */
public interface ComposeKumlTheme {
    /** Human-readable identifier for this theme (e.g. `"Plain"`, `"Dark"`). */
    public val name: String

    /** Compose colour palette. */
    public val colors: ComposeKumlColors

    /** Compose text styles. */
    public val typography: ComposeKumlTypography

    /** Border / stroke measurements. */
    public val borders: ComposeKumlBorders
}

/**
 * Compose-Farbpalette für ein [ComposeKumlTheme].
 *
 * Beispiel:
 * ```kotlin
 * val colors: ComposeKumlColors = PlainTheme.colors
 * Box(modifier = Modifier.background(colors.background))
 * ```
 */
public data class ComposeKumlColors(
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

/**
 * Compose-Typografie-Palette für ein [ComposeKumlTheme].
 *
 * Beispiel:
 * ```kotlin
 * Text(text = "MyClass", style = PlainTheme.typography.title)
 * ```
 */
public data class ComposeKumlTypography(
    /** Primary classifier / element name. Bold. */
    val title: TextStyle,
    /** Section header (attributes, operations). Medium weight. */
    val subtitle: TextStyle,
    /** Feature label (attribute / operation text). Regular. */
    val body: TextStyle,
    /** Small annotation (multiplicity, technology tag). */
    val small: TextStyle,
    /** Stereotype string — `«interface»`, `«enumeration»`. Italic. */
    val stereotype: TextStyle,
)

/**
 * Compose-Rahmen-Maße für ein [ComposeKumlTheme].
 *
 * Beispiel:
 * ```kotlin
 * Box(modifier = Modifier.border(PlainTheme.borders.regular, PlainTheme.colors.border))
 * ```
 */
public data class ComposeKumlBorders(
    /** Thin stroke — 1 dp. */
    val thin: Dp,
    /** Standard stroke — 1.5 dp. */
    val regular: Dp,
    /** Heavy stroke used for C4 SoftwareSystem borders — 2 dp. */
    val thick: Dp,
    /** Default corner radius for rounded rectangles. */
    val cornerRadius: Dp,
)

// ── Rückwärtskompatible Typealiase ─────────────────────────────────────────────
// kuml-kuiver importiert `dev.kuml.renderer.theme.KumlTheme` — diese Aliase
// stellen sicher, dass kein API-Bruch entsteht.

/** Rückwärtskompatibles Alias für [ComposeKumlTheme]. Bestehende Importe in `kuml-kuiver` bleiben gültig. */
public typealias KumlTheme = ComposeKumlTheme

/** Rückwärtskompatibles Alias für [ComposeKumlColors]. */
public typealias KumlColors = ComposeKumlColors

/** Rückwärtskompatibles Alias für [ComposeKumlTypography]. */
public typealias KumlTypography = ComposeKumlTypography

/** Rückwärtskompatibles Alias für [ComposeKumlBorders]. */
public typealias KumlBorders = ComposeKumlBorders
