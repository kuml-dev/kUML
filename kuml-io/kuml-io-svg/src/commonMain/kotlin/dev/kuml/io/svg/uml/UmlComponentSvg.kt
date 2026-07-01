package dev.kuml.io.svg.uml

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.fmt2
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.io.svg.xmlEscapeContent
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Rect
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlConnector

/**
 * Rendert eine [UmlComponent] — Rechteck mit zwei kleinen Rechteck-Glyphen
 * rechts oben (UML-Komponenten-Symbol) und dem Klassennamen.
 *
 * In V1.1: Wenn [UmlComponent.appliedStereotypes] gesetzt sind, werden diese als
 * zusätzliche `«…»`-Zeile vor dem `«component»`-Keyword gerendert.
 *
 * In V3.x (Composite-Structure): [internalConnectors] enthält die [UmlConnector]s
 * deren BEIDE Endpunkte in den Subtree dieser Komponente fallen (Boundary-Ports
 * oder verschachtelte Part-Ports). Diese werden als `<line class="kuml-connector">`
 * innerhalb des lokalen Koordinatenrahmens gezeichnet — ohne ELK-Routing.
 *
 * @see drawComponentBox
 */
internal fun renderUmlComponent(
    element: UmlComponent,
    layout: NodeLayout,
    theme: KumlTheme,
    builder: SvgBuilder,
    internalConnectors: List<UmlConnector> = emptyList(),
) {
    drawComponentBox(
        element = element,
        x = layout.bounds.origin.x,
        y = layout.bounds.origin.y,
        w = layout.bounds.size.width,
        h = layout.bounds.size.height,
        theme = theme,
        builder = builder,
        internalConnectors = internalConnectors,
    )
}

/**
 * Zeichnet eine [UmlComponent]-Box an expliziten Bounds. Rekursiv: enthält die
 * Komponente verschachtelte Parts ([UmlComponent.nestedComponents], Composite-
 * Structure-Diagramm), werden diese als gestapelte Boxen im Inneren gerendert.
 *
 * Die Innen-Layout-Mathe ist mit dem Size-Provider
 * [dev.kuml.layout.bridge.UmlContentSizeProvider] gespiegelt (identische
 * `NESTED_*`-Konstanten + `chromeHeight`/`compositeHeight`), damit der vom
 * Layout reservierte Platz exakt zur gezeichneten Innenstruktur passt.
 * kuml-io-svg hängt bewusst nicht an kuml-layout-bridge → Konstanten doppelt.
 *
 * [internalConnectors] werden nach den verschachtelten Parts gezeichnet — als
 * `<line class="kuml-connector">` im lokalen Koordinatenrahmen dieses `<g>`-Blocks.
 * Rekursive Aufrufe für nested Parts erhalten eine leere Liste (interne Connectors
 * leben nur auf der Ebene des DIRECT parents).
 *
 * [depth] wird bei jedem rekursiven Abstieg dekrementiert. Erreicht er 0, wird ein
 * [IllegalStateException] geworfen — Schutz gegen zyklische `nestedComponents`-Graphen.
 */
