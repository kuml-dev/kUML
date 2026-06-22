package dev.kuml.io.svg.uml

import dev.kuml.io.svg.SvgBuilder
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
        renderPorts(element, w, h)

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

private fun SvgBuilder.renderPorts(
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
        tag(
            "text",
            mapOf(
                "class" to "kuml-port-label",
                "x" to fmt(portSize / 2f + labelGap),
                "y" to fmt(py + portSize / 2f + 3f),
                "text-anchor" to "start",
            ),
        ) { text(port.name) }
    }

    rightPorts.forEachIndexed { i, port ->
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
        tag(
            "text",
            mapOf(
                "class" to "kuml-port-label",
                "x" to fmt(w - portSize / 2f - labelGap),
                "y" to fmt(py + portSize / 2f + 3f),
                "text-anchor" to "end",
            ),
        ) { text(port.name) }
    }
}

// ── Internal Connector Rendering (Composite-Structure) ────────────────────────
//
// Internal connectors are drawn as `<line class="kuml-connector">` inside the
// parent component's `<g transform="translate(x,y)">` — all coordinates are
// local to the parent box origin (0,0). No ELK routing is used; anchor points
// are computed with the SAME alternating-side rule as [renderPorts], so line
// endpoints touch the port-square centers exactly.

/**
 * Draws all [connectors] as SVG lines within the current parent-local coordinate
 * frame.  [partRects] maps component-id → bounding [Rect] in parent-local coords.
 * [componentById] maps component-id → [UmlComponent] for port-list lookup.
 *
 * Called from [drawComponentBox] after the nested-part sub-boxes are rendered,
 * so lines appear on top of part box fills while port squares (drawn recursively
 * by each part's own `renderPorts`) remain visible at the line endpoints.
 */
private fun drawInternalConnectors(
    connectors: List<UmlConnector>,
    partRects: Map<String, Rect>,
    componentById: Map<String, UmlComponent>,
    builder: SvgBuilder,
) {
    val cappedConnectors =
        if (connectors.size > MAX_INTERNAL_CONNECTORS) {
            System.err.println(
                "[kUML] WARNING: component subtree has ${connectors.size} internal connectors; " +
                    "rendering is capped at $MAX_INTERNAL_CONNECTORS to prevent resource exhaustion.",
            )
            connectors.subList(0, MAX_INTERNAL_CONNECTORS)
        } else {
            connectors
        }
    val sep = "::"
    for (connector in cappedConnectors) {
        val (c1x, c1y) = resolvePortCenter(connector.end1Id, sep, partRects, componentById) ?: continue
        val (c2x, c2y) = resolvePortCenter(connector.end2Id, sep, partRects, componentById) ?: continue

        builder.tag(
            "line",
            mapOf(
                "x1" to fmt(c1x),
                "y1" to fmt(c1y),
                "x2" to fmt(c2x),
                "y2" to fmt(c2y),
                "class" to "kuml-connector",
            ),
        )

        // Optional name label at midpoint.
        val connectorName = connector.name
        if (connectorName != null) {
            val mx = (c1x + c2x) / 2f
            val my = (c1y + c2y) / 2f - 3f
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
 * Resolves an endpoint id of the form `"<componentId>::<portName>"` to its
 * port-center (cx, cy) in parent-local coordinates.
 *
 * Uses the SAME alternating-side rule as [renderPorts]:
 *   - Even port index (0,2,4,…) → left side, center x = box.origin.x
 *   - Odd port index (1,3,5,…) → right side, center x = box.origin.x + box.size.width
 *   - y = box.origin.y + box.size.height × (indexOnSide + 1) / (sideCount + 1)
 *
 * Returns `null` if the endpoint id cannot be split, the component is unknown,
 * or the port is not found.
 */
private fun resolvePortCenter(
    endId: String,
    sep: String,
    partRects: Map<String, Rect>,
    componentById: Map<String, UmlComponent>,
): Pair<Float, Float>? {
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

    // y center within the box: same formula as renderPorts
    // (py = bh*(iOnSide+1)/(sideList.size+1) - portSize/2 → center = py + portSize/2)
    val cyLocal = bh * (iOnSide + 1) / (sideList.size + 1)
    // x center: left-side port square sits at x=-portSize/2 → center x=bx;
    //           right-side port square sits at x=bw-portSize/2 → center x=bx+bw.
    val cxLocal = if (isLeft) bx else bx + bw

    return Pair(cxLocal, by + cyLocal)
}

private fun fmt(v: Float): String {
    val i = v.toInt()
    return if (v == i.toFloat()) "$i" else "%.2f".format(java.util.Locale.ROOT, v)
}
