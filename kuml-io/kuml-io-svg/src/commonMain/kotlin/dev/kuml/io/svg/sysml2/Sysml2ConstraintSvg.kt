package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.fmt3
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.PortSide
import dev.kuml.layout.bridge.ParConstraintGeometry
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.sysml2.ConstraintDefinition

/**
 * Rendert eine SysML-2-[ConstraintDefinition] als drei-kompartimentige
 * Constraint-Box (V2.0.12, letzte SysML-2-Diagramm-Welle).
 *
 * Visuell:
 *  - Kompartiment 1: Stereotyp `«constraint»` (zentriert) + Name (fett zentriert).
 *  - Kompartiment 2: Expression-Body (monospaced, zentriert), bei Bedarf an
 *    [ParConstraintGeometry.EXPRESSION_WRAP_CHARS] Zeichen umbrochen (Grenze
 *    `Sysml2Limits.TEXT`, danach `…`). Leerer
 *    Expression-Body wird stillschweigend weggelassen (kein Divider, kein
 *    leeres Compartment).
 *  - Kompartiment 3: Parameter-Liste — eine Zeile pro [ConstraintParameter]
 *    in der Form `«in» m : Mass [kg]`, `«out» F : Force`, `«inout» x : Real`. Der
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
 */
internal fun renderConstraintDefinition(
    element: ConstraintDefinition,
    layout: NodeLayout,
    @Suppress("UNUSED_PARAMETER") theme: KumlTheme,
    builder: SvgBuilder,
    context: Sysml2NodeContext? = null,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height

    val expressionLines = ParConstraintGeometry.expressionLinesOf(element)
    val hasExpression = expressionLines.isNotEmpty()
    val unitOf = context?.let { ParConstraintGeometry.unitLookup(it.model) } ?: { _: String -> null }
    val hasParameters = element.parameters.isNotEmpty()
    val paramIndent =
        context?.let { ParConstraintGeometry.paramTextIndent(model = it.model, constraint = element) }
            ?: ParConstraintGeometry.PARAM_TEXT_INDENT

    builder.tag(
        name = "g",
        attrs = mapOf("id" to xmlEscapeAttr(element.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        tag(name = "rect", attrs = mapOf("width" to fmt(w), "height" to fmt(h), "class" to "kuml-class"))

        // Compartment 1 — `«constraint»`-Stereotyp.
        tag(
            name = "text",
            attrs =
                mapOf(
                    "class" to "kuml-stereotype",
                    "x" to fmt(w / 2f),
                    "y" to fmt(ParConstraintGeometry.STEREOTYPE_BASELINE),
                    "text-anchor" to "middle",
                ),
        ) { text("«constraint»") }

        // Compartment 1 — Name (fett, zentriert). Abstract → italic-via-style hint.
        val nameClass = if (element.isAbstract) "kuml-title kuml-title-abstract" else "kuml-title"
        tag(
            name = "text",
            attrs =
                mapOf(
                    "class" to nameClass,
                    "x" to fmt(w / 2f),
                    "y" to fmt(ParConstraintGeometry.NAME_BASELINE),
                    "text-anchor" to "middle",
                ),
        ) { text(element.name) }

        // Compartment 2 — Expression-Body (monospaced, one <text> per wrapped line).
        if (hasExpression) {
            divider(w = w, y = ParConstraintGeometry.FIRST_DIVIDER_Y)
            expressionLines.forEachIndexed { i, line ->
                tag(
                    name = "text",
                    attrs =
                        mapOf(
                            "class" to "kuml-body",
                            "x" to fmt(w / 2f),
                            "y" to fmt(ParConstraintGeometry.EXPRESSION_BASELINE + i * ParConstraintGeometry.EXPR_LINE_H),
                            "text-anchor" to "middle",
                            "font-family" to "monospace",
                        ),
                ) { text(line) }
            }
        }

        // Compartment 3 — Parameter-Liste.
        if (hasParameters) {
            divider(
                w = w,
                y =
                    if (hasExpression) {
                        ParConstraintGeometry.dividerAfterExpressionY(expressionLines.size)
                    } else {
                        ParConstraintGeometry.FIRST_DIVIDER_Y
                    },
            )
            element.parameters.forEachIndexed { index, parameter ->
                tag(
                    name = "text",
                    attrs =
                        mapOf(
                            "class" to "kuml-body",
                            "x" to fmt(paramIndent),
                            "y" to fmt(ParConstraintGeometry.paramBaseline(expressionLines = expressionLines.size, index = index)),
                        ),
                ) { text(ParConstraintGeometry.parameterLine(p = parameter, unitOf = unitOf)) }
            }
        }

        // Pin markers on the frame, drawn last so they sit on top of the box outline.
        val half = ParConstraintGeometry.PIN_SIZE / 2f
        element.parameters.forEachIndexed { index, parameter ->
            val px = if (ParConstraintGeometry.pinSide(parameter.direction) == PortSide.West) 0f else w
            val py = ParConstraintGeometry.pinY(expressionLines = expressionLines.size, index = index)
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

private fun SvgBuilder.divider(
    w: Float,
    y: Float,
) {
    tag(
        name = "line",
        attrs = mapOf("x1" to "0", "y1" to fmt(y), "x2" to fmt(w), "y2" to fmt(y), "class" to "kuml-divider"),
    )
}

private fun fmt(v: Float): String = fmt3(v)
