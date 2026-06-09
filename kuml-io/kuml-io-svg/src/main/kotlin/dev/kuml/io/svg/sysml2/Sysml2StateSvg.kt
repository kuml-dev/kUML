package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.layout.NodeLayout
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.sysml2.StateDefinition

/**
 * Rendert eine SysML-2-[StateDefinition] als State-Transition-Diagramm-Knoten
 * (V2.0.9).
 *
 * Dispatch nach State-Kind:
 *  - **Initial pseudo-state** (`isInitial = true`) — gefüllter Kreis,
 *    zentriert in den Layout-Bounds. Visuell entspricht das dem klassischen
 *    UML-/SysML-2-Initial-Marker.
 *  - **Final pseudo-state** (`isFinal = true`) — "Donut": ein äußerer Kreis
 *    mit einem kleineren gefüllten Innenkreis. Standardform für den
 *    Final-Marker.
 *  - **Regulärer Zustand** (beide Flags `false`) — abgerundetes Rechteck
 *    (`rx="12" ry="12"`) mit dem Namen zentriert oben. Wenn ein
 *    `entry / exit / do`-Action gesetzt ist: Divider-Linie + pro
 *    nicht-leerem Action-Slot eine Zeile in SysML-2-Syntax
 *    (`entry / <action>`, `exit / <action>`, `do / <action>`).
 *
 * Hinweis zum gegenseitigen Ausschluss: `isInitial` und `isFinal` sind per
 * SysML-2-Spec mutually exclusive. Der Renderer respektiert die Reihenfolge
 * Initial → Final → Regulär; ein State, der versehentlich beide Flags
 * setzt, würde als Initial gerendert. Die Validierung dieses Falls liegt
 * beim Validator, nicht beim Renderer (analog zu BDD-/REQ-Pattern).
 *
 * Theme-Anbindung: nutzt die existierenden CSS-Klassen (`kuml-class` für
 * Stroke, `kuml-title` für den Zustandsnamen, `kuml-divider` für die
 * Trennlinie, `kuml-body` für die Action-Zeilen) damit STM-Knoten visuell
 * mit BDD-/IBD-/UC-/REQ-Knoten im selben Diagramm harmonieren —
 * Tooling-Konsumenten brauchen keine Spezial-Stylesheets.
 *
 * V2.x:
 *  - Composite-States (geschachtelte STM-Diagramme in einer äußeren Box).
 *  - Orthogonal/History-States (gestrichelte Region-Trenner, History-Marker).
 *  - `trigger [guard] / effect`-Labels auf den Transitionen (heute auf der
 *    Edge nicht renderbar, weil die synthetische `KumlDiagram`-Hülle keine
 *    `UmlRelationship`-Elemente für TransitionUsages hat — gleiche
 *    Limitation wie UC / REQ).
 */
internal fun renderStateDefinition(
    element: StateDefinition,
    layout: NodeLayout,
    @Suppress("UNUSED_PARAMETER") theme: KumlTheme,
    builder: SvgBuilder,
) {
    when {
        element.isInitial -> renderInitialPseudoState(element, layout, builder)
        element.isFinal -> renderFinalPseudoState(element, layout, builder)
        else -> renderRegularState(element, layout, builder)
    }
}

/**
 * Initial pseudo-state — gefüllter Kreis zentriert in den Bounds. Radius =
 * 40 % der kleineren Bounds-Dimension, damit der Marker bei jeder
 * Bounds-Größe als kompakter Punkt erkennbar bleibt.
 */
