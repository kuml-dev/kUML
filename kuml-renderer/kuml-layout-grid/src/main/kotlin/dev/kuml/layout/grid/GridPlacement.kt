package dev.kuml.layout.grid

import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutNode
import dev.kuml.layout.LayoutWarning
import dev.kuml.layout.NodeHints
import dev.kuml.layout.NodeId
import dev.kuml.layout.RelativeConstraint

/**
 * Ein 2D-Grid-Slot, der von einem oder mehreren Knoten belegt werden kann.
 *
 * `col`/`row` sind 0-basiert. `colSpan`/`rowSpan` ≥ 1.
 */
internal data class GridSlot(
    val col: Int,
    val row: Int,
    val colSpan: Int,
    val rowSpan: Int,
) {
    /** Belegte Spalten dieses Slots als Range `col until col+colSpan`. */
    fun cols(): IntRange = col until (col + colSpan)

    /** Belegte Zeilen dieses Slots als Range `row until row+rowSpan`. */
    fun rows(): IntRange = row until (row + rowSpan)

    /** Liefert true, wenn dieser Slot mit einem anderen mindestens eine Zelle gemeinsam hat. */
    fun overlaps(other: GridSlot): Boolean = cols().any { c -> c in other.cols() } && rows().any { r -> r in other.rows() }
}

/**
 * Ergebnis der Grid-Platzierung: jeder Knoten hat genau einen Slot.
 *
 * `slots` ist nach Iterationsreihenfolge der Eingabe-Knoten sortiert (deterministic).
 * `warnings` enthält Hinweise auf nicht erfüllbare Constraints (z.B. Slot-Kollision).
 */
internal data class PlacementResult(
    val slots: Map<NodeId, GridSlot>,
    val warnings: List<LayoutWarning>,
    val cols: Int,
    val rows: Int,
)

/**
 * Berechnet eine Slot-Zuordnung für alle Knoten.
 *
 * **Algorithmus** (deterministisch, O(n²) im worst case, n ≤ 500 ist die typische
 * Größenordnung):
 *
 * 1. **Explizit gehintete Knoten** zuerst platzieren (`gridCol` UND `gridRow` gesetzt).
 *    Bei Slot-Kollision zwischen zwei Hard-Hints: Warning `hint.conflict.gridSlot`,
 *    der spätere Knoten wird in den nächstfreien Slot rechts daneben verschoben.
 *
 * 2. **Relative Constraints** (Above/Below/LeftOf/RightOf/SameRow/SameCol) auf
 *    den verbleibenden Knoten anwenden:
 *    - Pro Knoten wird der bevorzugte Slot aus den Constraints abgeleitet.
 *    - Wenn der bevorzugte Slot frei ist → dort platzieren; sonst nächster freier.
 *    - Ausgaben sind deterministisch durch stabile Sortierung nach `NodeId.value`.
 *
 * 3. **Auto-Fill**: alle übrigen Knoten in Reading-Order (links→rechts, oben→unten)
 *    auf die nächsten freien Slots verteilen.
 *
 * Der Algorithmus minimiert keine Edge-Crossings (das macht
 * [postProcessCrossings] in einem Folge-Schritt) — er kümmert sich ausschließlich
 * um die Slot-Allokation.
 */
internal fun placeOnGrid(graph: LayoutGraph): PlacementResult {
    val nodes = graph.nodes
    val warnings = mutableListOf<LayoutWarning>()
    val occupied = mutableMapOf<NodeId, GridSlot>()

    fun isFree(candidate: GridSlot): Boolean = occupied.values.none { it.overlaps(candidate) }

    fun firstFreeSlotFrom(
        startCol: Int,
        startRow: Int,
        colSpan: Int,
        rowSpan: Int,
    ): GridSlot {
        // Reading-Order-Scan: erst innerhalb der aktuellen Zeile, dann nächste Zeile.
        var r = startRow
        while (true) {
            for (c in startCol..(startCol + occupied.size + colSpan)) {
                val candidate = GridSlot(c, r, colSpan, rowSpan)
                if (isFree(candidate)) return candidate
            }
            r++
        }
    }

    // ── Schritt 1: explizit gehintete Knoten ──────────────────────────────
    val explicitlyHinted = nodes.filter { it.hints.gridCol != null && it.hints.gridRow != null }
    for (node in explicitlyHinted) {
        val h = node.hints
        val desired =
            GridSlot(
                col = h.gridCol!!,
                row = h.gridRow!!,
                colSpan = h.gridColSpan.coerceAtLeast(1),
                rowSpan = h.gridRowSpan.coerceAtLeast(1),
            )
        if (isFree(desired)) {
            occupied[node.id] = desired
        } else {
            // Hard-Constraint-Konflikt: zwei Knoten wollen denselben Slot.
            // Wir platzieren den späteren rechts daneben und warnen.
            val fallback =
                firstFreeSlotFrom(
                    startCol = desired.col + desired.colSpan,
                    startRow = desired.row,
                    colSpan = desired.colSpan,
                    rowSpan = desired.rowSpan,
                )
            occupied[node.id] = fallback
            warnings +=
                LayoutWarning(
                    code = "hint.conflict.gridSlot",
                    message =
                        "Node '${node.id.value}' requested grid slot " +
                            "(${desired.col},${desired.row}) but it was already occupied; " +
                            "placed at (${fallback.col},${fallback.row}) instead.",
                    affectedNodes = listOf(node.id),
                )
        }
    }

    // ── Schritt 2: relative Constraints ───────────────────────────────────
    val withRelative =
        nodes
            .filter { it.id !in occupied && it.hints.relative.isNotEmpty() }
            .sortedBy { it.id.value } // deterministisch
    for (node in withRelative) {
        val preferred = preferredSlotFromConstraints(node, occupied, warnings)
        val target =
            if (preferred != null && isFree(preferred)) {
                preferred
            } else {
                if (preferred != null) {
                    warnings +=
                        LayoutWarning(
                            code = "hint.unfulfilled.relativeConstraint",
                            message =
                                "Node '${node.id.value}' relative constraint placement " +
                                    "(${preferred.col},${preferred.row}) was occupied; " +
                                    "falling back to next free slot.",
                            affectedNodes = listOf(node.id),
                        )
                }
                firstFreeSlotFrom(
                    startCol = preferred?.col ?: 0,
                    startRow = preferred?.row ?: 0,
                    colSpan = node.hints.gridColSpan.coerceAtLeast(1),
                    rowSpan = node.hints.gridRowSpan.coerceAtLeast(1),
                )
            }
        occupied[node.id] = target
    }

    // ── Schritt 3: Auto-Fill in Reading-Order ─────────────────────────────
    val remaining = nodes.filter { it.id !in occupied }
    var cursorCol = 0
    var cursorRow = 0
    for (node in remaining) {
        val slot =
            firstFreeSlotFrom(
                startCol = cursorCol,
                startRow = cursorRow,
                colSpan = node.hints.gridColSpan.coerceAtLeast(1),
                rowSpan = node.hints.gridRowSpan.coerceAtLeast(1),
            )
        occupied[node.id] = slot
        cursorCol = slot.col + slot.colSpan
        cursorRow = slot.row
    }

    val maxCol = occupied.values.maxOfOrNull { it.col + it.colSpan } ?: 1
    val maxRow = occupied.values.maxOfOrNull { it.row + it.rowSpan } ?: 1

    return PlacementResult(
        slots = occupied.toMap(),
        warnings = warnings,
        cols = maxCol,
        rows = maxRow,
    )
}

