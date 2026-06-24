package dev.kuml.io.svg.blueprint

import dev.kuml.blueprint.model.BlueprintDiagram
import dev.kuml.blueprint.model.BlueprintDiagramFull
import dev.kuml.blueprint.model.BlueprintLayer
import dev.kuml.blueprint.model.BlueprintLine
import dev.kuml.blueprint.model.BlueprintModel
import dev.kuml.blueprint.model.JourneyDiagram
import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.blueprint.edge.renderConnection

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

    val geo = BlueprintGeometry(model, effectiveLayers, showEmotion)
    val b = SvgBuilder(pretty = false)

    b.tag(
        "svg",
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

        // 1. layer band backgrounds (z-order: background first) with distinct
        //    per-layer tint + the swimlane (layer) header on the left.
        geo.layers.forEach { layer ->
            val band = geo.bandY(layer)
            rawXml(
                """<rect x="${f(geo.contentLeft)}" y="${f(band.start)}" """ +
                    """width="${f(geo.columnWidth * geo.phases.size)}" height="${f(geo.rowHeight)}" """ +
                    """fill="${layerBandFill(layer)}"/>""",
            )
            tag(
                "text",
                mapOf(
                    "x" to f(geo.padding + 6),
                    "y" to f(band.start + geo.rowHeight / 2),
                    "class" to "kuml-body",
                    "font-size" to "12",
                    "font-weight" to "600",
                    "fill" to "#1d2968",
                ),
            ) { text(layerLabel(layer)) }
        }

        // 2. phase column headers
        geo.phases.forEachIndexed { i, phase ->
            tag(
                "text",
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
            b.renderEmotionCurve(
                curve = model.emotionCurve(),
                columnCenters = geo.columnCenters,
                bandTop = geo.emotionTop,
                bandHeight = geo.emotionHeight,
            )
        }

        // 4. step cards + touchpoints per cell
        geo.layers.forEach { layer ->
            geo.phases.forEachIndexed { i, phase ->
                val (cellX, cellY) = geo.cellOrigin(i, layer)
                val stepsHere = model.stepsIn(phase.id, layer)
                stepsHere.forEach { step ->
                    val actor = step.actorRef?.let { ref -> model.actors.firstOrNull { it.id == ref } }
                    b.renderStepCard(
                        step = step,
                        cellX = cellX,
                        cellY = cellY,
                        cellW = geo.columnWidth,
                        cellH = geo.rowHeight,
                        actor = actor,
                        accent = layerAccent(layer),
                    )
                    step.touchpointRefs.forEachIndexed { tIdx, tpId ->
                        val tp = model.touchpoints.firstOrNull { it.id == tpId } ?: return@forEachIndexed
                        val ch = tp.channelRef?.let { ref -> model.channels.firstOrNull { it.id == ref } }
                        b.renderTouchpoint(
                            tp = tp,
                            channel = ch,
                            cx = cellX + 18 + tIdx * 30,
                            cy = cellY + geo.rowHeight - 16,
                        )
                    }
                }
            }
        }

        // 5. separator lines (full view only), drawn over the bands/cards but
        //    under the connections.
        if (showLines.isNotEmpty()) {
            b.renderBlueprintLines(showLines, geo)
        }

        // 6. connections on top
        model.connections.forEach { conn ->
            val src = cellCenterOf(model, geo, conn.sourceRef)
            val dst = cellCenterOf(model, geo, conn.targetRef)
            if (src != null && dst != null) {
                b.renderConnection(src, dst, conn.style)
            }
        }
    }
    return b.toString()
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

private fun layerLabel(layer: BlueprintLayer): String =
    when (layer) {
        BlueprintLayer.CUSTOMER_ACTIONS -> "Customer Actions"
        BlueprintLayer.FRONTSTAGE -> "Frontstage"
        BlueprintLayer.BACKSTAGE -> "Backstage"
        BlueprintLayer.SUPPORT_PROCESSES -> "Support Processes"
    }

private fun cellCenterOf(
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
    val phaseIdx = geo.phases.indexOfFirst { it.id == step.phaseRef }
    if (phaseIdx < 0 || step.layer !in geo.layers) return null
    val (cx, cy) = geo.cellOrigin(phaseIdx, step.layer)
    return (cx + geo.columnWidth / 2) to (cy + geo.rowHeight / 2)
}
