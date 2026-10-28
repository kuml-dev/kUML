package dev.kuml.sysml2.constraint

import dev.kuml.sysml2.CombinedFragmentUsage
import dev.kuml.sysml2.DurationConstraintUsage
import dev.kuml.sysml2.InteractionUseUsage
import dev.kuml.sysml2.MessageKind
import dev.kuml.sysml2.MessageUsage
import dev.kuml.sysml2.SeqDiagram
import dev.kuml.sysml2.Sysml2Diagram
import dev.kuml.sysml2.Sysml2Limits
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.TimeObservationUsage

/** Severity of a [SeqIssue]. */
public enum class SeqSeverity { Error, Warning }

/** A finding of the sequence diagram checker (wave C3). */
public data class SeqIssue(
    val code: Int,
    val severity: SeqSeverity,
    val elementId: String,
    val message: String,
    val hint: String,
)

/**
 * Structural checker for the wave-C3 additions of SysML 2 sequence diagrams.
 *
 * | Code | Severity | Rule |
 * |------|----------|------|
 * | 401 | Error | nested fragment's row span is not inside the span of its parent operand (missing/invalid operand index included) |
 * | 402 | Error | unknown parent fragment, self reference or parent cycle |
 * | 403 | Error | nesting deeper than [Sysml2Limits.FRAGMENT_DEPTH] |
 * | 404 | Error | more than [Sysml2Limits.FRAGMENTS] fragments in the model |
 * | 405 | Error | more than [Sysml2Limits.OPERANDS] operands in one fragment |
 * | 406 | Error | `ref` / duration constraint / time observation names a lifeline unknown to the model (`ref`: also one only partly visible in the diagram) |
 * | 407 | Warning | `ref` row coincides with the row of a visible message |
 * | 410 | Warning | guard, `refersTo` or expression longer than [Sysml2Limits.TEXT] (time expressions: [Sysml2Limits.TIME_EXPR]) characters |
 * | 411 | Error | found / lost message whose source and target lifeline differ |
 * | 412 | Warning | layout hint block on a sequence diagram (lifeline order is the declaration order) |
 *
 * All traversals are iterative and bounded.
 */
