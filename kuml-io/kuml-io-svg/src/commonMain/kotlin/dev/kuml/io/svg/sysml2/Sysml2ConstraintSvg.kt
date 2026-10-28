package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.fmt3
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.PortSide
import dev.kuml.layout.bridge.ParConstraintGeometry
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.sysml2.ConstraintDefinition
import dev.kuml.sysml2.ConstraintParameter
import dev.kuml.sysml2.ConstraintParameterDirection
import dev.kuml.sysml2.Sysml2Limits

/**
 * Rendert eine SysML-2-[ConstraintDefinition] als drei-kompartimentige
 * Constraint-Box (V2.0.12, letzte SysML-2-Diagramm-Welle).
 *
 * Visuell:
 *  - Kompartiment 1: Stereotyp `«constraint»` (zentriert) + Name (fett zentriert).
 *  - Kompartiment 2: Expression-Body (monospaced, zentriert), bei Bedarf an
 *    [EXPRESSION_MAX_CHARS] Zeichen mit `…` abgeschnitten. Leerer
 *    Expression-Body wird stillschweigend weggelassen (kein Divider, kein
 *    leeres Compartment).
 *  - Kompartiment 3: Parameter-Liste — eine Zeile pro [ConstraintParameter]
 *    in der Form `«in» m : Mass`, `«out» F : Force`, `«inout» x : Real`. Der
 *    Direction-Stereotyp-Präfix entspricht dem
 *    [ConstraintParameterDirection]-Enum-Wert. Leere Parameter-Liste lässt
 *    das Compartment ganz aus.
 *
 * Theme-Anbindung: nutzt die existierenden CSS-Klassen (`kuml-class`,
 * `kuml-stereotype`, `kuml-title`, `kuml-body`, `kuml-divider`), damit die
 * PAR-Boxen visuell mit BDD/IBD/REQ-Boxen im selben Diagramm harmonieren —
 * Tooling-Konsumenten brauchen keine Spezial-Stylesheets.
 *
 * Pins (wave C4): every parameter gets a hollow square marker centred on the box frame
 * (`in` left, `out`/`inout` right, at the height of its parameter row). Positions come from
 * [ParConstraintGeometry], the same numbers the layout bridge uses for the fixed-position ports,
 * so a binding that ends at the pin and the marker always coincide.
 *
 * V2.x-Polish (out of scope):
 *  - Equation-Rendering via MathJax / KaTeX statt monospaced Raw-Text.
 *  - Word-Wrap der Expression statt einfacher Zeichen-Trunkierung.
 */
