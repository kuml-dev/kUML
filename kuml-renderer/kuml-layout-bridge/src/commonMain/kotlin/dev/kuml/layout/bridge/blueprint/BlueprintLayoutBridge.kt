package dev.kuml.layout.bridge.blueprint

import dev.kuml.blueprint.model.BlueprintDiagram
import dev.kuml.blueprint.model.BlueprintDiagramFull
import dev.kuml.blueprint.model.BlueprintGridConstants
import dev.kuml.blueprint.model.BlueprintLayer
import dev.kuml.blueprint.model.BlueprintModel
import dev.kuml.blueprint.model.JourneyDiagram

/**
 * Computes blueprint cell geometry as a plain grid (phases = columns, visible
 * layers = rows). Deliberately NOT backed by ELK — a journey/blueprint is a
 * strict table with no free graph topology to optimise; kuml-layout-grid's grid
 * model is sufficient. This bridge exposes deterministic cell rectangles that
 * both the SVG (V3.1.23) and LaTeX (V3.1.26) renderers consume.
 *
 * V3.1.23
 */
public class BlueprintLayoutBridge {
    public data class Cell(
        val phaseId: String,
        val layer: BlueprintLayer,
        val x: Double,
        val y: Double,
        val width: Double,
        val height: Double,
    )

    public data class BlueprintLayout(
        val cells: List<Cell>,
        val columnCenters: List<Double>,
        val layerOrder: List<BlueprintLayer>,
        val canvasWidth: Double,
        val canvasHeight: Double,
        val emotionBand: ClosedFloatingPointRange<Double>?,
    )

    public fun layout(
        model: BlueprintModel,
        diagram: BlueprintDiagram,
    ): BlueprintLayout {
        val labelW = BlueprintGridConstants.LABEL_COLUMN_WIDTH
        val colW = BlueprintGridConstants.COLUMN_WIDTH
        val pad = BlueprintGridConstants.PADDING
        val headerH = BlueprintGridConstants.HEADER_HEIGHT
        val emotionH = BlueprintGridConstants.EMOTION_BAND_HEIGHT

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
        val layers =
            BlueprintLayer.entries
                .filter { layer ->
                    layer in visibleLayers &&
                        (diagram is BlueprintDiagramFull || layer in model.activeLayers())
                }.ifEmpty { listOf(BlueprintLayer.CUSTOMER_ACTIONS) }

        val phases = model.orderedPhases()
        val contentLeft = pad + labelW
        val emotionTop = pad + headerH
        val emotionBand = if (showEmotion) emotionTop..(emotionTop + emotionH) else null
        val gridTop = emotionTop + (if (showEmotion) emotionH else 0.0)

        // Content-aware per layer — mirrors BlueprintGeometry (kuml-io-svg) so the cell
        // rectangles this bridge exposes (LaTeX renderer, MCP tools) match
        // what the SVG renderer actually draws.
        val layerHeights = BlueprintGridConstants.layerHeights(model = model, layers = layers)
        val layerTops = layers.runningFold(gridTop) { top, layer -> top + layerHeights.getValue(layer) }

        val columnCenters = phases.indices.map { contentLeft + colW * it + colW / 2 }
        val cells =
            phases.flatMapIndexed { i, phase ->
                layers.mapIndexed { j, layer ->
                    Cell(
                        phaseId = phase.id,
                        layer = layer,
                        x = contentLeft + colW * i,
                        y = layerTops[j],
                        width = colW,
                        height = layerHeights.getValue(layer),
                    )
                }
            }
        val contentWidth = colW * phases.size
        // Content-aware — mirrors BlueprintGeometry's legendHeight (kuml-io-svg)
        // so canvasHeight stays consistent even though this bridge doesn't
        // draw the legend itself (no SVG renderer here, just cell geometry).
        val legendH = BlueprintGridConstants.legendHeight(model = model, contentWidth = contentWidth, visibleLayers = layers.toSet())
        return BlueprintLayout(
            cells = cells,
            columnCenters = columnCenters,
            layerOrder = layers,
            canvasWidth = contentLeft + colW * phases.size + BlueprintGridConstants.noteColumnWidth(model) + pad,
            canvasHeight = layerTops.last() + legendH + pad,
            emotionBand = emotionBand,
        )
    }
}
