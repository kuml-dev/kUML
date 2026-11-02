package dev.kuml.io.svg.blueprint

import dev.kuml.blueprint.model.BlueprintDiagram
import dev.kuml.blueprint.model.BlueprintDiagramFull
import dev.kuml.blueprint.model.BlueprintGridConstants
import dev.kuml.blueprint.model.BlueprintLayer
import dev.kuml.blueprint.model.BlueprintLine
import dev.kuml.blueprint.model.BlueprintModel
import dev.kuml.blueprint.model.JourneyDiagram
import dev.kuml.io.svg.NOTE_ANCHOR_DOT_RADIUS
import dev.kuml.io.svg.NoteIcons
import dev.kuml.io.svg.NoteTones
import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.SvgDocument
import dev.kuml.io.svg.blueprint.edge.renderConnection
import dev.kuml.io.svg.renderNote
import dev.kuml.io.svg.xmlEscapeContent
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.renderer.theme.core.PlainTheme

/**
 * Top-level Journey-Map / Service-Blueprint SVG renderer.
 *
 * V3.1.23 introduced the Journey-Map view (Customer layer + emotion curve).
 * V3.1.24 extends it to the full Service Blueprint:
 *
 *  - distinct per-layer band styling (each Shostack layer has its own tint),
 *  - the three separator lines (Interaction/Visibility/Internal Interaction),
 *  - actor-role icons on backstage/support step cards,
 *  - per-layer accent strokes on the step cards.
 *
 * The renderer owns its geometry via [BlueprintGeometry] (grid, not ELK).
 */
