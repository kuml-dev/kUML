package dev.kuml.sysml2.projection

import dev.kuml.sysml2.ActionUsage
import dev.kuml.sysml2.AllocationUsage
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.ConnectionDefinition
import dev.kuml.sysml2.FlowPropertyUsage
import dev.kuml.sysml2.PartDefinition
import dev.kuml.sysml2.PartUsage
import dev.kuml.sysml2.PortDirection
import dev.kuml.sysml2.PortUsage
import dev.kuml.sysml2.Sysml2Model

enum class BddEdgeKind { Composition, Aggregation, Association, Allocation }

/**
 * A relationship edge of a BDD. [sourceId] is the owning end (diamond end for
 * compositions and aggregations). [endLabel] is shown next to the target end.
 */
data class BddEdge(
    val id: String,
    val kind: BddEdgeKind,
    val sourceId: String,
    val targetId: String,
    val label: String?,
    val endLabel: String?,
)

data class BddProjection(
    val edges: List<BddEdge>,
    /** Feature ids drawn as an edge or an own compartment (or not drawn at all, like flows) instead of a feature line. */
    val hiddenFeatureIds: Set<String>,
    val actionsByDef: Map<String, List<String>>,
    val signalsByDef: Map<String, List<String>>,
) {
    companion object {
        const val MAX_COMPARTMENT_LINES: Int = 20
    }
}

fun sysml2BddProjection(
    model: Sysml2Model,
    diagram: BdDiagram,
): BddProjection {
    val visible = diagram.elementIds.toSet()
    val edges = mutableListOf<BddEdge>()
    val hidden = mutableSetOf<String>()
    val actions = linkedMapOf<String, List<String>>()
    val signals = linkedMapOf<String, List<String>>()
    val defById = model.definitions.associateBy { it.id }

    for (def in model.definitions) {
        if (def.id !in visible || def !is PartDefinition) continue
        val ownPrefix = "${def.id}::"
        for (u in model.usages) {
            if (!u.id.startsWith(ownPrefix) || u.id.removePrefix(ownPrefix).contains("::")) continue
            when (u) {
                is PartUsage -> {
                    val target = u.definitionId
                    if (target !in visible || target == def.id || defById[target] == null) continue
                    val agg = u.isReference
                    hidden += u.id
                    val mult = u.multiplicity.toSpecForm()
                    val role = if (mult == "1") u.name else "${u.name} [$mult]"
                    edges +=
                        BddEdge(
                            id = (if (agg) "agg:" else "comp:") + u.id,
                            kind = if (agg) BddEdgeKind.Aggregation else BddEdgeKind.Composition,
                            sourceId = def.id,
                            targetId = target,
                            label = null,
                            endLabel = role,
                        )
                }
                is dev.kuml.sysml2.FlowUsage -> hidden += u.id
                is ActionUsage -> {
                    hidden += u.id
                    actions[def.id] =
                        ((actions[def.id] ?: emptyList()) + u.name).take(BddProjection.MAX_COMPARTMENT_LINES)
                }
                else -> Unit
            }
        }
        // Signals: out / inout flow properties of the ports of this block.
        val sig = linkedSetOf<String>()
        for (p in model.usages.filterIsInstance<PortUsage>()) {
            if (!p.id.startsWith(ownPrefix)) continue
            for (fp in model.usages.filterIsInstance<FlowPropertyUsage>()) {
                if (!fp.id.startsWith("${p.definitionId}::")) continue
                val eff = effectiveDir(p = p, fp = fp)
                if (eff == PortDirection.Out || eff == PortDirection.InOut) {
                    sig += defById[fp.definitionId]?.name ?: fp.definitionId
                }
            }
        }
        if (sig.isNotEmpty()) signals[def.id] = sig.take(BddProjection.MAX_COMPARTMENT_LINES)
    }

    // Association: a connection definition (not itself shown) with two block-typed part features.
    for (cd in model.definitions.filterIsInstance<ConnectionDefinition>()) {
        if (cd.id in visible) continue
        val ends =
            model.usages
                .filterIsInstance<PartUsage>()
                .filter { it.id.startsWith("${cd.id}::") && it.definitionId in visible }
        if (ends.size != 2 || ends[0].definitionId == ends[1].definitionId) continue
        val a = ends[0]
        val b = ends[1]
        edges +=
            BddEdge(
                id = "link:${cd.id}",
                kind = BddEdgeKind.Association,
                sourceId = a.definitionId,
                targetId = b.definitionId,
                label = cd.name,
                endLabel = b.name,
            )
    }

    for (al in model.usages.filterIsInstance<AllocationUsage>()) {
        if (al.sourceId in visible && al.targetId in visible) {
            edges +=
                BddEdge(
                    id = if (al.id.startsWith("alloc:")) al.id else "alloc:${al.id}",
                    kind = BddEdgeKind.Allocation,
                    sourceId = al.sourceId,
                    targetId = al.targetId,
                    label = null,
                    endLabel = null,
                )
        }
    }
    return BddProjection(edges = edges, hiddenFeatureIds = hidden, actionsByDef = actions, signalsByDef = signals)
}

private fun effectiveDir(
    p: PortUsage,
    fp: FlowPropertyUsage,
): PortDirection =
    if (!p.isConjugated) {
        fp.direction
    } else {
        when (fp.direction) {
            PortDirection.In -> PortDirection.Out
            PortDirection.Out -> PortDirection.In
            PortDirection.InOut -> PortDirection.InOut
        }
    }
