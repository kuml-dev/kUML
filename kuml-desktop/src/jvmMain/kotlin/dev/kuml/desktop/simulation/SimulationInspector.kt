package dev.kuml.desktop.simulation

import dev.kuml.core.model.KumlEvalContext
import dev.kuml.expr.AttributeRef
import dev.kuml.expr.BinaryOp
import dev.kuml.expr.FunctionCall
import dev.kuml.expr.KumlExpression
import dev.kuml.expr.OclLikeExpressionParser
import dev.kuml.expr.UnaryOp
import dev.kuml.runtime.Event
import dev.kuml.runtime.GuardEvaluator
import dev.kuml.runtime.GuardResult
import dev.kuml.runtime.ModelInstance
import dev.kuml.runtime.TraceEntry
import dev.kuml.runtime.toRuntimeValue
import dev.kuml.uml.UmlState
import dev.kuml.uml.UmlStateMachine
import dev.kuml.uml.UmlTransition
import dev.kuml.uml.UmlVertex
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.coroutineContext

// Pure (Compose-free) logic behind the simulator's inspector panel: which transitions are
// reachable from the active states, what their guards evaluate to against a FROZEN copy of the
// instance, and why an event is not enabled. Nothing here touches the running instance.

/** At most this many transitions are listed (bounds the work per evaluation round). */
internal const val MAX_INSPECTOR_ROWS: Int = 100

/** At most this many characters of one guard are scanned for variable references. */
internal const val MAX_SCANNED_GUARD_CHARS: Int = 16_384

/** At most this many distinct variables are read out of one guard. */
internal const val MAX_READ_VARIABLES: Int = 32

/** Where a guard reads a value from: the instance variables (`vars.x`) or the event payload (`event.x`). */
internal enum class VarRoot { VARS, EVENT }

internal data class VariableRef(
    val root: VarRoot,
    val name: String,
)

private val VARIABLE_REF_REGEX = Regex("""\b(vars|event)\.([A-Za-z_][A-Za-z0-9_]*)""")

/**
 * Distinct `vars.<name>` / `event.<name>` references of [guard], in order of first occurrence,
 * capped at [MAX_READ_VARIABLES].
 *
 * Walks the typed AST iteratively (a guard with thousands of chained operands must not overflow
 * the stack); a guard the AST parser cannot handle falls back to a linear regex scan.
 */
internal fun readVariables(guard: String): List<VariableRef> {
    // A guard comes from the script and has no length limit of its own; scanning is bounded.
    val bounded = guard.take(MAX_SCANNED_GUARD_CHARS)
    val cleaned =
        bounded.trim().let {
            if (it.length >= 2 &&
                it.startsWith("[") &&
                it.endsWith("]")
            ) {
                it.substring(1, it.length - 1)
            } else {
                it
            }
        }
    val ast =
        try {
            OclLikeExpressionParser.tryParse(input = cleaned)
        } catch (_: StackOverflowError) {
            null
        }
    val found = LinkedHashSet<VariableRef>()
    if (ast != null) {
        val stack = ArrayDeque<KumlExpression>()
        stack.addFirst(ast)
        while (stack.isNotEmpty() && found.size < MAX_READ_VARIABLES) {
            when (val node = stack.removeFirst()) {
                is AttributeRef -> {
                    val root =
                        when (node.path.firstOrNull()) {
                            "vars" -> VarRoot.VARS
                            "event" -> VarRoot.EVENT
                            else -> null
                        }
                    if (root != null && node.path.size >= 2) found += VariableRef(root = root, name = node.path[1])
                }
                is BinaryOp -> {
                    stack.addFirst(node.right)
                    stack.addFirst(node.left)
                }
                is UnaryOp -> stack.addFirst(node.operand)
                is FunctionCall -> node.args.asReversed().forEach { stack.addFirst(it) }
                else -> Unit
            }
        }
    } else {
        for (m in VARIABLE_REF_REGEX.findAll(cleaned)) {
            if (found.size >= MAX_READ_VARIABLES) break
            found += VariableRef(root = if (m.groupValues[1] == "vars") VarRoot.VARS else VarRoot.EVENT, name = m.groupValues[2])
        }
    }
    return found.toList()
}

