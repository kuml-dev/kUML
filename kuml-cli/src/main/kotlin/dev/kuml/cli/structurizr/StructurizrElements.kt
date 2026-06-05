package dev.kuml.cli.structurizr

internal data class StructurizrWorkspace(
    val name: String,
    val description: String?,
    val model: StructurizrModel,
    val views: StructurizrViews,
)

internal data class StructurizrModel(
    val elements: List<StructurizrElement>,
    val relationships: List<StructurizrRelationship>,
)

internal sealed class StructurizrElement {
    abstract val identifier: String?
    abstract val name: String
    abstract val description: String?

    internal data class Person(
        override val identifier: String?,
        override val name: String,
        override val description: String?,
        val external: Boolean = false,
    ) : StructurizrElement()

    internal data class SoftwareSystem(
        override val identifier: String?,
        override val name: String,
        override val description: String?,
        val external: Boolean = false,
        val containers: List<Container> = emptyList(),
    ) : StructurizrElement()

    internal data class Container(
        override val identifier: String?,
        override val name: String,
        override val description: String?,
        val technology: String?,
        val system: String?,
        val components: List<Component> = emptyList(),
    ) : StructurizrElement()

    internal data class Component(
        override val identifier: String?,
        override val name: String,
        override val description: String?,
        val technology: String?,
        val container: String?,
    ) : StructurizrElement()

    internal data class DeploymentNode(
        override val identifier: String?,
        override val name: String,
        override val description: String?,
        val technology: String?,
        val environment: String?,
        val children: List<DeploymentNode> = emptyList(),
    ) : StructurizrElement()
}

internal data class StructurizrRelationship(
    val sourceIdentifier: String,
    val targetIdentifier: String,
    val description: String?,
    val technology: String?,
)

internal data class StructurizrViews(
    val views: List<StructurizrView> = emptyList(),
)

internal sealed class StructurizrView {
    abstract val key: String?
    abstract val description: String?

    internal data class SystemContext(
        val systemIdentifier: String?,
        override val key: String?,
        override val description: String?,
    ) : StructurizrView()

    internal data class Container(
        val systemIdentifier: String?,
        override val key: String?,
        override val description: String?,
    ) : StructurizrView()

    internal data class Component(
        val containerIdentifier: String?,
        override val key: String?,
        override val description: String?,
    ) : StructurizrView()

    internal data class Deployment(
        val environment: String?,
        override val key: String?,
        override val description: String?,
    ) : StructurizrView()

    internal data class SystemLandscape(
        override val key: String?,
        override val description: String?,
    ) : StructurizrView()
}
