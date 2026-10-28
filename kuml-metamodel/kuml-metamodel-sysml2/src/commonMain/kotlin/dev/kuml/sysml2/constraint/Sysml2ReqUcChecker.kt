package dev.kuml.sysml2.constraint

import dev.kuml.sysml2.ActorDefinition
import dev.kuml.sysml2.ReqDiagram
import dev.kuml.sysml2.RequirementDefinition
import dev.kuml.sysml2.Sysml2Diagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.TestCaseDefinition
import dev.kuml.sysml2.UcDiagram
import dev.kuml.sysml2.UseCaseDefinition

/** Severity of a [ReqUcIssue]. */
public enum class ReqUcSeverity { Error, Warning, Hint }

/**
 * A finding of the requirement / use-case diagram checker (wave C1).
 *
 * @property code stable numeric code (201-217), see [Sysml2ReqUcChecker].
 * @property elementId id of the offending definition, edge or group.
 * @property hint how to fix it.
 */
public data class ReqUcIssue(
    val code: Int,
    val severity: ReqUcSeverity,
    val elementId: String,
    val message: String,
    val hint: String,
)

/**
 * Structural checker for SysML 2 requirement and use-case diagrams (wave C1).
 *
 * | Code | Severity | Rule |
 * |------|----------|------|
 * | 201 | Error | `copy` endpoint is not a requirement |
 * | 202 | Error | `copy` slave and master have different `text` |
 * | 203 | Error | `subjectRef` does not resolve to a definition |
 * | 204 | Error | Requirement groups nested deeper than [MAX_GROUP_DEPTH] (or a group cycle) |
 * | 205 | Error | Use case belongs to more than one subject frame |
 * | 206 | Error | Actor generalisation cycle |
 * | 207 | Error | Actor generalisation with a non-actor side |
 * | 208 | Error | Requirement belongs to more than one group |
 * | 209 | Error | Requirement / test-case title longer than [MAX_TITLE] characters |
 * | 210 | Error | Requirement text longer than [MAX_TEXT] characters |
 * | 211 | Error | Requirement source longer than [MAX_SOURCE] characters |
 * | 212 | Error | Use-case description longer than [MAX_DESCRIPTION] characters |
 * | 213 | Error | More than [MAX_GROUPS] groups in one diagram |
 * | 214 | Error | More than [MAX_SUBJECTS] subject frames in one diagram |
 * | 215 | Warning | `verify` source is neither a test case nor a use case |
 * | 216 | Warning | `refine` target is not a requirement |
 * | 217 | Hint | Use-case description will be longer than 4 lines (it is truncated in the diagram) |
 *
 * `trace` is deliberately unchecked. All traversals are iterative and bounded.
 */
public object Sysml2ReqUcChecker {
    public const val MAX_GROUP_DEPTH: Int = 3
    public const val MAX_TITLE: Int = 200
    public const val MAX_TEXT: Int = 2000
    public const val MAX_SOURCE: Int = 200
    public const val MAX_DESCRIPTION: Int = 1000
    public const val MAX_GROUPS: Int = 64
    public const val MAX_SUBJECTS: Int = 16

    /** Approximate characters per rendered description line, used only for the 4-line hint. */
    private const val APPROX_DESCRIPTION_LINE_CHARS: Int = 32
    private const val DESCRIPTION_HINT_LINES: Int = 4

    /**
     * Checks [diagram] (or, when `null`, every requirement and use-case diagram of [model]).
     * Findings are returned in a deterministic order.
     */
    public fun check(
        model: Sysml2Model,
        diagram: Sysml2Diagram? = null,
    ): List<ReqUcIssue> {
        val diagrams = if (diagram != null) listOf(diagram) else model.diagrams
        val issues = ArrayList<ReqUcIssue>()
        for (d in diagrams) {
            when (d) {
                is ReqDiagram -> checkReq(model = model, d = d, issues = issues)
                is UcDiagram -> checkUc(model = model, d = d, issues = issues)
                else -> Unit
            }
        }
        return issues
    }

