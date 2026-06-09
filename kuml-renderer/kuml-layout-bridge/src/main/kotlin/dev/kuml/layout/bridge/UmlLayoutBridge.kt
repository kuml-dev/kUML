package dev.kuml.layout.bridge

import dev.kuml.core.model.KumlDiagram
import dev.kuml.layout.EdgeHints
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EndpointRef
import dev.kuml.layout.GroupId
import dev.kuml.layout.Insets
import dev.kuml.layout.LayoutEdge
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutGroup
import dev.kuml.layout.LayoutNode
import dev.kuml.layout.NodeId
import dev.kuml.layout.PortId
import dev.kuml.layout.Size
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlFinalState
import dev.kuml.uml.UmlInteraction
import dev.kuml.uml.UmlNamedElement
import dev.kuml.uml.UmlPackage
import dev.kuml.uml.UmlPseudostate
import dev.kuml.uml.UmlRelationship
import dev.kuml.uml.UmlState
import dev.kuml.uml.UmlStateMachine
import dev.kuml.uml.UmlVertex

/**
 * Übersetzt ein UML-Diagramm in einen [LayoutGraph].
 *
 * Liest `kuml.layout.*` aus den Element-`metadata`-Maps und materialisiert
 * sie als [LayoutNode.hints] (NodeHints). Beziehungen werden zu [LayoutEdge].
 * UML-Packages werden zu [LayoutGroup]s (V1: keine Verschachtelung).
 *
 * Diagrammtypen: Class, Component, UseCase, State. Sequenzdiagramme sind
 * nicht im Scope — sie haben ihre eigene Pipeline.
 *
 * Beispiel:
 * ```kotlin
 * val graph = UmlLayoutBridge.toLayoutGraph(diagram)
 * ```
 */
public object UmlLayoutBridge {
    /**
     * Übersetzt [diagram] in einen [LayoutGraph].
     *
     * Elemente in [KumlDiagram.elements] werden wie folgt verarbeitet:
     * - [UmlPackage] → [LayoutGroup] (parent = null, V1: keine Verschachtelung);
     *   direkte Members werden zu [LayoutNode] mit gesetztem groupId.
     * - Weitere [UmlNamedElement]-Subtypen (außer [UmlPackage]) → [LayoutNode].
     * - [UmlRelationship]-Subtypen → [LayoutEdge] (EdgeHints.NONE in V1).
     * - Alles andere wird schweigend ignoriert.
     *
     * @param diagram Das zu übersetzende UML-Diagramm.
     * @param sizeProvider Liefert die intrinsische Größe pro Knoten.
     */
    public fun toLayoutGraph(
        diagram: KumlDiagram,
        sizeProvider: SizeProvider = SizeProvider.constant(),
    ): LayoutGraph {
        val nodes = mutableListOf<LayoutNode>()
        val edges = mutableListOf<LayoutEdge>()
        val groups = mutableListOf<LayoutGroup>()

        // Pre-pass: collect port names per component for connector-endpoint
        // splitting (componentId::portName → nodeId+portId). Walks packages,
        // sub-packages and nested components transitively.
        val componentPorts: Map<String, Set<String>> = collectComponentPorts(diagram)

        for (element in diagram.elements) {
            when (element) {
                is UmlPackage -> {
                    // V1: max. 1 Ebene — Package wird zur Group, Members zu Nodes
                    val groupId = GroupId(element.id)
                    groups.add(LayoutGroup(id = groupId, parent = null))
                    for (member in element.members) {
                        when (member) {
                            is UmlPackage -> {
                                // Sub-Package: eigene eigenständige Group ohne parent (V1: keine Verschachtelung)
                                val subGroupId = GroupId(member.id)
                                groups.add(LayoutGroup(id = subGroupId, parent = null))
                                for (subMember in member.members) {
                                    if (subMember !is UmlPackage && subMember !is UmlRelationship) {
                                        nodes.add(
                                            LayoutNode(
                                                id = NodeId(subMember.id),
                                                intrinsicSize =
                                                    sizeProvider.sizeOf(
                                                        subMember.id,
                                                        subMember::class.simpleName ?: "Unknown",
                                                    ),
                                                hints = HintsReader.read(subMember.metadata),
                                                groupId = subGroupId,
                                            ),
                                        )
                                    }
                                }
                            }
                            is UmlRelationship -> {
                                // Relationships inside packages are treated as edges
                                val endpoints = EndpointResolver.resolveWithPorts(member, componentPorts)
                                if (endpoints != null) {
                                    edges.add(toEdge(member.id, endpoints))
                                }
                            }
                            else -> {
                                // Regular named element inside a package
                                nodes.add(
                                    LayoutNode(
                                        id = NodeId(member.id),
                                        intrinsicSize =
                                            sizeProvider.sizeOf(
                                                member.id,
                                                member::class.simpleName ?: "Unknown",
                                            ),
                                        hints = HintsReader.read(member.metadata),
                                        groupId = groupId,
                                    ),
                                )
                            }
                        }
                    }
                }
                is UmlRelationship -> {
                    val endpoints = EndpointResolver.resolveWithPorts(element, componentPorts)
                    if (endpoints != null) {
                        edges.add(toEdge(element.id, endpoints))
                    }
                }
                is UmlInteraction -> {
                    // SEQ: one LayoutNode per lifeline, height from message count. No edges —
                    // messages and fragments are rendered directly by KumlSvgRenderer.
                    val maxSeq = element.messages.maxOfOrNull { it.sequence } ?: 0
                    val rowCount = if (maxSeq < 1) 1 else maxSeq
                    val nodeH =
                        Sysml2LayoutBridge.SEQ_LIFELINE_HEAD_HEIGHT +
                            (rowCount + 1) * Sysml2LayoutBridge.SEQ_MESSAGE_ROW_HEIGHT +
                            Sysml2LayoutBridge.SEQ_LIFELINE_TAIL_PADDING
                    for (lifeline in element.lifelines) {
                        nodes.add(
                            LayoutNode(
                                id = NodeId(lifeline.id),
                                intrinsicSize = Size(Sysml2LayoutBridge.SEQ_LIFELINE_WIDTH, nodeH),
                            ),
                        )
                    }
                }
                is UmlStateMachine -> {
                    // Create a group for the state machine frame so ELK encloses all vertices
                    val smGroupId = GroupId(element.id)
                    groups.add(LayoutGroup(id = smGroupId, parent = null, padding = Insets(32f, 16f, 24f, 16f)))

                    // Collect all vertices flat (including substates) and add as LayoutNodes
                    fun collectVertices(vertices: List<UmlVertex>) {
                        for (vertex in vertices) {
                            val size =
                                when (vertex) {
                                    is UmlPseudostate -> Size(24f, 24f)
                                    is UmlFinalState -> Size(28f, 28f)
                                    is UmlState -> sizeProvider.sizeOf(vertex.id, "UmlState")
                                }
                            nodes.add(
                                LayoutNode(
                                    id = NodeId(vertex.id),
                                    intrinsicSize = size,
                                    hints = HintsReader.read(vertex.metadata),
                                    groupId = smGroupId,
                                ),
                            )
                            // Recurse into composite state substates (flat — same group)
                            if (vertex is UmlState && vertex.substates.isNotEmpty()) {
                                collectVertices(vertex.substates)
                            }
                        }
                    }
                    collectVertices(element.vertices)

                    // Transitions as edges
                    for (transition in element.transitions) {
                        edges.add(
                            LayoutEdge(
                                id = EdgeId(transition.id),
                                source = EndpointRef(nodeId = NodeId(transition.sourceId)),
                                target = EndpointRef(nodeId = NodeId(transition.targetId)),
                                hints = EdgeHints.NONE,
                            ),
                        )
                    }
                }
                is UmlNamedElement -> {
                    // Any other named element (classifier, state machine vertex, etc.)
                    nodes.add(
                        LayoutNode(
                            id = NodeId(element.id),
                            intrinsicSize =
                                sizeProvider.sizeOf(
                                    element.id,
                                    element::class.simpleName ?: "Unknown",
                                ),
                            hints = HintsReader.read(element.metadata),
                            groupId = null,
                        ),
                    )
                }
                else -> {
                    // Non-UML or non-named elements: silently ignored
                }
            }
        }

        return LayoutGraph(nodes = nodes, edges = edges, groups = groups)
    }

