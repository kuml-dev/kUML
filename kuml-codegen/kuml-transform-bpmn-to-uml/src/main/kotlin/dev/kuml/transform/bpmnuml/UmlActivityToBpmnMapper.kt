package dev.kuml.transform.bpmnuml

import dev.kuml.bpmn.model.BpmnEvent
import dev.kuml.bpmn.model.BpmnGateway
import dev.kuml.bpmn.model.BpmnProcess
import dev.kuml.bpmn.model.BpmnTask
import dev.kuml.bpmn.model.EventBehaviour
import dev.kuml.bpmn.model.EventDefinition
import dev.kuml.bpmn.model.EventPosition
import dev.kuml.bpmn.model.GatewayDirection
import dev.kuml.bpmn.model.GatewayType
import dev.kuml.bpmn.model.SequenceFlow
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlMetaValue
import dev.kuml.uml.UmlActivityEdge
import dev.kuml.uml.UmlActivityNode
import dev.kuml.uml.UmlActivityNodeKind

/**
 * Reverse mapping from a [KumlDiagram] (type = ACTIVITY) back to a [BpmnProcess].
 *
 * Mapping rules:
 * - [UmlActivityNodeKind.ACTION] → [BpmnTask]
 * - [UmlActivityNodeKind.INITIAL] → [BpmnEvent] (START, CATCHING)
 * - [UmlActivityNodeKind.ACTIVITY_FINAL] / [UmlActivityNodeKind.FLOW_FINAL] → [BpmnEvent] (END, THROWING)
 * - [UmlActivityNodeKind.DECISION] → [BpmnGateway] (EXCLUSIVE, DIVERGING)
 * - [UmlActivityNodeKind.MERGE] → [BpmnGateway] (EXCLUSIVE, CONVERGING)
 * - [UmlActivityNodeKind.FORK] → [BpmnGateway] (PARALLEL, DIVERGING)
 * - [UmlActivityNodeKind.JOIN] → [BpmnGateway] (PARALLEL, CONVERGING)
 * - [UmlActivityNodeKind.OBJECT] → [dev.kuml.bpmn.model.BpmnDataObject] (best-effort)
 * - [UmlActivityEdge] → [SequenceFlow] (guard → conditionExpression)
 *
 * DECISION+MERGE (or FORK+JOIN) pairs that share the same `"bpmn.sourceId"` metadata
 * are collapsed back into a single MIXED [BpmnGateway] so that round-trips are
 * lossless for the MIXED-gateway case.
 */
internal object UmlActivityToBpmnMapper {
    private const val META_BPMN_SOURCE_ID = "bpmn.sourceId"

