package dev.kuml.c4.dsl

import dev.kuml.c4.model.C4Component
import dev.kuml.c4.model.C4Container
import dev.kuml.c4.model.C4DeploymentNode
import dev.kuml.c4.model.C4Element
import dev.kuml.c4.model.C4Model
import dev.kuml.c4.model.C4Person
import dev.kuml.c4.model.C4Relationship
import dev.kuml.c4.model.C4SoftwareSystem
import dev.kuml.core.dsl.KumlDsl
import dev.kuml.core.dsl.layout.LayoutHintsBuilder

/**
 * Builder for a C4 model.
 *
 * Supports building C4 diagrams by composing persons, software systems with containers
 * and components, deployment nodes, and relationships.
 *
 * Do not instantiate directly — use the [c4Model] entry-point function.
 */
@KumlDsl
class C4ModelBuilder(
    private val name: String,
) : C4ModelScope {
    override val takenIds: MutableSet<String> = mutableSetOf()
    override val elements: MutableList<C4Element> = mutableListOf()
    override val relationships: MutableList<C4Relationship> = mutableListOf()

    private sealed class DiagramDef(
        val name: String,
        val description: String?,
    ) {
        class SystemContext(
            name: String,
            description: String?,
            val block: SystemContextDiagramBuilder.() -> Unit,
        ) : DiagramDef(name, description)

        class Container(
            name: String,
            description: String?,
            val block: ContainerDiagramBuilder.() -> Unit,
        ) : DiagramDef(name, description)

        class SystemLandscape(
            name: String,
            description: String?,
            val block: SystemLandscapeDiagramBuilder.() -> Unit,
        ) : DiagramDef(name, description)

        class Component(
            name: String,
            description: String?,
            val block: ComponentDiagramBuilder.() -> Unit,
        ) : DiagramDef(name, description)

        class Deployment(
            name: String,
            description: String?,
            val block: DeploymentDiagramBuilder.() -> Unit,
        ) : DiagramDef(name, description)

        class Dynamic(
            name: String,
            description: String?,
            val block: DynamicDiagramBuilder.() -> Unit,
        ) : DiagramDef(name, description)
    }

    private val diagramDefs = mutableListOf<DiagramDef>()

    /**
     * Creates a person element.
     *
     * @param name The person's name
     * @param block Configuration block for optional properties
     * @return The created C4Person element
     */
    fun person(
        name: String,
        block: PersonScope.() -> Unit = {},
    ): C4Person {
        val scope = PersonScopeImpl()
        scope.apply(block)

        val id = C4Ids.generateId()
        val person =
            C4Person(
                id = id,
                name = name,
                description = scope.description,
                external = scope.external,
                location = scope.location,
                metadata = scope.layoutHintsBuilder.toMetadata(),
            )
        addElement(person)
        return person
    }

    /**
     * Creates a software system element.
     *
     * @param name The system's name
     * @param block Configuration block for optional properties and containers
     * @return The created C4SoftwareSystem element
     */
    fun softwareSystem(
        name: String,
        block: SoftwareSystemScopeImpl.() -> Unit = {},
    ): C4SoftwareSystem {
        val systemId = C4Ids.generateId()
        val scope = SoftwareSystemScopeImpl(systemId, takenIds, elements, relationships)
        scope.apply(block)

        val containerIds = scope.containers.map { it.id }
        val system =
            C4SoftwareSystem(
                id = systemId,
                name = name,
                description = scope.description,
                external = scope.external,
                location = scope.location,
                containers = containerIds,
                metadata = scope.layoutHintsBuilder.toMetadata(),
            )
        addElement(system)
        return system
    }

    /**
     * Creates a deployment node element.
     *
     * @param name The node's name
     * @param block Configuration block for optional properties and child nodes
     * @return The created C4DeploymentNode element
     */
    fun deploymentNode(
        name: String,
        block: DeploymentNodeScopeImpl.() -> Unit = {},
    ): C4DeploymentNode {
        val nodeId = C4Ids.generateId()
        val scope = DeploymentNodeScopeImpl(nodeId, takenIds, elements, relationships)
        scope.apply(block)

        val childrenIds = scope.children.map { it.id }
        val node =
            C4DeploymentNode(
                id = nodeId,
                name = name,
                description = scope.description,
                technology = scope.technology,
                instances = scope.instances,
                containerInstances = scope.containerInstances.map { it.id },
                children = childrenIds,
            )
        addElement(node)
        return node
    }

    /**
     * Creates a relationship between two C4 elements.
     *
     * @param source The source element
     * @param target The target element
     * @param block Configuration block for optional properties
     * @return The created C4Relationship element
     */
    fun relationship(
        source: C4Element,
        target: C4Element,
        block: RelationshipScope.() -> Unit = {},
    ): C4Relationship {
        val scope = RelationshipScopeImpl()
        scope.apply(block)

        val relationshipId = C4Ids.relationship(source.id, target.id)
        val relationship =
            C4Relationship(
                id = relationshipId,
                source = source.id,
                target = target.id,
                label = scope.description ?: (source.name + " -> " + target.name),
                technology = scope.technology,
                bidirectional = scope.bidirectional,
                description = scope.description,
            )
        addRelationship(relationship)
        return relationship
    }

    /**
     * Creates a System Context Diagram (Level 1 of C4).
     *
     * @param name The diagram name
     * @param description Optional description
     * @param block Configuration block for diagram content
     */
    fun systemContextDiagram(
        name: String,
        description: String? = null,
        block: SystemContextDiagramBuilder.() -> Unit = {},
    ) {
        diagramDefs.add(DiagramDef.SystemContext(name, description, block))
    }

    /**
     * Creates a Container Diagram (Level 2 of C4).
     *
     * Decomposes a single software system to show its containers and their relationships.
     *
     * @param name The diagram name
     * @param description Optional description
     * @param block Configuration block for diagram content
     */
    fun containerDiagram(
        name: String,
        description: String? = null,
        block: ContainerDiagramBuilder.() -> Unit = {},
    ) {
        diagramDefs.add(DiagramDef.Container(name, description, block))
    }

    /**
     * Creates a System Landscape Diagram — an enterprise overview.
     *
     * Shows all software systems and persons in the enterprise along with their relationships.
     *
     * @param name The diagram name
     * @param description Optional description
     * @param block Configuration block for diagram content
     */
    fun systemLandscapeDiagram(
        name: String,
        description: String? = null,
        block: SystemLandscapeDiagramBuilder.() -> Unit = {},
    ) {
        diagramDefs.add(DiagramDef.SystemLandscape(name, description, block))
    }

    /**
     * Creates a Component Diagram (Level 3 of C4).
     *
     * Decomposes a single container to show its components and their relationships.
     *
     * @param name The diagram name
     * @param description Optional description
     * @param block Configuration block for diagram content
     */
    fun componentDiagram(
        name: String,
        description: String? = null,
        block: ComponentDiagramBuilder.() -> Unit,
    ) {
        diagramDefs.add(DiagramDef.Component(name, description, block))
    }

    /**
     * Creates a Deployment Diagram (Level 4 of C4).
     *
     * Shows how the software system is deployed across infrastructure nodes
     * and which containers run on which nodes.
     *
     * @param name The diagram name
     * @param description Optional description
     * @param block Configuration block for diagram content
     */
    fun deploymentDiagram(
        name: String,
        description: String? = null,
        block: DeploymentDiagramBuilder.() -> Unit = {},
    ) {
        diagramDefs.add(DiagramDef.Deployment(name, description, block))
    }

    /**
     * Creates a Dynamic Diagram (Level 4 of C4).
     *
     * Shows how elements interact over time using sequence-like notation.
     * Captures dynamic behavior and message flows between system components.
     *
     * @param name The diagram name
     * @param description Optional description
     * @param block Configuration block for diagram content
     */
    fun dynamicDiagram(
        name: String,
        description: String? = null,
        block: DynamicDiagramBuilder.() -> Unit = {},
    ) {
        diagramDefs.add(DiagramDef.Dynamic(name, description, block))
    }

    /**
     * Builds the immutable C4Model.
     *
     * @return The constructed C4Model
     */
    fun build(): C4Model {
        // First create the base model with all elements and relationships
        val baseModel =
            C4Model(
                id = C4Ids.generateId(),
                name = name,
                elements = elements.toList(),
                relationships = relationships.toList(),
                // Will be populated below
                diagrams = emptyList(),
            )

        // Build all diagrams using the complete model
        val diagrams =
            diagramDefs.map { def ->
                when (def) {
                    is DiagramDef.SystemContext -> {
                        SystemContextDiagramBuilderImpl(baseModel)
                            .apply(def.block)
                            .build()
                            .copy(
                                name = def.name,
                                description = def.description,
                            )
                    }
                    is DiagramDef.Container -> {
                        ContainerDiagramBuilderImpl(baseModel)
                            .apply(def.block)
                            .build()
                            .copy(
                                name = def.name,
                                description = def.description,
                            )
                    }
                    is DiagramDef.SystemLandscape -> {
                        SystemLandscapeDiagramBuilderImpl(baseModel)
                            .apply(def.block)
                            .build()
                            .copy(
                                name = def.name,
                                description = def.description,
                            )
                    }
                    is DiagramDef.Component -> {
                        ComponentDiagramBuilderImpl(baseModel)
                            .apply(def.block)
                            .build()
                            .copy(
                                name = def.name,
                                description = def.description,
                            )
                    }
                    is DiagramDef.Deployment -> {
                        DeploymentDiagramBuilderImpl(baseModel)
                            .apply(def.block)
                            .build()
                            .copy(
                                name = def.name,
                                description = def.description,
                            )
                    }
                    is DiagramDef.Dynamic -> {
                        DynamicDiagramBuilderImpl(baseModel)
                            .apply(def.block)
                            .build()
                            .copy(
                                name = def.name,
                                description = def.description,
                            )
                    }
                }
            }

        return baseModel.copy(diagrams = diagrams)
    }
}

