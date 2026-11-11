package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.fmt3
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.bridge.ActTextWrap
import dev.kuml.layout.bridge.port.PortAttachment
import dev.kuml.layout.bridge.port.PortEdgeClamp
import dev.kuml.layout.bridge.port.toPortFace
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
        // Nacharbeit 69: long names wrap at about 24 characters, `\n` forces a break; one <text> per line.
        val nameLines = ActTextWrap.actionNameLines(element.name)
        val blockShift = (nameLines.size - 1) * ACT_NAME_LINE_PX / 2f
        val firstY = (if (hasAction) h / 2f - 2f else h / 2f + 4f) - blockShift
        val nameClass = if (element.isAbstract) "kuml-title kuml-title-abstract" else "kuml-title"
        for ((i, line) in nameLines.withIndex()) {
            val nameAttrs =
                buildMap<String, String> {
                    put("class", nameClass)
                    put("x", fmt(w / 2f))
                    put("y", fmt(firstY + i * ACT_NAME_LINE_PX))
                    put("text-anchor", "middle")
                    if (nameLines.size > 1) put("data-line", i.toString())
                    if (element.isAbstract) put("font-style", "italic")
                }
            tag(name = "text", attrs = nameAttrs) { text(line) }
        }

        if (hasAction) {
            val truncated = truncate(s = element.action!!, max = ACT_BODY_MAX_LEN)
            tag(
                name = "text",
                attrs =
                    mapOf(
                        "class" to "kuml-body",
                        "x" to fmt(w / 2f),
                        "y" to fmt(h / 2f + 14f + blockShift),
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
            // PAL 2C-b2b: the pin label is not drawn here; the label plan places it and the diagram renderer draws it.
        }
    }
}

/**
 * Local (node-relative) pin-square centres for [count] pins on [side] of a [w] x [h] box. Left
 * and right pins are spread along the height, top and bottom pins along the width; one pin sits in
 * the middle. Shared by the pin drawing and the pin-to-pin edge anchoring ([actionPinAttachment]).
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
 * The pin square named [pinName] (direction [direction]) of [def], laid out at [bounds], together with the face
 * an edge docks at; `null` when the pin does not exist. The rectangle is exactly the square drawn by
 * [renderActionPins] (centre from [pinPositions], side [PIN_SIZE]); the face is the pin's outward face
 * ([toPortFace], so [PinSide.Auto] resolves by direction). Edge ends are derived from it through
 * [PortEdgeClamp.borderPoint], the same geometry source as the IBD and PAR port ends.
 */
internal fun actionPinAttachment(
    def: ActionDefinition,
    bounds: dev.kuml.layout.Rect,
    pinName: String,
    direction: PinDirection,
): PortAttachment? {
    val pin = def.pins.firstOrNull { it.name == pinName && it.direction == direction } ?: return null
    val side = pin.effectiveSide
    val onSide = def.pins.filter { it.effectiveSide == side }
    val idx = onSide.indexOf(pin)
    val (cx, cy) = pinPositions(count = onSide.size, side = side, w = bounds.size.width, h = bounds.size.height)[idx]
    val rect =
        dev.kuml.layout.Rect(
            origin = dev.kuml.layout.Point(x = bounds.origin.x + cx - PIN_SIZE / 2f, y = bounds.origin.y + cy - PIN_SIZE / 2f),
            size = dev.kuml.layout.Size(width = PIN_SIZE, height = PIN_SIZE),
        )
    return PortAttachment(rect = rect, face = side.toPortFace(isOutput = direction == PinDirection.Output))
}

/** Id of a pin label in the label plan: action id, direction and pin name. */
internal fun actionPinLabelId(
    actionId: String,
    pin: ActionPin,
): String = "$actionId#${pin.direction.name}:${pin.name}"

private fun PinSide.toLabelSide(): dev.kuml.layout.bridge.label.LabelSide =
    when (this) {
        PinSide.Left -> dev.kuml.layout.bridge.label.LabelSide.WEST
        PinSide.Right -> dev.kuml.layout.bridge.label.LabelSide.EAST
        PinSide.Top -> dev.kuml.layout.bridge.label.LabelSide.NORTH
        else -> dev.kuml.layout.bridge.label.LabelSide.SOUTH
    }

/**
 * The pin labels of [def] laid out at [bounds] as label-plan requests (PAL wave 2C-b2b, absolute coordinates). A pin
 * listed in [dockedPins] has an edge that docks at its outer face: its label stays off that side.
 */
