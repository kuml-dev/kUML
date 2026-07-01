package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.layout.NodeLayout
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.sysml2.ActionDefinition
import dev.kuml.sysml2.ActionPin
import dev.kuml.sysml2.ActivityNodeKind
import dev.kuml.sysml2.PinDirection
import dev.kuml.io.svg.fmt3

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
        val nameAttrs =
            buildMap<String, String> {
                put("class", nameClass)
                put("x", fmt(w / 2f))
                put("y", fmt(nameY))
                put("text-anchor", "middle")
                if (element.isAbstract) put("font-style", "italic")
            }
        tag("text", nameAttrs) { text(element.name) }

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
            ) { text(truncated) }
        }

        // V2.0.16: render pins on the action box edge. Input pins land on
        // the left edge, Output pins on the right edge, each evenly
        // distributed along the available vertical height. The pin name
        // surfaces as a small text label adjacent to the square (outside
        // for inputs, outside-right for outputs) so the label does not
        // collide with the action name / body.
        renderActionPins(element.pins, w, h)
    }
}

/**
 * Renders the input / output pins on the edge of a regular action box
 * (V2.0.16). Called from [renderRegularAction] *inside* the action group
 * (the translate is already in effect, so all coordinates are box-local).
 *
 * Layout convention:
 *  - **Input pins** — left edge, x = `-PIN_SIZE/2` so the square straddles
 *    the action border (visual cue that the pin is a port, not a
 *    decoration). Names render to the right of each square inside the
 *    box edge, anchored at the start of the text.
 *  - **Output pins** — right edge, x = `w - PIN_SIZE/2` symmetric to the
 *    input side. Names render to the left of each square (`text-anchor =
 *    end`) again inside the box edge.
 *  - **Vertical distribution** — each side's pins are evenly distributed
 *    in the vertical band `[PIN_VERTICAL_PAD … h - PIN_VERTICAL_PAD]` so
 *    they don't overlap the box corners. Single pins land at the
 *    vertical centre.
 *
 * Pins for non-Action kinds (Initial / Final / FlowFinal / Decision /
 * Merge / Fork / Join) are filtered out at the metamodel level (the
 * `pins` list is empty for those by spec), so this helper does not need a
 * kind-discriminator.
 */
private fun SvgBuilder.renderActionPins(
    pins: List<ActionPin>,
    w: Float,
    h: Float,
) {
    if (pins.isEmpty()) return
    val inputs = pins.filter { it.direction == PinDirection.Input }
    val outputs = pins.filter { it.direction == PinDirection.Output }
    // Input pins: label OUTSIDE the box to the LEFT (text-anchor="end")
    renderPinColumn(inputs, edgeX = -PIN_SIZE / 2f, h = h, labelOffsetX = -(PIN_SIZE / 2f + 4f), anchor = "end")
    // Output pins: label OUTSIDE the box to the RIGHT (text-anchor="start")
    renderPinColumn(
        outputs,
        edgeX = w - PIN_SIZE / 2f,
        h = h,
        labelOffsetX = PIN_SIZE / 2f + 4f,
        anchor = "start",
    )
}

/**
 * Helper for [renderActionPins] — draws one column of pins (all inputs or
 * all outputs) on a given x position with a kind-specific label anchor.
 */
private fun SvgBuilder.renderPinColumn(
    pins: List<ActionPin>,
    edgeX: Float,
    h: Float,
    labelOffsetX: Float,
    anchor: String,
) {
    if (pins.isEmpty()) return
    val usableH = h - 2f * PIN_VERTICAL_PAD
    val step = if (pins.size == 1) 0f else usableH / (pins.size - 1)
    for ((i, pin) in pins.withIndex()) {
        val py =
            if (pins.size == 1) {
                h / 2f - PIN_SIZE / 2f
            } else {
                PIN_VERTICAL_PAD + step * i - PIN_SIZE / 2f
            }
        tag(
            "rect",
            mapOf(
                "x" to fmt(edgeX),
                "y" to fmt(py),
                "width" to fmt(PIN_SIZE),
                "height" to fmt(PIN_SIZE),
                // SysML v2 zeichnet Parameter (directed features) als ABGERUNDETE
                // Quadrate auf der Aktionskante — nicht scharfkantig wie ein UML-Pin.
                "rx" to fmt(PIN_CORNER_RADIUS),
                "ry" to fmt(PIN_CORNER_RADIUS),
                "class" to "kuml-class",
                "fill" to "white",
            ),
        )
        // Pin name as a small label adjacent to the square. The label
        // sits at the vertical centre of the square so it lines up with
        // the action's body text.
        tag(
            "text",
            mapOf(
                "class" to "kuml-body",
                "x" to fmt(edgeX + PIN_SIZE / 2f + labelOffsetX),
                "y" to fmt(py + PIN_SIZE / 2f + 3f),
                "text-anchor" to anchor,
            ),
        ) { text(pin.name) }
    }
}

/**
 * Side length (px) of a single pin square on an action box edge (V2.0.16).
 * Small enough to be visually a "port", not a decoration; large enough to
 * accommodate a 1-2 character text label inside if a V2.x polish wave
 * ever decides to put the name inside the square.
 */
internal const val PIN_SIZE: Float = 10f

/**
 * Corner radius (px) of a pin square. SysML v2 notates action parameters
 * (directed features) as **rounded** squares on the action boundary; a small
 * 2 px radius gives that rounded-corner cue without softening the port into a
 * blob.
 */
private const val PIN_CORNER_RADIUS: Float = 2f

/**
 * Vertical padding inside the action box where pins must not be placed
 * (V2.0.16). Keeps pins clear of the action's rounded corners.
 */
private const val PIN_VERTICAL_PAD: Float = 12f

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

private fun fmt(v: Float): String = fmt3(v)
