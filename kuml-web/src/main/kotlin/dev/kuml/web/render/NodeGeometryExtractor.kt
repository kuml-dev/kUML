package dev.kuml.web.render

import dev.kuml.core.model.DiagramType
import dev.kuml.io.svg.SvgRenderOptions
import dev.kuml.layout.LayoutResult
import dev.kuml.web.api.GridGeometry
import dev.kuml.web.api.NodeBox

/**
 * Extracts client-facing hit-test geometry (per-node boxes + an approximate
 * uniform grid) from the exact same [LayoutResult] instance handed to the
 * SVG renderer for a given render.
 *
 * The renderer shifts every node by [paddingPx] and sizes the `viewBox` as
 * `canvas + 2 * paddingPx` — so the coordinates returned here must be
 * `bounds.origin + paddingPx` to live in the same SVG user-unit space the
 * client hit-tests against (see `KumlSvgRenderer` / `<g id="…">` groups,
 * whose ids are the raw element ids used to build [dev.kuml.layout.NodeId]).
 */
internal object NodeGeometryExtractor {
    /** Result of [extract]: per-node hit boxes plus an optional approximate grid. */
    data class Geometry(
        val nodes: List<NodeBox>,
        val grid: GridGeometry?,
    )

    /**
     * Extracts [Geometry] from [layoutResult].
     *
     * [nodes] is populated for every node in [layoutResult], regardless of
     * diagram type (cheap, correct geometry straight off the layout). [grid]
     * is only derived for [DiagramType.CLASS] diagrams with at least one node
     * — the only diagram type the drag-and-drop grid-hint feature targets and
     * the only type `LayoutHintService` accepts (see Wave 1).
     */
    fun extract(
        diagramType: DiagramType,
        layoutResult: LayoutResult,
        paddingPx: Float = SvgRenderOptions.DEFAULT.paddingPx,
    ): Geometry {
        val nodes =
            layoutResult.nodes
                .map { (id, nodeLayout) ->
                    NodeBox(
                        id = id.value,
                        x = nodeLayout.bounds.origin.x + paddingPx,
                        y = nodeLayout.bounds.origin.y + paddingPx,
                        w = nodeLayout.bounds.size.width,
                        h = nodeLayout.bounds.size.height,
                    )
                }.sortedBy { it.id }
        val grid = if (diagramType == DiagramType.CLASS && nodes.isNotEmpty()) deriveGrid(nodes) else null
        return Geometry(nodes = nodes, grid = grid)
    }

    /**
     * Clusters node centers into left-to-right column bands and top-to-bottom
     * row bands, then models a *uniform* grid over the nodes' bounding box in
     * padded space.
     *
     * ELK's real cells are not equal-width/-height — this is an accepted
     * approximation for MVP drag-and-drop snapping (documented as a known UX
     * rough edge), not an exact reconstruction of the layout engine's grid.
     */
    private fun deriveGrid(nodes: List<NodeBox>): GridGeometry {
        // Tolerance is derived from the median node extent on each axis (half
        // the median width/height) rather than a fixed pixel value: ELK's
        // real cells are not equal-sized, so two nodes "in the same column"
        // rarely share an exact center — but their centers are close relative
        // to their own size. Falls back to BAND_TOLERANCE_FALLBACK_PX when the
        // median is degenerate (e.g. zero-size fixtures).
        val colTolerance = (median(nodes.map { it.w }) / 2f).takeIf { it > 0f } ?: BAND_TOLERANCE_FALLBACK_PX
        val rowTolerance = (median(nodes.map { it.h }) / 2f).takeIf { it > 0f } ?: BAND_TOLERANCE_FALLBACK_PX

        val cols = clusterBands(nodes.map { it.x + it.w / 2f }, colTolerance)
        val rows = clusterBands(nodes.map { it.y + it.h / 2f }, rowTolerance)

        val originX = nodes.minOf { it.x }
        val originY = nodes.minOf { it.y }
        val maxRight = nodes.maxOf { it.x + it.w }
        val maxBottom = nodes.maxOf { it.y + it.h }

        val cellW =
            ((maxRight - originX) / cols).takeIf { it > 0f }
                ?: nodes.first().w
        val cellH =
            ((maxBottom - originY) / rows).takeIf { it > 0f }
                ?: nodes.first().h

        return GridGeometry(
            cols = cols,
            rows = rows,
            cellW = cellW,
            cellH = cellH,
            originX = originX,
            originY = originY,
        )
    }

    /**
     * Clusters a list of 1-D center coordinates into bands: sorts them, then
     * starts a new band whenever the next center is further than
     * [tolerance] away from the previous one. Returns the band count
     * (always >= 1 for a non-empty input).
     */
    private fun clusterBands(
        centers: List<Float>,
        tolerance: Float,
    ): Int {
        val sorted = centers.sorted()
        var bandCount = 1
        var previous = sorted.first()
        for (i in 1 until sorted.size) {
            val current = sorted[i]
            if (current - previous > tolerance) {
                bandCount++
            }
            previous = current
        }
        return bandCount
    }

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2f else sorted[mid]
    }

    private const val BAND_TOLERANCE_FALLBACK_PX = 1f
}