    /** Maps the given diagram to a [BpmnProcess]. Returns null if the diagram is not ACTIVITY type. */
    fun map(diagram: KumlDiagram): BpmnProcess? {
        if (diagram.type != DiagramType.ACTIVITY) return null

        val activityNodes = diagram.elements.filterIsInstance<UmlActivityNode>()
        val activityEdges = diagram.elements.filterIsInstance<UmlActivityEdge>()

        // Detect DECISION+MERGE (and FORK+JOIN) pairs sharing the same bpmn.sourceId
        // so we can collapse them back into a single MIXED gateway.
        // bpmnSourceId → list of UML node ids that share it
        val sourceIdToNodes = mutableMapOf<String, MutableList<UmlActivityNode>>()
        for (node in activityNodes) {
            val srcId = (node.metadata[META_BPMN_SOURCE_ID] as? KumlMetaValue.Text)?.value ?: continue
            sourceIdToNodes.getOrPut(srcId) { mutableListOf() } += node
        }

        // Pairs: bpmnSourceId → (merge/join node, decision/fork node) — only when there are 2 nodes
        data class MixedPair(
            val convergeNode: UmlActivityNode,
            val divergeNode: UmlActivityNode,
        )

        val mixedPairs = mutableMapOf<String, MixedPair>() // bpmnSourceId → pair
        val collapsedUmlIds = mutableSetOf<String>() // UML node ids that are part of a collapsed pair

        for ((srcId, nodes) in sourceIdToNodes) {
            if (nodes.size == 2) {
                val converge = nodes.firstOrNull { it.kind in setOf(UmlActivityNodeKind.MERGE, UmlActivityNodeKind.JOIN) }
                val diverge = nodes.firstOrNull { it.kind in setOf(UmlActivityNodeKind.DECISION, UmlActivityNodeKind.FORK) }
                if (converge != null && diverge != null) {
                    mixedPairs[srcId] = MixedPair(converge, diverge)
                    collapsedUmlIds += converge.id
                    collapsedUmlIds += diverge.id
                }
            }
        }

        val flowNodes = mutableListOf<dev.kuml.bpmn.model.BpmnFlowNode>()
        // umlNodeId → bpmn element id (for flow rewiring)
        val umlIdToBpmnId = mutableMapOf<String, String>()
        var counter = 0

        fun nextId(prefix: String): String = "${diagram.id}_${prefix}_${++counter}"

        // Emit flow nodes — skip nodes that belong to a collapsed pair (we emit one gateway for the pair)
        for (node in activityNodes) {
            if (node.id in collapsedUmlIds) continue

            when (node.kind) {
                UmlActivityNodeKind.ACTION -> {
                    val bpmnId = nextId("task")
                    flowNodes += BpmnTask(id = bpmnId, name = node.name.takeIf { it.isNotBlank() })
                    umlIdToBpmnId[node.id] = bpmnId
                }

                UmlActivityNodeKind.INITIAL -> {
                    val bpmnId = nextId("start")
                    flowNodes +=
                        BpmnEvent(
                            id = bpmnId,
                            name = node.name.takeIf { it.isNotBlank() },
                            position = EventPosition.START,
                            behaviour = EventBehaviour.CATCHING,
                            definition = EventDefinition.NONE,
                        )
                    umlIdToBpmnId[node.id] = bpmnId
                }

                UmlActivityNodeKind.ACTIVITY_FINAL, UmlActivityNodeKind.FLOW_FINAL -> {
                    val bpmnId = nextId("end")
                    flowNodes +=
                        BpmnEvent(
                            id = bpmnId,
                            name = node.name.takeIf { it.isNotBlank() },
                            position = EventPosition.END,
                            behaviour = EventBehaviour.THROWING,
                            definition = EventDefinition.NONE,
                        )
                    umlIdToBpmnId[node.id] = bpmnId
                }

                UmlActivityNodeKind.DECISION -> {
                    val bpmnId = nextId("gw")
                    flowNodes +=
                        BpmnGateway(
                            id = bpmnId,
                            name = node.name.takeIf { it.isNotBlank() },
                            gatewayType = GatewayType.EXCLUSIVE,
                            direction = GatewayDirection.DIVERGING,
                        )
                    umlIdToBpmnId[node.id] = bpmnId
                }

                UmlActivityNodeKind.MERGE -> {
                    val bpmnId = nextId("gw")
                    flowNodes +=
                        BpmnGateway(
                            id = bpmnId,
                            name = node.name.takeIf { it.isNotBlank() },
                            gatewayType = GatewayType.EXCLUSIVE,
                            direction = GatewayDirection.CONVERGING,
                        )
                    umlIdToBpmnId[node.id] = bpmnId
                }

                UmlActivityNodeKind.FORK -> {
                    val bpmnId = nextId("gw")
                    flowNodes +=
                        BpmnGateway(
                            id = bpmnId,
                            name = node.name.takeIf { it.isNotBlank() },
                            gatewayType = GatewayType.PARALLEL,
                            direction = GatewayDirection.DIVERGING,
                        )
                    umlIdToBpmnId[node.id] = bpmnId
                }

                UmlActivityNodeKind.JOIN -> {
                    val bpmnId = nextId("gw")
                    flowNodes +=
                        BpmnGateway(
                            id = bpmnId,
                            name = node.name.takeIf { it.isNotBlank() },
                            gatewayType = GatewayType.PARALLEL,
                            direction = GatewayDirection.CONVERGING,
                        )
                    umlIdToBpmnId[node.id] = bpmnId
                }

                UmlActivityNodeKind.OBJECT -> {
                    // Best-effort: map as BpmnTask (BpmnDataObject cannot appear in sequenceFlows as source/target directly)
                    val bpmnId = nextId("task")
                    flowNodes += BpmnTask(id = bpmnId, name = node.name.takeIf { it.isNotBlank() })
                    umlIdToBpmnId[node.id] = bpmnId
                }
            }
        }

        // Emit collapsed MIXED gateways
        for ((srcId, pair) in mixedPairs) {
            val bpmnId = srcId // restore the original BPMN id for lossless round-trip
            val isParallel = pair.convergeNode.kind == UmlActivityNodeKind.JOIN
            flowNodes +=
                BpmnGateway(
                    id = bpmnId,
                    name = pair.convergeNode.name.takeIf { it.isNotBlank() },
                    gatewayType = if (isParallel) GatewayType.PARALLEL else GatewayType.EXCLUSIVE,
                    direction = GatewayDirection.MIXED,
                )
            umlIdToBpmnId[pair.convergeNode.id] = bpmnId
            umlIdToBpmnId[pair.divergeNode.id] = bpmnId
        }

        // Map edges → sequence flows, skipping synthetic internal edges between split gateway nodes
        val syntheticEdgeTargets = mixedPairs.values.map { it.divergeNode.id }.toSet()
        val syntheticEdgeSources = mixedPairs.values.map { it.convergeNode.id }.toSet()

        val sequenceFlows = mutableListOf<SequenceFlow>()
        var flowCounter = 0
        for (edge in activityEdges) {
            // Skip the internal synthetic edge between merge/join → decision/fork within a mixed pair
            val isSyntheticInternal =
                edge.sourceId in syntheticEdgeSources &&
                    edge.targetId in syntheticEdgeTargets &&
                    run {
                        val srcNode = activityNodes.firstOrNull { it.id == edge.sourceId }
                        val tgtNode = activityNodes.firstOrNull { it.id == edge.targetId }
                        srcNode != null &&
                            tgtNode != null &&
                            (srcNode.metadata[META_BPMN_SOURCE_ID] as? KumlMetaValue.Text)?.value ==
                            (tgtNode.metadata[META_BPMN_SOURCE_ID] as? KumlMetaValue.Text)?.value
                    }
            if (isSyntheticInternal) continue

            val srcBpmnId = umlIdToBpmnId[edge.sourceId] ?: continue
            val tgtBpmnId = umlIdToBpmnId[edge.targetId] ?: continue

            flowCounter++
            sequenceFlows +=
                SequenceFlow(
                    id = "${diagram.id}_flow_$flowCounter",
                    sourceRef = srcBpmnId,
                    targetRef = tgtBpmnId,
                    conditionExpression = edge.guard,
                )
        }

        // Populate incoming/outgoing lists on each flow node by scanning sequence flows
        val incomingFlows = sequenceFlows.groupBy { it.targetRef }.mapValues { it.value.map { f -> f.id } }
        val outgoingFlows = sequenceFlows.groupBy { it.sourceRef }.mapValues { it.value.map { f -> f.id } }

        val enrichedNodes =
            flowNodes.map { node ->
                val inc = incomingFlows[node.id] ?: emptyList()
                val out = outgoingFlows[node.id] ?: emptyList()
                when (node) {
                    is BpmnTask -> node.copy(incoming = inc, outgoing = out)
                    is BpmnEvent -> node.copy(incoming = inc, outgoing = out)
                    is BpmnGateway -> {
                        val dir =
                            when {
                                inc.size > 1 && out.size > 1 -> GatewayDirection.MIXED
                                out.size > 1 -> GatewayDirection.DIVERGING
                                inc.size > 1 -> GatewayDirection.CONVERGING
                                else -> node.direction
                            }
                        node.copy(incoming = inc, outgoing = out, direction = dir)
                    }
                    else -> node
                }
            }

        return BpmnProcess(
            id = diagram.id,
            name = diagram.name,
            flowNodes = enrichedNodes,
            sequenceFlows = sequenceFlows,
        )
    }
}