/**
 * Leitet aus den `relative`-Constraints eines Knotens einen bevorzugten Slot ab.
 *
 * Pragmatik:
 *  - `Above(other)` → Slot eine Zeile über `other`s Slot.
 *  - `Below(other)` → Slot eine Zeile unter `other`s Slot.
 *  - `LeftOf(other)` → Slot eine Spalte links von `other`s Slot.
 *  - `RightOf(other)` → Slot eine Spalte rechts von `other`s Slot.
 *  - `SameRowAs(other)` → erste freie Spalte in `other`s Zeile.
 *  - `SameColAs(other)` → erste freie Zeile in `other`s Spalte.
 *
 * Bei mehreren Constraints werden sie in Reihenfolge aufgesammelt; widersprechen
 * sich zwei Constraints → der erste gewinnt + Warning.
 *
 * Wenn keiner der referenzierten Knoten bereits platziert ist, kann kein
 * bevorzugter Slot abgeleitet werden — Rückgabe `null`, der Aufrufer fällt auf
 * Auto-Fill zurück.
 */
private fun preferredSlotFromConstraints(
    node: LayoutNode,
    placed: Map<NodeId, GridSlot>,
    warnings: MutableList<LayoutWarning>,
): GridSlot? {
    val h: NodeHints = node.hints
    val colSpan = h.gridColSpan.coerceAtLeast(1)
    val rowSpan = h.gridRowSpan.coerceAtLeast(1)
    var preferredCol: Int? = null
    var preferredRow: Int? = null

    for (constraint in h.relative) {
        val anchor = constraint.anchor()
        val anchorSlot = placed[anchor]
        if (anchorSlot == null) {
            warnings +=
                LayoutWarning(
                    code = "hint.deferred.relativeConstraint",
                    message =
                        "Node '${node.id.value}' references not-yet-placed " +
                            "node '${anchor.value}' in a relative constraint; " +
                            "constraint deferred.",
                    affectedNodes = listOf(node.id),
                )
            continue
        }
        when (constraint) {
            is RelativeConstraint.Above -> preferredRow = (anchorSlot.row - rowSpan).coerceAtLeast(0)
            is RelativeConstraint.Below -> preferredRow = anchorSlot.row + anchorSlot.rowSpan
            is RelativeConstraint.LeftOf -> preferredCol = (anchorSlot.col - colSpan).coerceAtLeast(0)
            is RelativeConstraint.RightOf -> preferredCol = anchorSlot.col + anchorSlot.colSpan
            is RelativeConstraint.SameRowAs -> preferredRow = anchorSlot.row
            is RelativeConstraint.SameColAs -> preferredCol = anchorSlot.col
        }
    }

    if (preferredCol == null && preferredRow == null) return null
    return GridSlot(
        col = preferredCol ?: 0,
        row = preferredRow ?: 0,
        colSpan = colSpan,
        rowSpan = rowSpan,
    )
}

/** Liefert die `NodeId`, auf die der Constraint sich bezieht. */
private fun RelativeConstraint.anchor(): NodeId =
    when (this) {
        is RelativeConstraint.Above -> other
        is RelativeConstraint.Below -> other
        is RelativeConstraint.LeftOf -> other
        is RelativeConstraint.RightOf -> other
        is RelativeConstraint.SameRowAs -> other
        is RelativeConstraint.SameColAs -> other
    }