/** One transition reachable from the active states, as shown in the inspector. */
internal data class InspectorRow(
    val transition: UmlTransition,
    /** Event name of the trigger, or `null` for a completion transition. */
    val triggerName: String?,
    /** The vertex the transition is declared on (its source). */
    val ownerVertexId: String,
    /** The owner's id when the transition is inherited from a composite ancestor, else `null`. */
    val inheritedFrom: String?,
    /** `true` when the guard differs from the original model's guard. */
    val edited: Boolean,
    /** The original guard when [edited]; may be `null` ("had no guard"). */
    val originalGuard: String?,
)

/** Transitions leaving the active states or their composite ancestors, innermost state first, capped at [MAX_INSPECTOR_ROWS]. */
internal fun inspectorRows(
    model: UmlStateMachine,
    initialModel: UmlStateMachine,
    activeVertexIds: Set<String>,
): List<InspectorRow> {
    if (activeVertexIds.isEmpty()) return emptyList()
    val vertexIds = flattenVertices(model).map { it.id }.toSet()
    val active = activeVertexIds.filter { it in vertexIds }
    val leaves = leafIds(model = model, activeVertexIds = active)
    val owners = LinkedHashSet<String>()
    for (leaf in leaves) owners += ancestorChain(model = model, vertexId = leaf)
    val original = initialModel.transitions.associate { it.id to it.guard?.ifBlank { null } }
    val rows = ArrayList<InspectorRow>()
    for (owner in owners) {
        for (t in model.transitions) {
            if (t.sourceId != owner) continue
            val originalGuard = original[t.id]
            val edited = original.containsKey(t.id) && originalGuard != t.guard?.ifBlank { null }
            rows +=
                InspectorRow(
                    transition = t,
                    triggerName =
                        t.trigger
                            ?.substringBefore('(')
                            ?.trim()
                            ?.takeIf { it.isNotBlank() },
                    ownerVertexId = owner,
                    inheritedFrom = owner.takeIf { it !in leaves },
                    edited = edited,
                    originalGuard = if (edited) originalGuard else null,
                )
            if (rows.size >= MAX_INSPECTOR_ROWS) return rows
        }
    }
    return rows
}

/** Active vertices that are not the parent of another active vertex: the innermost states. */
internal fun leafIds(
    model: UmlStateMachine,
    activeVertexIds: Collection<String>,
): Set<String> {
    val active = activeVertexIds.toSet()
    val parentsOfActive = active.mapNotNull { id -> ancestorChain(model = model, vertexId = id).getOrNull(1) }.toSet()
    return active.filterTo(LinkedHashSet()) { it !in parentsOfActive }
}

/** Every vertex of [model], including nested substates. */
internal fun flattenVertices(model: UmlStateMachine): List<UmlVertex> {
    val result = ArrayList<UmlVertex>()
    val stack = ArrayDeque<UmlVertex>()
    model.vertices.asReversed().forEach { stack.addFirst(it) }
    while (stack.isNotEmpty()) {
        val v = stack.removeFirst()
        result += v
        if (v is UmlState) v.substates.asReversed().forEach { stack.addFirst(it) }
    }
    return result
}

/** Result of evaluating one guard against the frozen inspector copy. */
internal sealed interface GuardEvaluation {
    data object True : GuardEvaluation

    data object False : GuardEvaluation

    data class Error(
        val message: String,
    ) : GuardEvaluation

    /** Not evaluated yet (a new round is running). */
    data object Pending : GuardEvaluation
}

internal data class VariableRead(
    val ref: VariableRef,
    /** `null` when the variable is not set. */
    val value: JsonElement?,
)

internal data class FalseCandidate(
    val transitionId: String,
    val expression: String,
    val reads: List<VariableRead>,
)

/** Why an event is not enabled in the current state. */
internal sealed interface WhyNot {
    val eventName: String

    /** No transition for the event leaves the active states (or their ancestors). */
    data class NoTransition(
        override val eventName: String,
        val stateIds: List<String>,
    ) : WhyNot

    /** Candidates exist, but every guard is false. */
    data class GuardFalse(
        override val eventName: String,
        val candidates: List<FalseCandidate>,
    ) : WhyNot

    /** No candidate fired and a guard could not be evaluated (error or sandbox timeout). */
    data class GuardError(
        override val eventName: String,
        val transitionId: String,
        val message: String,
    ) : WhyNot
}

/**
 * Explains every event of [eventNames] that is not enabled.
 *
 * Rule: no candidate leaves the active states: [WhyNot.NoTransition]. Any candidate that is true
 * (or has no guard): the event is enabled and not listed. Otherwise an evaluation error wins
 * ([WhyNot.GuardError]), else [WhyNot.GuardFalse] with every false candidate. Events whose
 * evaluation is still pending are not listed (no flicker).
 */
