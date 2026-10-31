package dev.kuml.sysml2.constraint

import dev.kuml.core.model.layout.LayoutLimits
import dev.kuml.sysml2.ActDiagram
import dev.kuml.sysml2.ActivityPartitionDefinition
import dev.kuml.sysml2.ControlFlowUsage
import dev.kuml.sysml2.SeqDiagram
import dev.kuml.sysml2.Sysml2Diagram
import dev.kuml.sysml2.Sysml2LayoutFlow
import dev.kuml.sysml2.Sysml2Limits
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.Sysml2Usage
import dev.kuml.sysml2.TransitionUsage

/** Severity of a [LayoutIssue]. */
public enum class LayoutSeverity { Error, Warning }

/** A finding of the layout hint checker (wave C3). */
public data class LayoutIssue(
    val code: Int,
    val severity: LayoutSeverity,
    val elementId: String,
    val message: String,
    val hint: String,
)

/**
 * Checker for a diagram's `layout { }` block.
 *
 * | Code | Severity | Rule |
 * |------|----------|------|
 * | 412 | Warning | layout hints on a sequence diagram (the lifeline order is the declaration order) |
 * | 431 | Warning | an id in the hints is not visible in the diagram |
 * | 432 | Error | `wrapColumns` outside 1..32 |
 * | 433 | Warning | a flow direction other than `Down` on an activity diagram with partitions (lanes are drawn top to bottom) |
 * | 434 | Error | more than [Sysml2Limits.LAYOUT_GROUPS] groups or more than [Sysml2Limits.LAYOUT_IDS_PER_GROUP] ids in a group/list |
 * | 435 | Error | an id is both in `first` and in `last` |
 * | 436 | Warning | two elements of one `sameLayer` group are connected by an edge |
 * | 437 | Error | `maxWidth` outside 200..20000 or `aspectRatio` outside 0.1..10 (or not a finite number) |
 */
