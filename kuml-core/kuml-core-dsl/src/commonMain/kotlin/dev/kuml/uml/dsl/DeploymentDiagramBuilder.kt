package dev.kuml.uml.dsl

import dev.kuml.core.dsl.KumlDsl
import dev.kuml.core.model.DeploymentDiagramConfig
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.profile.KumlProfile
import dev.kuml.uml.UmlArtifact
import dev.kuml.uml.UmlDependency
import dev.kuml.uml.UmlElement
import dev.kuml.uml.UmlNamedElement
import dev.kuml.uml.UmlNode
import dev.kuml.uml.UmlRelationship
import dev.kuml.uml.ids.UmlIds

/**
 * Builder for a UML 2.x deployment diagram (V1.1).
 *
 * Available builders inside the lambda:
 *  - [node] / [executionEnvironment] / [device] — deployment nodes
 *  - [artifact] — an artifact (e.g. JAR, image)
 *  - [deploy] — a `«deploy»` dependency from an artifact to a node
 *  - [communicationPath] — a connection between two nodes
 */
@KumlDsl
public class DeploymentDiagramBuilder(
    private val name: String,
) : UmlModelScope {
    override val containerId: String? = null
    override val takenIds: MutableSet<String> = mutableSetOf()

    private val appliedProfilesList = mutableListOf<KumlProfile>()
    private val elements = mutableListOf<UmlElement>()

    public var showHardwareStereotype: Boolean = true
    public var showArtifactStereotype: Boolean = true

    override fun addNamedElement(element: UmlNamedElement) {
        when (element) {
            is UmlNode, is UmlArtifact -> {}
            else ->
                require(false) {
                    "[$name] ${element::class.simpleName} is not a valid element for a deployment diagram."
                }
        }
        elements += element
        takenIds += element.id
    }

    override fun addRelationship(relationship: UmlRelationship) {
        when (relationship) {
            is UmlDependency -> {}
            else ->
                require(false) {
                    "[$name] ${relationship::class.simpleName} is not a valid relationship for a deployment diagram."
                }
        }
        elements += relationship
        takenIds += relationship.id
    }

    /**
     * Adds a [dev.kuml.uml.UmlComment] (UML note) to this diagram.
     *
     * Comment/Note support (V0.23.1) currently targets class, sequence, and
     * state-machine diagrams — see `UmlModelScope.addComment` KDoc. This diagram
     * type accepts the call for interface completeness but the `comment()` DSL
     * function is not documented/promoted for this diagram type.
     */
    override fun addComment(comment: dev.kuml.uml.UmlComment) {
        elements += comment
        takenIds += comment.id
    }

    public fun node(
        name: String,
        block: NodeScope.() -> Unit = {},
    ): UmlNode = buildNode(name = name, kind = "node", block = block)

    public fun executionEnvironment(
        name: String,
        block: NodeScope.() -> Unit = {},
    ): UmlNode = buildNode(name = name, kind = "executionEnvironment", block = block)

    public fun device(
        name: String,
        block: NodeScope.() -> Unit = {},
    ): UmlNode = buildNode(name = name, kind = "device", block = block)

    private fun buildNode(
        name: String,
        kind: String,
        block: NodeScope.() -> Unit,
    ): UmlNode {
        val id = UmlIds.disambiguate(candidate = UmlIds.child(containerId, name), taken = takenIds)
        val scope = NodeScope(takenIds = takenIds, parentId = id)
        scope.apply(block)
        val n =
            UmlNode(
                id = id,
                name = name,
                nodeKind = kind,
                artifacts = scope.artifacts.toList(),
                children = scope.children.toList(),
            )
        addNamedElement(n)
        return n
    }

    public fun artifact(
        name: String,
        fileName: String? = null,
    ): UmlArtifact {
        val id = UmlIds.disambiguate(candidate = UmlIds.child(containerId, name), taken = takenIds)
        val a = UmlArtifact(id = id, name = name, fileName = fileName)
        addNamedElement(a)
        return a
    }

    public fun deploy(
        artifact: UmlArtifact,
        node: UmlNode,
    ): UmlDependency {
        val id = UmlIds.disambiguate(candidate = "deploy::${artifact.id}-->${node.id}", taken = takenIds)
        val d = UmlDependency(id = id, clientId = artifact.id, supplierId = node.id, name = "«deploy»")
        addRelationship(d)
        return d
    }

    public fun communicationPath(
        end1: UmlNode,
        end2: UmlNode,
    ): UmlDependency {
        val id = UmlIds.disambiguate(candidate = "commPath::${end1.id}--${end2.id}", taken = takenIds)
        val d = UmlDependency(id = id, clientId = end1.id, supplierId = end2.id, name = "«communicationPath»")
        addRelationship(d)
        return d
    }

    override fun addAppliedProfile(profile: KumlProfile) {
        appliedProfilesList += profile
    }

    override fun appliedProfiles(): List<KumlProfile> = appliedProfilesList.toList()

    public fun build(): KumlDiagram =
        KumlDiagram(
            name = name,
            type = DiagramType.DEPLOYMENT,
            elements = elements.toList(),
            config =
                DeploymentDiagramConfig(
                    showHardwareStereotype = showHardwareStereotype,
                    showArtifactStereotype = showArtifactStereotype,
                ),
        )
}

/** Scope inside a `node(…) { }` block — nested nodes + artifacts. */
@KumlDsl
public class NodeScope internal constructor(
    internal val takenIds: MutableSet<String>,
    private val parentId: String,
) {
    internal val children: MutableList<UmlNamedElement> = mutableListOf()
    internal val artifacts: MutableList<UmlArtifact> = mutableListOf()

    public fun artifact(
        name: String,
        fileName: String? = null,
    ): UmlArtifact {
        val id = UmlIds.disambiguate(candidate = UmlIds.child(parentId, name), taken = takenIds)
        val a = UmlArtifact(id = id, name = name, fileName = fileName)
        artifacts += a
        takenIds += id
        return a
    }

    public fun node(
        name: String,
        block: NodeScope.() -> Unit = {},
    ): UmlNode {
        val id = UmlIds.disambiguate(candidate = UmlIds.child(parentId, name), taken = takenIds)
        val nested = NodeScope(takenIds = takenIds, parentId = id)
        nested.apply(block)
        val n =
            UmlNode(
                id = id,
                name = name,
                nodeKind = "node",
                children = nested.children.toList(),
                artifacts = nested.artifacts.toList(),
            )
        children += n
        takenIds += id
        return n
    }
}
