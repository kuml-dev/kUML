package dev.kuml.renderer.theme.core

import kotlinx.serialization.Serializable

/**
 * Framework-neutrale Farbpalette für ein [KumlTheme].
 *
 * Alle Farben sind als [KumlColor] (24-bit RGB) gespeichert, ohne
 * Framework-Abhängigkeit. Der Compose-Adapter wandelt diese in
 * `androidx.compose.ui.graphics.Color`.
 *
 * Beispiel:
 * ```kotlin
 * val colors = PlainTheme().colors
 * println(colors.background.toHex()) // "#FFFFFF"
 * ```
 *
 * @see KumlTheme
 * @see KumlColor
 */
@Serializable
public data class KumlColors(
    /** Canvas- / Knoten-Füllfarbe. */
    public val background: KumlColor,
    /** Primäre Text- und Icon-Farbe. */
    public val foreground: KumlColor,
    /** Standard-Rahmenfarbe für Knoten. */
    public val border: KumlColor,
    /** Sekundäre / gedämpfte Textfarbe (Stereotypen, Annotationen). */
    public val muted: KumlColor,
    /** Akzent-Hervorhebung (in PlainTheme identisch mit foreground). */
    public val accent: KumlColor,
    /** Standard-Kantenfarbe (Pfeile, Linien). */
    public val edge: KumlColor,
    /** Gedämpfte Kantenfarbe (gestrichelte Beziehungen). */
    public val edgeMuted: KumlColor,
)