internal fun renderBlueprintJourney(
    model: BlueprintModel,
    diagram: BlueprintDiagram,
    theme: KumlTheme = PlainTheme(),
): String {
    val visibleLayers =
        when (diagram) {
            is JourneyDiagram -> diagram.visibleLayers
            is BlueprintDiagramFull -> diagram.visibleLayers
        }
    val showEmotion =
        when (diagram) {
            is JourneyDiagram -> diagram.showEmotionCurve
            is BlueprintDiagramFull -> diagram.showEmotionCurve
        }
    // Journey view hides empty layers; full view keeps requested layers visible
    // (even when empty) so the blueprint structure stays complete.
    val effectiveLayers =
        when (diagram) {
            is JourneyDiagram -> visibleLayers.filter { it in model.activeLayers() }.toSet()
            is BlueprintDiagramFull -> visibleLayers
        }.ifEmpty { setOf(BlueprintLayer.CUSTOMER_ACTIONS) }

    // Which separator lines to draw — only the full view carries them.
    val showLines: Set<BlueprintLine> =
        when (diagram) {
            is BlueprintDiagramFull -> diagram.showLines
            is JourneyDiagram -> emptySet()
        }

    val labels = diagram.labels
    val geo =
        BlueprintGeometry(
            model = model,
            visibleLayers = effectiveLayers,
            showEmotionCurve = showEmotion,
            captionWidth = BlueprintGridConstants.captionWidth(labels = labels, lines = showLines),
        )
    // Touchpoint id -> legend badge number, so each touchpoint's icon in the
    // grid (below) can carry the same number the legend uses for its name.
    val badgeByTouchpointId = geo.legendEntries.associate { (badge, tp) -> tp.id to badge }
    val b = SvgBuilder(pretty = false)

    b.tag(
        name = "svg",
        attrs =
            mapOf(
                "xmlns" to "http://www.w3.org/2000/svg",
                "width" to f(geo.totalWidth),
                "height" to f(geo.totalHeight),
                "viewBox" to "0 0 ${f(geo.totalWidth)} ${f(geo.totalHeight)}",
            ),
    ) {
        // Arrowhead marker — emitted once per SVG in the root <defs> block.
        rawXml(
            """<defs><marker id="bp-arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto">""" +
                """<path d="M0,0 L8,4 L0,8 z" fill="#555"/></marker></defs>""",
        )
        // Standard kUML CSS (kuml-title, kuml-body, etc.) so that text renders
        // correctly in standalone SVG/PNG — same style block used by all other renderers.
        SvgDocument.buildDefs(b = b, theme = theme)
        // Notes N2: the tone colours of the symbols and accent stripes of the kinded notes (none for plain notes: no change).
        val toneCss =
            NoteTones.css(
                used = geo.placedNotes.filter { it.kind.hasIcon }.mapTo(LinkedHashSet()) { NoteIcons.tone(it.kind) },
                theme = theme,
            )
        if (toneCss.isNotEmpty()) tag(name = "style") { text(toneCss) }

        // 1. layer band backgrounds (z-order: background first) with distinct
        //    per-layer tint + the swimlane (layer) header on the left.
        geo.layers.forEach { layer ->
            val band = geo.bandY(layer)
            val bandH = geo.bandHeight(layer)
            rawXml(
                """<rect x="${f(geo.contentLeft)}" y="${f(band.start)}" """ +
                    """width="${f(geo.columnWidth * geo.phases.size)}" height="${f(bandH)}" """ +
                    """fill="${layerBandFill(layer)}"/>""",
            )
            // Swimlane header: wrapped to the label column's width, vertically centred in the band.
            val labelLines = BlueprintGridConstants.laneLabelLines(labels.layer(layer))
            val labelX = geo.padding + BlueprintGridConstants.LABEL_COLUMN_TEXT_INSET
            val firstY = band.start + bandH / 2 - (labelLines.size - 1) * BlueprintGridConstants.LABEL_LINE_HEIGHT / 2.0
            if (labelLines.size == 1) {
                tag(
                    name = "text",
                    attrs =
                        mapOf(
                            "x" to f(labelX),
                            "y" to f(firstY),
                            "class" to "kuml-body",
                            "font-size" to "12",
                            "font-weight" to "600",
                            "fill" to "#1d2968",
                        ),
                ) { text(labelLines[0]) }
            } else {
                rawXml(
                    buildString {
                        append("""<text x="${f(labelX)}" y="${f(firstY)}" class="kuml-body" font-size="12" """)
                        append("""font-weight="600" fill="#1d2968">""")
                        labelLines.forEachIndexed { idx, line ->
                            val dy = if (idx == 0) "0" else f(BlueprintGridConstants.LABEL_LINE_HEIGHT)
                            append("""<tspan x="${f(labelX)}" dy="$dy">${xmlEscapeContent(line)}</tspan>""")
                        }
                        append("</text>")
                    },
                )
            }
        }

        // 2. phase column headers
        geo.phases.forEachIndexed { i, phase ->
            tag(
                name = "text",
                attrs =
                    mapOf(
                        "x" to f(geo.columnCenters[i]),
                        "y" to f(geo.padding + 22),
                        "text-anchor" to "middle",
                        "class" to "kuml-title",
                        "font-size" to "13",
                        "font-weight" to "700",
                    ),
            ) { text(phase.name ?: phase.id) }
        }

        // 3. emotion curve band (over the customer layer)
        if (showEmotion) {
            renderEmotionCurve(
                curve = model.emotionCurve(),
                columnCenters = geo.columnCenters,
                bandTop = geo.emotionTop,
                bandHeight = geo.emotionHeight,
                contentLeft = geo.contentLeft,
                contentRight = geo.contentRight,
                labelX = geo.padding + 6,
            )
        }

        // 4. step cards per cell: several cards of one cell are stacked top to bottom (declaration order);
        //    each card carries its own footer (actor + touchpoint badges).
        geo.layers.forEach { layer ->
            geo.phases.forEach { phase ->
                geo.cardsIn(phaseId = phase.id, layer = layer).forEach { card ->
                    val step = card.step
                    val actor = step.actorRef?.let { ref -> model.actors.firstOrNull { it.id == ref } }
                    val footerTouchpoints =
                        step.touchpointRefs.mapNotNull { tpId ->
                            val tp = model.touchpoints.firstOrNull { it.id == tpId } ?: return@mapNotNull null
                            val ch = tp.channelRef?.let { ref -> model.channels.firstOrNull { it.id == ref } }
                            FooterTouchpoint(tp = tp, channel = ch, badge = badgeByTouchpointId[tp.id])
                        }
                    renderStepCard(
                        step = step,
                        x = card.x,
                        y = card.y,
                        w = card.w,
                        h = card.h,
                        labels = labels,
                        actor = actor,
                        accent = layerAccent(layer),
                        touchpoints = footerTouchpoints,
                    )
                }
            }
        }

        // 5. separator lines (full view only), drawn over the bands/cards but
        //    under the connections.
        if (showLines.isNotEmpty()) {
            renderBlueprintLines(lines = showLines, geo = geo, labels = labels)
        }

        // 6. connections on top
        model.connections.forEach { conn ->
            val src = cardCenterOf(model = model, geo = geo, elementId = conn.sourceRef)
            val dst = cardCenterOf(model = model, geo = geo, elementId = conn.targetRef)
            if (src != null && dst != null) {
                renderConnection(id = conn.id, from = src, to = dst, style = conn.style)
            }
        }

        // 6b. notes (P-COM-04): column right of the lanes, dotted anchor line to each anchored step card
        geo.placedNotes.forEach { n ->
            n.routes.forEach { (_, pts) ->
                tag(
                    name = "path",
                    attrs =
                        mapOf(
                            "d" to pts.mapIndexed { i, (x, y) -> "${if (i == 0) "M" else "L"} ${f(x)} ${f(y)}" }.joinToString(" "),
                            "class" to "kuml-note-anchor",
                        ),
                )
                val (ex, ey) = pts.last()
                tag(
                    name = "circle",
                    attrs =
                        mapOf(
                            "cx" to f(ex),
                            "cy" to f(ey),
                            "r" to NOTE_ANCHOR_DOT_RADIUS,
                            "class" to "kuml-note-anchor-dot",
                        ),
                )
            }
            val size = dev.kuml.layout.Size(width = n.w.toFloat(), height = n.h.toFloat())
            renderNote(
                id = n.id,
                body = n.body,
                layout =
                    dev.kuml.layout.NodeLayout(
                        bounds = dev.kuml.layout.Rect(origin = dev.kuml.layout.Point(x = n.x.toFloat(), y = n.y.toFloat()), size = size),
                    ),
                frame = dev.kuml.layout.bridge.NoteFrameStyle.DOG_EAR,
                frameClasses = "kuml-comment kuml-blueprint-note",
                builder = this,
                kind = n.kind,
            )
        }

        // 7. touchpoint legend (fix: touchpoint names were never rendered
        // anywhere — see BlueprintLegendSvg.kt). Empty when no step
        // references a touchpoint, so unaffected diagrams render unchanged.
        if (geo.legendRows.isNotEmpty()) {
            renderBlueprintLegend(
                rows = geo.legendRows,
                x = geo.contentLeft,
                y = geo.gridBottom + BlueprintGridConstants.LEGEND_TOP_PADDING,
                channelOf = { tp -> tp.channelRef?.let { ref -> model.channels.firstOrNull { it.id == ref } } },
            )
        }
    }
    // "Powered by kUML" branding — this renderer bypasses SvgDocument.render's
    // `<svg>` assembly entirely (deterministic grid geometry, no LayoutResult),
    // so the always-on attribution comment must be prepended explicitly here
    // to keep the same "every SVG this project emits carries it" guarantee.
    return SvgDocument.attributionComment() + b.toString()
}