private fun renderInitialPseudoState(
    element: StateDefinition,
    layout: NodeLayout,
    builder: SvgBuilder,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height
    val cx = w / 2f
    val cy = h / 2f
    val r = minOf(w, h) * 0.4f

    builder.tag(
        "g",
        mapOf("id" to xmlEscapeAttr(element.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        tag(
            "circle",
            mapOf(
                "cx" to fmt(cx),
                "cy" to fmt(cy),
                "r" to fmt(r),
                "class" to "kuml-class",
                "fill" to "currentColor",
            ),
        )
    }
}

/**
 * Final pseudo-state — "Donut": äußerer Kreis (nur Stroke, kein Fill) +
 * innerer gefüllter Kreis. Der Innenradius ist die Hälfte des Außenradius —
 * eine etablierte Konvention für den Final-Marker.
 */
private fun renderFinalPseudoState(
    element: StateDefinition,
    layout: NodeLayout,
    builder: SvgBuilder,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height
    val cx = w / 2f
    val cy = h / 2f
    val outerR = minOf(w, h) * 0.45f
    val innerR = outerR * 0.5f

    builder.tag(
        "g",
        mapOf("id" to xmlEscapeAttr(element.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        // Outer ring — Stroke only, weißer Hintergrund.
        tag(
            "circle",
            mapOf(
                "cx" to fmt(cx),
                "cy" to fmt(cy),
                "r" to fmt(outerR),
                "class" to "kuml-class",
                "fill" to "white",
            ),
        )
        // Inner filled disc.
        tag(
            "circle",
            mapOf(
                "cx" to fmt(cx),
                "cy" to fmt(cy),
                "r" to fmt(innerR),
                "class" to "kuml-class",
                "fill" to "currentColor",
            ),
        )
    }
}

/**
 * Regulärer State — abgerundetes Rechteck (`rx="12" ry="12"`) mit Namen
 * zentriert oben. Wenn mindestens ein Action-Slot
 * ([StateDefinition.entryAction] / [StateDefinition.exitAction] /
 * [StateDefinition.doAction]) gesetzt ist: Divider-Linie + pro gesetztem
 * Slot eine Zeile in SysML-2-Konkretsyntax (`entry / <expr>`,
 * `exit / <expr>`, `do / <expr>`).
 */
private fun renderRegularState(
    element: StateDefinition,
    layout: NodeLayout,
    builder: SvgBuilder,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height

    val hasActions =
        !element.entryAction.isNullOrEmpty() ||
            !element.exitAction.isNullOrEmpty() ||
            !element.doAction.isNullOrEmpty()

    builder.tag(
        "g",
        mapOf("id" to xmlEscapeAttr(element.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        tag(
            "rect",
            mapOf(
                "width" to fmt(w),
                "height" to fmt(h),
                "rx" to "12",
                "ry" to "12",
                "class" to "kuml-class",
            ),
        )

        var cy = 18f

        // Zustands-Name zentriert oben.
        val nameClass = if (element.isAbstract) "kuml-title kuml-title-abstract" else "kuml-title"
        val nameAttrs =
            buildMap<String, String> {
                put("class", nameClass)
                put("x", fmt(w / 2f))
                put("y", fmt(cy))
                put("text-anchor", "middle")
                if (element.isAbstract) put("font-style", "italic")
            }
        tag("text", nameAttrs) { text(element.name) }
        cy += 8f

        if (hasActions) {
            tag(
                "line",
                mapOf(
                    "x1" to "0",
                    "y1" to fmt(cy),
                    "x2" to fmt(w),
                    "y2" to fmt(cy),
                    "class" to "kuml-divider",
                ),
            )
            cy += 14f

            element.entryAction?.takeIf { it.isNotEmpty() }?.let { action ->
                tag(
                    "text",
                    mapOf("class" to "kuml-body", "x" to "8", "y" to fmt(cy)),
                ) { text("entry / $action") }
                cy += 13f
            }
            element.exitAction?.takeIf { it.isNotEmpty() }?.let { action ->
                tag(
                    "text",
                    mapOf("class" to "kuml-body", "x" to "8", "y" to fmt(cy)),
                ) { text("exit / $action") }
                cy += 13f
            }
            element.doAction?.takeIf { it.isNotEmpty() }?.let { action ->
                tag(
                    "text",
                    mapOf("class" to "kuml-body", "x" to "8", "y" to fmt(cy)),
                ) { text("do / $action") }
                cy += 13f
            }
        }
    }
}

private fun fmt(v: Float): String = if (v == v.toInt().toFloat()) v.toInt().toString() else String.format(java.util.Locale.US, "%.3f", v)
