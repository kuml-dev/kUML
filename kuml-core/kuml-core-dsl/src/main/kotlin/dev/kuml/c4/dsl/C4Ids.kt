package dev.kuml.c4.dsl

import java.util.concurrent.atomic.AtomicLong

/**
 * Helper functions for generating and managing C4 element IDs.
 *
 * C4 uses simple sequential IDs for elements. IDs are automatically
 * generated if not explicitly provided.
 *
 * **Determinism guarantee**: [generateId] produces IDs that are stable across
 * repeated evaluations of the same script by resetting the counter at the start
 * of each script evaluation via [resetForScript]. This makes SVG output
 * byte-for-byte identical for the same source, enabling content-addressability,
 * caching, diff detection, and audit reproducibility.
 *
 * Thread-safety: a single global [AtomicLong] is used. Multiple concurrent
 * script evaluations each receive unique IDs (no collision), though the exact
 * numeric values may differ across runs when evaluations interleave. For
 * fully isolated, reproducible per-script IDs in a concurrent environment,
 * call [resetForScript] with a script-scoped seed via [withScriptContext].
 */
object C4Ids {
    /** Separator constant for qualified names (compatible with UML). */
    const val SEP = "::"

    private val counter = AtomicLong(0L)

    /**
     * Resets the ID counter to zero. Must be called once at the start of each
     * script evaluation to guarantee deterministic IDs across repeated runs of
     * the same source script.
     *
     * Not thread-safe across concurrent evaluations — intended for single-
     * threaded script evaluation paths (CLI, MCP tool handler).
     */
    fun resetForScript() {
        counter.set(0L)
    }

    /**
     * Evaluates [block] inside a fresh ID counter scope. The counter is reset
     * to zero before [block] and restored to its previous value afterwards,
     * making this safe for nested or concurrent callers.
     */
    fun <T> withScriptContext(block: () -> T): T {
        val saved = counter.get()
        counter.set(0L)
        return try {
            block()
        } finally {
            counter.set(saved)
        }
    }

    /**
     * Generates a unique sequential ID for a C4 element.
     *
     * Returns deterministic values (`c4-0`, `c4-1`, …) when the counter is
     * reset via [resetForScript] or [withScriptContext] before script evaluation.
     *
     * @return A stable string ID.
     */
    fun generateId(): String = "c4-${counter.getAndIncrement()}"

    /**
     * Returns the qualified ID of a child element inside an optional parent namespace.
     *
     * ```
     * child(null,            "Order")  // → "Order"
     * child("",              "Order")  // → "Order"
     * child("domain",        "Order")  // → "domain::Order"
     * child("domain::svc",   "Order")  // → "domain::svc::Order"
     * ```
     */
    fun child(
        parentId: String?,
        name: String,
    ): String = if (parentId.isNullOrEmpty()) name else "$parentId$SEP$name"

    /**
     * ID for a C4 relationship.
     *
     * ```
     * relationship("source-id", "target-id")  // → "rel::source-id->target-id"
     * ```
     */
    fun relationship(
        sourceId: String,
        targetId: String,
    ): String = "rel$SEP$sourceId->$targetId"

    /**
     * Returns [candidate] unchanged if it is not in [taken]; otherwise appends
     * `~2`, `~3`, … until a unique string is found.
     *
     * ```
     * disambiguate("Customer", emptySet())           // → "Customer"
     * disambiguate("Customer", setOf("Customer"))    // → "Customer~2"
     * ```
     */
    fun disambiguate(
        candidate: String,
        taken: Set<String>,
    ): String =
        if (candidate !in taken) {
            candidate
        } else {
            generateSequence(2) { it + 1 }
                .map { "$candidate~$it" }
                .first { it !in taken }
        }
}
