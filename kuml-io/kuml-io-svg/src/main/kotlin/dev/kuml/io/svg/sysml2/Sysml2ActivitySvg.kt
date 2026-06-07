package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.io.svg.xmlEscapeText
import dev.kuml.layout.NodeLayout
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.sysml2.ActionDefinition
import dev.kuml.sysml2.ActivityNodeKind

/**
 * Rendert eine SysML-2-[ActionDefinition] als Activity-Diagramm-Knoten
 * (V2.0.10).
 *
 * Dispatch nach [ActivityNodeKind]:
 *  - **Action** (`kind = Action`) — abgerundetes Rechteck (`rx="14" ry="14"`)
 *    mit Namen zentriert oben; bei nicht-null `action` wird der Body als
 *    zweite Textzeile angehängt (bei ~30 Zeichen abgeschnitten mit `…`).
 *  - **Initial** — gefüllter Kreis zentriert in den Bounds (klassischer
 *    UML-/SysML-2-Start-Marker).
 *  - **Final** — Donut: äußerer Kreis (Stroke + weißer Fill) + innerer
 *    gefüllter Kreis (Stop des gesamten Activities).
 *  - **FlowFinal** — Kreis (Stroke only) plus zwei diagonale Linien
 *    (X-Form) im Inneren — Ende eines einzelnen Tokens, andere Tokens
 *    laufen weiter.
 *  - **Decision / Merge** — Rautenform: `<polygon>` mit vier Punkten
 *    (oben/rechts/unten/links der Bounds). Decision verzweigt auf Guards;
 *    Merge bringt alternative Pfade zusammen — visuell identisch in der
 *    SysML-2-Konvention, die Disambiguierung erfolgt über Position im
 *    Token-Fluss.
 *  - **Fork / Join** — Synchronisations-Bar: dickes gefülltes Rechteck.
 *    Die Layout-Bridge gibt 120×10 Bounds (horizontale Bar); die
 *    Orientierung kann die Layout-Engine später flippen.
 *
 * Theme-Anbindung: nutzt die existierenden CSS-Klassen (`kuml-class` für
 * Stroke, `kuml-title` für den Aktions-Namen, `kuml-body` für den optionalen
 * Action-Body) damit ACT-Knoten visuell mit BDD-/IBD-/UC-/REQ-/STM-Knoten
 * im selben Diagramm harmonieren — Tooling-Konsumenten brauchen keine
 * Spezial-Stylesheets.
 *
 * V2.x:
 *  - Activity-Partitions (Swimlanes) — gestrichelte Spalten/Zeilen-Trenner.
 *  - Interruptible Regions — gestrichelte Umrahmung.
 *  - Pin-Notation auf Aktionen (typisierte Input/Output-Pins als kleine
 *    Quadrate auf der Box-Außenseite).
 *  - `[guard]`-Label auf Control-Flow-Edges und `[ObjectType]`-Label auf
 *    Object-Flow-Edges (heute auf der Edge nicht renderbar, weil die
 *    synthetische `KumlDiagram`-Hülle keine `UmlRelationship`-Elemente für
 *    `ControlFlowUsage` / `ObjectFlowUsage` hat — gleiche Limitation wie
 *    UC / REQ / STM).
 *  - Stream-Flow / Multicast-Semantics auf Object-Flow.
 *  - Live Token-Flow-Runtime — separate Behaviour-Runtime-Welle.
 */
internal fun renderActionDefinition(
    element: ActionDefinition,
    layout: NodeLayout,
    @Suppress("UNUSED_PARAMETER") theme: KumlTheme,
    builder: SvgBuilder,
) {
    when (element.kind) {
        ActivityNodeKind.Action -> renderRegularAction(element, layout, builder)
        ActivityNodeKind.Initial -> renderInitialNode(element, layout, builder)
        ActivityNodeKind.Final -> renderFinalNode(element, layout, builder)
        ActivityNodeKind.FlowFinal -> renderFlowFinalNode(element, layout, builder)
        ActivityNodeKind.Decision,
        ActivityNodeKind.Merge,
        -> renderDiamond(element, layout, builder)
        ActivityNodeKind.Fork,
        ActivityNodeKind.Join,
        -> renderBar(element, layout, builder)
    }
}

/**
 * Reguläre Aktion — abgerundetes Rechteck (`rx="14" ry="14"`) mit Namen
 * zentriert oben. Wenn [ActionDefinition.action] gesetzt ist, kommt eine
 * zweite Zeile darunter mit dem Action-Body (bei ~30 Zeichen mit `…`
 * abgeschnitten — Lesbarkeit über Vollständigkeit; V2.x bringt Wort-Wrap).
 */
