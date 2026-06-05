package dev.kuml.uml.ids

/**
 * Pure functions for deriving deterministic, path-based IDs for UML elements.
 *
 * ## ID strategy
 *
 * IDs are derived from the qualified namespace path of an element, using `::` as
 * separator (UML convention). This makes IDs readable, reproducible, and diff-friendly
 * without any runtime state.
 *
 * | Element                  | Example ID                              |
 * |--------------------------|-----------------------------------------|
 * | Root-level class         | `Order`                                 |
 * | Packaged class           | `domain::Order`                         |
 * | Attribute                | `domain::Order::id`                     |
 * | Operation (no overload)  | `domain::Order::confirm()`              |
 * | Operation (overloaded)   | `domain::Order::find(Long,String)`      |
 * | Enum literal             | `domain::OrderStatus::DRAFT`            |
 * | Interaction lifeline     | `PlaceOrder::ll::Customer`              |
 * | Interaction message      | `PlaceOrder::msg::1`                    |
 * | Combined fragment        | `PlaceOrder::frag::1`                   |
 * | State machine state      | `OrderSM::CONFIRMED`                    |
 * | Transition               | `OrderSM::t::DRAFT->CONFIRMED`          |
 * | Association              | `assoc::domain::Order-->domain::Item`   |
 * | Generalization           | `gen::Animal-|>LivingBeing`             |
 * | Interface realization    | `real::OrderSvc..|>IOrderSvc`           |
 * | Dependency               | `dep::Order..>OrderStatus`              |
 * | Include                  | `include::Checkout..>ValidateCart`      |
 * | Extend                   | `extend::Checkout..>ApplyDiscount`      |
 * | Connector                | `conn::portA--portB`                    |
 *
 * ## User override
 *
 * If the DSL supplies an explicit `id = "…"` the caller must pass that value
 * through directly — none of these functions are invoked in that case.
 *
 * ## Collision handling
 *
 * Duplicate qualified names are illegal in UML (a later validator will report
 * them as `KUML-E-xxx`). As a safety net [disambiguate] appends `~2`, `~3`, …
 * when a derived candidate already exists in the taken set.
 *
 * ## Cross-language reuse
 *
 * The separator constant and [child] / [disambiguate] helpers are good
 * candidates for promotion to `kuml-core-model` once C4 introduces its own
 * ID strategy.
 * TODO(core): promote SEP, child(), disambiguate() to kuml-core-model once C4 follows.
 */
object UmlIds {
    /** Separator used in all qualified-name IDs. */
    const val SEP = "::"

    // ── Namespace / child ────────────────────────────────────────────────────

    /**
     * Returns the qualified ID of a child element inside an optional parent namespace.
     *
     * ```
     * child(null,       "Order")  // → "Order"
     * child("",         "Order")  // → "Order"
     * child("domain",   "Order")  // → "domain::Order"
     * child("a::b",     "C")      // → "a::b::C"
     * ```
     */
    fun child(
        parentId: String?,
        name: String,
    ): String = if (parentId.isNullOrEmpty()) name else "$parentId$SEP$name"

    // ── Features ─────────────────────────────────────────────────────────────

    /**
     * ID for an operation, including parameter types for overload disambiguation.
     *
     * ```
     * operation("domain::Order", "confirm", emptyList())     // → "domain::Order::confirm()"
     * operation("domain::Order", "find",    listOf("Long"))  // → "domain::Order::find(Long)"
     * ```
     *
     * @param ownerId Qualified ID of the owning classifier.
     * @param name Operation name.
     * @param paramTypes Simple type names of the parameters, in declaration order.
     */
    fun operation(
        ownerId: String,
        name: String,
        paramTypes: List<String> = emptyList(),
    ): String = "$ownerId$SEP$name(${paramTypes.joinToString(",")})"

    // ── Relationships ─────────────────────────────────────────────────────────

    /**
     * ID for a [dev.kuml.uml.UmlAssociation].
     *
     * ```
     * association("domain::Order", "domain::Item", "contains")
     * // → "assoc::domain::Order-->domain::Item::contains"
     *
     * association("Order", "Item", null)
     * // → "assoc::Order-->Item"
     * ```
     */
    fun association(
        sourceId: String,
        targetId: String,
        name: String?,
    ): String = "assoc$SEP$sourceId-->$targetId" + (name?.let { "$SEP$it" } ?: "")

