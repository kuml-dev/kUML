package dev.kuml.io.svg.uml

import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.uml.ParameterDirection
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlParameter
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.Visibility
import dev.kuml.uml.displayLine

/** Formatiert eine [UmlProperty] als `+ name: Type`. */
internal fun UmlProperty.format(): String = displayLine()

/**
 * Formatiert eine [UmlOperation] als `+ name(params): ReturnType`.
 *
 * Parameter mit angewendeten Stereotypen erhalten einen Inline-Präfix
 * `«Stereo» name: Type`, sofern [theme] `showFeatureStereotypes = true` setzt.
 * Mehrere Stereotypen werden mit dem Theme-`joinSeparator` separiert.
 */
internal fun UmlOperation.format(theme: KumlTheme): String {
    val vis = visibility.symbol()
    val params =
        parameters
            .filter { it.direction != ParameterDirection.RETURN }
            .joinToString(", ") { it.formatParameter(theme) }
    val ret = returnType?.name?.let { ": $it" } ?: ""
    return "$vis $name($params)$ret"
}

/**
 * Formatiert einen einzelnen [UmlParameter] als `name: Type`, optional
 * mit Stereotyp-Präfix `«Stereo» name: Type`.
 *
 * Stereotyp-Präfix wird nur dann eingebaut, wenn:
 *  - der Parameter mindestens eine angewendete Stereotyp-Anwendung hat
 *  - das [theme] `stereotypes.showFeatureStereotypes = true` setzt
 */
internal fun UmlParameter.formatParameter(theme: KumlTheme): String {
    val base = "$name: ${type.name}"
    if (!theme.stereotypes.showFeatureStereotypes) return base
    if (appliedStereotypes.isEmpty()) return base
    val joined =
        appliedStereotypes.joinToString(theme.stereotypes.joinSeparator) {
            it.stereotypeName
        }
    return "«$joined» $base"
}

/** Gibt das Sichtbarkeits-Symbol zurück. */
internal fun Visibility.symbol(): String =
    when (this) {
        Visibility.PUBLIC -> "+"
        Visibility.PRIVATE -> "-"
        Visibility.PROTECTED -> "#"
        Visibility.PACKAGE -> "~"
    }

/**
 * Wraps already-escaped feature text in an underlined `<tspan>` when the feature is
 * static (UML 2.5: class-scope features are underlined). The `kuml-static-underline`
 * class makes the element findable by invariant tests. Using `text-decoration` on a
 * tspan keeps the underline exactly as wide as the glyphs in every SVG consumer.
 */
internal fun staticUnderline(
    escapedContent: String,
    isStatic: Boolean,
): String =
    if (isStatic) {
        "<tspan class=\"kuml-static-underline\" text-decoration=\"underline\">$escapedContent</tspan>"
    } else {
        escapedContent
    }