private fun renderRegularAction(
    element: ActionDefinition,
    layout: NodeLayout,
    builder: SvgBuilder,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height

    builder.tag(
        "g",
        mapOf("id" to xmlEscapeAttr(element.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        tag(
            "rect",
            mapOf(
                "width" to fmt(w),
                "height" to fmt(h),
                "rx" to "14",
                "ry" to "14",
                "class" to "kuml-class",
            ),
        )

        val hasAction = !element.action.isNullOrEmpty()
        val nameY = if (hasAction) h / 2f - 2f else h / 2f + 4f
        val nameClass = if (element.isAbstract) "kuml-title kuml-title-abstract" else "kuml-title"
        tag(
            "text",
            mapOf(
                "class" to nameClass,
                "x" to fmt(w / 2f),
                "y" to fmt(nameY),
                "text-anchor" to "middle",
            ),
        ) { text(xmlEscapeText(element.name)) }

        if (hasAction) {
            val truncated = truncate(element.action!!, ACT_BODY_MAX_LEN)
            tag(
                "text",
                mapOf(
                    "class" to "kuml-body",
                    "x" to fmt(w / 2f),
                    "y" to fmt(h / 2f + 14f),
                    "text-anchor" to "middle",
                ),
            ) { text(xmlEscapeText(truncated)) }
        }
    }
}

/** Initial node — gefüllter Kreis zentriert in den Bounds. */
private fun renderInitialNode(
    element: ActionDefinition,
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
 * Final node — Donut: äußerer Kreis (Stroke + weißer Fill) + innerer
 * gefüllter Kreis. Innenradius ist Hälfte des Außenradius — etablierte
 * Konvention für den Final-Marker.
 */
private fun renderFinalNode(
    element: ActionDefinition,
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
 * Flow-Final node — Kreis (Stroke only) plus zwei diagonale Linien im
 * Inneren, die ein X bilden. Markiert das Ende eines einzelnen Tokens;
 * andere parallele Tokens laufen weiter.
 */
private fun renderFlowFinalNode(
    element: ActionDefinition,
    layout: NodeLayout,
    builder: SvgBuilder,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height
    val cx = w / 2f
    val cy = h / 2f
    val r = minOf(w, h) * 0.45f
    // X liegt innerhalb des Kreises — etwas kleiner als der Radius.
    val xArm = r * 0.6f

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
                "fill" to "white",
            ),
        )
        // X-Form: zwei diagonale Linien.
        tag(
            "line",
            mapOf(
                "x1" to fmt(cx - xArm),
                "y1" to fmt(cy - xArm),
                "x2" to fmt(cx + xArm),
                "y2" to fmt(cy + xArm),
                "class" to "kuml-class",
            ),
        )
        tag(
            "line",
            mapOf(
                "x1" to fmt(cx + xArm),
                "y1" to fmt(cy - xArm),
                "x2" to fmt(cx - xArm),
                "y2" to fmt(cy + xArm),
                "class" to "kuml-class",
            ),
        )
    }
}

/**
 * Decision / Merge — Rautenform: `<polygon>` mit vier Punkten
 * (oben/rechts/unten/links der Bounds). Beide Kinds rendern identisch;
 * Disambiguierung über Token-Fluss-Richtung.
 */
private fun renderDiamond(
    element: ActionDefinition,
    layout: NodeLayout,
    builder: SvgBuilder,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height
    val cx = w / 2f
    val cy = h / 2f

    val points =
        "${fmt(cx)},${fmt(0f)} " +
            "${fmt(w)},${fmt(cy)} " +
            "${fmt(cx)},${fmt(h)} " +
            "${fmt(0f)},${fmt(cy)}"

    builder.tag(
        "g",
        mapOf("id" to xmlEscapeAttr(element.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        tag(
            "polygon",
            mapOf(
                "points" to points,
                "class" to "kuml-class",
            ),
        )
    }
}

/**
 * Fork / Join — Synchronisations-Bar: dickes gefülltes Rechteck. Die
 * Layout-Bridge gibt 120×10 Bounds (horizontale Bar); die Layout-Engine
 * kann später flippen, wenn das Routing das nahelegt.
 */
private fun renderBar(
    element: ActionDefinition,
    layout: NodeLayout,
    builder: SvgBuilder,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height

    builder.tag(
        "g",
        mapOf("id" to xmlEscapeAttr(element.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        tag(
            "rect",
            mapOf(
                "width" to fmt(w),
                "height" to fmt(h),
                "class" to "kuml-class",
                "fill" to "currentColor",
            ),
        )
    }
}

/**
 * Maximalzahl Zeichen für den Action-Body, bevor er mit `…` abgeschnitten
 * wird. Bewusst konservativ, weil der Body unterhalb des Namens in einer
 * Action-Box ~150px breit liegt; eine echte Wort-Wrap-Lösung ist V2.x.
 */
private const val ACT_BODY_MAX_LEN = 30

private fun truncate(
    s: String,
    max: Int,
): String =
    if (s.length <= max) {
        s
    } else {
        s.substring(0, max - 1) + "…"
    }

private fun fmt(v: Float): String =
    if (v == v.toInt().toFloat()) {
        v.toInt().toString()
    } else {
        String.format(java.util.Locale.US, "%.3f", v)
    }
