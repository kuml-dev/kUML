package dev.kuml.uml.dsl

import dev.kuml.core.dsl.KumlDsl
import dev.kuml.uml.AggregationKind
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlClassifier
import dev.kuml.uml.UmlTypeRef
import dev.kuml.uml.ids.UmlIds

/**
 * Builder for one end of a [UmlAssociation].
 *
 * Instantiated automatically within [AssociationBuilder.source] and
 * [AssociationBuilder.target] lambdas.
 */
@KumlDsl
class AssociationEndBuilder internal constructor(
    internal val typeId: String,
) {
    /** Optional role name shown near this end. */
    var role: String? = null

    /** Multiplicity of this end. */
    var multiplicity: Multiplicity = Multiplicity()

    /** `false` hides the navigation arrow on this end. */
    var navigable: Boolean = true

    /**
     * Sets [multiplicity] from a compact string such as `"1"`, `"0..1"`,
     * `"1..*"`, or `"0..*"`.
     */
    fun multiplicity(spec: String) {
        multiplicity = parseMultiplicity(spec)
    }

    internal fun build(): UmlAssociationEnd =
        UmlAssociationEnd(
            typeId = typeId,
            role = role,
            multiplicity = multiplicity,
            navigable = navigable,
        )
}

/**
 * Builder for a [UmlAssociation].
 *
 * Do not instantiate directly — use one of the [association] extension functions
 * on a [UmlModelScope].
 */
@KumlDsl
class AssociationBuilder internal constructor(
    private val sourceTypeRef: UmlTypeRef,
    private val targetTypeRef: UmlTypeRef,
    private val takenIds: MutableSet<String>,
    private val explicitId: String?,
) {
    /** Optional association name shown on the connecting line. */
    var name: String? = null

    /** Aggregation kind for the source end (default: [AggregationKind.NONE]). */
    var aggregation: AggregationKind = AggregationKind.NONE

    private val sourceEnd = AssociationEndBuilder(typeId = sourceTypeRef.referencedId ?: sourceTypeRef.name)
    private val targetEnd = AssociationEndBuilder(typeId = targetTypeRef.referencedId ?: targetTypeRef.name)

    /**
     * Configures the source end of this association.
     *
     * ```kotlin
     * association(source = "Order", target = "Item") {
     *     source { multiplicity("1") }
     *     target { multiplicity("1..*") }
     * }
     * ```
     */
    fun source(block: AssociationEndBuilder.() -> Unit) {
        sourceEnd.block()
    }

    /** Configures the target end of this association. */
    fun target(block: AssociationEndBuilder.() -> Unit) {
        targetEnd.block()
    }

    internal fun build(): UmlAssociation {
        val srcId = sourceTypeRef.referencedId ?: sourceTypeRef.name
        val tgtId = targetTypeRef.referencedId ?: targetTypeRef.name
        val assocId =
            explicitId
                ?: UmlIds.disambiguate(
                    candidate = UmlIds.association(srcId, tgtId, name),
                    taken = takenIds,
                )
        takenIds += assocId
        return UmlAssociation(
            id = assocId,
            name = name,
            aggregation = aggregation,
            ends = listOf(sourceEnd.build(), targetEnd.build()),
        )
    }
}

// ── Extension functions ───────────────────────────────────────────────────────

/**
 * Adds a [UmlAssociation] between two elements referenced by [UmlTypeRef].
 *
 * @param source Type reference for the source end.
 * @param target Type reference for the target end.
 * @param id Optional explicit ID override.
 */
fun UmlModelScope.association(
    source: UmlTypeRef,
    target: UmlTypeRef,
    id: String? = null,
    block: AssociationBuilder.() -> Unit = {},
): UmlAssociation {
    val builder = AssociationBuilder(source, target, takenIds, id)
    builder.block()
    val assoc = builder.build()
    addRelationship(assoc)
    return assoc
}

/**
 * Adds a [UmlAssociation] between two classifiers referenced by string ID.
 *
 * ```kotlin
 * association(sourceId = "domain::Order", targetId = "domain::Item") {
 *     source { multiplicity("1") }
 *     target { multiplicity("1..*") }
 *     aggregation = COMPOSITE
 * }
 * ```
 */
fun UmlModelScope.association(
    sourceId: String,
    targetId: String,
    id: String? = null,
    block: AssociationBuilder.() -> Unit = {},
): UmlAssociation = association(typeRef(sourceId), typeRef(targetId), id, block)

/**
 * Adds a [UmlAssociation] between two classifiers via builder handles.
 *
 * ```kotlin
 * val order = classOf("Order") { … }
 * val item = classOf("Item") { … }
 * association(source = order, target = item) {
 *     aggregation = COMPOSITE
 * }
 * ```
 */
fun UmlModelScope.association(
    source: UmlClassifier,
    target: UmlClassifier,
    id: String? = null,
    block: AssociationBuilder.() -> Unit = {},
): UmlAssociation = association(typeRef(source), typeRef(target), id, block)