private fun drawComponentBox(
    element: UmlComponent,
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    theme: KumlTheme,
    builder: SvgBuilder,
    internalConnectors: List<UmlConnector> = emptyList(),
    depth: Int = NESTED_MAX_DEPTH,
) {
    if (depth <= 0) {
        throw IllegalStateException(
            "UmlComponent nesting depth exceeds $NESTED_MAX_DEPTH — " +
                "possible cycle in nestedComponents (component id: ${element.id})",
        )
    }
    val cx = (w - 20f) / 2f

    builder.tag(
        "g",
        mapOf("id" to xmlEscapeAttr(element.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        tag("rect", mapOf("width" to fmt(w), "height" to fmt(h), "class" to "kuml-component"))

        // Component glyph: two small rects protruding at top-right
        val gx = w - 16f
        tag(
            "rect",
            mapOf(
                "x" to fmt(gx - 4f),
                "y" to "6",
                "width" to "12",
                "height" to "6",
                "class" to "kuml-component",
            ),
        )
        tag(
            "rect",
            mapOf(
                "x" to fmt(gx - 4f),
                "y" to "16",
                "width" to "12",
                "height" to "6",
                "class" to "kuml-component",
            ),
        )

        var cy = 20f

        // Applied stereotypes header (V1.1) — prepended before «component»
        val stereoAdv = StereotypeHelper.renderHeader(element, theme, this, cx, cy)
        cy += stereoAdv

        // Fixed «component» keyword always present
        tag(
            "text",
            mapOf(
                "class" to "kuml-stereotype",
                "x" to fmt(cx),
                "y" to fmt(cy),
                "text-anchor" to "middle",
            ),
        ) { text("«component»") }
        cy += 15f

        tag(
            "text",
            mapOf(
                "class" to "kuml-title",
                "x" to fmt(cx),
                "y" to fmt(cy),
                "text-anchor" to "middle",
            ),
        ) { text(element.name) }

        // V1.1.3 — feature compartments (attributes/operations) when present.
        // Backward-compat: feature-free components render exactly as before
        // (no extra dividers, no extra lines).
        val hasFeatures = element.attributes.isNotEmpty() || element.operations.isNotEmpty()
        if (hasFeatures) {
            cy += 6f

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
            cy += 12f

            for (attr in element.attributes) {
                val stereoPrefix = StereotypeHelper.featureStereotypeTspan(attr, theme)
                tag(
                    "text",
                    mapOf("class" to "kuml-body", "x" to "8", "y" to fmt(cy)),
                ) { rawXml(stereoPrefix + xmlEscapeContent(attr.format())) }
                cy += 13f
            }

            if (element.attributes.isNotEmpty() && element.operations.isNotEmpty()) {
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
                cy += 12f
            }

            for (op in element.operations) {
                val stereoPrefix = StereotypeHelper.featureStereotypeTspan(op, theme)
                tag(
                    "text",
                    mapOf("class" to "kuml-body", "x" to "8", "y" to fmt(cy)),
                ) { rawXml(stereoPrefix + xmlEscapeContent(op.format(theme))) }
                cy += 13f
            }
        }

        // V2.0.44 — Ports on left/right borders. Each port is a 12×12 black-filled
        // square half-overlapping the border (UML 2.x convention) with its name as
        // a label INSIDE the box, next to the square. Ports are assigned to sides
        // alternately (even index → left, odd index → right) so up to two columns
        // of ports can co-exist without overlap. Vertical placement is even-spaced
        // per side: position i / (count + 1) of the box height. Labels INSIDE
        // because labels-outside would be clipped by the canvas — ELK does not
        // know about ports yet, so we cannot reserve outside whitespace. The
        // [UmlContentSizeProvider.componentSize] computation reserves enough
        // horizontal width (left + right port-label widths) so the inside labels
        // never overlap the centred title.
        // When internal connectors are present, port squares are drawn here and
        // labels are deferred to AFTER the connectors so that labels sit on top
        // in SVG z-order. Without internal connectors the combined renderPorts is fine.
        val hasInternalConnectors = element.nestedComponents.isNotEmpty() && internalConnectors.isNotEmpty()
        if (hasInternalConnectors) {
            renderPortSquares(element, w, h)
        } else {
            renderPorts(element, w, h)
        }

        // V3.x — Composite-Structure: verschachtelte Parts als Innenstruktur.
        // Parts werden vertikal gestapelt und füllen die Innenbreite uniform
        // (w − 2·SIDE_PAD). Dadurch braucht der Renderer KEINE eigene
        // Zeichenbreiten-Schätzung — die Breite kommt aus der vom Size-Provider
        // gelieferten Parent-Box. Die Höhen kommen aus `compositeHeight` (rein
        // zeilenbasiert, identisch zum Size-Provider). Die rekursiven
        // drawComponentBox-Aufrufe laufen INNERHALB dieses <g>-Blocks, ihre
        // (x,y) sind also relativ zum Parent-Ursprung.
        if (element.nestedComponents.isNotEmpty()) {
            // Hat der Parent eigene Boundary-Ports, rücken die Parts links/rechts
            // ein, damit die Port-Quadrate + Inside-Labels in einem freien
            // Rand-Streifen sitzen statt von den Part-Boxen verdeckt zu werden.
            val sideInset = NESTED_SIDE_PAD + if (element.ports.isNotEmpty()) NESTED_PORT_CLEARANCE else 0f
            val interiorW = (w - sideInset * 2f).coerceAtLeast(NESTED_MIN_INTERIOR_W)
            var py = chromeHeight(element) + NESTED_TOP_GAP

            // Build a local id→Rect map (in parent-local coords) used both for
            // drawing the parts and for resolving connector anchor points.
            // The outer box itself is keyed by element.id at (0,0,w,h).
            val partRects = mutableMapOf<String, Rect>()
            partRects[element.id] =
                Rect(origin = dev.kuml.layout.Point(0f, 0f), size = dev.kuml.layout.Size(w, h))

            for (part in element.nestedComponents) {
                val ph = compositeHeight(part, depth - 1)
                partRects[part.id] =
                    Rect(
                        origin = dev.kuml.layout.Point(sideInset, py),
                        size = dev.kuml.layout.Size(interiorW, ph),
                    )
                drawComponentBox(part, sideInset, py, interiorW, ph, theme, this, depth = depth - 1)
                py += ph + NESTED_PART_GAP
            }

            // Draw internal connectors AFTER parts so lines sit on top of part fills.
            if (internalConnectors.isNotEmpty()) {
                // Build component-lookup and rect maps for the full subtree so
                // resolvePortCenter can find port lists and bounding boxes for any
                // endpoint — including grandchild components (2+-level nesting).
                // collectSubtreeComponents walks the tree recursively and accumulates
                // each descendant's bounding rect in the top-level parent-local frame
                // by adding the ancestor offset passed in via (offsetX, offsetY).
                // [visited] is a cycle-detection set: if a component id is encountered
                // a second time we skip it instead of recursing infinitely.
                val componentById = mutableMapOf<String, UmlComponent>()
                componentById[element.id] = element
                val visited = mutableSetOf(element.id)

                fun collectSubtreeComponents(
                    comp: UmlComponent,
                    offsetX: Float,
                    offsetY: Float,
                    depth: Int,
                ) {
                    val compSideInset =
                        NESTED_SIDE_PAD + if (comp.ports.isNotEmpty()) NESTED_PORT_CLEARANCE else 0f
                    val compInteriorW =
                        ((partRects[comp.id]?.size?.width ?: 0f) - compSideInset * 2f)
                            .coerceAtLeast(NESTED_MIN_INTERIOR_W)
                    var childPy = chromeHeight(comp) + NESTED_TOP_GAP
                    for (child in comp.nestedComponents) {
                        if (!visited.add(child.id)) continue // cycle guard
                        val childH = compositeHeight(child, depth - 1)
                        val childAbsX = offsetX + compSideInset
                        val childAbsY = offsetY + childPy
                        partRects[child.id] =
                            Rect(
                                origin = dev.kuml.layout.Point(childAbsX, childAbsY),
                                size = dev.kuml.layout.Size(compInteriorW, childH),
                            )
                        componentById[child.id] = child
                        if (child.nestedComponents.isNotEmpty()) {
                            collectSubtreeComponents(child, childAbsX, childAbsY, depth - 1)
                        }
                        childPy += childH + NESTED_PART_GAP
                    }
                }

                for (part in element.nestedComponents) {
                    componentById[part.id] = part
                    val partRect = partRects[part.id]
                    if (part.nestedComponents.isNotEmpty() && partRect != null) {
                        collectSubtreeComponents(part, partRect.origin.x, partRect.origin.y, depth)
                    }
                }

                drawInternalConnectors(internalConnectors, partRects, componentById, this)

                // Port labels drawn LAST so they appear on top of connector polylines.
                renderPortLabels(element, w, h)
            }
        }
    }
}

// ── Composite-Structure Innen-Layout (gespiegelt zu UmlContentSizeProvider) ──
//
// Diese Konstanten und Funktionen MÜSSEN mit den gleichnamigen Werten in
// [dev.kuml.layout.bridge.UmlContentSizeProvider] übereinstimmen. kuml-io-svg
// hängt bewusst nicht an kuml-layout-bridge, daher Spiegelung statt Import
// (Hauskonvention, vgl. C4DescriptionWrap ↔ C4ContentSizeProvider).

private const val NESTED_TOP_GAP = 12f
private const val NESTED_PART_GAP = 12f
private const val NESTED_SIDE_PAD = 14f
private const val NESTED_BOTTOM_PAD = 14f
private const val NESTED_PART_MIN_H = 48f
private const val NESTED_MIN_INTERIOR_W = 40f
private const val NESTED_PORT_CLEARANCE = 30f

/**
 * Maximale Schachtelungstiefe für `compositeHeight`, `drawComponentBox` und
 * `collectSubtreeComponents`. Schützt gegen zyklische `nestedComponents`-Graphen
 * (z.B. nach XMI-Import: A enthält B, B enthält A) und pathologisch tiefe,
 * nicht-zyklische Hierarchien, die einen StackOverflow auslösen würden.
 * 64 liegt weit über jedem realistischen UML-Composite-Structure-Modell.
 * Muss mit [dev.kuml.layout.bridge.UmlContentSizeProvider.MAX_NESTING_DEPTH]
 * übereinstimmen.
 */
private const val NESTED_MAX_DEPTH = 64

/**
 * Maximum number of internal connectors rendered per component. Guards against
 * resource exhaustion (DoS-adjacent) when adversarial or auto-generated models
 * contain hundreds or thousands of [UmlConnector]s within a single component
 * subtree. Connectors beyond this limit are silently dropped with a logged warning.
 */
private const val MAX_INTERNAL_CONNECTORS = 500

private const val NESTED_CHROME_START = 20f
private const val NESTED_STEREO_H = 18f
private const val NESTED_KEYWORD_H = 15f
private const val NESTED_NAME_H = 16f
private const val NESTED_FEATURE_TOP_GAP = 6f
private const val NESTED_FEATURE_DIVIDER_GAP = 12f
private const val NESTED_FEATURE_LINE_H = 13f

/**
 * Y-Position direkt unter der "Chrome" (Stereotyp + «component» + Titel +
 * Feature-Compartments), ab der die Innenstruktur beginnt. Rein zeilenbasiert —
 * spiegelt `UmlContentSizeProvider.chromeHeight`.
 */
private fun chromeHeight(c: UmlComponent): Float {
    val hasStereoHeader = c.appliedStereotypes.isNotEmpty() || c.stereotypes.any { it.isNotBlank() }
    var cy = NESTED_CHROME_START
    if (hasStereoHeader) cy += NESTED_STEREO_H
    cy += NESTED_KEYWORD_H
    cy += NESTED_NAME_H
    val hasFeatures = c.attributes.isNotEmpty() || c.operations.isNotEmpty()
    if (hasFeatures) {
        cy += NESTED_FEATURE_TOP_GAP + NESTED_FEATURE_DIVIDER_GAP
        cy += c.attributes.size * NESTED_FEATURE_LINE_H
        if (c.attributes.isNotEmpty() && c.operations.isNotEmpty()) {
            cy += NESTED_FEATURE_DIVIDER_GAP
        }
        cy += c.operations.size * NESTED_FEATURE_LINE_H
    }
    return cy
}

/**
 * Gesamthöhe einer (ggf. verschachtelten) Part-Box. Rekursiv. Spiegelt
 * `UmlContentSizeProvider.compositeHeight`.
 *
 * [depth] wird bei jedem rekursiven Abstieg dekrementiert. Erreicht er 0,
 * wird ein [IllegalStateException] geworfen — Schutz gegen zyklische oder
 * pathologisch tiefe `nestedComponents`-Graphen (z.B. nach XMI-Import).
 */
private fun compositeHeight(
    c: UmlComponent,
    depth: Int = NESTED_MAX_DEPTH,
): Float {
    if (depth <= 0) {
        throw IllegalStateException(
            "UmlComponent nesting depth exceeds $NESTED_MAX_DEPTH — " +
                "possible cycle in nestedComponents (component id: ${c.id})",
        )
    }
    val chrome = chromeHeight(c)
    if (c.nestedComponents.isEmpty()) {
        return maxOf(chrome + NESTED_BOTTOM_PAD, NESTED_PART_MIN_H)
    }
    val parts = c.nestedComponents
    val stack =
        parts.sumOf { compositeHeight(it, depth - 1).toDouble() }.toFloat() +
            (parts.size - 1) * NESTED_PART_GAP
    return chrome + NESTED_TOP_GAP + stack + NESTED_BOTTOM_PAD
}

/**
 * Renders port squares AND labels in one pass.
 * Use for inner parts where no internal connectors can overlap the labels.
 */
private fun SvgBuilder.renderPorts(
    element: UmlComponent,
    w: Float,
    h: Float,
) {
    renderPortSquares(element, w, h)
    renderPortLabels(element, w, h)
}

/**
 * Renders only the filled port squares (black rects on the component border).
 * Called first so connectors are drawn on top of squares but below labels.
 */
private fun SvgBuilder.renderPortSquares(
    element: UmlComponent,
    w: Float,
    h: Float,
) {
    if (element.ports.isEmpty()) return

    val portSize = 12f
    val leftPorts = element.ports.filterIndexed { idx, _ -> idx % 2 == 0 }
    val rightPorts = element.ports.filterIndexed { idx, _ -> idx % 2 == 1 }

    leftPorts.forEachIndexed { i, _ ->
        val py = h * (i + 1) / (leftPorts.size + 1) - portSize / 2f
        tag(
            "rect",
            mapOf(
                "x" to fmt(-portSize / 2f),
                "y" to fmt(py),
                "width" to fmt(portSize),
                "height" to fmt(portSize),
                "class" to "kuml-port",
            ),
        )
    }

    rightPorts.forEachIndexed { i, _ ->
        val py = h * (i + 1) / (rightPorts.size + 1) - portSize / 2f
        tag(
            "rect",
            mapOf(
                "x" to fmt(w - portSize / 2f),
                "y" to fmt(py),
                "width" to fmt(portSize),
                "height" to fmt(portSize),
                "class" to "kuml-port",
            ),
        )
    }
}

/**
 * Renders only the port name labels. Called AFTER internal connectors so that
 * labels always appear on top of connector polylines in the SVG z-order.
 *
 * Each label is emitted as two sibling `<text>` elements:
 *  1. `kuml-port-label-halo` — white stroke, no fill → acts as a background
 *     that masks the connector line behind the text without relying on
 *     `paint-order` (not supported by Batik / PNG export).
 *  2. `kuml-port-label` — black fill, no stroke → the visible text.
 */
private fun SvgBuilder.renderPortLabels(
    element: UmlComponent,
    w: Float,
    h: Float,
) {
    if (element.ports.isEmpty()) return

    val portSize = 12f
    val labelGap = 4f
    val leftPorts = element.ports.filterIndexed { idx, _ -> idx % 2 == 0 }
    val rightPorts = element.ports.filterIndexed { idx, _ -> idx % 2 == 1 }

    leftPorts.forEachIndexed { i, port ->
        val py = h * (i + 1) / (leftPorts.size + 1) - portSize / 2f
        val baseAttrs =
            mapOf(
                "x" to fmt(portSize / 2f + labelGap),
                "y" to fmt(py + portSize / 2f + 3f),
                "text-anchor" to "start",
            )
        tag("text", baseAttrs + mapOf("class" to "kuml-port-label-halo")) { text(port.name) }
        tag("text", baseAttrs + mapOf("class" to "kuml-port-label")) { text(port.name) }
    }

    rightPorts.forEachIndexed { i, port ->
        val py = h * (i + 1) / (rightPorts.size + 1) - portSize / 2f
        val baseAttrs =
            mapOf(
                "x" to fmt(w - portSize / 2f - labelGap),
                "y" to fmt(py + portSize / 2f + 3f),
                "text-anchor" to "end",
            )
        tag("text", baseAttrs + mapOf("class" to "kuml-port-label-halo")) { text(port.name) }
        tag("text", baseAttrs + mapOf("class" to "kuml-port-label")) { text(port.name) }
    }
}

// ── Internal Connector Rendering (Composite-Structure) ────────────────────────
//
// Internal connectors are drawn as `<polyline class="kuml-connector">` inside the
// parent component's `<g transform="translate(x,y)">` — all coordinates are
// local to the parent box origin (0,0). No ELK routing is used; anchor points
// are computed with the SAME alternating-side rule as [renderPorts].
//
// Routing strategy (V0.16.2):
//   - Same side (LEFT↔LEFT or RIGHT↔RIGHT) → U-shape: shared corridor
//     to the outside of both components.
//   - Opposite sides (RIGHT↔LEFT or LEFT↔RIGHT) → Gap routing: the
//     connector leaves each port as a short horizontal stub, then routes
//     the vertical segment through the GAP between the two component boxes.
//     This avoids the diagonal line cutting through the component rectangles
//     that occurred with the previous `<line>` approach.

/** Stub length (px) added at each port to leave the component edge orthogonally. */
private const val INTERNAL_STUB_PX = 16f

/** Full port information needed for orthogonal routing of internal connectors. */
private data class PortAnchor(
    val cx: Float,
    val cy: Float,
    val isLeft: Boolean,
    val compId: String,
)

/**
 * Draws all [connectors] as SVG polylines within the current parent-local
 * coordinate frame, using gap-aware orthogonal routing.
 *
 * [partRects] maps component-id → bounding [Rect] in parent-local coords.
 * [componentById] maps component-id → [UmlComponent] for port-list lookup.
 *
 * Called from [drawComponentBox] after the nested-part sub-boxes are rendered,
 * so polylines appear on top of part box fills while port squares (drawn
 * recursively by each part's own `renderPorts`) remain visible at the endpoints.
 */
private fun drawInternalConnectors(
    connectors: List<UmlConnector>,
    partRects: Map<String, Rect>,
    componentById: Map<String, UmlComponent>,
    builder: SvgBuilder,
) {
    val cappedConnectors =
        if (connectors.size > MAX_INTERNAL_CONNECTORS) {
            println(
                "[kUML] WARNING: component subtree has ${connectors.size} internal connectors; " +
                    "rendering is capped at $MAX_INTERNAL_CONNECTORS to prevent resource exhaustion.",
            )
            connectors.subList(0, MAX_INTERNAL_CONNECTORS)
        } else {
            connectors
        }
    val sep = "::"
    for (connector in cappedConnectors) {
        val pa1 = resolvePortAnchor(connector.end1Id, sep, partRects, componentById) ?: continue
        val pa2 = resolvePortAnchor(connector.end2Id, sep, partRects, componentById) ?: continue

        val points = buildInternalRoute(pa1, pa2, partRects)
        val pointsAttr = points.joinToString(" ") { (x, y) -> "${fmt(x)},${fmt(y)}" }
        builder.tag(
            "polyline",
            mapOf(
                "points" to pointsAttr,
                "class" to "kuml-connector",
                "fill" to "none",
            ),
        )

        // Optional name label at midpoint of the full route.
        val connectorName = connector.name
        if (connectorName != null) {
            val mx = (pa1.cx + pa2.cx) / 2f
            val my = (pa1.cy + pa2.cy) / 2f - 3f
            builder.tag(
                "text",
                mapOf(
                    "class" to "kuml-connector-label",
                    "x" to fmt(mx),
                    "y" to fmt(my),
                    "text-anchor" to "middle",
                ),
            ) { text(connectorName) }
        }
    }
}

/**
 * Builds an orthogonal waypoint list between two [PortAnchor]s.
 *
 * **Same side** (both LEFT or both RIGHT): U-shape — stubs leave ports
 * perpendicularly, then share a single vertical corridor outside both boxes.
 *
 * **Opposite sides**: Gap routing — each stub leaves its port perpendicularly,
 * then the connector's vertical segment runs through the gap between the two
 * component boxes (i.e., above or below both parts). This prevents the route
 * from cutting diagonally through either component rectangle.
 */
private fun buildInternalRoute(
    p1: PortAnchor,
    p2: PortAnchor,
    partRects: Map<String, Rect>,
): List<Pair<Float, Float>> {
    // Determine whether one endpoint sits on a box that geometrically CONTAINS
    // the other endpoint's box — i.e. a delegation connector from the composite's
    // own boundary port to an inner part's port. The containing box's port lies
    // ON the composite boundary, so its stub must leave INWARD (into the
    // composite); routing it outward like an inner-part port would push the line
    // — and any shared corridor derived from it — past the composite edge.
    val r1 = partRects[p1.compId]
    val r2 = partRects[p2.compId]
    val distinctBoxes = p1.compId != p2.compId && r1 != null && r2 != null
    val p1IsOuter = distinctBoxes && r1 != null && r2 != null && rectContains(r1, r2)
    val p2IsOuter = distinctBoxes && r1 != null && r2 != null && rectContains(r2, r1)

    // Stub x: inner-part ports leave outward (away from their box body); an outer
    // boundary port leaves inward (its open face points into the composite).
    fun stubX(
        p: PortAnchor,
        isOuter: Boolean,
    ): Float {
        val pointsLeft = if (isOuter) !p.isLeft else p.isLeft
        return if (pointsLeft) p.cx - INTERNAL_STUB_PX else p.cx + INTERNAL_STUB_PX
    }
    val s1x = stubX(p1, p1IsOuter)
    val s2x = stubX(p2, p2IsOuter)

    return if (p1.isLeft == p2.isLeft && p1IsOuter != p2IsOuter) {
        // Delegation on the same physical side: one boundary port + one inner
        // part port. Their stubs point TOWARD each other, so a shared outside
        // corridor would either backtrack or leave the composite. Route a simple
        // Z through the margin strip between the boundary and the part edge.
        val corridorX = (p1.cx + p2.cx) / 2f
        listOf(
            p1.cx to p1.cy,
            corridorX to p1.cy,
            corridorX to p2.cy,
            p2.cx to p2.cy,
        )
    } else if (p1.isLeft == p2.isLeft) {
        // U-shape: corridor on the outside of the shared side.
        val cornerX =
            if (p1.isLeft) minOf(s1x, s2x) else maxOf(s1x, s2x)
        listOf(
            p1.cx to p1.cy,
            s1x to p1.cy,
            cornerX to p1.cy,
            cornerX to p2.cy,
            s2x to p2.cy,
            p2.cx to p2.cy,
        )
    } else {
        // Opposite sides: route the bridge through the gap between the
        // two component boxes, not across their horizontal midpoint.
        val rect1 = partRects[p1.compId]
        val rect2 = partRects[p2.compId]
        val gapY =
            if (rect1 != null && rect2 != null) {
                val top1 = rect1.origin.y
                val bottom1 = rect1.origin.y + rect1.size.height
                val top2 = rect2.origin.y
                val bottom2 = rect2.origin.y + rect2.size.height
                when {
                    top2 > bottom1 -> (bottom1 + top2) / 2f // rect2 is below rect1
                    top1 > bottom2 -> (bottom2 + top1) / 2f // rect1 is below rect2
                    else -> (p1.cy + p2.cy) / 2f // overlapping — midY fallback
                }
            } else {
                (p1.cy + p2.cy) / 2f
            }
        listOf(
            p1.cx to p1.cy,
            s1x to p1.cy,
            s1x to gapY,
            s2x to gapY,
            s2x to p2.cy,
            p2.cx to p2.cy,
        )
    }
}

/**
 * Resolves an endpoint id of the form `"<componentId>::<portName>"` to its
 * [PortAnchor] — port-center (cx, cy), which side it sits on, and the
 * owning component id — all in parent-local coordinates.
 *
 * Uses the SAME alternating-side rule as [renderPorts]:
 *   - Even port index (0,2,4,…) → left side, center x = box.origin.x
 *   - Odd port index (1,3,5,…) → right side, center x = box.origin.x + box.size.width
 *   - y = box.origin.y + box.size.height × (indexOnSide + 1) / (sideCount + 1)
 *
 * Returns `null` if the endpoint id cannot be split, the component is unknown,
 * or the port is not found.
 */
private fun resolvePortAnchor(
    endId: String,
    sep: String,
    partRects: Map<String, Rect>,
    componentById: Map<String, UmlComponent>,
): PortAnchor? {
    val sepIdx = endId.lastIndexOf(sep)
    if (sepIdx <= 0) return null
    val componentId = endId.substring(0, sepIdx)
    val portName = endId.substring(sepIdx + sep.length)
    if (portName.isEmpty()) return null

    val box = partRects[componentId] ?: return null
    val component = componentById[componentId] ?: return null
    val ports = component.ports
    val portIndex = ports.indexOfFirst { it.name == portName }
    if (portIndex < 0) return null

    val isLeft = portIndex % 2 == 0
    val sideList = ports.filterIndexed { idx, _ -> idx % 2 == (if (isLeft) 0 else 1) }
    val iOnSide = sideList.indexOfFirst { it.name == portName }
    if (iOnSide < 0) return null

    val bx = box.origin.x
    val by = box.origin.y
    val bw = box.size.width
    val bh = box.size.height

    // y center: same formula as renderPorts
    // (py = bh*(iOnSide+1)/(sideList.size+1) - portSize/2 → center = py + portSize/2)
    val cyLocal = bh * (iOnSide + 1) / (sideList.size + 1)
    // x center: left-side port sits at x=-portSize/2 → center x=bx;
    //           right-side port sits at x=bw-portSize/2 → center x=bx+bw.
    val cxLocal = if (isLeft) bx else bx + bw

    return PortAnchor(cx = cxLocal, cy = by + cyLocal, isLeft = isLeft, compId = componentId)
}

/**
 * True if [outer] fully encloses [inner] (inclusive bounds). Used to detect a
 * composite-boundary port whose box contains the inner part it delegates to, so
 * its connector stub can be routed inward instead of across the composite edge.
 */
private fun rectContains(
    outer: Rect,
    inner: Rect,
): Boolean =
    outer.origin.x <= inner.origin.x &&
        outer.origin.y <= inner.origin.y &&
        outer.origin.x + outer.size.width >= inner.origin.x + inner.size.width &&
        outer.origin.y + outer.size.height >= inner.origin.y + inner.size.height

private fun fmt(v: Float): String = fmt2(v)