public object Sysml2LayoutHintChecker {
    public fun check(
        model: Sysml2Model,
        diagram: Sysml2Diagram,
    ): List<LayoutIssue> {
        val spec = diagram.layout ?: return emptyList()
        val name = diagram.name
        if (diagram is SeqDiagram) {
            return listOf(
                LayoutIssue(
                    code = 412,
                    severity = LayoutSeverity.Warning,
                    elementId = name,
                    message = "Layout hints have no effect on a sequence diagram.",
                    hint = "Declare the lifelines in the order you want.",
                ),
            )
        }
        val issues = mutableListOf<LayoutIssue>()
        val visible: Set<String> =
            if (diagram.elementIds.isEmpty()) {
                (model.definitions.map { it.id } + model.usages.map { it.id }).toSet()
            } else {
                diagram.elementIds.toSet()
            }
        val cap = Sysml2Limits.LAYOUT_GROUPS * Sysml2Limits.LAYOUT_IDS_PER_GROUP
        val all = (spec.first + spec.last + spec.sameLayer.flatten()).take(cap).distinct()
        for (id in all) {
            if (id !in visible) {
                val option =
                    when {
                        id in spec.first -> "first"
                        id in spec.last -> "last"
                        else -> "sameLayer"
                    }
                issues +=
                    LayoutIssue(
                        code = 431,
                        severity = LayoutSeverity.Warning,
                        elementId = id,
                        message = "Unknown element id '$id' in layout.$option (not visible in the diagram).",
                        hint = "Use an id from the diagram.",
                    )
            }
        }
        spec.wrapColumns?.let {
            if (it !in 1..Sysml2Limits.LAYOUT_GROUPS) {
                issues +=
                    LayoutIssue(
                        code = 432,
                        severity = LayoutSeverity.Error,
                        elementId = name,
                        message = "wrapColumns = $it is outside 1..${Sysml2Limits.LAYOUT_GROUPS}.",
                        hint = "Choose a value in range.",
                    )
            }
        }
        spec.maxWidth?.let {
            if (it !in LayoutLimits.MAX_WIDTH_MIN..LayoutLimits.MAX_WIDTH_MAX) {
                issues +=
                    LayoutIssue(
                        code = 437,
                        severity = LayoutSeverity.Error,
                        elementId = name,
                        message = "maxWidth = $it is outside ${LayoutLimits.MAX_WIDTH_MIN}..${LayoutLimits.MAX_WIDTH_MAX}.",
                        hint = "Choose a value in range.",
                    )
            }
        }
        spec.aspectRatio?.let {
            if (!it.isFinite() || it < LayoutLimits.ASPECT_MIN || it > LayoutLimits.ASPECT_MAX) {
                issues +=
                    LayoutIssue(
                        code = 437,
                        severity = LayoutSeverity.Error,
                        elementId = name,
                        message = "aspectRatio = $it is outside ${LayoutLimits.ASPECT_MIN}..${LayoutLimits.ASPECT_MAX}.",
                        hint = "Choose a value in range.",
                    )
            }
        }
        if (diagram is ActDiagram &&
            spec.direction != null &&
            spec.direction != Sysml2LayoutFlow.Down &&
            model.definitions.any { it is ActivityPartitionDefinition && it.id in visible }
        ) {
            issues +=
                LayoutIssue(
                    code = 433,
                    severity = LayoutSeverity.Warning,
                    elementId = name,
                    message = "Swimlanes are drawn top to bottom; direction ${spec.direction} is not supported with partitions.",
                    hint = "Remove the direction or the partitions.",
                )
        }
        if (spec.sameLayer.size > Sysml2Limits.LAYOUT_GROUPS ||
            spec.sameLayer.any { it.size > Sysml2Limits.LAYOUT_IDS_PER_GROUP } ||
            spec.first.size > Sysml2Limits.LAYOUT_IDS_PER_GROUP ||
            spec.last.size > Sysml2Limits.LAYOUT_IDS_PER_GROUP
        ) {
            issues +=
                LayoutIssue(
                    code = 434,
                    severity = LayoutSeverity.Error,
                    elementId = name,
                    message = "Too many layout hint groups or ids.",
                    hint = "At most ${Sysml2Limits.LAYOUT_GROUPS} groups with ${Sysml2Limits.LAYOUT_IDS_PER_GROUP} ids each.",
                )
        }
        for (id in spec.first.intersect(spec.last.toSet())) {
            issues +=
                LayoutIssue(
                    code = 435,
                    severity = LayoutSeverity.Error,
                    elementId = id,
                    message = "'$id' is pinned to both the first and the last layer.",
                    hint = "Keep it in one list.",
                )
        }
        val edgePairs = HashSet<Pair<String, String>>()
        for (u in model.usages) {
            val ends = connectionEnds(u) ?: continue
            edgePairs += ends
            edgePairs += ends.second to ends.first
        }
        for (g in spec.sameLayer.take(Sysml2Limits.LAYOUT_GROUPS)) {
            val ids = g.take(Sysml2Limits.LAYOUT_IDS_PER_GROUP)
            var reported = false
            for (i in ids.indices) {
                for (j in i + 1 until ids.size) {
                    if (!reported && ids[i] to ids[j] in edgePairs) {
                        issues +=
                            LayoutIssue(
                                code = 436,
                                severity = LayoutSeverity.Warning,
                                elementId = ids[i],
                                message = "'${ids[i]}' and '${ids[j]}' share a layer but are connected.",
                                hint = "The edge then runs inside the layer.",
                            )
                        reported = true
                    }
                }
            }
        }
        return issues
    }

    private fun connectionEnds(u: Sysml2Usage): Pair<String, String>? =
        when (u) {
            is ControlFlowUsage -> u.sourceNodeId to u.targetNodeId
            is TransitionUsage -> u.sourceStateId to u.targetStateId
            else -> null
        }
}