internal fun actionPinLabelSpecs(
    def: ActionDefinition,
    bounds: dev.kuml.layout.Rect,
    dockedPins: Set<String>,
): List<dev.kuml.layout.bridge.label.ActPinLabelSpec> {
    val out = ArrayList<dev.kuml.layout.bridge.label.ActPinLabelSpec>()
    for (side in PinSide.entries) {
        if (side == PinSide.Auto) continue
        val onSide = def.pins.filter { it.effectiveSide == side }
        if (onSide.isEmpty()) continue
        val pos = pinPositions(count = onSide.size, side = side, w = bounds.size.width, h = bounds.size.height)
        onSide.forEachIndexed { i, pin ->
            val cx = bounds.origin.x + pos[i].first
            val cy = bounds.origin.y + pos[i].second
            val id = actionPinLabelId(actionId = def.id, pin = pin)
            out +=
                dev.kuml.layout.bridge.label.ActPinLabelSpec(
                    id = id,
                    text = pin.name,
                    pinRect =
                        dev.kuml.layout.Rect(
                            origin = dev.kuml.layout.Point(x = cx - PIN_SIZE / 2f, y = cy - PIN_SIZE / 2f),
                            size = dev.kuml.layout.Size(width = PIN_SIZE, height = PIN_SIZE),
                        ),
                    side = side.toLabelSide(),
                    dockSide = if (id in dockedPins) side.toLabelSide() else null,
                    ownerId = def.id,
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
                    "class" to "kuml-pseudo-filled",
                ),
        )
    }
}

/**
 * Final node — Donut: äußerer Kreis (Stroke) + innerer
 * gefüllter Kreis (`kuml-pseudo-filled`, wie der Initial-Knoten; `.kuml-class` würde ihn hohl rendern). Innenradius ist Hälfte des Außenradius — etablierte
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
                    "class" to "kuml-pseudo-filled",
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

/** Nacharbeit 69 — the name of a control node as drawn (shortened to [CONTROL_NAME_MAX_CHARS]). */
internal fun controlNodeDisplayName(name: String): String = truncate(s = name, max = CONTROL_NAME_MAX_CHARS)

/** Nacharbeit 69 — draws a control-node name at an absolute, already placed position (the first free side). */
internal fun SvgBuilder.renderPlacedControlName(
    name: String,
    x: Float,
    y: Float,
    anchor: String,
) {
    if (name.isBlank()) return
    tag(
        name = "text",
        attrs = mapOf("class" to "kuml-body kuml-control-name", "x" to fmt(x), "y" to fmt(y), "text-anchor" to anchor),
    ) { text(controlNodeDisplayName(name)) }
}

/** Nacharbeit 69 — line height (px) of a wrapped action name; equals the size provider value. */
private const val ACT_NAME_LINE_PX = 16f

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
    // Wave 2F: names are wrapped at word boundaries (same function the size provider uses), never truncated.
    val signal = element.signal?.takeIf { it.isNotBlank() && it != element.name }
    val nameLines = ActTextWrap.wrapActName(text = element.name, maxWidthPx = 200f, maxLines = 4)
    val signalLines = if (signal != null) ActTextWrap.wrapActName(text = signal, maxWidthPx = 200f, maxLines = 4) else emptyList()
    val total = nameLines.size + signalLines.size
    val firstBaseline = h / 2f - total * ACT_SIGNAL_LINE / 2f + 11f
    builder.tag(
        name = "g",
        attrs = mapOf("id" to xmlEscapeAttr(element.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        tag(name = "polygon", attrs = mapOf("points" to points, "class" to "kuml-class"))
        tag(
            name = "text",
            attrs = mapOf("class" to "kuml-title", "text-anchor" to "middle"),
        ) {
            nameLines.forEachIndexed { i, line ->
                tag(name = "tspan", attrs = mapOf("x" to fmt(textX), "y" to fmt(firstBaseline + i * ACT_SIGNAL_LINE))) { text(line) }
            }
        }
        if (signalLines.isNotEmpty()) {
            tag(
                name = "text",
                attrs = mapOf("class" to "kuml-body", "text-anchor" to "middle"),
            ) {
                signalLines.forEachIndexed { i, line ->
                    tag(
                        name = "tspan",
                        attrs = mapOf("x" to fmt(textX), "y" to fmt(firstBaseline + (nameLines.size + i) * ACT_SIGNAL_LINE)),
                    ) { text(line) }
                }
            }
        }
    }
}

private const val ACT_SIGNAL_LINE: Float = 15f

/**
 * Wave C2 — accept-time-event: an hourglass of two triangles ([ACT_TIME_EVENT_SYMBOL_W] x
 * [ACT_TIME_EVENT_SYMBOL_H]) centred at the top of the node box, the name (if any) and the time
 * expression (once if identical) as 11 px label lines 4 px below it.
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
        val parts = ActTextWrap.timeEventLines(name = element.name, time = element.timeExpression)
        tag(
            name = "text",
            attrs = mapOf("class" to "kuml-body", "text-anchor" to "middle", "font-size" to "11"),
        ) {
            parts.all.forEachIndexed { i, line ->
                val part = if (i < parts.name.size) "name" else "time"
                tag(
                    name = "tspan",
                    attrs = mapOf("x" to fmt(w / 2f), "y" to fmt(sh + 4f + 11f + i * 13f), "data-part" to part),
                ) { text(line) }
            }
        }
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