// ── Scope implementations ────────────────────────────────────────────────────

@KumlDsl
private class PersonScopeImpl : PersonScope {
    override var description: String? = null
    override var external: Boolean = false
    override var location: String? = null
    override val layoutHintsBuilder: LayoutHintsBuilder = LayoutHintsBuilder()
}

@KumlDsl
class SoftwareSystemScopeImpl(
    override val systemId: String,
    override val takenIds: MutableSet<String>,
    override val elements: MutableList<C4Element>,
    override val relationships: MutableList<C4Relationship>,
) : SoftwareSystemScope {
    override var description: String? = null
    override var external: Boolean = false
    override var location: String? = null
    override val layoutHintsBuilder: LayoutHintsBuilder = LayoutHintsBuilder()

    val containers: MutableList<C4Container> = mutableListOf()

    override fun container(
        name: String,
        block: ContainerScope.() -> Unit,
    ): C4Container {
        val containerId = C4Ids.generateId()
        val scope = ContainerScopeImpl(containerId, takenIds, elements, relationships)
        scope.apply(block)

        val componentIds = scope.components.map { it.id }
        val container =
            C4Container(
                id = containerId,
                name = name,
                description = scope.description,
                technology = scope.technology,
                system = systemId,
                components = componentIds,
                metadata = scope.layoutHintsBuilder.toMetadata(),
            )
        containers += container
        addElement(container)
        return container
    }
}

