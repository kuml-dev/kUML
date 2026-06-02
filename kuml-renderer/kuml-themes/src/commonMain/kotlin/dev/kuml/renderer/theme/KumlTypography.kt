package dev.kuml.renderer.theme

import androidx.compose.ui.text.TextStyle

/**
 * Text-style palette for a [KumlTheme].
 *
 * Each slot maps to a semantic role in diagram elements.
 *
 * Example:
 * ```kotlin
 * Text(text = "MyClass", style = PlainTheme.typography.title)
 * ```
 *
 * @see KumlTheme
 */
public data class KumlTypography(
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