    /**
     * ID for a [dev.kuml.uml.UmlGeneralization].
     *
     * `"gen::Animal-|>LivingBeing"`
     */
    fun generalization(
        specificId: String,
        generalId: String,
    ): String = "gen$SEP$specificId-|>$generalId"

    /**
     * ID for a [dev.kuml.uml.UmlInterfaceRealization].
     *
     * `"real::OrderSvc..|>IOrderSvc"`
     */
    fun realization(
        implementingId: String,
        interfaceId: String,
    ): String = "real$SEP$implementingId..|>$interfaceId"

    /**
     * ID for a [dev.kuml.uml.UmlDependency].
     *
     * `"dep::Order..>OrderStatus"`
     */
    fun dependency(
        clientId: String,
        supplierId: String,
    ): String = "dep$SEP$clientId..>$supplierId"

    /**
     * ID for a [dev.kuml.uml.UmlInclude] relationship.
     *
     * `"include::Checkout..>ValidateCart"`
     */
    fun include(
        baseId: String,
        additionId: String,
    ): String = "include$SEP$baseId..>$additionId"

    /**
     * ID for a [dev.kuml.uml.UmlExtend] relationship.
     *
     * `"extend::Checkout..>ApplyDiscount"`
     */
    fun extend(
        baseId: String,
        extensionId: String,
    ): String = "extend$SEP$baseId..>$extensionId"

    /**
     * ID for a [dev.kuml.uml.UmlConnector].
     *
     * `"conn::portA--portB"`
     */
    fun connector(
        end1Id: String,
        end2Id: String,
    ): String = "conn$SEP$end1Id--$end2Id"

    // ── Interaction elements ──────────────────────────────────────────────────

    /**
     * ID for a [dev.kuml.uml.UmlLifeline] inside an interaction.
     *
     * `"PlaceOrder::ll::Customer"`
     */
    fun lifeline(
        interactionId: String,
        name: String,
    ): String = "$interactionId${SEP}ll$SEP$name"

    /**
     * ID for a [dev.kuml.uml.UmlMessage] inside an interaction.
     *
     * Message labels can repeat, so the 1-based [sequence] index is used
     * instead of the label text.
     *
     * `"PlaceOrder::msg::3"`
     */
    fun message(
        interactionId: String,
        sequence: Int,
    ): String = "$interactionId${SEP}msg$SEP$sequence"

    /**
     * ID for a [dev.kuml.uml.UmlCombinedFragment] inside an interaction.
     *
     * `"PlaceOrder::frag::1"`
     */
    fun fragment(
        interactionId: String,
        index: Int,
    ): String = "$interactionId${SEP}frag$SEP$index"

    // ── State machine elements ────────────────────────────────────────────────

    /**
     * ID for a [dev.kuml.uml.UmlVertex] (state, pseudostate, or final state).
     *
     * `"OrderSM::CONFIRMED"`
     */
    fun vertex(
        stateMachineId: String,
        name: String,
    ): String = "$stateMachineId$SEP$name"

    /**
     * ID for a [dev.kuml.uml.UmlTransition].
     *
     * The optional [disambiguationIndex] is appended (starting at 2) when
     * multiple transitions share the same source → target pair.
     *
     * ```
     * transition("OrderSM", "DRAFT", "CONFIRMED")     // → "OrderSM::t::DRAFT->CONFIRMED"
     * transition("OrderSM", "DRAFT", "CANCELLED", 2)  // → "OrderSM::t::DRAFT->CANCELLED#2"
     * ```
     */
    fun transition(
        stateMachineId: String,
        sourceName: String,
        targetName: String,
        disambiguationIndex: Int? = null,
    ): String {
        val base = "$stateMachineId${SEP}t$SEP$sourceName->$targetName"
        return if (disambiguationIndex != null) "$base#$disambiguationIndex" else base
    }

    // ── Collision handling ────────────────────────────────────────────────────

    /**
     * Returns [candidate] unchanged if it is not in [taken]; otherwise appends
     * `~2`, `~3`, … until a unique string is found.
     *
     * Duplicate qualified names are illegal in UML; this is a last-resort safety
     * net. The validator in `kuml-core-ocl` (Phase 2) will report such duplicates
     * as structured errors.
     *
     * ```
     * disambiguate("domain::Order", emptySet())            // → "domain::Order"
     * disambiguate("domain::Order", setOf("domain::Order")) // → "domain::Order~2"
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