@KumlDsl
class ContainerScopeImpl(
    override val containerId: String,
    override val takenIds: MutableSet<String>,
    override val elements: MutableList<C4Element>,
    override val relationships: MutableList<C4Relationship>,
) : ContainerScope {
    override var description: String? = null
    override var technology: String? = null
    override val layoutHintsBuilder: LayoutHintsBuilder = LayoutHintsBuilder()

    val components: MutableList<C4Component> = mutableListOf()

    override fun component(
        name: String,
        block: ComponentScope.() -> Unit,
    ): C4Component {
        val componentId = C4Ids.generateId()
        val scope = ComponentScopeImpl()
        scope.apply(block)

        val component =
            C4Component(
                id = componentId,
                name = name,
                description = scope.description,
                technology = scope.technology,
                container = containerId,
                metadata = scope.layoutHintsBuilder.toMetadata(),
            )
        components += component
        addElement(component)
        return component
    }
}

@KumlDsl
private class ComponentScopeImpl : ComponentScope {
    override var description: String? = null
    override var technology: String? = null
    override val layoutHintsBuilder: LayoutHintsBuilder = LayoutHintsBuilder()
}

@KumlDsl
class DeploymentNodeScopeImpl(
    override val nodeId: String,
    override val takenIds: MutableSet<String>,
    override val elements: MutableList<C4Element>,
    override val relationships: MutableList<C4Relationship>,
) : DeploymentNodeScope {
    override var description: String? = null
    override var technology: String? = null
    override var instances: Int = 1

    val children: MutableList<C4DeploymentNode> = mutableListOf()
    val containerInstances: MutableList<C4Container> = mutableListOf()

    override fun node(
        name: String,
        block: DeploymentNodeScope.() -> Unit,
    ): C4DeploymentNode {
        val childNodeId = C4Ids.generateId()
        val scope = DeploymentNodeScopeImpl(childNodeId, takenIds, elements, relationships)
        scope.apply(block)

        val childrenIds = scope.children.map { it.id }
        val node =
            C4DeploymentNode(
                id = childNodeId,
                name = name,
                description = scope.description,
                technology = scope.technology,
                instances = scope.instances,
                containerInstances = scope.containerInstances.map { it.id },
                children = childrenIds,
            )
        children += node
        addElement(node)
        return node
    }

    override fun containerInstance(
        name: String,
        containerId: String,
    ) {
        val instance =
            C4Container(
                id = C4Ids.generateId(),
                name = name,
                system = null,
                components = emptyList(),
            )
        containerInstances += instance
        addElement(instance)
    }
}

@KumlDsl
private class RelationshipScopeImpl : RelationshipScope {
    override var description: String? = null
    override var technology: String? = null
    override var bidirectional: Boolean = false
}
