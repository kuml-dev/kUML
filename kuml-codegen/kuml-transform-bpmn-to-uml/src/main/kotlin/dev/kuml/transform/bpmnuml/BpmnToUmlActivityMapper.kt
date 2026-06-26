package dev.kuml.transform.bpmnuml

import dev.kuml.bpmn.model.BpmnEvent
import dev.kuml.bpmn.model.BpmnGateway
import dev.kuml.bpmn.model.BpmnProcess
import dev.kuml.bpmn.model.BpmnTask
import dev.kuml.bpmn.model.EventPosition
import dev.kuml.bpmn.model.GatewayType
import dev.kuml.bpmn.model.SequenceFlow
import dev.kuml.core.model.KumlMetaValue
import dev.kuml.uml.UmlActivityEdge
import dev.kuml.uml.UmlActivityNode
import dev.kuml.uml.UmlActivityNodeKind

/**
 * Pure mapping logic from a [BpmnProcess] to a [UmlActivityModel].
 *
 * Mapping rules:
 * - [BpmnTask] → [UmlActivityNodeKind.ACTION]
 * - [BpmnEvent] (START) → [UmlActivityNodeKind.INITIAL]
 * - [BpmnEvent] (END) → [UmlActivityNodeKind.ACTIVITY_FINAL]
 * - [BpmnGateway] (EXCLUSIVE / INCLUSIVE), diverging → [UmlActivityNodeKind.DECISION]
 * - [BpmnGateway] (EXCLUSIVE / INCLUSIVE), converging → [UmlActivityNodeKind.MERGE]
 * - [BpmnGateway] (PARALLEL), diverging → [UmlActivityNodeKind.FORK]
 * - [BpmnGateway] (PARALLEL), converging → [UmlActivityNodeKind.JOIN]
 * - Mixed gateways (incoming > 1 AND outgoing > 1) are split into two UML nodes
 *   (MERGE/JOIN → DECISION/FORK) sharing the same `bpmn.sourceId` metadata so the
 *   reverse mapper can collapse them losslessly.
 * - [SequenceFlow] → [UmlActivityEdge] with [SequenceFlow.conditionExpression] as guard.
 *
 * Lane membership (from BpmnCollaboration / BpmnLane) is best-effort only: the
 * lane name is recorded in node metadata under the key `"uml.partition"` and
 * emitted as a comment in the generated script. No partition node is created
 * because the kUML UML Activity metamodel has no PARTITION node kind.
 */
internal object BpmnToUmlActivityMapper {
    private const val META_BPMN_SOURCE_ID = "bpmn.sourceId"

