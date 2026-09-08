package dev.kuml.runtime.activity

import kotlinx.serialization.Serializable

/**
 * Immutable snapshot of the current token distribution in an activity.
 *
 * The [tokenCounts] multiset maps nodeId → number of tokens currently at
 * that node. [isTerminated] becomes `true` once an ActivityFinal node
 * has consumed all tokens. [clock] is a logical tick counter incremented
 * by each successful [ActivityRuntime.step].
 */
@Serializable
public data class ActivityInstance(
    /** Ids of nodes that currently hold at least one token (multiset — a node can hold N tokens). */
    val tokenCounts: Map<String, Int> = emptyMap(),
    /** True once any ActivityFinal node has terminated the activity. */
    val isTerminated: Boolean = false,
    /** Logical clock — incremented on each successful step. */
    val clock: Long = 0L,
    /**
     * Join-synchronisation tracking: maps joinNodeId → set of sourceNodeIds that
     * have already delivered a token to this join in the current firing cycle.
     * Reset after the join fires successfully.
     */
    val joinTokensReceived: Map<String, Set<String>> = emptyMap(),
    /**
     * Token arrivals per join **edge** (ADR-0015 — `kuml-runtime-tokenflow`):
     * `joinNodeId → (edgeId → arrival count)`. Populated exclusively by
     * `dev.kuml.runtime.tokenflow.TokenFlowEngine`; [ActivityRuntime] continues
     * to use [joinTokensReceived] (source-node based) and never touches this
     * field. Kept separate rather than reusing [joinTokensReceived] because
     * correct Petri-net AND-join semantics require counting **edges**, not
     * source nodes — two parallel edges from the same source node to the same
     * join must each contribute a token, which a `Set<sourceNodeId>` cannot
     * represent (it collapses both arrivals into one).
     *
     * Default is empty, so — combined with `KumlRuntimeJson`'s
     * `encodeDefaults = false` / `ignoreUnknownKeys = true` — this field needs
     * no snapshot schema migration: old snapshots decode fine (field defaults
     * to empty), and snapshots written by [ActivityRuntime] never populate it.
     * A snapshot must not be restored by the *other* engine, though —
     * `ActivityRuntime.restoreFrom` enforces this with a hard
     * `dev.kuml.runtime.snapshot.MigrationException` when this field is non-empty; see the
     * full restriction documented on `dev.kuml.runtime.snapshot.ActivityInstanceSnapshot`.
     */
    val joinEdgeTokens: Map<String, Map<String, Int>> = emptyMap(),
)

// ── Extension helpers ─────────────────────────────────────────────────────────

/** Returns a copy with an additional token at [nodeId]. */
public fun ActivityInstance.withTokenAt(nodeId: String): ActivityInstance {
    val current = tokenCounts.getOrDefault(nodeId, 0)
    return copy(tokenCounts = tokenCounts + (nodeId to current + 1))
}

/** Returns a copy with one token removed from [nodeId] (clamped to zero). */
public fun ActivityInstance.withoutTokenAt(nodeId: String): ActivityInstance {
    val current = tokenCounts.getOrDefault(nodeId, 0)
    return if (current <= 1) {
        copy(tokenCounts = tokenCounts - nodeId)
    } else {
        copy(tokenCounts = tokenCounts + (nodeId to current - 1))
    }
}

/** Returns a copy with the clock advanced by one. */
public fun ActivityInstance.incrementClock(): ActivityInstance = copy(clock = clock + 1)

/** Total number of tokens currently held across all nodes (used for the `maxTokens` DoS guard). */
public val ActivityInstance.totalTokens: Int get() = tokenCounts.values.sum()

/**
 * Returns a copy recording one more token arrival for [joinId] via [edgeId]
 * (ADR-0015 edge-based join tracking — see [ActivityInstance.joinEdgeTokens]).
 */
public fun ActivityInstance.withJoinArrival(
    joinId: String,
    edgeId: String,
): ActivityInstance {
    val current = joinEdgeTokens[joinId] ?: emptyMap()
    val updated = current + (edgeId to (current.getOrDefault(edgeId, 0) + 1))
    return copy(joinEdgeTokens = joinEdgeTokens + (joinId to updated))
}

/** Returns a copy with all recorded arrivals for [joinId] cleared. */
public fun ActivityInstance.clearJoinArrivals(joinId: String): ActivityInstance = copy(joinEdgeTokens = joinEdgeTokens - joinId)

/**
 * Returns a copy with exactly one arrival consumed per edge in [edgeIds] for
 * [joinId] — an edge whose count reaches zero is removed from the map, and an
 * edge already absent (no arrival recorded) is left alone.
 *
 * This is the correct AND-join (PARALLEL converging) consumption rule: unlike
 * [clearJoinArrivals] (which wipes every recorded arrival for the join,
 * appropriate for an OR-join that always fires on its *entire* current
 * arrival set), an AND-join must only ever discharge one arrival per incoming
 * edge per firing. If an edge has delivered more tokens than its siblings
 * (e.g. a cycle feeding the join twice before the other branch catches up),
 * [clearJoinArrivals] would silently drop the surplus arrival's matching
 * token — stranding it in [ActivityInstance.tokenCounts] forever, since
 * `isReady` requires every incoming edge to be present in `arrived` again.
 */
public fun ActivityInstance.consumeJoinArrivals(
    joinId: String,
    edgeIds: Collection<String>,
): ActivityInstance {
    val current = joinEdgeTokens[joinId] ?: return this
    var updated = current
    for (edgeId in edgeIds) {
        val count = updated[edgeId] ?: continue
        updated = if (count <= 1) updated - edgeId else updated + (edgeId to count - 1)
    }
    return if (updated.isEmpty()) {
        copy(joinEdgeTokens = joinEdgeTokens - joinId)
    } else {
        copy(joinEdgeTokens = joinEdgeTokens + (joinId to updated))
    }
}
