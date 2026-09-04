package dev.kuml.uml.dsl

import dev.kuml.core.dsl.KumlDsl
import dev.kuml.core.dsl.layout.LayoutHintsBuilder
import dev.kuml.core.dsl.layout.LayoutHintsScope
import dev.kuml.profile.KumlStereotypeApplication
import dev.kuml.profile.UmlMetaclass
import dev.kuml.uml.AggregationKind
import dev.kuml.uml.AppliedStereotype
import dev.kuml.uml.UmlAssociationClass
import dev.kuml.uml.UmlClassifier
import dev.kuml.uml.UmlConstraint
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInterfaceRealization
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.UmlTypeRef
import dev.kuml.uml.Visibility
import dev.kuml.uml.ids.UmlIds

/**
 * Builder for a [UmlAssociationClass].
 *
 * Do not instantiate directly — use one of the [associationClass] extension
 * functions on a [UmlModelScope]. Combines the classifier body of
 * [ClassBuilder] (attributes, operations, constraints, generalization/
 * realization) with the two association ends of [AssociationBuilder] — the
 * same double nature the built [UmlAssociationClass] itself has.
 *
 * The [id] is derived exactly like [ClassBuilder.id] (`UmlIds.child` +
 * `disambiguate`), not like [AssociationBuilder]'s `UmlIds.association`
 * scheme — an association class is, first and foremost, a named classifier.
 */
@KumlDsl
class AssociationClassBuilder internal constructor(
    private val name: String,
    parentId: String?,
    private val sourceTypeRef: UmlTypeRef,
    private val targetTypeRef: UmlTypeRef,
    override val takenIds: MutableSet<String>,
    explicitId: String?,
    override val container: UmlContainerScope,
) : UmlClassifierScope,
    UmlElementScope,
    LayoutHintsScope {
    override val layoutHintsBuilder: LayoutHintsBuilder = LayoutHintsBuilder()

    /** The computed or explicitly provided ID for this association class. */
    val id: String =
        run {
            val candidate = explicitId ?: UmlIds.child(parentId = parentId, name = name)
            val resolved = UmlIds.disambiguate(candidate = candidate, taken = takenIds)
            takenIds += resolved
            resolved
        }

    override val ownerId: String get() = id
    override val metaclass: UmlMetaclass = UmlMetaclass.Class

    var visibility: Visibility = Visibility.PUBLIC
    var isAbstract: Boolean = false
    var aggregation: AggregationKind = AggregationKind.NONE
    val stereotypes: MutableList<String> = mutableListOf()

    private val attributes = mutableListOf<UmlProperty>()
    private val operations = mutableListOf<UmlOperation>()
    private val constraints = mutableListOf<UmlConstraint>()
    private val pendingGeneralizations = mutableListOf<Pair<String, String>>() // specificId -> generalId
    private val pendingRealizations = mutableListOf<Pair<String, String>>() // implementingId -> interfaceId
    private val stereotypeApplications = mutableListOf<KumlStereotypeApplication>()

    private val sourceEnd = AssociationEndBuilder(typeId = sourceTypeRef.referencedId ?: sourceTypeRef.name)
    private val targetEnd = AssociationEndBuilder(typeId = targetTypeRef.referencedId ?: targetTypeRef.name)

    /** Configures the source end of this association class. */
    fun source(block: AssociationEndBuilder.() -> Unit) {
        sourceEnd.block()
    }

    /** Configures the target end of this association class. */
    fun target(block: AssociationEndBuilder.() -> Unit) {
        targetEnd.block()
    }

    override fun addAttribute(property: UmlProperty) {
        attributes += property
    }

    override fun addOperation(op: UmlOperation) {
        operations += op
    }

    override fun addPendingGeneralization(
        specificId: String,
        generalId: String,
    ) {
        pendingGeneralizations += Pair(specificId, generalId)
    }

    override fun addPendingRealization(
        implementingId: String,
        interfaceId: String,
    ) {
        pendingRealizations += Pair(implementingId, interfaceId)
    }

    override fun addConstraint(constraint: UmlConstraint) {
        constraints += constraint
    }

    override fun addStereotype(app: KumlStereotypeApplication) {
        stereotypeApplications += app
    }

    internal fun buildAssociationClass(): UmlAssociationClass =
        UmlAssociationClass(
            id = id,
            name = name,
            visibility = visibility,
            ends = listOf(sourceEnd.build(), targetEnd.build()),
            aggregation = aggregation,
            isAbstract = isAbstract,
            attributes = attributes.toList(),
            operations = operations.toList(),
            constraints = constraints.toList(),
            stereotypes = stereotypes.toList(),
            metadata = layoutHintsBuilder.toMetadata(),
            appliedStereotypes = stereotypeApplications.toList<AppliedStereotype>(),
        )

    internal fun buildPendingGeneralizations(): List<UmlGeneralization> =
        pendingGeneralizations.map { (specId, genId) ->
            val relId =
                UmlIds.disambiguate(
                    candidate = UmlIds.generalization(specificId = specId, generalId = genId),
                    taken = takenIds,
                )
            takenIds += relId
            UmlGeneralization(id = relId, specificId = specId, generalId = genId)
        }

    internal fun buildPendingRealizations(): List<UmlInterfaceRealization> =
        pendingRealizations.map { (implId, ifaceId) ->
            val relId =
                UmlIds.disambiguate(
                    candidate = UmlIds.realization(implementingId = implId, interfaceId = ifaceId),
                    taken = takenIds,
                )
            takenIds += relId
            UmlInterfaceRealization(id = relId, implementingId = implId, interfaceId = ifaceId)
        }
}

