package dev.kuml.c4.dsl

import java.util.UUID

/**
 * Helper functions for generating and managing C4 element IDs.
 *
 * C4 uses simple, UUID-based IDs for elements. IDs are automatically
 * generated if not explicitly provided.
 */
object C4Ids {
    /** Separator constant for qualified names (compatible with UML). */
    const val SEP = "::"

    /**
     * Generates a unique random ID for a C4 element.
     *
     * @return A UUID-based string ID.
     */
    fun generateId(): String = UUID.randomUUID().toString()

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
