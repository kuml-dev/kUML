package dev.kuml.web.layout

import dev.kuml.layout.bridge.LayoutHintWriter
import dev.kuml.web.api.GridGeometry
import kotlin.math.floor

/**
 * Resolves a pointer drop position (in the same padded SVG user-unit space as
 * [dev.kuml.web.render.NodeGeometryExtractor]'s [dev.kuml.web.api.NodeBox]
 * coordinates) to a [LayoutHintWriter.GridCell] using an approximate,
 * uniform [GridGeometry].
 *
 * Out-of-range drops (e.g. into the padding margin, or past the last row/
 * column) are clamped to the nearest edge cell rather than producing
 * negative or overflowing indices — a drag-and-drop UI should always resolve
 * to *some* valid cell.
 */
internal object GridCellResolver {
    fun resolve(
        grid: GridGeometry,
        xPx: Float,
        yPx: Float,
    ): LayoutHintWriter.GridCell {
        val col = resolveAxis(grid.originX, grid.cellW, grid.cols, xPx)
        val row = resolveAxis(grid.originY, grid.cellH, grid.rows, yPx)
        return LayoutHintWriter.GridCell(col = col, row = row)
    }

    /**
     * Resolves a single axis: degenerate cell extent (`<= 0`, e.g. a single
     * node whose own size was zero) collapses the axis to index 0 regardless
     * of [positionPx]; otherwise floors to the containing cell and clamps
     * into `[0, count - 1]`.
     */
    private fun resolveAxis(
        origin: Float,
        cellExtent: Float,
        count: Int,
        positionPx: Float,
    ): Int {
        if (cellExtent <= 0f) return 0
        val index = floor((positionPx - origin) / cellExtent).toInt()
        return index.coerceIn(0, (count - 1).coerceAtLeast(0))
    }
}
