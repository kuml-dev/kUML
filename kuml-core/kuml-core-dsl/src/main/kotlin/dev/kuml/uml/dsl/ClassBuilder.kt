package dev.kuml.uml.dsl

import dev.kuml.core.dsl.KumlDsl
import dev.kuml.core.dsl.layout.LayoutHintsBuilder
import dev.kuml.core.dsl.layout.LayoutHintsScope
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlClassifier
import dev.kuml.uml.UmlConstraint
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlInterfaceRealization
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.UmlTypeRef
import dev.kuml.uml.Visibility
import dev.kuml.uml.ids.UmlIds

/**
 * Builder for a [UmlClass].
 *
 * Do not instantiate directly — use the [classOf] extension function on a
 * [UmlContainerScope] or [UmlModelScope].
 */
@KumlDsl
class ClassBuilder internal constructor(
    private val name: String,
    private val parentId: String?,
    override val takenIds: MutableSet<String>,
    explicitId: String?,
) : UmlClassifierScope,
    LayoutHintsScope {
    override val layoutHintsBuilder: LayoutHintsBuilder = LayoutHintsBuilder()

    /** The computed or explicitly provided ID for this class. */
    val id: String =
        run {
            val candidate = explicitId ?: UmlIds.child(parentId, name)
            val resolved = UmlIds.disambiguate(candidate, takenIds)
            takenIds += resolved
            resolved
        }

    override val ownerId: String get() = id

    var visibility: Visibility = Visibility.PUBLIC
    var isAbstract: Boolean = false
    val stereotypes: MutableList<String> = mutableListOf()

    private val attributes = mutableListOf<UmlProperty>()
    private val operations = mutableListOf<UmlOperation>()
    private val constraints = mutableListOf<UmlConstraint>()
    private val pendingGeneralizations = mutableListOf<Pair<String, String>>() // specificId -> generalId
    private val pendingRealizations = mutableListOf<Pair<String, String>>() // implementingId -> interfaceId

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

    internal fun buildClass(): UmlClass =
        UmlClass(
            id = id,
            name = name,
            visibility = visibility,
            isAbstract = isAbstract,
            attributes = attributes.toList(),
            operations = operations.toList(),
            constraints = constraints.toList(),
            stereotypes = stereotypes.toList(),
            metadata = layoutHintsBuilder.toMetadata(),
        )

    internal fun buildPendingGeneralizations(): List<UmlGeneralization> =
        pendingGeneralizations.map { (specId, genId) ->
            val relId =
                UmlIds.disambiguate(
                    candidate = UmlIds.generalization(specId, genId),
                    taken = takenIds,
                )
            takenIds += relId
            UmlGeneralization(id = relId, specificId = specId, generalId = genId)
        }

    internal fun buildPendingRealizations(): List<UmlInterfaceRealization> =
        pendingRealizations.map { (implId, ifaceId) ->
            val relId =
                UmlIds.disambiguate(
                    candidate = UmlIds.realization(implId, ifaceId),
                    taken = takenIds,
                )
            takenIds += relId
            UmlInterfaceRealization(id = relId, implementingId = implId, interfaceId = ifaceId)
        }
}

// ── Feature extension functions (available inside classOf { }) ────────────────

/**
 * Adds a property (attribute) to the enclosing class or interface.
 *
 * ```kotlin
 * classOf("Order") {
 *     attribute(name = "id", type = "UUID")
 *     attribute(name = "status", type = status)   // status = enumOf("OrderStatus") { … }
 * }
 * ```
 *
 * @param name Attribute name.
 * @param type Type reference (string or classifier handle — see [typeRef]).
 * @param visibility Default [Visibility.PRIVATE] (UML convention for attributes).
 */
fun UmlClassifierScope.attribute(
    name: String,
    type: UmlTypeRef,
    visibility: Visibility = Visibility.PRIVATE,
    multiplicity: Multiplicity = Multiplicity(),
    defaultValue: String? = null,
    isStatic: Boolean = false,
    isReadOnly: Boolean = false,
    id: String? = null,
): UmlProperty {
    val propId =
        id ?: UmlIds.disambiguate(
            candidate = UmlIds.child(ownerId, name),
            taken = takenIds,
        )
    takenIds += propId
    val prop =
        UmlProperty(
            id = propId,
            name = name,
            visibility = visibility,
            type = type,
            multiplicity = multiplicity,
            defaultValue = defaultValue,
            isStatic = isStatic,
            isReadOnly = isReadOnly,
        )
    addAttribute(prop)
    return prop
}

