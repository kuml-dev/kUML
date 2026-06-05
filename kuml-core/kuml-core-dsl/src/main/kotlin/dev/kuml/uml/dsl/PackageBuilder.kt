package dev.kuml.uml.dsl

import dev.kuml.core.dsl.KumlDsl
import dev.kuml.uml.UmlNamedElement
import dev.kuml.uml.UmlPackage
import dev.kuml.uml.Visibility
import dev.kuml.uml.ids.UmlIds

/**
 * Builder for a [UmlPackage].
 *
 * Do not instantiate directly — use the [packageOf] extension function on a
 * [UmlContainerScope] or [UmlModelScope].
 *
 * Note: [UmlPackage.members] only stores [UmlNamedElement] instances.
 * Relationships (associations, generalizations, …) must be declared at the
 * enclosing diagram or model scope. Inline [extends] / [implements] inside
 * `classOf` blocks within a package are silently dropped — use top-level
 * [generalization] / [realization] in the diagram scope instead.
 */
@KumlDsl
class PackageBuilder internal constructor(
    private val name: String,
    parentId: String?,
    override val takenIds: MutableSet<String>,
    explicitId: String?,
) : UmlContainerScope {
    /** The computed or explicitly provided ID for this package. */
    override val containerId: String =
        run {
            val candidate = explicitId ?: UmlIds.child(parentId, name)
            val resolved = UmlIds.disambiguate(candidate, takenIds)
            takenIds += resolved
            resolved
        }

    var visibility: Visibility = Visibility.PUBLIC
    val stereotypes: MutableList<String> = mutableListOf()

    private val members = mutableListOf<UmlNamedElement>()

    override fun addNamedElement(element: UmlNamedElement) {
        members += element
    }

    internal fun build(): UmlPackage =
        UmlPackage(
            id = containerId,
            name = name,
            visibility = visibility,
            members = members.toList(),
            stereotypes = stereotypes.toList(),
        )
}

// ── Extension functions ───────────────────────────────────────────────────────

/**
 * Adds a [UmlPackage] to this container.
 *
 * Note: `package` is a Kotlin keyword, so this function is accessed via
 * backtick syntax in scripts: `` `package`("domain") { … } ``.
 *
 * ```kotlin
 * diagram("Order Domain") {
 *     `package`("domain") {
 *         classOf("Order") { … }
 *         classOf("Customer") { … }
 *         enumOf("OrderStatus") { … }
 *     }
 *     // relationships must be declared here, at diagram scope
 *     association(sourceId = "domain::Order", targetId = "domain::Customer") { … }
 * }
 * ```
 *
 * @param name Package name.
 * @param id Optional explicit ID override.
 * @return The built [UmlPackage].
 */
@Suppress("ktlint:standard:function-naming")
fun UmlContainerScope.`package`(
    name: String,
    id: String? = null,
    block: PackageBuilder.() -> Unit = {},
): UmlPackage {
    val builder =
        PackageBuilder(
            name = name,
            parentId = containerId,
            takenIds = takenIds,
            explicitId = id,
        )
    builder.block()
    val pkg = builder.build()
    addNamedElement(pkg)
    return pkg
}

/**
 * Alias for [UmlContainerScope.`package`] that avoids backtick syntax.
 *
 * Prefer `` `package` `` in script files for clarity.
 */
fun UmlContainerScope.packageOf(
    name: String,
    id: String? = null,
    block: PackageBuilder.() -> Unit = {},
): UmlPackage = `package`(name, id, block)
