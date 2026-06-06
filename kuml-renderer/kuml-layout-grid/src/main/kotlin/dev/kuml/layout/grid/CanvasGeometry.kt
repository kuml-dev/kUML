package dev.kuml.layout.grid

import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.NodeId
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.Spacing

/**
 * Berechnete Spalten-/Zeilen-Geometrie:
 *
 *  - `colWidths[i]` ist die Breite der i-ten Spalte (max der intrinsischen Breiten
 *    der Knoten in dieser Spalte, geteilt durch ihren `colSpan`).
 *  - `colX[i]` ist die x-Koordinate des linken Rands der i-ten Spalte.
 *  - `colX[colWidths.size]` ist die x-Koordinate des rechten Rands der letzten Spalte
 *    (entspricht der Canvas-Breite ohne abschließendes Padding).
 *
 *  Analog für Zeilen.
 */
internal data class CanvasGeometry(
    val colWidths: FloatArray,
    val rowHeights: FloatArray,
    val colX: FloatArray,
    val rowY: FloatArray,
) {
    val canvasSize: Size
        get() = Size(width = colX.last(), height = rowY.last())

    /** Bounds eines Knotens, der in [slot] platziert ist und [nodeSize] intrinsische Größe hat. */
    fun boundsFor(
        slot: GridSlot,
        nodeSize: Size,
    ): Rect {
        val cellLeft = colX[slot.col]
        val cellTop = rowY[slot.row]
        val cellRight = colX[slot.col + slot.colSpan]
        val cellBottom = rowY[slot.row + slot.rowSpan]
        val cellW = cellRight - cellLeft
        val cellH = cellBottom - cellTop
        // Wir zentrieren den Knoten innerhalb seiner Zelle — das vermeidet Lücken
        // bei colSpan>1 / rowSpan>1 und sieht auch bei einzelnen Zellen gut aus.
        val x = cellLeft + (cellW - nodeSize.width) / 2f
        val y = cellTop + (cellH - nodeSize.height) / 2f
        return Rect(origin = Point(x, y), size = nodeSize)
    }
}

/**
 * Berechnet [CanvasGeometry] aus der Slot-Allokation und den intrinsischen
 * Knoten-Größen.
 *
 * Spaltenbreiten ergeben sich aus dem Max der Knotenbreiten in der Spalte;
 * für Knoten mit `colSpan > 1` wird die Größe gleichmäßig auf die belegten
 * Spalten verteilt (die Spalte darf nicht künstlich aufgebläht werden, nur
 * weil ein span-1-Nachbar es einschränkt).
 *
 * Spacing zwischen Spalten/Zeilen kommt aus [Spacing.nodeToNode].
 */
internal fun computeGeometry(
    graph: LayoutGraph,
    placement: PlacementResult,
    spacing: Spacing,
): CanvasGeometry {
    val cols = placement.cols.coerceAtLeast(1)
    val rows = placement.rows.coerceAtLeast(1)
    val colWidths = FloatArray(cols) { 0f }
    val rowHeights = FloatArray(rows) { 0f }
    val nodeById: Map<NodeId, Size> = graph.nodes.associate { it.id to it.intrinsicSize }

    for ((nodeId, slot) in placement.slots) {
        val intrinsic = nodeById[nodeId] ?: continue
        val perCol = intrinsic.width / slot.colSpan
        val perRow = intrinsic.height / slot.rowSpan
        for (c in slot.cols()) if (c in colWidths.indices) colWidths[c] = maxOf(colWidths[c], perCol)
        for (r in slot.rows()) if (r in rowHeights.indices) rowHeights[r] = maxOf(rowHeights[r], perRow)
    }

    val gap = spacing.nodeToNode
    val colX =
        FloatArray(cols + 1).also { xs ->
            xs[0] = gap / 2f
            for (i in 0 until cols) xs[i + 1] = xs[i] + colWidths[i] + gap
        }
    val rowY =
        FloatArray(rows + 1).also { ys ->
            ys[0] = gap / 2f
            for (i in 0 until rows) ys[i + 1] = ys[i] + rowHeights[i] + gap
        }

    return CanvasGeometry(
        colWidths = colWidths,
        rowHeights = rowHeights,
        colX = colX,
        rowY = rowY,
    )
}
