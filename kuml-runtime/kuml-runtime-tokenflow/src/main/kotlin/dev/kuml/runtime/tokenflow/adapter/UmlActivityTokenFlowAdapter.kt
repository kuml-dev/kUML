package dev.kuml.runtime.tokenflow.adapter

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.runtime.tokenflow.GatewayFamily
import dev.kuml.runtime.tokenflow.GatewaySpec
import dev.kuml.runtime.tokenflow.TokenFlowEdge
import dev.kuml.runtime.tokenflow.TokenFlowNode
import dev.kuml.runtime.tokenflow.TokenFlowSpec
import dev.kuml.runtime.tokenflow.TokenNodeKind
import dev.kuml.uml.UmlActivityEdge
import dev.kuml.uml.UmlActivityNode
import dev.kuml.uml.UmlActivityNodeKind

/**
 * Builds a [TokenFlowSpec] from a UML Activity [KumlDiagram] (`type ==
 * DiagramType.ACTIVITY`), e.g. one built via `activityDiagram("…") { … }`.
 *
 * A diagram produced by `kuml transform` from a BPMN process (see
 * `dev.kuml.transform.bpmnuml.BpmnToUmlActivityMapper`) carries
 * `metadata["bpmn.gatewayType"] == "INCLUSIVE"` on gateway nodes that were
 * originally BPMN `INCLUSIVE` gateways — this adapter reads that metadata to
 * recover the OR-semantics that the UML metamodel itself cannot express
 * (UML only has Decision/Merge = exclusive, Fork/Join = parallel), so a
 * round-tripped BPMN→UML Activity diagram remains faithfully executable.
 */
public object UmlActivityTokenFlowAdapter {
    private const val META_BPMN_GATEWAY_TYPE = "bpmn.gatewayType"

    /** @throws IllegalArgumentException if `diagram.type != DiagramType.ACTIVITY`. */
    public fun toSpec(diagram: KumlDiagram): TokenFlowSpec {
        require(diagram.type == DiagramType.ACTIVITY) {
            "UmlActivityTokenFlowAdapter requires a diagram of type ACTIVITY, got ${diagram.type}."
        }

        val umlNodes = diagram.elements.filterIsInstance<UmlActivityNode>()
        val umlEdges = diagram.elements.filterIsInstance<UmlActivityEdge>()

        val nodes =
            umlNodes.map { n ->
                val inclusiveOverride =
                    (n.metadata[META_BPMN_GATEWAY_TYPE] as? dev.kuml.core.model.KumlMetaValue.Text)?.value == "INCLUSIVE"
                when (n.kind) {
                    UmlActivityNodeKind.INITIAL -> TokenFlowNode(id = n.id, kind = TokenNodeKind.INITIAL, name = n.name)
                    UmlActivityNodeKind.ACTION -> TokenFlowNode(id = n.id, kind = TokenNodeKind.ACTION, name = n.name, actionBody = n.name)
                    UmlActivityNodeKind.ACTIVITY_FINAL -> TokenFlowNode(id = n.id, kind = TokenNodeKind.TERMINATE_FINAL, name = n.name)
                    UmlActivityNodeKind.FLOW_FINAL -> TokenFlowNode(id = n.id, kind = TokenNodeKind.FLOW_FINAL, name = n.name)
                    UmlActivityNodeKind.OBJECT -> TokenFlowNode(id = n.id, kind = TokenNodeKind.OBJECT_NODE, name = n.name)
                    UmlActivityNodeKind.DECISION ->
                        TokenFlowNode(
                            id = n.id,
                            kind = TokenNodeKind.GATEWAY,
                            gateway =
                                GatewaySpec(
                                    family = if (inclusiveOverride) GatewayFamily.INCLUSIVE else GatewayFamily.EXCLUSIVE,
                                    converging = false,
                                    diverging = true,
                                ),
                            name = n.name,
                        )
                    UmlActivityNodeKind.MERGE ->
                        TokenFlowNode(
                            id = n.id,
                            kind = TokenNodeKind.GATEWAY,
                            gateway =
                                GatewaySpec(
                                    family = if (inclusiveOverride) GatewayFamily.INCLUSIVE else GatewayFamily.EXCLUSIVE,
                                    converging = true,
                                    diverging = false,
                                ),
                            name = n.name,
                        )
                    UmlActivityNodeKind.FORK ->
                        TokenFlowNode(
                            id = n.id,
                            kind = TokenNodeKind.GATEWAY,
                            gateway = GatewaySpec(family = GatewayFamily.PARALLEL, converging = false, diverging = true),
                            name = n.name,
                        )
                    UmlActivityNodeKind.JOIN ->
                        TokenFlowNode(
                            id = n.id,
                            kind = TokenNodeKind.GATEWAY,
                            gateway = GatewaySpec(family = GatewayFamily.PARALLEL, converging = true, diverging = false),
                            name = n.name,
                        )
                }
            }

        val edges =
            umlEdges.map { e ->
                TokenFlowEdge(
                    id = e.id,
                    sourceNodeId = e.sourceId,
                    targetNodeId = e.targetId,
                    guard = e.guard,
                    isObjectFlow = e.isObjectFlow,
                )
            }

        return TokenFlowSpec.of(id = diagram.id, name = diagram.name, nodes = nodes, edges = edges)
    }
}
