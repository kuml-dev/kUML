package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.fmt3
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.layout.NodeLayout
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.sysml2.ActionDefinition
import dev.kuml.sysml2.ActionPin
import dev.kuml.sysml2.ActivityNodeKind
import dev.kuml.sysml2.PinDirection
import dev.kuml.sysml2.PinSide

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
        ActivityNodeKind.Action -> renderRegularAction(element = element, layout = layout, builder = builder)
        ActivityNodeKind.Initial -> renderInitialNode(element = element, layout = layout, builder = builder)
        ActivityNodeKind.Final -> renderFinalNode(element = element, layout = layout, builder = builder)
        ActivityNodeKind.FlowFinal -> renderFlowFinalNode(element = element, layout = layout, builder = builder)
        ActivityNodeKind.Decision,
        ActivityNodeKind.Merge,
        -> renderDiamond(element = element, layout = layout, builder = builder)
        ActivityNodeKind.Fork,
        ActivityNodeKind.Join,
        -> renderBar(element = element, layout = layout, builder = builder)
        ActivityNodeKind.SendSignal -> renderSignalNode(element = element, layout = layout, builder = builder, send = true)
        ActivityNodeKind.AcceptEvent -> renderSignalNode(element = element, layout = layout, builder = builder, send = false)
        ActivityNodeKind.AcceptTimeEvent -> renderTimeEventNode(element = element, layout = layout, builder = builder)
        ActivityNodeKind.CallBehavior -> renderRegularAction(element = element, layout = layout, builder = builder)
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

    val isCall = element.kind == ActivityNodeKind.CallBehavior
    val groupAttrs =
        buildMap {
            put("id", xmlEscapeAttr(element.id))
            put("transform", "translate(${fmt(x)},${fmt(y)})")
            // Wave C2: machine-readable link to the called activity (no <a>: navigation is the host's job).
            if (isCall) element.calledActivityId?.let { put("data-kuml-calls", xmlEscapeAttr(it)) }
        }
    builder.tag(
        name = "g",
        attrs = groupAttrs,
    ) {
        tag(
            name = "rect",
            attrs =
                mapOf(
                    "width" to fmt(w),
                    "height" to fmt(h),
                    "rx" to "14",
                    "ry" to "14",
                    "class" to "kuml-class",
                ),
        )
        if (isCall) renderRake(w = w, h = h)

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
        tag(name = "text", attrs = nameAttrs) { text(element.name) }

        if (hasAction) {
            val truncated = truncate(s = element.action!!, max = ACT_BODY_MAX_LEN)
            tag(
                name = "text",
                attrs =
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
        renderActionPins(pins = element.pins, w = w, h = h)
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
    for (side in PinSide.entries) {
        if (side == PinSide.Auto) continue
        val onSide = pins.filter { it.effectiveSide == side }
        if (onSide.isEmpty()) continue
        val explicit = onSide.any { it.side != PinSide.Auto }
        val positions = pinPositions(count = onSide.size, side = side, w = w, h = h)
        for ((i, pin) in onSide.withIndex()) {
            val (cx, cy) = positions[i]
            tag(
                name = "rect",
                attrs =
                    mapOf(
                        "x" to fmt(cx - PIN_SIZE / 2f),
                        "y" to fmt(cy - PIN_SIZE / 2f),
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
            // Explicit sides cap the label at PIN_LABEL_MAX_CHARS; the historic left / right placement stays untouched.
            val label = if (pin.side != PinSide.Auto || explicit) truncate(s = pin.name, max = PIN_LABEL_MAX_CHARS) else pin.name
            val (lx, ly, anchor) =
                when (side) {
                    // Wave C2b: labels sit in the quadrant away from the edge that docks perpendicular at the pin
                    // centre line (offset 9 px sideways), so neither the line nor its arrow head runs through the text.
                    PinSide.Left -> Triple(cx - 9f, cy - 9f, "end")
                    PinSide.Right -> Triple(cx + 9f, cy - 9f, "start")
                    PinSide.Top -> Triple(cx + 9f, cy - 9f, "start")
                    else -> Triple(cx + 9f, cy + 17f, "start")
                }
            tag(
                name = "text",
                attrs =
                    mapOf(
                        "class" to "kuml-body",
                        "x" to fmt(lx),
                        "y" to fmt(ly),
                        "text-anchor" to anchor,
                    ),
            ) { text(label) }
        }
    }
}

/**
 * Local (node-relative) pin-square centres for [count] pins on [side] of a [w] x [h] box. Left
 * and right pins are spread along the height, top and bottom pins along the width; one pin sits in
 * the middle. Shared by the pin drawing and the pin-to-pin edge anchoring ([actionPinAnchor]).
 */
internal fun pinPositions(
    count: Int,
    side: PinSide,
    w: Float,
    h: Float,
): List<Pair<Float, Float>> {
    val vertical = side == PinSide.Left || side == PinSide.Right
    val length = if (vertical) h else w
    val usable = length - 2f * PIN_VERTICAL_PAD
    val step = if (count <= 1) 0f else usable / (count - 1)
    return (0 until count).map { i ->
        val along = if (count == 1) length / 2f else PIN_VERTICAL_PAD + step * i
        when (side) {
            PinSide.Left -> 0f to along
            PinSide.Right -> w to along
            PinSide.Top -> along to 0f
            else -> along to h
        }
    }
}

/**
 * Absolute point where an edge docks at the outer edge of the pin named [pinName] (direction
 * [direction]) of [def], laid out at [bounds]; `null` when the pin does not exist.
 */
internal fun actionPinAnchor(
    def: ActionDefinition,
    bounds: dev.kuml.layout.Rect,
    pinName: String,
    direction: PinDirection,
): dev.kuml.layout.Point? = actionPinAnchorWithSide(def = def, bounds = bounds, pinName = pinName, direction = direction)?.first

/** Like [actionPinAnchor], additionally returning the box side the pin sits on (wave C2b: pin routing). */
internal fun actionPinAnchorWithSide(
    def: ActionDefinition,
    bounds: dev.kuml.layout.Rect,
    pinName: String,
    direction: PinDirection,
): Pair<dev.kuml.layout.Point, PinSide>? {
    val pin = def.pins.firstOrNull { it.name == pinName && it.direction == direction } ?: return null
    val side = pin.effectiveSide
    val onSide = def.pins.filter { it.effectiveSide == side }
    val idx = onSide.indexOf(pin)
    val (cx, cy) = pinPositions(count = onSide.size, side = side, w = bounds.size.width, h = bounds.size.height)[idx]
    val (ox, oy) =
        when (side) {
            PinSide.Left -> -PIN_SIZE / 2f to 0f
            PinSide.Right -> PIN_SIZE / 2f to 0f
            PinSide.Top -> 0f to -PIN_SIZE / 2f
            else -> 0f to PIN_SIZE / 2f
        }
    return dev.kuml.layout.Point(x = bounds.origin.x + cx + ox, y = bounds.origin.y + cy + oy) to side
}

/**
 * Estimated rectangles (absolute coordinates, [PIN_LABEL_CHAR_PX] per character, font independent) of the
 * pin labels of [def] laid out at [bounds]; used as obstacles by the edge-label placer (wave C2b).
 */
internal fun pinLabelRects(
    def: ActionDefinition,
    bounds: dev.kuml.layout.Rect,
): List<dev.kuml.layout.Rect> {
    val out = ArrayList<dev.kuml.layout.Rect>()
    val explicit = def.pins.any { it.side != PinSide.Auto }
    for (side in PinSide.entries) {
        if (side == PinSide.Auto) continue
        val onSide = def.pins.filter { it.effectiveSide == side }
        if (onSide.isEmpty()) continue
        val pos = pinPositions(count = onSide.size, side = side, w = bounds.size.width, h = bounds.size.height)
        onSide.forEachIndexed { i, pin ->
            val text = if (pin.side != PinSide.Auto || explicit) truncate(s = pin.name, max = PIN_LABEL_MAX_CHARS) else pin.name
            val w = text.length * PIN_LABEL_CHAR_PX
            val cx = bounds.origin.x + pos[i].first
            val cy = bounds.origin.y + pos[i].second
            val (x0, base) =
                when (side) {
                    PinSide.Left -> (cx - 9f - w) to (cy - 9f)
                    PinSide.Right, PinSide.Top -> (cx + 9f) to (cy - 9f)
                    else -> (cx + 9f) to (cy + 17f)
                }
            out +=
                dev.kuml.layout.Rect(
                    origin = dev.kuml.layout.Point(x = x0, y = base - 10f),
                    size = dev.kuml.layout.Size(width = w, height = 13f),
                )
        }
    }
    return out
}

/** Absolute pin-square centres of [def] at [bounds] with their side (wave C2b: keeps plain edges off pins). */
internal fun pinCenters(
    def: ActionDefinition,
    bounds: dev.kuml.layout.Rect,
): List<Pair<PinSide, dev.kuml.layout.Point>> {
    val out = ArrayList<Pair<PinSide, dev.kuml.layout.Point>>()
    for (side in PinSide.entries) {
        if (side == PinSide.Auto) continue
        val onSide = def.pins.filter { it.effectiveSide == side }
        if (onSide.isEmpty()) continue
        val pos = pinPositions(count = onSide.size, side = side, w = bounds.size.width, h = bounds.size.height)
        pos.forEach { out += side to dev.kuml.layout.Point(x = bounds.origin.x + it.first, y = bounds.origin.y + it.second) }
    }
    return out
}

internal const val PIN_LABEL_CHAR_PX: Float = 6.2f

/** Maximum characters of a pin label on an explicitly chosen side. */
internal const val PIN_LABEL_MAX_CHARS: Int = 12

/** Maximum characters of a signal / time-expression / control-node label inside the diagram. */
internal const val ACT_LABEL_MAX_CHARS: Int = 40

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
        name = "g",
        attrs = mapOf("id" to xmlEscapeAttr(element.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        tag(
            name = "circle",
            attrs =
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
        name = "g",
        attrs = mapOf("id" to xmlEscapeAttr(element.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        tag(
            name = "circle",
            attrs =
                mapOf(
                    "cx" to fmt(cx),
                    "cy" to fmt(cy),
                    "r" to fmt(outerR),
                    "class" to "kuml-class",
                    "fill" to "white",
                ),
        )
        tag(
            name = "circle",
            attrs =
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
        name = "g",
        attrs = mapOf("id" to xmlEscapeAttr(element.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        tag(
            name = "circle",
            attrs =
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
            name = "line",
            attrs =
                mapOf(
                    "x1" to fmt(cx - xArm),
                    "y1" to fmt(cy - xArm),
                    "x2" to fmt(cx + xArm),
                    "y2" to fmt(cy + xArm),
                    "class" to "kuml-class",
                ),
        )
        tag(
            name = "line",
            attrs =
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
        name = "g",
        attrs = mapOf("id" to xmlEscapeAttr(element.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        tag(
            name = "polygon",
            attrs =
                mapOf(
                    "points" to points,
                    "class" to "kuml-class",
                ),
        )
        if (element.showName) controlNodeName(name = element.name, x = w + 6f, y = cy + 4f, anchor = "start")
    }
}

/** Wave C2 — name of a decision / merge / fork / join next to its shape (max. [CONTROL_NAME_MAX_CHARS] characters). */
private fun SvgBuilder.controlNodeName(
    name: String,
    x: Float,
    y: Float,
    anchor: String,
) {
    if (name.isBlank()) return
    tag(
        name = "text",
        attrs = mapOf("class" to "kuml-body", "x" to fmt(x), "y" to fmt(y), "text-anchor" to anchor),
    ) { text(truncate(s = name, max = CONTROL_NAME_MAX_CHARS)) }
}

private const val CONTROL_NAME_MAX_CHARS = 40

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
        name = "g",
        attrs = mapOf("id" to xmlEscapeAttr(element.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        tag(
            name = "rect",
            attrs =
                mapOf(
                    "width" to fmt(w),
                    "height" to fmt(h),
                    "class" to "kuml-class",
                    "fill" to "currentColor",
                ),
        )
        // Wave C2: optional name left of the bar; edges leave and arrive at its top and bottom.
        if (element.showName) controlNodeName(name = element.name, x = -6f, y = h / 2f + 4f, anchor = "end")
    }
}

/** Wave C2 — rake symbol of a call-behavior action: stem, cross bar and three prongs, 8 px inset from the lower right. */
private fun SvgBuilder.renderRake(
    w: Float,
    h: Float,
) {
    val cx = w - 8f - 5f
    val y0 = h - 8f - 14f
    val yBar = y0 + 10f
    val strokes =
        listOf(
            listOf(cx, y0, cx, yBar),
            listOf(cx - 5f, yBar, cx + 5f, yBar),
            listOf(cx - 5f, yBar, cx - 5f, yBar + 4f),
            listOf(cx, yBar, cx, yBar + 4f),
            listOf(cx + 5f, yBar, cx + 5f, yBar + 4f),
        )
    for ((i, l) in strokes.withIndex()) {
        tag(
            name = "line",
            attrs =
                mapOf(
                    "x1" to fmt(l[0]),
                    "y1" to fmt(l[1]),
                    "x2" to fmt(l[2]),
                    "y2" to fmt(l[3]),
                    "class" to "kuml-class kuml-act-rake",
                    "data-part" to
                        if (i == 0) {
                            "stem"
                        } else if (i == 1) {
                            "bar"
                        } else {
                            "prong"
                        },
                ),
        )
    }
}

/**
 * Wave C2 — send-signal (pentagon, tip on the right, [ACT_SIGNAL_NOTCH_PX] deep) and accept-event
 * (rectangle with a notch on the left). The node name is the label; a signal that differs from the
 * name is shown as a second line.
 */
private fun renderSignalNode(
    element: ActionDefinition,
    layout: NodeLayout,
    builder: SvgBuilder,
    send: Boolean,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height
    val n = minOf(ACT_SIGNAL_NOTCH_PX, w / 3f)
    val points =
        if (send) {
            "0,0 ${fmt(w - n)},0 ${fmt(w)},${fmt(h / 2f)} ${fmt(w - n)},${fmt(h)} 0,${fmt(h)}"
        } else {
            "0,0 ${fmt(w)},0 ${fmt(w)},${fmt(h)} 0,${fmt(h)} ${fmt(n)},${fmt(h / 2f)}"
        }
    val textX = if (send) (w - n) / 2f else (w + n) / 2f
    val signal = element.signal?.takeIf { it.isNotBlank() && it != element.name }
    builder.tag(
        name = "g",
        attrs = mapOf("id" to xmlEscapeAttr(element.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        tag(name = "polygon", attrs = mapOf("points" to points, "class" to "kuml-class"))
        tag(
            name = "text",
            attrs =
                mapOf(
                    "class" to "kuml-title",
                    "x" to fmt(textX),
                    "y" to fmt(if (signal != null) h / 2f - 2f else h / 2f + 4f),
                    "text-anchor" to "middle",
                ),
        ) { text(truncate(s = element.name, max = ACT_LABEL_MAX_CHARS)) }
        if (signal != null) {
            tag(
                name = "text",
                attrs =
                    mapOf(
                        "class" to "kuml-body",
                        "x" to fmt(textX),
                        "y" to fmt(h / 2f + 14f),
                        "text-anchor" to "middle",
                    ),
            ) { text(truncate(s = signal, max = ACT_LABEL_MAX_CHARS)) }
        }
    }
}

/**
 * Wave C2 — accept-time-event: an hourglass of two triangles ([ACT_TIME_EVENT_SYMBOL_W] x
 * [ACT_TIME_EVENT_SYMBOL_H]) centred at the top of the node box, the time expression as 11 px
 * label 4 px below it.
 */
private fun renderTimeEventNode(
    element: ActionDefinition,
    layout: NodeLayout,
    builder: SvgBuilder,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val sw = ACT_TIME_EVENT_SYMBOL_W
    val sh = ACT_TIME_EVENT_SYMBOL_H
    val x0 = (w - sw) / 2f
    val upper = "${fmt(x0)},0 ${fmt(x0 + sw)},0 ${fmt(x0 + sw / 2f)},${fmt(sh / 2f)}"
    val lower = "${fmt(x0 + sw / 2f)},${fmt(sh / 2f)} ${fmt(x0)},${fmt(sh)} ${fmt(x0 + sw)},${fmt(sh)}"
    builder.tag(
        name = "g",
        attrs = mapOf("id" to xmlEscapeAttr(element.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        tag(name = "polygon", attrs = mapOf("points" to upper, "class" to "kuml-class", "data-part" to "hourglass-top"))
        tag(name = "polygon", attrs = mapOf("points" to lower, "class" to "kuml-class", "data-part" to "hourglass-bottom"))
        val label = element.timeExpression?.takeIf { it.isNotBlank() } ?: element.name
        tag(
            name = "text",
            attrs =
                mapOf(
                    "class" to "kuml-body",
                    "x" to fmt(w / 2f),
                    "y" to fmt(sh + 4f + 11f),
                    "text-anchor" to "middle",
                    "font-size" to "11",
                ),
        ) { text(truncate(s = label, max = ACT_LABEL_MAX_CHARS)) }
    }
}

internal const val ACT_SIGNAL_NOTCH_PX: Float = 14f
internal const val ACT_TIME_EVENT_SYMBOL_W: Float = 20f
internal const val ACT_TIME_EVENT_SYMBOL_H: Float = 28f

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