internal fun whyNotExplanations(
    model: UmlStateMachine,
    activeVertexIds: Set<String>,
    eventNames: List<String>,
    evaluations: Map<String, GuardEvaluation>,
    variables: Map<String, JsonElement>,
    payload: JsonObject,
): List<WhyNot> {
    val reachable = withAncestors(model = model, activeVertexIds = activeVertexIds)
    val leaves = leafIds(model = model, activeVertexIds = activeVertexIds).toList()
    val result = ArrayList<WhyNot>()
    for (eventName in eventNames) {
        val candidates =
            model.transitions.filter { t ->
                t.sourceId in reachable && t.trigger?.substringBefore('(')?.trim() == eventName
            }
        if (candidates.isEmpty()) {
            result += WhyNot.NoTransition(eventName = eventName, stateIds = leaves)
            continue
        }
        val states =
            candidates.map { t ->
                if (t.guard.isNullOrBlank()) {
                    GuardEvaluation.True
                } else {
                    evaluations[t.id]
                        ?: GuardEvaluation.Pending
                }
            }
        if (states.any { it == GuardEvaluation.True }) continue
        if (states.any { it == GuardEvaluation.Pending }) continue
        val failedIndex = states.indexOfFirst { it is GuardEvaluation.Error }
        if (failedIndex >= 0) {
            val error = states[failedIndex] as GuardEvaluation.Error
            result += WhyNot.GuardError(eventName = eventName, transitionId = candidates[failedIndex].id, message = error.message)
            continue
        }
        result +=
            WhyNot.GuardFalse(
                eventName = eventName,
                candidates =
                    candidates.map { t ->
                        val expression = t.guard.orEmpty()
                        FalseCandidate(
                            transitionId = t.id,
                            expression = expression,
                            reads =
                                readVariables(expression).map { ref ->
                                    VariableRead(
                                        ref = ref,
                                        value =
                                            when (ref.root) {
                                                VarRoot.VARS -> variables[ref.name]
                                                VarRoot.EVENT -> payload[ref.name]
                                            },
                                    )
                                },
                        )
                    },
            )
    }
    return result
}

/**
 * Frozen, read-only copy of an instance used to evaluate guards without touching (or racing with)
 * the running one. Variables are converted with the runtime's own JSON mapping.
 */
internal class InspectorEvalInstance(
    override val model: UmlStateMachine,
    activeVertexIds: Collection<String>,
    variables: Map<String, JsonElement>,
) : ModelInstance<UmlStateMachine>,
    KumlEvalContext {
    private val byId = flattenVertices(model).associateBy { it.id }

    override val currentVertices: List<UmlVertex> = activeVertexIds.mapNotNull { byId[it] }
    override val variables: MutableMap<String, Any?> = LinkedHashMap(variables.mapValues { (_, v) -> v.toRuntimeValue() })
    override val trace: List<TraceEntry> = emptyList()
    override val isTerminated: Boolean = false
    override val currentVertexIds: List<String> = currentVertices.map { it.id }
}

/**
 * Evaluates the guard of every [rows] entry (blank guards are skipped, they are simply true)
 * against a frozen copy. Blocking: call from a background dispatcher. [evaluator] is the
 * session's sandboxed one (time limit per guard), never a bypass.
 */
internal suspend fun evaluateInspectorGuards(
    evaluator: GuardEvaluator,
    model: UmlStateMachine,
    rows: List<InspectorRow>,
    activeVertexIds: Set<String>,
    variables: Map<String, JsonElement>,
    payload: JsonObject,
): Map<String, GuardEvaluation> {
    val instance = InspectorEvalInstance(model = model, activeVertexIds = activeVertexIds, variables = variables)
    val result = LinkedHashMap<String, GuardEvaluation>()
    for (row in rows) {
        val guard = row.transition.guard
        if (guard.isNullOrBlank()) continue
        coroutineContext.ensureActive()
        val event = Event(name = row.triggerName.orEmpty(), payload = payload)
        result[row.transition.id] =
            when (val r = evaluator.evaluate(guard = guard, instance = instance, event = event)) {
                GuardResult.True -> GuardEvaluation.True
                GuardResult.False -> GuardEvaluation.False
                is GuardResult.Failed -> GuardEvaluation.Error(r.message)
            }
    }
    return result
}