internal fun renderConstraintDefinition(
    element: ConstraintDefinition,
    layout: NodeLayout,
    @Suppress("UNUSED_PARAMETER") theme: KumlTheme,
    builder: SvgBuilder,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height

    val hasExpression = element.expression.isNotEmpty()
    val expressionText =
        if (hasExpression) truncateExpression(element.expression) else ""
    val hasParameters = element.parameters.isNotEmpty()

    builder.tag(
        name = "g",
        attrs = mapOf("id" to xmlEscapeAttr(element.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        tag(name = "rect", attrs = mapOf("width" to fmt(w), "height" to fmt(h), "class" to "kuml-class"))

        var cy = 16f

        // Compartment 1 — `«constraint»`-Stereotyp.
        tag(
            name = "text",
            attrs =
                mapOf(
                    "class" to "kuml-stereotype",
                    "x" to fmt(w / 2f),
                    "y" to fmt(cy),
                    "text-anchor" to "middle",
                ),
        ) { text("«constraint»") }
        cy += 14f

        // Compartment 1 — Name (fett, zentriert). Abstract → italic-via-style hint.
        val nameClass = if (element.isAbstract) "kuml-title kuml-title-abstract" else "kuml-title"
        tag(
            name = "text",
            attrs =
                mapOf(
                    "class" to nameClass,
                    "x" to fmt(w / 2f),
                    "y" to fmt(cy),
                    "text-anchor" to "middle",
                ),
        ) { text(element.name) }
        cy += 12f

        // Compartment 2 — Expression-Body (monospaced, ggf. ellipsis-trunkiert).
        if (hasExpression) {
            tag(
                name = "line",
                attrs =
                    mapOf(
                        "x1" to "0",
                        "y1" to fmt(cy),
                        "x2" to fmt(w),
                        "y2" to fmt(cy),
                        "class" to "kuml-divider",
                    ),
            )
            cy += 14f
            tag(
                name = "text",
                attrs =
                    mapOf(
                        "class" to "kuml-body",
                        "x" to fmt(w / 2f),
                        "y" to fmt(cy),
                        "text-anchor" to "middle",
                        "font-family" to "monospace",
                    ),
            ) { text(expressionText) }
            cy += 13f
        }

        // Compartment 3 — Parameter-Liste.
        if (hasParameters) {
            tag(
                name = "line",
                attrs =
                    mapOf(
                        "x1" to "0",
                        "y1" to fmt(cy),
                        "x2" to fmt(w),
                        "y2" to fmt(cy),
                        "class" to "kuml-divider",
                    ),
            )
            cy += 14f
            for (parameter in element.parameters) {
                tag(
                    name = "text",
                    attrs = mapOf("class" to "kuml-body", "x" to fmt(ParConstraintGeometry.PARAM_TEXT_INDENT), "y" to fmt(cy)),
                ) { text(parameter.formatParameterLine()) }
                cy += ParConstraintGeometry.PARAM_ROW_H
            }
        }

        // Pin markers on the frame, drawn last so they sit on top of the box outline.
        val half = ParConstraintGeometry.PIN_SIZE / 2f
        element.parameters.forEachIndexed { index, parameter ->
            val px = if (ParConstraintGeometry.pinSide(parameter.direction) == PortSide.West) 0f else w
            val py = ParConstraintGeometry.pinY(hasExpression = hasExpression, index = index)
            tag(
                name = "rect",
                attrs =
                    mapOf(
                        "x" to fmt(px - half),
                        "y" to fmt(py - half),
                        "width" to fmt(ParConstraintGeometry.PIN_SIZE),
                        "height" to fmt(ParConstraintGeometry.PIN_SIZE),
                        "class" to "kuml-class kuml-par-pin",
                    ),
            )
        }
    }
}

/**
 * Formatiert einen [ConstraintParameter] als Zeile für das Parameter-
 * Kompartiment: `«in» m : Mass`, `«out» F : Force`, `«inout» x` (wenn
 * `typeId` null ist).
 */
private fun ConstraintParameter.formatParameterLine(): String {
    val stereotype =
        when (direction) {
            ConstraintParameterDirection.In -> "«in»"
            ConstraintParameterDirection.Out -> "«out»"
            ConstraintParameterDirection.Inout -> "«inout»"
        }
    val typeSuffix = typeId?.let { " : ${it.take(Sysml2Limits.TEXT)}" } ?: ""
    return "$stereotype ${name.take(Sysml2Limits.TEXT)}$typeSuffix"
}

/**
 * Trunkiert eine Expression an [EXPRESSION_MAX_CHARS] Zeichen mit `…`-Suffix,
 * wenn sie länger ist. V2.x-Polish: echte Glyph-Breiten-Messung statt
 * Zeichenanzahl (siehe `wrapWords` im REQ-Renderer für die analoge MVP-
 * Approximation auf der Text-Wrap-Seite).
 */
private fun truncateExpression(expression: String): String =
    if (expression.length <= EXPRESSION_MAX_CHARS) {
        expression
    } else {
        expression.substring(0, EXPRESSION_MAX_CHARS - 1) + "…"
    }

private const val EXPRESSION_MAX_CHARS: Int = 30

private fun fmt(v: Float): String = fmt3(v)