/** Convenience overload — type by name string. */
fun UmlClassifierScope.attribute(
    name: String,
    type: String,
    visibility: Visibility = Visibility.PRIVATE,
    multiplicity: Multiplicity = Multiplicity(),
    defaultValue: String? = null,
    isStatic: Boolean = false,
    isReadOnly: Boolean = false,
    id: String? = null,
): UmlProperty = attribute(name, typeRef(type), visibility, multiplicity, defaultValue, isStatic, isReadOnly, id)

/** Convenience overload — type by classifier handle. */
fun UmlClassifierScope.attribute(
    name: String,
    type: UmlClassifier,
    visibility: Visibility = Visibility.PRIVATE,
    multiplicity: Multiplicity = Multiplicity(),
    defaultValue: String? = null,
    isStatic: Boolean = false,
    isReadOnly: Boolean = false,
    id: String? = null,
): UmlProperty = attribute(name, typeRef(type), visibility, multiplicity, defaultValue, isStatic, isReadOnly, id)

/**
 * Declares that this class extends (inherits from) [generalId].
 *
 * Creates a [UmlGeneralization] that is registered with the enclosing
 * diagram/model scope when using [UmlModelScope.classOf].
 * Inside a [PackageBuilder] scope this declaration is recorded but
 * the generated relationship is not added to the model — declare it
 * explicitly with [UmlModelScope.generalization] instead.
 *
 * @param generalId Qualified ID of the parent class.
 */
fun UmlClassifierScope.extends(generalId: String) {
    addPendingGeneralization(ownerId, generalId)
}

/** Overload — parent class via builder handle. */
fun UmlClassifierScope.extends(general: UmlClassifier) = extends(general.id)

/**
 * Declares that this class implements [interfaceId].
 *
 * Creates a [UmlInterfaceRealization] that is registered with the enclosing
 * diagram/model scope when using [UmlModelScope.classOf].
 *
 * @param interfaceId Qualified ID of the realised interface.
 */
fun UmlClassifierScope.implements(interfaceId: String) {
    addPendingRealization(ownerId, interfaceId)
}

/** Overload — interface via builder handle. */
fun UmlClassifierScope.implements(iface: UmlInterface) = implements(iface.id)

// ── Container extension functions ─────────────────────────────────────────────

/**
 * Adds a [UmlClass] to this container (package or diagram/model root).
 *
 * For diagram/model scopes [UmlModelScope.classOf] is preferred because it
 * additionally propagates inline [extends] / [implements] declarations as
 * relationships to the diagram element list.
 *
 * @param name Class name.
 * @param id Optional explicit ID override.
 * @return The built [UmlClass] for use as a builder handle.
 */
fun UmlContainerScope.classOf(
    name: String,
    id: String? = null,
    block: ClassBuilder.() -> Unit = {},
): UmlClass {
    val builder =
        ClassBuilder(
            name = name,
            parentId = containerId,
            takenIds = takenIds,
            explicitId = id,
        )
    builder.block()
    val cls = builder.buildClass()
    addNamedElement(cls)
    return cls
}

/**
 * Adds a [UmlClass] to this diagram or model root.
 *
 * Unlike the [UmlContainerScope] overload, this version also registers
 * inline [extends] / [implements] declarations from the class body as
 * [UmlGeneralization] and [UmlInterfaceRealization] relationships.
 *
 * ```kotlin
 * diagram("Domain") {
 *     val animal = classOf("Animal") { isAbstract = true }
 *     classOf("Dog") {
 *         extends(animal)   // ← creates a UmlGeneralization in the diagram
 *     }
 * }
 * ```
 *
 * @param name Class name.
 * @param id Optional explicit ID override.
 * @return The built [UmlClass] for use as a builder handle.
 */
fun UmlModelScope.classOf(
    name: String,
    id: String? = null,
    block: ClassBuilder.() -> Unit = {},
): UmlClass {
    val builder =
        ClassBuilder(
            name = name,
            parentId = containerId,
            takenIds = takenIds,
            explicitId = id,
        )
    builder.block()
    val cls = builder.buildClass()
    addNamedElement(cls)
    builder.buildPendingGeneralizations().forEach { addRelationship(it) }
    builder.buildPendingRealizations().forEach { addRelationship(it) }
    return cls
}