/** Per-layer band tint (distinct Shostack-layer styling, V3.1.24). */
private fun layerBandFill(layer: BlueprintLayer): String =
    when (layer) {
        BlueprintLayer.CUSTOMER_ACTIONS -> "#fff8e1" // warm — the customer's world
        BlueprintLayer.FRONTSTAGE -> "#eaf2fb" // light blue — visible interaction
        BlueprintLayer.BACKSTAGE -> "#eef1f6" // grey-blue — internal
        BlueprintLayer.SUPPORT_PROCESSES -> "#f3eef8" // light violet — systems/partners
    }

/** Per-layer step-card accent stroke (V3.1.24). */
private fun layerAccent(layer: BlueprintLayer): String =
    when (layer) {
        BlueprintLayer.CUSTOMER_ACTIONS -> "#fab500"
        BlueprintLayer.FRONTSTAGE -> "#186cb4"
        BlueprintLayer.BACKSTAGE -> "#6b7588"
        BlueprintLayer.SUPPORT_PROCESSES -> "#8a4fbf"
    }

private fun cardCenterOf(
    model: BlueprintModel,
    geo: BlueprintGeometry,
    elementId: String,
): Pair<Double, Double>? {
    val step =
        model.steps.firstOrNull { it.id == elementId }
            ?: model.touchpoints
                .firstOrNull { it.id == elementId }
                ?.let { tp -> model.steps.firstOrNull { tp.id in it.touchpointRefs } }
            ?: return null
    // Connections end at the centre of the step's own card (not the cell centre), so stacked cards stay distinguishable.
    val card = geo.cardRect(step.id) ?: return null
    return card.cx to card.cy
}