// ── Extension functions ───────────────────────────────────────────────────────

/**
 * Adds a [UmlAssociationClass] between two elements referenced by [UmlTypeRef].
 *
 * The association class is registered **once**, via [UmlContainerScope.addNamedElement]
 * — it is *not* additionally passed to [UmlModelScope.addRelationship]. Both
 * hooks feed the same underlying `elements` list on diagram/model builders; a
 * second call would duplicate the element (double rendering, double printing).
 * Its double nature (classifier **and** relationship) is expressed structurally
 * by the [UmlAssociationClass] type itself, not by a double registration here.
 *
 * ```kotlin
 * diagram("Elections") {
 *     val party = classOf("Party") { }
 *     val district = classOf("District") { }
 *     associationClass(name = "Tally", source = party, target = district) {
 *         attribute("votes", "Int")
 *         source { multiplicity("1") }
 *         target { multiplicity("0..*") }
 *     }
 * }
 * ```
 *
 * @param name Association class name (required — an association class is
 *   first and foremost a classifier).
 * @param source Type reference for the source end.
 * @param target Type reference for the target end.
 * @param id Optional explicit ID override.
 */
fun UmlModelScope.associationClass(
    name: String,
    source: UmlTypeRef,
    target: UmlTypeRef,
    id: String? = null,
    block: AssociationClassBuilder.() -> Unit = {},
): UmlAssociationClass {
    val builder =
        AssociationClassBuilder(
            name = name,
            parentId = containerId,
            sourceTypeRef = source,
            targetTypeRef = target,
            takenIds = takenIds,
            explicitId = id,
            container = this,
        )
    builder.block()
    val ac = builder.buildAssociationClass()
    addNamedElement(ac)
    builder.buildPendingGeneralizations().forEach { addRelationship(it) }
    builder.buildPendingRealizations().forEach { addRelationship(it) }
    return ac
}

/**
 * Adds a [UmlAssociationClass] between two classifiers referenced by string ID.
 *
 * ```kotlin
 * associationClass(name = "Tally", sourceId = "Party", targetId = "District") {
 *     attribute("votes", "Int")
 * }
 * ```
 */
fun UmlModelScope.associationClass(
    name: String,
    sourceId: String,
    targetId: String,
    id: String? = null,
    block: AssociationClassBuilder.() -> Unit = {},
): UmlAssociationClass = associationClass(name = name, source = typeRef(sourceId), target = typeRef(targetId), id = id, block = block)

/**
 * Adds a [UmlAssociationClass] between two classifiers via builder handles.
 *
 * ```kotlin
 * val party = classOf("Party") { }
 * val district = classOf("District") { }
 * associationClass(name = "Tally", source = party, target = district) {
 *     attribute("votes", "Int")
 * }
 * ```
 */
fun UmlModelScope.associationClass(
    name: String,
    source: UmlClassifier,
    target: UmlClassifier,
    id: String? = null,
    block: AssociationClassBuilder.() -> Unit = {},
): UmlAssociationClass = associationClass(name = name, source = typeRef(source), target = typeRef(target), id = id, block = block)
