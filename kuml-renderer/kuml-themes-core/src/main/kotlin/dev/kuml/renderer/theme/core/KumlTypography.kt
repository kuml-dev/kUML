package dev.kuml.renderer.theme.core

import kotlinx.serialization.Serializable

/**
 * Framework-neutrale Typografie-Palette für ein [KumlTheme].
 *
 * Jeder Slot entspricht einer semantischen Rolle in Diagramm-Elementen.
 *
 * Beispiel:
 * ```kotlin
 * val typo = PlainTheme().typography
 * println(typo.title.sizePt) // 14.0
 * ```
 *
 * @see KumlTheme
 * @see KumlFont
 */
@Serializable
public data class KumlTypography(
    /** Primärer Klassifizier-/Element-Name. Fett. */
    public val title: KumlFont,
    /** Abschnitts-Header (Attribute, Operationen). Mittleres Gewicht. */
    public val subtitle: KumlFont,
    /** Feature-Label (Attribut-/Operations-Text). Normal. */
    public val body: KumlFont,
    /** Kleine Annotation (Multiplizität, Technologie-Tag). */
    public val small: KumlFont,
    /** Stereotyp-String — `«interface»`, `«enumeration»`. Kursiv. */
    public val stereotype: KumlFont,
)
