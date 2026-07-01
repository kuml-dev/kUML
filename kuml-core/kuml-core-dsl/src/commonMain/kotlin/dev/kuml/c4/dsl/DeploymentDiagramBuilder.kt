package dev.kuml.c4.dsl

import dev.kuml.c4.model.C4DeploymentNode
import dev.kuml.c4.model.C4Model
import dev.kuml.c4.model.DeploymentDiagram
import dev.kuml.c4.model.ElementId
import dev.kuml.core.dsl.KumlDsl

/**
 * Scope for building a Deployment Diagram.
 *
 * Deployment Diagrams show how the software system is deployed across
 * infrastructure nodes and which containers run on which nodes.
 * Automatically includes all top-level deployment nodes and their children,
 * along with any deployed container instances.
 */
@KumlDsl
interface DeploymentDiagramBuilder {
    var description: String?

    fun include(vararg nodes: C4DeploymentNode)

    fun exclude(vararg nodes: C4DeploymentNode)

    fun title(text: String)

    fun note(text: String)
}

/**
 * Implementation of DeploymentDiagramBuilder.
 *
 * Builds deployment diagrams by collecting deployment nodes and all their descendants,
 * along with container instances deployed on those nodes.
 */
@KumlDsl
class DeploymentDiagramBuilderImpl(
    private val parentModel: C4Model,
) : DeploymentDiagramBuilder {
    override var description: String? = null

    private val includedNodes = mutableSetOf<ElementId>()
    private val excludedNodes = mutableSetOf<ElementId>()
    private val annotations = mutableListOf<String>()

    override fun include(vararg nodes: C4DeploymentNode) {
        nodes.forEach { includedNodes.add(it.id) }
    }

    override fun exclude(vararg nodes: C4DeploymentNode) {
        nodes.forEach { excludedNodes.add(it.id) }
    }

    override fun title(text: String) {
        annotations.add("TITLE: $text")
    }

    override fun note(text: String) {
        annotations.add("NOTE: $text")
    }

    /**
     * Builds the DeploymentDiagram from the current state.
     *
     * @return The constructed diagram with filtered elements and relationships
     */
    fun build(): DeploymentDiagram {
        // Get all deployment nodes from the model
        val allDeploymentNodes =
            parentModel.elements
                .filterIsInstance<C4DeploymentNode>()

        // Determine which root nodes to include
        val rootNodesToInclude =
            if (includedNodes.isNotEmpty()) {
                allDeploymentNodes
                    .filter { node -> node.id in includedNodes }
            } else {
                // If no nodes explicitly included, include all root nodes (nodes with no parent)
                allDeploymentNodes
                    .filter { node ->
                        !allDeploymentNodes.any { parent ->
                            node.id in parent.children
                        }
                    }
            }

        // Recursively collect all node IDs (root + descendants)
        val allCollectedNodeIds =
            rootNodesToInclude
                .flatMap { node -> collectNodeAndChildren(node, allDeploymentNodes) }
                .filter { nodeId -> nodeId !in excludedNodes }
                .distinct()

        // Collect all container instances on these nodes
        val containerInstanceIds =
            allDeploymentNodes
                .filter { node -> node.id in allCollectedNodeIds }
                .flatMap { node -> node.containerInstances }
                .distinct()

        val allElements = (allCollectedNodeIds + containerInstanceIds).distinct()

        // Filter relationships
        val filteredRelationships =
            parentModel.relationships
                .filter { rel -> rel.source in allElements && rel.target in allElements }
                .map { it.id }

        return DeploymentDiagram(
            id = C4Ids.generateId(),
            name = "",
            description = description,
            elements = allElements,
            relationships = filteredRelationships,
        )
    }

    private fun collectNodeAndChildren(
        node: C4DeploymentNode,
        allNodes: List<C4DeploymentNode>,
    ): List<ElementId> {
        val ids = mutableListOf(node.id)
        for (childId in node.children) {
            val childNode = allNodes.find { it.id == childId }
            if (childNode != null) {
                ids.addAll(collectNodeAndChildren(childNode, allNodes))
            }
        }
        return ids
    }
}