    private fun checkReq(
        model: Sysml2Model,
        d: ReqDiagram,
        issues: MutableList<ReqUcIssue>,
    ) {
        val defs = model.definitions.associateBy { it.id }
        val visible = d.elementIds.toSet()

        // Limits on the visible definitions.
        for (id in d.elementIds) {
            val def = defs[id] ?: continue
            when (def) {
                is RequirementDefinition -> {
                    if (def.name.length + def.reqId.length >
                        MAX_TITLE
                    ) {
                        issues +=
                            err(
                                code = 209,
                                id = def.id,
                                message = "Title of '${def.name.take(40)}…' exceeds $MAX_TITLE characters.",
                                hint = "Shorten the name.",
                            )
                    }
                    if (def.text.length >
                        MAX_TEXT
                    ) {
                        issues +=
                            err(
                                code = 210,
                                id = def.id,
                                message = "Text of requirement '${def.name}' exceeds $MAX_TEXT characters.",
                                hint = "Shorten the text or split the requirement.",
                            )
                    }
                    if (def.source.length >
                        MAX_SOURCE
                    ) {
                        issues +=
                            err(
                                code = 211,
                                id = def.id,
                                message = "Source of requirement '${def.name}' exceeds $MAX_SOURCE characters.",
                                hint = "Shorten the source.",
                            )
                    }
                    val ref = def.subjectRef
                    if (ref != null && ref !in defs) {
                        issues +=
                            err(
                                code = 203,
                                id = def.id,
                                message = "subjectRef '$ref' of requirement '${def.name}' does not resolve to a definition.",
                                hint = "Reference an existing definition id.",
                            )
                    }
                }
                is TestCaseDefinition ->
                    if (def.name.length >
                        MAX_TITLE
                    ) {
                        issues +=
                            err(
                                code = 209,
                                id = def.id,
                                message = "Title of test case '${def.name.take(40)}…' exceeds $MAX_TITLE characters.",
                                hint = "Shorten the name.",
                            )
                    }
                else -> Unit
            }
        }

        // copy: both ends requirements, identical text.
        for (c in d.copies) {
            val slave = defs[c.sourceRequirementId]
            val master = defs[c.targetRequirementId]
            if (slave !is RequirementDefinition || master !is RequirementDefinition) {
                issues +=
                    err(
                        code = 201,
                        id = c.id,
                        message = "copy ${c.sourceRequirementId} -> ${c.targetRequirementId}: both ends must be requirements.",
                        hint = "Use requirement definitions as copy endpoints.",
                    )
            } else if (slave.text != master.text) {
                issues +=
                    err(
                        code = 202,
                        id = c.id,
                        message = "copy: requirement '${slave.name}' has a different text than its master '${master.name}'.",
                        hint = "Make the texts identical or use derive instead.",
                    )
            }
        }

        // verify / refine type warnings.
        for (v in d.verifies) {
            val src = defs[v.sourceId] ?: continue
            if (src !is TestCaseDefinition && src !is UseCaseDefinition) {
                issues +=
                    warn(
                        code = 215,
                        id = v.id,
                        message = "verify source '${src.name}' is neither a test case nor a use case.",
                        hint = "Verify requirements with a testCaseDef (or a use case).",
                    )
            }
        }
        for (r in d.refines) {
            val target = defs[r.requirementId] ?: continue
            if (target !is RequirementDefinition) {
                issues +=
                    warn(
                        code = 216,
                        id = r.id,
                        message = "refine target '${target.name}' is not a requirement.",
                        hint = "Refine a requirement.",
                    )
            }
        }

        // groups
        if (d.groups.size > MAX_GROUPS) {
            issues +=
                err(code = 213, id = d.name, message = "Diagram has ${d.groups.size} groups (maximum $MAX_GROUPS).", hint = "Merge groups.")
        }
        val groups = d.groups.take(MAX_GROUPS)
        val byId = groups.associateBy { it.id }
        val parentOf = HashMap<String, String>()
        for (g in groups) for (c in g.childGroupIds) if (c in byId && c != g.id && c !in parentOf) parentOf[c] = g.id
        for (g in groups) {
            var depth = 1
            var cur = g.id
            val seen = HashSet<String>()
            seen += cur
            var cyc = false
            while (true) {
                val p = parentOf[cur] ?: break
                if (!seen.add(p)) {
                    cyc = true
                    break
                }
                depth++
                cur = p
            }
            if (cyc || depth > MAX_GROUP_DEPTH) {
                issues +=
                    err(
                        code = 204,
                        id = g.id,
                        message = "Group '${g.name}' is nested too deep (maximum $MAX_GROUP_DEPTH levels) or part of a cycle.",
                        hint = "Flatten the group hierarchy.",
                    )
            }
        }
        val groupOf = HashMap<String, String>()
        for (g in groups) {
            for (m in g.memberIds) {
                val prev = groupOf[m]
                if (prev == null) groupOf[m] = g.id
                if (prev != null && prev != g.id && m in visible) {
                    issues +=
                        err(
                            code = 208,
                            id = m,
                            message = "Element '$m' is a member of the groups '$prev' and '${g.id}'.",
                            hint = "Put each element in at most one group.",
                        )
                }
            }
        }
    }

