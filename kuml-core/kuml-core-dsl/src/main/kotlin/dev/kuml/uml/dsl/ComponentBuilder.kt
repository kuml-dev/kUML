package dev.kuml.uml.dsl

import dev.kuml.core.dsl.KumlDsl
import dev.kuml.core.dsl.layout.LayoutHintsBuilder
import dev.kuml.core.dsl.layout.LayoutHintsScope
import dev.kuml.profile.KumlStereotypeApplication
import dev.kuml.profile.UmlMetaclass
import dev.kuml.uml.AppliedStereotype
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlPort
import dev.kuml.uml.Visibility
import dev.kuml.uml.ids.UmlIds

/**
 * Builder for a [UmlComponent].
 *
 * Do not instantiate directly — use the [component] extension function on a
 * [UmlContainerScope] or another [UmlComponentScope] (for nesting).
 */
@KumlDsl
class ComponentBuilder internal constructor(
    private val name: String,
    private val parentId: String?,
    override val takenIds: MutableSet<String>,
    explicitId: String?,
    override val container: UmlContainerScope,
) : UmlComponentScope,
    UmlElementScope,
    LayoutHintsScope {
    override val layoutHintsBuilder: LayoutHintsBuilder = LayoutHintsBuilder()

    /** The computed or explicitly provided ID for this component. */
    val id: String =
        run {
            val candidate = explicitId ?: UmlIds.child(parentId, name)
            val resolved = UmlIds.disambiguate(candidate, takenIds)
            takenIds += resolved
            resolved
        }

    override val ownerId: String get() = id
    override val metaclass: UmlMetaclass = UmlMetaclass.Component

    var visibility: Visibility = Visibility.PUBLIC
    var isAbstract: Boolean = false
    val stereotypes: MutableList<String> = mutableListOf()

    private val ports = mutableListOf<UmlPort>()
    private val nestedComponents = mutableListOf<UmlComponent>()
    private val providedInterfaceIds = mutableListOf<String>()
    private val requiredInterfaceIds = mutableListOf<String>()
    private val stereotypeApplications = mutableListOf<KumlStereotypeApplication>()

    override fun addPort(port: UmlPort) {
        ports += port
    }

    override fun addNestedComponent(component: UmlComponent) {
        nestedComponents += component
    }

    override fun addProvidedInterface(interfaceId: String) {
        providedInterfaceIds += interfaceId
    }

    override fun addRequiredInterface(interfaceId: String) {
        requiredInterfaceIds += interfaceId
    }

    override fun addStereotype(app: KumlStereotypeApplication) {
        stereotypeApplications += app
    }

    internal fun buildComponent(): UmlComponent =
        UmlComponent(
            id = id,
            name = name,
            visibility = visibility,
            isAbstract = isAbstract,
            ports = ports.toList(),
            providedInterfaceIds = providedInterfaceIds.toList(),
            requiredInterfaceIds = requiredInterfaceIds.toList(),
            nestedComponents = nestedComponents.toList(),
            stereotypes = stereotypes.toList(),
            metadata = layoutHintsBuilder.toMetadata(),
            appliedStereotypes = stereotypeApplications.toList<AppliedStereotype>(),
        )
}
