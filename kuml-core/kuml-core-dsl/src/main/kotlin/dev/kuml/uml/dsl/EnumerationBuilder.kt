package dev.kuml.uml.dsl

import dev.kuml.core.dsl.KumlDsl
import dev.kuml.core.dsl.layout.LayoutHintsBuilder
import dev.kuml.core.dsl.layout.LayoutHintsScope
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlEnumerationLiteral
import dev.kuml.uml.Visibility
import dev.kuml.uml.ids.UmlIds

/**
 * Builder for a [UmlEnumeration].
 *
 * Do not instantiate directly — use the [enumOf] extension function on a
 * [UmlContainerScope].
 */
@KumlDsl
class EnumerationBuilder internal constructor(
    private val name: String,
    private val parentId: String?,
    override val takenIds: MutableSet<String>,
    explicitId: String?,
) : UmlClassifierScope, LayoutHintsScope {
    override val layoutHintsBuilder: LayoutHintsBuilder = LayoutHintsBuilder()

    /** The computed or explicitly provided ID for this enumeration. */
    val id: String =
        run {
            val candidate = explicitId ?: UmlIds.child(parentId, name)
            val resolved = UmlIds.disambiguate(candidate, takenIds)
            takenIds += resolved
            resolved
        }

    override val ownerId: String get() = id

    private val literals = mutableListOf<UmlEnumerationLiteral>()

    var visibility: Visibility = Visibility.PUBLIC
    val stereotypes: MutableList<String> = mutableListOf()

    // UmlClassifierScope stubs — enumerations do not own attributes or operations
    override fun addAttribute(property: dev.kuml.uml.UmlProperty) = Unit

    override fun addOperation(op: dev.kuml.uml.UmlOperation) = Unit

    override fun addPendingGeneralization(
        specificId: String,
        generalId: String,
    ) = Unit

    override fun addPendingRealization(
        implementingId: String,
        interfaceId: String,
    ) = Unit

    /**
     * Adds a literal value to this enumeration.
     *
     * @param name Literal name (e.g. `"DRAFT"`).
     * @param id Optional explicit ID override.
     */
    fun literal(
        name: String,
        id: String? = null,
    ) {
        val litId = id ?: UmlIds.disambiguate(UmlIds.child(this.id, name), takenIds)
        takenIds += litId
        literals += UmlEnumerationLiteral(id = litId, name = name)
    }

    internal fun build(): UmlEnumeration =
        UmlEnumeration(
            id = id,
            name = name,
            visibility = visibility,
            literals = literals.toList(),
            stereotypes = stereotypes.toList(),
            metadata = layoutHintsBuilder.toMetadata(),
        )
}

// ── Extension functions ───────────────────────────────────────────────────────

/**
 * Adds a [UmlEnumeration] to this container.
 *
 * ```kotlin
 * val status = enumOf("OrderStatus") {
 *     literal("DRAFT")
 *     literal("CONFIRMED")
 *     literal("SHIPPED")
 *     literal("CANCELLED")
 * }
 * ```
 *
 * @param name Enumeration name.
 * @param id Optional explicit ID override (derives from namespace path by default).
 * @return The built [UmlEnumeration] for use as a builder handle.
 */
fun UmlContainerScope.enumOf(
    name: String,
    id: String? = null,
    block: EnumerationBuilder.() -> Unit = {},
): UmlEnumeration {
    val builder =
        EnumerationBuilder(
            name = name,
            parentId = containerId,
            takenIds = takenIds,
            explicitId = id,
        )
    builder.block()
    val enum = builder.build()
    addNamedElement(enum)
    return enum
}
