package dev.kuml.runtime.internal

import dev.kuml.uml.UmlState
import dev.kuml.uml.UmlStateMachine
import dev.kuml.uml.UmlVertex

/** Synthetic root id used by [buildParentOf] to terminate ancestor walks. */
internal const val SYNTHETIC_ROOT_ID: String = "__root__"

/**
 * Returns `triggerRaw` truncated at the first `(` (e.g. `"confirm(amount)"` → `"confirm"`).
 * `null` or blank input returns `null`.
 */
internal fun triggerName(triggerRaw: String?): String? = triggerRaw?.substringBefore('(')?.trim()?.takeIf { it.isNotBlank() }

/**
 * Builds the child → parent map keyed by vertex id. Synthetic root entries for
 * top-level vertices map to [SYNTHETIC_ROOT_ID].
 */
internal fun buildParentOf(sm: UmlStateMachine): Map<String, String> {
    val parents = mutableMapOf<String, String>()
    for (v in sm.vertices) {
        parents[v.id] = SYNTHETIC_ROOT_ID
        if (v is UmlState) descend(v, parents)
    }
    return parents
}

private fun descend(
    state: UmlState,
    parents: MutableMap<String, String>,
) {
    for (sub in state.substates) {
        parents[sub.id] = state.id
        if (sub is UmlState) descend(sub, parents)
    }
}

/** All vertices (flat) including substates. Used by lookup map. */
internal fun allVertices(sm: UmlStateMachine): List<UmlVertex> {
    val out = mutableListOf<UmlVertex>()

    fun visit(v: UmlVertex) {
        out += v
        if (v is UmlState) for (sub in v.substates) visit(sub)
    }
    for (v in sm.vertices) visit(v)
    return out
}

/**
 * Computes the lowest common ancestor of two vertex ids using the parentOf map.
 * Returns [SYNTHETIC_ROOT_ID] if no shared ancestor below the root exists.
 */
internal fun lowestCommonAncestor(
    aId: String,
    bId: String,
    parentOf: Map<String, String>,
): String {
    val ancestorsOfA = mutableSetOf<String>()
    var cur: String? = aId
    while (cur != null) {
        ancestorsOfA += cur
        cur = parentOf[cur]
    }
    cur = bId
    while (cur != null) {
        if (cur in ancestorsOfA) return cur
        cur = parentOf[cur]
    }
    return SYNTHETIC_ROOT_ID
}

/** Path from [vertexId] up to (and excluding) [stopAt], inclusive of [vertexId]. */
internal fun pathUpTo(
    vertexId: String,
    stopAt: String,
    parentOf: Map<String, String>,
): List<String> {
    val out = mutableListOf<String>()
    var cur: String? = vertexId
    while (cur != null && cur != stopAt) {
        out += cur
        cur = parentOf[cur]
    }
    return out
}
