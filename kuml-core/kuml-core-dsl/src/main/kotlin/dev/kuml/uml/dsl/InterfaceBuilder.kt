package dev.kuml.uml.dsl

import dev.kuml.core.dsl.KumlDsl
import dev.kuml.core.dsl.layout.LayoutHintsBuilder
import dev.kuml.core.dsl.layout.LayoutHintsScope
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlInterfaceRealization
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.Visibility
import dev.kuml.uml.ids.UmlIds

/**
 * Builder for a [UmlInterface].
 *
 * Do not instantiate directly — use the [interfaceOf] extension function on a
 * [UmlContainerScope] or [UmlModelScope].
 */
@KumlDsl
class InterfaceBuilder internal constructor(
    private val name: String,
    private val parentId: String?,
    override val takenIds: MutableSet<String>,
    explicitId: String?,
) : UmlClassifierScope, LayoutHintsScope {
    override val layoutHintsBuilder: LayoutHintsBuilder = LayoutHintsBuilder()

    /** The computed or explicitly provided ID for this interface. */
    val id: String =
        run {
            val candidate = explicitId ?: UmlIds.child(parentId, name)
            val resolved = UmlIds.disambiguate(candidate, takenIds)
            takenIds += resolved
            resolved
        }

    override val ownerId: String get() = id

    var visibility: Visibility = Visibility.PUBLIC
    val stereotypes: MutableList<String> = mutableListOf()

    private val attributes = mutableListOf<UmlProperty>()
    private val operations = mutableListOf<UmlOperation>()
    private val pendingGeneralizations = mutableListOf<Pair<String, String>>()
    private val pendingRealizations = mutableListOf<Pair<String, String>>()

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

    internal fun buildInterface(): UmlInterface =
        UmlInterface(
            id = id,
            name = name,
            visibility = visibility,
            attributes = attributes.toList(),
            operations = operations.toList(),
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

// ── Container extension functions ─────────────────────────────────────────────

/**
 * Adds a [UmlInterface] to this container (package or diagram/model root).
 *
 * @param name Interface name.
 * @param id Optional explicit ID override.
 * @return The built [UmlInterface] for use as a builder handle.
 */
fun UmlContainerScope.interfaceOf(
    name: String,
    id: String? = null,
    block: InterfaceBuilder.() -> Unit = {},
): UmlInterface {
    val builder =
        InterfaceBuilder(
            name = name,
            parentId = containerId,
            takenIds = takenIds,
            explicitId = id,
        )
    builder.block()
    val iface = builder.buildInterface()
    addNamedElement(iface)
    return iface
}

/**
 * Adds a [UmlInterface] to this diagram or model root.
 *
 * Unlike the [UmlContainerScope] overload, this version also registers
 * inline [extends] declarations as [UmlGeneralization] relationships.
 *
 * @param name Interface name.
 * @param id Optional explicit ID override.
 * @return The built [UmlInterface] for use as a builder handle.
 */
fun UmlModelScope.interfaceOf(
    name: String,
    id: String? = null,
    block: InterfaceBuilder.() -> Unit = {},
): UmlInterface {
    val builder =
        InterfaceBuilder(
            name = name,
            parentId = containerId,
            takenIds = takenIds,
            explicitId = id,
        )
    builder.block()
    val iface = builder.buildInterface()
    addNamedElement(iface)
    builder.buildPendingGeneralizations().forEach { addRelationship(it) }
    builder.buildPendingRealizations().forEach { addRelationship(it) }
    return iface
}
