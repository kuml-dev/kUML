package dev.kuml.ai.tools.internal

/**
 * Helpers for generating stable, collision-free element ids from names.
 *
 * Ids are snake_case derivations of the element name + an optional numeric
 * suffix for disambiguation. The ID space is tracked per-context via a
 * taken-set that callers must maintain.
 */
internal object IdHelpers {
    /**
     * Converts a display name to a snake_case id base.
     *
     * "OrderService" → "order_service"
     * "Place Order" → "place_order"
     */
    internal fun nameToIdBase(name: String): String =
        name
            .replace(Regex("([a-z])([A-Z])"), "$1_$2")
            .replace(Regex("[^a-zA-Z0-9]+"), "_")
            .lowercase()
            .trim('_')
            .ifEmpty { "element" }

    /**
     * Returns a unique id derived from [name] that is not in [takenIds].
     *
     * First tries the base id; if taken, appends _2, _3, … until unique.
     */
    internal fun uniqueId(
        name: String,
        takenIds: Set<String>,
        prefix: String = "",
    ): String {
        val base = if (prefix.isNotEmpty()) "${prefix}_${nameToIdBase(name)}" else nameToIdBase(name)
        if (base !in takenIds) return base
        var n = 2
        while (true) {
            val candidate = "${base}_$n"
            if (candidate !in takenIds) return candidate
            n++
        }
    }

    /** Returns all element ids currently present in a model. */
    internal fun takenIdsFrom(elements: List<dev.kuml.uml.UmlNamedElement>): Set<String> = elements.map { it.id }.toSet()
}
