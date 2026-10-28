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
        if (v is UmlState) descend(state = v, parents = parents)
    }
    return parents
}

private fun descend(
    state: UmlState,
    parents: MutableMap<String, String>,
) {
    for (sub in state.substates) {
        parents[sub.id] = state.id
        if (sub is UmlState) descend(state = sub, parents = parents)
    }
    // Explicit regions: a region is an addressable hierarchy node between the state and its
    // vertices, so transitions between siblings of one region only leave that region.
    for (region in state.regions) {
        val node = regionNodeId(state = state, regionId = region.id)
        parents[node] = state.id
        for (sub in region.vertices) {
            parents[sub.id] = node
            if (sub is UmlState) descend(state = sub, parents = parents)
        }
    }
}

/** Hierarchy-node id of an explicit region (never a vertex id). */
internal fun regionNodeId(
    state: UmlState,
    regionId: String,
): String = "${state.id}#$regionId"

/**
 * A region of a composite state: either the implicit one ([UmlState.substates], node id = owner id)
 * or an explicit [dev.kuml.uml.UmlRegion].
 */
internal data class RegionInfo(
    val nodeId: String,
    val ownerId: String,
    val vertices: List<UmlVertex>,
    val explicit: Boolean,
)

/** Regions of [state]: the implicit one first (when it has substates), then the explicit ones. */
internal fun regionsOf(state: UmlState): List<RegionInfo> =
    buildList {
        if (state.substates.isNotEmpty()) {
            add(
                RegionInfo(nodeId = state.id, ownerId = state.id, vertices = state.substates, explicit = false),
            )
        }
        for (r in state.regions) {
            add(
                RegionInfo(
                    nodeId = regionNodeId(state = state, regionId = r.id),
                    ownerId = state.id,
                    vertices = r.vertices,
                    explicit = true,
                ),
            )
        }
    }

/** Composite states (those with at least one region) keyed by state id. */
internal fun buildRegionsByOwner(sm: UmlStateMachine): Map<String, List<RegionInfo>> {
    val out = LinkedHashMap<String, List<RegionInfo>>()
    for (v in allVertices(sm)) {
        if (v is UmlState) {
            val regions = regionsOf(v)
            if (regions.isNotEmpty()) out[v.id] = regions
        }
    }
    return out
}

/** All vertices (flat) including substates. Used by lookup map. */
internal fun allVertices(sm: UmlStateMachine): List<UmlVertex> {
    val out = mutableListOf<UmlVertex>()

    fun visit(v: UmlVertex) {
        out += v
        if (v is UmlState) {
            for (sub in v.substates) visit(sub)
            for (r in v.regions) for (sub in r.vertices) visit(sub)
        }
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