    private fun checkUc(
        model: Sysml2Model,
        d: UcDiagram,
        issues: MutableList<ReqUcIssue>,
    ) {
        val defs = model.definitions.associateBy { it.id }

        for (id in d.elementIds) {
            val uc = defs[id] as? UseCaseDefinition ?: continue
            if (uc.description.length > MAX_DESCRIPTION) {
                issues +=
                    err(
                        code = 212,
                        id = uc.id,
                        message = "Description of use case '${uc.name}' exceeds $MAX_DESCRIPTION characters.",
                        hint = "Shorten the description.",
                    )
            } else if (descriptionLines(uc.description) > DESCRIPTION_HINT_LINES) {
                issues +=
                    hint(
                        code = 217,
                        id = uc.id,
                        message =
                            "Description of use case '${uc.name}' is longer than $DESCRIPTION_HINT_LINES lines; " +
                                "the diagram shows only the first lines.",
                        hint = "Shorten the description.",
                    )
            }
        }

        if (d.subjects.size > MAX_SUBJECTS) {
            issues +=
                err(
                    code = 214,
                    id = d.name,
                    message = "Diagram has ${d.subjects.size} subject frames (maximum $MAX_SUBJECTS).",
                    hint = "Merge subject frames.",
                )
        }
        val owner = HashMap<String, String>()
        for (s in d.subjects.take(MAX_SUBJECTS)) {
            for (uc in s.useCaseIds) {
                val prev = owner[uc]
                if (prev == null) owner[uc] = s.id
                if (prev != null && prev != s.id) {
                    issues +=
                        err(
                            code = 205,
                            id = uc,
                            message = "Use case '$uc' is inside the subject frames '$prev' and '${s.id}'.",
                            hint = "Put a use case into at most one subject frame.",
                        )
                }
            }
        }

        // generalisation: actor sides + cycles
        val adjacency = LinkedHashMap<String, MutableList<String>>()
        for (g in d.actorGeneralizations) {
            val specific = defs[g.specificId]
            val general = defs[g.generalId]
            if (specific !is ActorDefinition || general !is ActorDefinition) {
                issues +=
                    err(
                        code = 207,
                        id = g.id,
                        message = "Generalisation ${g.specificId} -> ${g.generalId}: both sides must be actors.",
                        hint = "Generalise actors only.",
                    )
                continue
            }
            adjacency.getOrPut(g.specificId) { ArrayList() } += g.generalId
        }
        if (hasCycle(adjacency)) {
            issues +=
                err(
                    code = 206,
                    id = d.name,
                    message = "The actor generalisations contain a cycle.",
                    hint = "Remove one generalisation so the hierarchy is acyclic.",
                )
        }
    }

    private fun descriptionLines(text: String): Int {
        if (text.isBlank()) return 0
        return text.lines().sumOf { l -> maxOf(1, (l.length + APPROX_DESCRIPTION_LINE_CHARS - 1) / APPROX_DESCRIPTION_LINE_CHARS) }
    }

    /** Iterative three-colour DFS; `true` if the directed graph has a cycle. */
    private fun hasCycle(adj: Map<String, List<String>>): Boolean {
        val state = HashMap<String, Int>() // 1 = on stack, 2 = done
        for (start in adj.keys) {
            if (state[start] == 2) continue
            val stack = ArrayDeque<Pair<String, Int>>()
            stack.addLast(start to 0)
            state[start] = 1
            while (stack.isNotEmpty()) {
                val (node, idx) = stack.removeLast()
                val next = adj[node].orEmpty()
                if (idx < next.size) {
                    stack.addLast(node to idx + 1)
                    val n = next[idx]
                    when (state[n]) {
                        1 -> return true
                        2 -> Unit
                        else -> {
                            state[n] = 1
                            stack.addLast(n to 0)
                        }
                    }
                } else {
                    state[node] = 2
                }
            }
        }
        return false
    }

    private fun err(
        code: Int,
        id: String,
        message: String,
        hint: String,
    ) = ReqUcIssue(code = code, severity = ReqUcSeverity.Error, elementId = id, message = message, hint = hint)

    private fun warn(
        code: Int,
        id: String,
        message: String,
        hint: String,
    ) = ReqUcIssue(code = code, severity = ReqUcSeverity.Warning, elementId = id, message = message, hint = hint)

    private fun hint(
        code: Int,
        id: String,
        message: String,
        hint: String,
    ) = ReqUcIssue(code = code, severity = ReqUcSeverity.Hint, elementId = id, message = message, hint = hint)
}
