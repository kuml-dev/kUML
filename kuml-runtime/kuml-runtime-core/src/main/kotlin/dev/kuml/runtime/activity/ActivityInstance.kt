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