    private fun toEdge(
        edgeId: String,
        endpoints: ResolvedEndpoints,
    ): LayoutEdge =
        LayoutEdge(
            id = EdgeId(edgeId),
            source =
                EndpointRef(
                    nodeId = NodeId(endpoints.sourceNodeId),
                    portId = endpoints.sourcePortId?.let(::PortId),
                ),
            target =
                EndpointRef(
                    nodeId = NodeId(endpoints.targetNodeId),
                    portId = endpoints.targetPortId?.let(::PortId),
                ),
            hints = EdgeHints.NONE,
        )

    /**
     * Walks the diagram and collects, per component ID, the set of declared port
     * names. Required so [EndpointResolver.resolveWithPorts] can split
     * qualified connector endpoint IDs (`"compId::portName"`) into node + port.
     *
     * Handles components at top level, inside packages (incl. sub-packages),
     * and nested components inside other components.
     */
    private fun collectComponentPorts(diagram: KumlDiagram): Map<String, Set<String>> {
        val result = mutableMapOf<String, Set<String>>()
        for (element in diagram.elements) {
            when (element) {
                is UmlComponent -> collectFromComponent(element, result)
                is UmlPackage -> collectFromPackage(element, result)
                else -> {} // ignore
            }
        }
        return result
    }

    private fun collectFromPackage(
        pkg: UmlPackage,
        out: MutableMap<String, Set<String>>,
    ) {
        for (member in pkg.members) {
            when (member) {
                is UmlComponent -> collectFromComponent(member, out)
                is UmlPackage -> collectFromPackage(member, out)
                else -> {}
            }
        }
    }

    private fun collectFromComponent(
        component: UmlComponent,
        out: MutableMap<String, Set<String>>,
    ) {
        out[component.id] = component.ports.map { it.name }.toSet()
        for (nested in component.nestedComponents) {
            collectFromComponent(nested, out)
        }
    }
}