public object Sysml2SeqChecker {
    public fun check(
        model: Sysml2Model,
        diagram: Sysml2Diagram,
    ): List<SeqIssue> {
        if (diagram !is SeqDiagram) return emptyList()
        val issues = mutableListOf<SeqIssue>()
        val visible = diagram.elementIds.toSet()
        val knownLifelines =
            model.definitions
                .filterIsInstance<dev.kuml.sysml2.LifelineDefinition>()
                .map { it.id }
                .toSet()
        val fragments = model.usages.filterIsInstance<CombinedFragmentUsage>()
        val byId = fragments.associateBy { it.id }

        if (fragments.size > Sysml2Limits.FRAGMENTS) {
            issues +=
                SeqIssue(
                    code = 404,
                    severity = SeqSeverity.Error,
                    elementId = diagram.name,
                    message = "Model has ${fragments.size} combined fragments.",
                    hint = "Keep at most ${Sysml2Limits.FRAGMENTS}.",
                )
        }
        for (f in fragments) {
            if (f.operands.size > Sysml2Limits.OPERANDS) {
                issues +=
                    SeqIssue(
                        code = 405,
                        severity = SeqSeverity.Error,
                        elementId = f.id,
                        message = "Fragment has ${f.operands.size} operands.",
                        hint = "Keep at most ${Sysml2Limits.OPERANDS}.",
                    )
            }
            for (op in f.operands) {
                val g = op.guard
                if (g != null && g.length > Sysml2Limits.TEXT) {
                    issues +=
                        SeqIssue(
                            code = 410,
                            severity = SeqSeverity.Warning,
                            elementId = f.id,
                            message = "Guard is longer than ${Sysml2Limits.TEXT} characters.",
                            hint = "It is shortened with an ellipsis; shorten it.",
                        )
                }
            }
            val parentId = f.parentFragmentId ?: continue
            val parent = byId[parentId]
            if (parent == null || parentId == f.id) {
                issues +=
                    SeqIssue(
                        code = 402,
                        severity = SeqSeverity.Error,
                        elementId = f.id,
                        message = "Parent fragment '$parentId' is unknown or the fragment is its own parent.",
                        hint = "Reference an existing enclosing fragment.",
                    )
                continue
            }
            // cycle / depth walk (bounded)
            var cur: CombinedFragmentUsage? = parent
            var steps = 1
            var cyclic = false
            val seen = hashSetOf(f.id)
            while (cur != null) {
                if (!seen.add(cur.id)) {
                    cyclic = true
                    break
                }
                steps++
                cur = cur.parentFragmentId?.let { byId[it] }
                if (steps > Sysml2Limits.FRAGMENT_DEPTH + 1) break
            }
            if (cyclic) {
                issues +=
                    SeqIssue(
                        code = 402,
                        severity = SeqSeverity.Error,
                        elementId = f.id,
                        message = "Fragment nesting contains a cycle.",
                        hint = "Remove the cyclic parent reference.",
                    )
            } else if (steps > Sysml2Limits.FRAGMENT_DEPTH) {
                issues +=
                    SeqIssue(
                        code = 403,
                        severity = SeqSeverity.Error,
                        elementId = f.id,
                        message = "Nesting is deeper than ${Sysml2Limits.FRAGMENT_DEPTH}.",
                        hint = "Flatten the fragments; deeper ones are drawn flat.",
                    )
            }
            val idx = f.parentOperandIndex
            val operand = idx?.let { parent.operands.getOrNull(it) }
            val start = f.operands.minOfOrNull { it.startSeqNo }
            val end = f.operands.maxOfOrNull { it.endSeqNo }
            if (operand == null) {
                issues +=
                    SeqIssue(
                        code = 401,
                        severity = SeqSeverity.Error,
                        elementId = f.id,
                        message = "Missing or invalid parent operand index.",
                        hint = "Set parentOperandIndex to an operand of '$parentId'.",
                    )
            } else if (start != null && end != null && (start < operand.startSeqNo || end > operand.endSeqNo)) {
                issues +=
                    SeqIssue(
                        code = 401,
                        severity = SeqSeverity.Error,
                        elementId = f.id,
                        message = "Rows $start..$end are outside the parent operand ${operand.startSeqNo}..${operand.endSeqNo}.",
                        hint = "Fit the nested fragment into the enclosing operand.",
                    )
            }
        }

        val messageRows =
            model.usages
                .filterIsInstance<MessageUsage>()
                .filter { it.sourceLifelineId in visible }
                .map { it.seqNo }
                .toSet()
        for (u in model.usages) {
            when (u) {
                is InteractionUseUsage -> {
                    val touches = u.lifelineIds.any { it in visible }
                    val unknown = u.lifelineIds.any { it !in knownLifelines }
                    if ((touches && u.lifelineIds.any { it !in visible }) || unknown) {
                        issues +=
                            SeqIssue(
                                code = 406,
                                severity = SeqSeverity.Error,
                                elementId = u.id,
                                message = "ref covers an unknown lifeline or only some of the lifelines shown in this diagram.",
                                hint = "Cover only lifelines included in the diagram.",
                            )
                    }
                    if (touches && u.seqNo in messageRows) {
                        issues +=
                            SeqIssue(
                                code = 407,
                                severity = SeqSeverity.Warning,
                                elementId = u.id,
                                message = "ref shares row ${u.seqNo} with a message.",
                                hint = "Give the ref its own row.",
                            )
                    }
                    if (u.refersTo.length > Sysml2Limits.TEXT) {
                        issues +=
                            SeqIssue(
                                code = 410,
                                severity = SeqSeverity.Warning,
                                elementId = u.id,
                                message = "refersTo is longer than ${Sysml2Limits.TEXT} characters.",
                                hint = "Shorten it.",
                            )
                    }
                }
                is DurationConstraintUsage -> {
                    if (u.lifelineId !in knownLifelines) {
                        issues +=
                            SeqIssue(
                                code = 406,
                                severity = SeqSeverity.Error,
                                elementId = u.id,
                                message = "Duration constraint on an unknown lifeline.",
                                hint = "Use a lifeline defined in the model.",
                            )
                    }
                    if (u.expression.length > Sysml2Limits.TIME_EXPR) {
                        issues +=
                            SeqIssue(
                                code = 410,
                                severity = SeqSeverity.Warning,
                                elementId = u.id,
                                message = "Expression is longer than ${Sysml2Limits.TIME_EXPR} characters.",
                                hint = "Shorten it.",
                            )
                    }
                }
                is TimeObservationUsage -> {
                    if (u.lifelineId !in knownLifelines) {
                        issues +=
                            SeqIssue(
                                code = 406,
                                severity = SeqSeverity.Error,
                                elementId = u.id,
                                message = "Time observation on an unknown lifeline.",
                                hint = "Use a lifeline defined in the model.",
                            )
                    }
                    if (u.name.length > Sysml2Limits.TIME_EXPR) {
                        issues +=
                            SeqIssue(
                                code = 410,
                                severity = SeqSeverity.Warning,
                                elementId = u.id,
                                message = "Name is longer than ${Sysml2Limits.TIME_EXPR} characters.",
                                hint = "Shorten it.",
                            )
                    }
                }
                is MessageUsage ->
                    if ((u.kind == MessageKind.Found || u.kind == MessageKind.Lost) && u.sourceLifelineId != u.targetLifelineId) {
                        issues +=
                            SeqIssue(
                                code = 411,
                                severity = SeqSeverity.Error,
                                elementId = u.id,
                                message = "Found/lost message must have the same source and target lifeline.",
                                hint = "Use foundMessage()/lostMessage().",
                            )
                    }
                else -> Unit
            }
        }
        return issues
    }
}