    fun map(process: BpmnProcess): UmlActivityModel {
        val nodes = mutableListOf<UmlActivityNode>()
        val edges = mutableListOf<UmlActivityEdge>()

        // id → list of incoming/outgoing flow ids — computed from sequenceFlows directly
        val incomingCount = mutableMapOf<String, Int>()
        val outgoingCount = mutableMapOf<String, Int>()
        for (flow in process.sequenceFlows) {
            outgoingCount[flow.sourceRef] = (outgoingCount[flow.sourceRef] ?: 0) + 1
            incomingCount[flow.targetRef] = (incomingCount[flow.targetRef] ?: 0) + 1
        }

        // Maps original BPMN node id to UML node id(s) produced for it.
        // Simple nodes: 1-to-1; MIXED gateways: 1-to-2 (merge/join id, then decision/fork id).
        val bpmnIdToUmlIds = mutableMapOf<String, List<String>>() // sourceId -> [node1Id, (node2Id)?]

        var edgeCounter = 0

        for (node in process.flowNodes) {
            when (node) {
                is BpmnTask -> {
                    val umlNode =
                        UmlActivityNode(
                            id = node.id,
                            name = node.name ?: node.id,
                            kind = UmlActivityNodeKind.ACTION,
                            metadata = mapOf(META_BPMN_SOURCE_ID to KumlMetaValue.Text(node.id)),
                        )
                    nodes += umlNode
                    bpmnIdToUmlIds[node.id] = listOf(node.id)
                }

                is BpmnEvent -> {
                    val kind =
                        when (node.position) {
                            EventPosition.START -> UmlActivityNodeKind.INITIAL
                            EventPosition.END -> UmlActivityNodeKind.ACTIVITY_FINAL
                            EventPosition.INTERMEDIATE -> UmlActivityNodeKind.ACTION
                        }
                    val umlNode =
                        UmlActivityNode(
                            id = node.id,
                            name = node.name ?: "",
                            kind = kind,
                            metadata = mapOf(META_BPMN_SOURCE_ID to KumlMetaValue.Text(node.id)),
                        )
                    nodes += umlNode
                    bpmnIdToUmlIds[node.id] = listOf(node.id)
                }

                is BpmnGateway -> {
                    val inc = incomingCount[node.id] ?: 0
                    val out = outgoingCount[node.id] ?: 0
                    val isMixed = inc > 1 && out > 1

                    if (isMixed) {
                        // Split into two nodes: (MERGE or JOIN) → (DECISION or FORK)
                        val (convergeKind, divergeKind) =
                            when (node.gatewayType) {
                                GatewayType.PARALLEL -> UmlActivityNodeKind.JOIN to UmlActivityNodeKind.FORK
                                else -> UmlActivityNodeKind.MERGE to UmlActivityNodeKind.DECISION
                            }
                        val mergeId = "${node.id}_merge"
                        val decisionId = "${node.id}_decision"
                        val inclusiveMeta =
                            if (node.gatewayType == GatewayType.INCLUSIVE) {
                                mapOf(
                                    META_BPMN_SOURCE_ID to KumlMetaValue.Text(node.id),
                                    "bpmn.gatewayType" to KumlMetaValue.Text("INCLUSIVE"),
                                )
                            } else {
                                mapOf(META_BPMN_SOURCE_ID to KumlMetaValue.Text(node.id))
                            }

                        nodes +=
                            UmlActivityNode(
                                id = mergeId,
                                name = node.name ?: "",
                                kind = convergeKind,
                                metadata = inclusiveMeta,
                            )
                        nodes +=
                            UmlActivityNode(
                                id = decisionId,
                                name = node.name ?: "",
                                kind = divergeKind,
                                metadata = inclusiveMeta,
                            )
                        // Internal synthetic edge between the two split nodes
                        edgeCounter++
                        edges +=
                            UmlActivityEdge(
                                id = "${node.id}_internal_$edgeCounter",
                                sourceId = mergeId,
                                targetId = decisionId,
                            )
                        bpmnIdToUmlIds[node.id] = listOf(mergeId, decisionId)
                    } else {
                        // Single node
                        val kind =
                            when (node.gatewayType) {
                                GatewayType.PARALLEL ->
                                    if (out > 1) UmlActivityNodeKind.FORK else UmlActivityNodeKind.JOIN

                                else ->
                                    if (out > 1) UmlActivityNodeKind.DECISION else UmlActivityNodeKind.MERGE
                            }
                        val meta =
                            if (node.gatewayType == GatewayType.INCLUSIVE) {
                                mapOf(
                                    META_BPMN_SOURCE_ID to KumlMetaValue.Text(node.id),
                                    "bpmn.gatewayType" to KumlMetaValue.Text("INCLUSIVE"),
                                )
                            } else {
                                mapOf(META_BPMN_SOURCE_ID to KumlMetaValue.Text(node.id))
                            }
                        nodes +=
                            UmlActivityNode(
                                id = node.id,
                                name = node.name ?: "",
                                kind = kind,
                                metadata = meta,
                            )
                        bpmnIdToUmlIds[node.id] = listOf(node.id)
                    }
                }

                else -> {
                    // BpmnSubProcess, BpmnCallActivity — map as ACTION (best-effort)
                    val umlNode =
                        UmlActivityNode(
                            id = node.id,
                            name = node.name ?: node.id,
                            kind = UmlActivityNodeKind.ACTION,
                            metadata = mapOf(META_BPMN_SOURCE_ID to KumlMetaValue.Text(node.id)),
                        )
                    nodes += umlNode
                    bpmnIdToUmlIds[node.id] = listOf(node.id)
                }
            }
        }

        // Map sequence flows → UML activity edges.
        // For MIXED gateways: incoming flows → merge/join node; outgoing flows → decision/fork node.
        for (flow in process.sequenceFlows) {
            val sourceIds = bpmnIdToUmlIds[flow.sourceRef] ?: continue
            val targetIds = bpmnIdToUmlIds[flow.targetRef] ?: continue

            // sourceId: for MIXED gateways pick the LAST node (decision/fork = diverging)
            val sourceUmlId = sourceIds.last()
            // targetId: for MIXED gateways pick the FIRST node (merge/join = converging)
            val targetUmlId = targetIds.first()

            edgeCounter++
            edges +=
                UmlActivityEdge(
                    id = "${flow.id}_e$edgeCounter",
                    sourceId = sourceUmlId,
                    targetId = targetUmlId,
                    guard = flow.conditionExpression,
                    metadata = mapOf(META_BPMN_SOURCE_ID to KumlMetaValue.Text(flow.id)),
                )
        }

        val diagramName = process.name ?: process.id
        return UmlActivityModel(name = diagramName, nodes = nodes, edges = edges)
    }
}
