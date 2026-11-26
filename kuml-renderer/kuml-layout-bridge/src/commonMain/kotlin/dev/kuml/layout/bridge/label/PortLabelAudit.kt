package dev.kuml.layout.bridge.label

import dev.kuml.layout.Rect
import dev.kuml.layout.bridge.note.ObstacleIndex
import dev.kuml.layout.bridge.note.ObstacleKind
import dev.kuml.layout.bridge.note.inflated
import dev.kuml.layout.bridge.note.intersects
import dev.kuml.layout.bridge.note.isLabel
import dev.kuml.layout.bridge.note.segmentIntersectsRect

/**
 * PAL Welle S: a read-only audit of a finished label plan, for tests and diagnostics. A surface wraps its render call in
 * [collecting]; every [Sysml2DiagramLabelPlan.compute] run inside it leaves one [Snapshot]: per port label its rectangle,
 * the collisions it has against the finished plan (all labels, parts, pins, titles and lines, measured with the same
 * [LabelPlacer.portViolations] rule the placer uses) and its distance to the pin. Outside [collecting] nothing is recorded
 * and nothing changes. The state is per thread (like `NotePlacementMissCollector`), so a render on another thread is never
 * affected by a [collecting] scope and production renders always run with the repair on.
 *
 * [collecting] can also switch the port label repair off (`repair = false`), to measure what it changed.
 */
public object PortLabelAudit {
    /** One port label of a finished plan. */
    public data class Entry(
        val id: String,
        val rect: Rect,
        val anchorRect: Rect,
        /** Collisions against the finished plan (every label, also those placed after this one). */
        val violations: Int,
        /** What the collisions touch, as `kind id`, most frequent first, at most three. */
        val blockers: List<String>,
        /** Rectangle distance between the label and its pin square. */
        val pinDistance: Float,
    )

    /** The port labels of one plan run plus the collisions of its other labels (edge, multiplicity, role) with parts and lines. */
    public data class Snapshot(
        val ports: List<Entry>,
        /** Count of non-port labels that overlap a part, pin, title, another label or a foreign edge. */
        val otherLabelViolations: Int,
        /** Number of labels of every role in the run. */
        val labelCount: Int,
    )

    /** Per-thread audit scope: the snapshot sink and whether the repair is on. */
    internal class State(
        val sink: MutableList<Snapshot>,
        val repair: Boolean,
    )

    /** `true` unless an enclosing [collecting] on this thread switched the repair off; the IBD planner passes it to [LabelCostModel.portRepair]. */
    public val repairEnabled: Boolean get() = currentAuditState()?.repair ?: true

    /** Runs [block] and returns its value with the snapshots of the plan runs inside it, in order. */
    public fun <T> collecting(
        repair: Boolean = true,
        block: () -> T,
    ): Pair<T, List<Snapshot>> {
        val previous = currentAuditState()
        val mine = mutableListOf<Snapshot>()
        setAuditState(State(sink = mine, repair = repair))
        try {
            val value = block()
            return value to mine.toList()
        } finally {
            setAuditState(previous)
        }
    }

    internal fun record(
        requests: List<LabelRequest>,
        placed: Map<String, PlacedLabel>,
        index: ObstacleIndex,
    ) {
        val out = currentAuditState()?.sink ?: return
        val ports = ArrayList<Entry>()
        var other = 0
        for (req in requests.sortedBy { it.id }) {
            val label = placed[req.id] ?: continue
            if (req.role == LabelRole.PORT) {
                val anchor = req.anchorRect ?: continue
                val why = ArrayList<String>()
                val v = LabelPlacer.portViolations(rect = label.rect, req = req, index = index, blockers = why)
                ports +=
                    Entry(
                        id = req.id,
                        rect = label.rect,
                        anchorRect = anchor,
                        violations = v,
                        blockers = LabelPlacer.topBlockers(why),
                        pinDistance = LabelPlacer.rectGap(r = label.rect, pin = anchor),
                    )
            } else if (otherCollides(req = req, rect = label.rect, index = index)) {
                other++
            }
        }
        out += Snapshot(ports = ports, otherLabelViolations = other, labelCount = placed.size)
    }

    /** An edge-end label that overlaps a part, pin, title, another label (core rectangles) or a foreign edge. */
    private fun otherCollides(
        req: LabelRequest,
        rect: Rect,
        index: ObstacleIndex,
    ): Boolean {
        for (o in index.rects) {
            if (o.ownerId != null && o.ownerId == req.ownerId) continue
            if (o.kind != ObstacleKind.NODE &&
                o.kind != ObstacleKind.PORT &&
                o.kind != ObstacleKind.COMPOSITE_TITLE &&
                !o.kind.isLabel
            ) {
                continue
            }
            if (o.core.intersects(rect)) return true
        }
        val reach = rect.inflated(0f)
        for (l in index.lines) {
            if (l.kind != ObstacleKind.EDGE || l.ownerId == req.ownerId) continue
            for (i in 0 until l.points.size - 1) {
                if (segmentIntersectsRect(a = l.points[i], b = l.points[i + 1], r = reach)) return true
            }
        }
        return false
    }
}

internal expect fun currentAuditState(): PortLabelAudit.State?

internal expect fun setAuditState(state: PortLabelAudit.State?)
