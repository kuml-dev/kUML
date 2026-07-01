package dev.kuml.uml.dsl

import dev.kuml.core.dsl.KumlDsl
import dev.kuml.profile.KumlStereotypeApplication
import dev.kuml.profile.UmlMetaclass
import dev.kuml.uml.AppliedStereotype
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlCollaboration
import dev.kuml.uml.UmlCollaborationRole
import dev.kuml.uml.UmlTypeRef
import dev.kuml.uml.Visibility
import dev.kuml.uml.ids.UmlIds

/**
 * Builder for a [UmlCollaboration].
 *
 * Do not instantiate directly — use the [collaboration] extension function on a
 * [UmlContainerScope] or [UmlModelScope].
 *
 * ```kotlin
 * umlModel("Sales") {
 *     collaboration("OrderPlacement") {
 *         role("buyer", type = typeRef("Customer"))
 *         role("seller", type = typeRef("Merchant"))
 *     }
 * }
 * ```
 */
@KumlDsl
class CollaborationBuilder internal constructor(
    private val name: String,
    private val parentId: String?,
    val takenIds: MutableSet<String>,
    explicitId: String?,
    override val container: UmlContainerScope,
) : UmlElementScope {
    /** The computed or explicitly provided ID for this collaboration. */
    val id: String =
        run {
            val candidate = explicitId ?: UmlIds.child(parentId, name)
            val resolved = UmlIds.disambiguate(candidate, takenIds)
            takenIds += resolved
            resolved
        }

    override val metaclass: UmlMetaclass = UmlMetaclass.Collaboration

    var visibility: Visibility = Visibility.PUBLIC

    private val roles = mutableListOf<UmlCollaborationRole>()
    private val stereotypeApplications = mutableListOf<KumlStereotypeApplication>()

    override fun addStereotype(app: KumlStereotypeApplication) {
        stereotypeApplications += app
    }

    /**
     * Adds a collaboration role to this collaboration.
     *
     * @param name Role name.
     * @param type The type (classifier) that fills this role.
     * @param multiplicity Multiplicity for the role (default: exactly one).
     * @param block Optional block for stereotypes on the role.
     */
    fun role(
        name: String,
        type: UmlTypeRef,
        multiplicity: Multiplicity = Multiplicity(),
        block: CollaborationRoleBuilder.() -> Unit = {},
    ): UmlCollaborationRole {
        val roleId = UmlIds.disambiguate(UmlIds.child(id, name), takenIds)
        takenIds += roleId
        val roleBuilder =
            CollaborationRoleBuilder(
                id = roleId,
                name = name,
                type = type,
                multiplicity = multiplicity,
                container = container,
            )
        roleBuilder.block()
        val r = roleBuilder.build()
        roles += r
        return r
    }

    /** Convenience overload — type by name string. */
    fun role(
        name: String,
        type: String,
        multiplicity: Multiplicity = Multiplicity(),
        block: CollaborationRoleBuilder.() -> Unit = {},
    ): UmlCollaborationRole = role(name, UmlTypeRef(name = type), multiplicity, block)

    internal fun build(): UmlCollaboration =
        UmlCollaboration(
            id = id,
            name = name,
            visibility = visibility,
            roles = roles.toList(),
            appliedStereotypes = stereotypeApplications.toList<AppliedStereotype>(),
        )
}

// ── CollaborationRoleBuilder ──────────────────────────────────────────────────

/**
 * Builder for a [UmlCollaborationRole] — the scope inside `role("name") { … }`.
 *
 * Supports [stereotype] via [UmlElementScope] so that
 * `role("buyer") { stereotype("Initiator") { } }` is valid.
 */
@KumlDsl
class CollaborationRoleBuilder internal constructor(
    private val id: String,
    private val name: String,
    private val type: UmlTypeRef,
    private val multiplicity: Multiplicity,
    override val container: UmlContainerScope,
) : UmlElementScope {
    override val metaclass: UmlMetaclass = UmlMetaclass.Property // roles are typed properties in UML
    val takenIds: MutableSet<String> get() = container.takenIds

    private val stereotypeApplications = mutableListOf<KumlStereotypeApplication>()

    override fun addStereotype(app: KumlStereotypeApplication) {
        stereotypeApplications += app
    }

    internal fun build(): UmlCollaborationRole =
        UmlCollaborationRole(
            id = id,
            name = name,
            type = type,
            multiplicity = multiplicity,
            appliedStereotypes = stereotypeApplications.toList<AppliedStereotype>(),
        )
}

// ── Container extension functions ─────────────────────────────────────────────

/**
 * Adds a [UmlCollaboration] to this container (package or diagram/model root).
 *
 * ```kotlin
 * umlModel("Sales") {
 *     val op = collaboration("OrderPlacement") {
 *         role("buyer", type = "Customer")
 *         role("seller", type = "Merchant")
 *     }
 * }
 * ```
 *
 * @param name Collaboration name.
 * @param id Optional explicit ID override.
 * @return The built [UmlCollaboration] for use as a handle.
 */
fun UmlContainerScope.collaboration(
    name: String,
    id: String? = null,
    block: CollaborationBuilder.() -> Unit = {},
): UmlCollaboration {
    val builder =
        CollaborationBuilder(
            name = name,
            parentId = containerId,
            takenIds = takenIds,
            explicitId = id,
            container = this,
        )
    builder.block()
    val collab = builder.build()
    addNamedElement(collab)
    return collab
}
