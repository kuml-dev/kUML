package dev.kuml.sysml2.constraint

import dev.kuml.sysml2.ActionDefinition
import dev.kuml.sysml2.AnalysisCaseDefinition
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.BehaviorRef
import dev.kuml.sysml2.CalculationDefinition
import dev.kuml.sysml2.PartDefinition
import dev.kuml.sysml2.StateDefinition
import dev.kuml.sysml2.Sysml2Limits
import dev.kuml.sysml2.Sysml2Model

/**
 * A finding of the analysis checker (wave 2I-b). Every finding is an error.
 *
 * @property code stable code, e.g. `SYSML2_CALC_REF_UNKNOWN`.
 * @property elementId id of the offending definition.
 * @property hint how to fix it.
 */
public data class AnalysisIssue(
    val code: String,
    val elementId: String,
    val message: String,
    val hint: String,
)

/**
 * Structural checker for analysis cases, calculations and `perform` / `exhibit` references (wave 2I-b).
 *
 * | Code | Rule |
 * |------|------|
 * | SYSML2_CALC_REF_UNKNOWN | a `calc` of an analysis points at nothing, or at a definition that is no calculation |
 * | SYSML2_ANALYSIS_SUBJECT_UNKNOWN | the `subject` of an analysis points at no existing definition |
 * | SYSML2_PERFORM_TARGET_NOT_ACTION | a `perform` of a part points at nothing, or at a definition that is no action |
 * | SYSML2_EXHIBIT_TARGET_NOT_STATE | an `exhibit` of a part points at nothing, or at a definition that is no state |
 * | SYSML2_DUPLICATE_USAGE_NAME | two `calc`s, `perform`s or `exhibit`s of one definition share a name |
 * | SYSML2_ANALYSIS_LIMIT | a list is longer than [Sysml2Limits.CALC_ENTRIES] |
 *
 * Models without the new constructs produce no findings. Findings come in declaration order.
 */
public object Sysml2AnalysisChecker {
    /** Checks all definitions of [model], or only those listed by [diagram] when one is given. */
    public fun check(
        model: Sysml2Model,
        diagram: BdDiagram? = null,
    ): List<AnalysisIssue> {
        val issues = mutableListOf<AnalysisIssue>()
        val byId = model.definitions.associateBy { it.id }
        val visible = diagram?.elementIds?.toSet()

        fun duplicates(
            owner: String,
            kind: String,
            names: List<String>,
        ) {
            val seen = HashSet<String>()
            for (n in names) {
                if (!seen.add(n)) {
                    issues +=
                        AnalysisIssue(
                            code = "SYSML2_DUPLICATE_USAGE_NAME",
                            elementId = owner,
                            message = "Definition '$owner' declares the $kind name '$n' more than once.",
                            hint = "Give every $kind of a definition its own name.",
                        )
                }
            }
        }

        fun limit(
            owner: String,
            kind: String,
            size: Int,
        ) {
            if (size > Sysml2Limits.CALC_ENTRIES) {
                issues +=
                    AnalysisIssue(
                        code = "SYSML2_ANALYSIS_LIMIT",
                        elementId = owner,
                        message = "Definition '$owner' has more than ${Sysml2Limits.CALC_ENTRIES} $kind.",
                        hint = "Split the definition.",
                    )
            }
        }

        fun behaviours(
            owner: String,
            refs: List<BehaviorRef>,
            kind: String,
            ok: (Any?) -> Boolean,
            code: String,
            expected: String,
        ) {
            limit(owner = owner, kind = "${kind}s", size = refs.size)
            duplicates(owner = owner, kind = kind, names = refs.map { it.name })
            for (r in refs.take(Sysml2Limits.CALC_ENTRIES)) {
                if (!ok(byId[r.definitionId])) {
                    issues +=
                        AnalysisIssue(
                            code = code,
                            elementId = owner,
                            message = "$kind '${r.name}' of '$owner' points at '${r.definitionId}', which is not $expected.",
                            hint = "Use the id of $expected.",
                        )
                }
            }
        }

        for (def in model.definitions) {
            if (visible != null && def.id !in visible) continue
            when (def) {
                is AnalysisCaseDefinition -> {
                    limit(owner = def.id, kind = "calcs", size = def.calculations.size)
                    duplicates(owner = def.id, kind = "calc", names = def.calculations.map { it.name })
                    for (c in def.calculations.take(Sysml2Limits.CALC_ENTRIES)) {
                        if (byId[c.definitionId] !is CalculationDefinition) {
                            issues +=
                                AnalysisIssue(
                                    code = "SYSML2_CALC_REF_UNKNOWN",
                                    elementId = def.id,
                                    message =
                                        "calc '${c.name}' of '${def.id}' points at '${c.definitionId}', " +
                                            "which is not a calculation definition.",
                                    hint = "Declare a calcDef and use its id.",
                                )
                        }
                    }
                    def.subjectId?.let { s ->
                        if (byId[s] == null) {
                            issues +=
                                AnalysisIssue(
                                    code = "SYSML2_ANALYSIS_SUBJECT_UNKNOWN",
                                    elementId = def.id,
                                    message = "The subject '$s' of analysis '${def.id}' does not exist.",
                                    hint = "Use the id of an existing definition.",
                                )
                        }
                    }
                }
                is PartDefinition -> {
                    behaviours(
                        owner = def.id,
                        refs = def.performs,
                        kind = "perform",
                        ok = { it is ActionDefinition },
                        code = "SYSML2_PERFORM_TARGET_NOT_ACTION",
                        expected = "an action definition",
                    )
                    behaviours(
                        owner = def.id,
                        refs = def.exhibits,
                        kind = "exhibit",
                        ok = { it is StateDefinition },
                        code = "SYSML2_EXHIBIT_TARGET_NOT_STATE",
                        expected = "a state definition",
                    )
                }
                is CalculationDefinition ->
                    limit(owner = def.id, kind = "parameters", size = def.parameters.size)
                else -> Unit
            }
        }
        return issues
    }
}
