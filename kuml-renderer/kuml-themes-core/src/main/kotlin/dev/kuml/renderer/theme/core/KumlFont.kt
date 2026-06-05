package dev.kuml.renderer.theme.core

import kotlinx.serialization.Serializable

/**
 * Framework-neutrale Schriftart-Definition.
 *
 * Wird vom SVG-Renderer direkt in CSS-Attribute gewandelt und vom
 * Compose-Adapter in [androidx.compose.ui.text.TextStyle] konvertiert.
 *
 * Beispiel:
 * ```kotlin
 * val titleFont = KumlFont("system-ui, sans-serif", sizePt = 14f, weight = 700)
 * println(titleFont.family) // "system-ui, sans-serif"
 * ```
 *
 * @property family CSS-kompatibler Font-Stack, z.B. `"system-ui, sans-serif"`.
 * @property sizePt Schriftgröße in Punkt (wird SVG-seitig als `px` interpretiert).
 * @property weight Schriftgewicht: 400 = normal, 600 = semi-bold, 700 = bold.
 * @property italic `true` für kursiven Schnitt.
 */
@Serializable
public data class KumlFont(
    public val family: String,
    public val sizePt: Float,
    public val weight: Int = 400,
    public val italic: Boolean = false,
)
