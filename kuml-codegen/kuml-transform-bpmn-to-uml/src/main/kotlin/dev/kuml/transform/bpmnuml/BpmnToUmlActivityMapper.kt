package dev.kuml.transform.bpmnuml

import dev.kuml.bpmn.model.BpmnEvent
import dev.kuml.bpmn.model.BpmnGateway
import dev.kuml.bpmn.model.BpmnLane
import dev.kuml.bpmn.model.BpmnProcess
import dev.kuml.bpmn.model.BpmnTask
import dev.kuml.bpmn.model.EventDefinition
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
 * - [BpmnEvent] (END, definition = TERMINATE or NONE) → [UmlActivityNodeKind.ACTIVITY_FINAL]
 *   A TERMINATE end event terminates the whole activity; a plain (NONE) end event also
 *   maps to ACTIVITY_FINAL because it consumes the token at the process level.
 * - [BpmnEvent] (END, definition = MESSAGE / SIGNAL / ERROR / ESCALATION / COMPENSATION /
 *   CONDITIONAL / CANCEL / MULTIPLE / PARALLEL_MULTIPLE / LINK) → [UmlActivityNodeKind.FLOW_FINAL]
 *   These end event types terminate a single flow token (not the whole activity), which is
 *   the UML semantics of FlowFinalNode (circle with X).
 * - [BpmnEvent] (INTERMEDIATE) → [UmlActivityNodeKind.ACTION]
 *   The UML Activity metamodel has no equivalent for intermediate catching/throwing events.
 *   They are mapped to ACTION (the closest structural approximation) and tagged with
 *   metadata `"bpmn.eventPosition" = "INTERMEDIATE"` and `"bpmn.eventDefinition" = <def>`
 *   so downstream tools can recognise and re-specialise them if needed.
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
 * To supply lane information, use [map(BpmnProcess, List<BpmnLane>)].
 */
internal object BpmnToUmlActivityMapper {
    private const val META_BPMN_SOURCE_ID = "bpmn.sourceId"
    private const val META_BPMN_EVENT_POSITION = "bpmn.eventPosition"
    private const val META_BPMN_EVENT_DEFINITION = "bpmn.eventDefinition"
    private const val META_UML_PARTITION = "uml.partition"

    /**
     * Maps a [BpmnProcess] to a [UmlActivityModel] without lane information.
     *
     * Equivalent to `map(process, emptyList())`.
     */
    fun map(process: BpmnProcess): UmlActivityModel = map(process, emptyList())

    /**
     * Maps a [BpmnProcess] to a [UmlActivityModel], enriching mapped nodes with
     * lane membership metadata when [lanes] is non-empty.
     *
     * Each flow node ID found in a [BpmnLane.flowNodeRefs] list is annotated with
     * `"uml.partition" = <lane name>` in the resulting [UmlActivityNode.metadata].
     * Nested child lanes are also walked recursively — the innermost lane name wins
     * when a node appears in multiple nesting levels.
     *
     * No structural partition node is emitted because the kUML UML Activity metamodel
     * has no PARTITION node kind.
     *
     * @param process The BPMN process to transform.
     * @param lanes   All lanes from the enclosing [dev.kuml.bpmn.model.BpmnParticipant]
     *                (or an empty list for a flat process without lanes).
     */
    fun map(
        process: BpmnProcess,
        lanes: List<BpmnLane>,
    ): UmlActivityModel {
        // Build nodeId → lane name index (innermost lane wins on overlap)
        val laneIndex = buildLaneIndex(lanes)

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
                    val meta = buildBaseMeta(node.id, laneIndex)
                    val umlNode =
                        UmlActivityNode(
                            id = node.id,
                            name = node.name ?: node.id,
                            kind = UmlActivityNodeKind.ACTION,
                            metadata = meta,
                        )
                    nodes += umlNode
                    bpmnIdToUmlIds[node.id] = listOf(node.id)
                }

                is BpmnEvent -> {
                    val (kind, extraMeta) = mapEventKind(node.position, node.definition)
                    val meta = buildBaseMeta(node.id, laneIndex) + extraMeta
                    val umlNode =
                        UmlActivityNode(
                            id = node.id,
                            name = node.name ?: "",
                            kind = kind,
                            metadata = meta,
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
                            buildGatewayMeta(node.id, node.gatewayType, laneIndex)

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
                        val meta = buildGatewayMeta(node.id, node.gatewayType, laneIndex)
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
                    val meta = buildBaseMeta(node.id, laneIndex)
                    val umlNode =
                        UmlActivityNode(
                            id = node.id,
                            name = node.name ?: node.id,
                            kind = UmlActivityNodeKind.ACTION,
                            metadata = meta,
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

    /**
     * Maps a BPMN event position and definition to a UML activity node kind plus
     * any additional metadata entries needed to preserve BPMN semantics.
     *
     * - START → INITIAL (no extra metadata)
     * - END + TERMINATE or NONE → ACTIVITY_FINAL (no extra metadata)
     * - END + any other definition → FLOW_FINAL (no extra metadata)
     * - INTERMEDIATE → ACTION + `bpmn.eventPosition` + `bpmn.eventDefinition` metadata
     */
    private fun mapEventKind(
        position: EventPosition,
        definition: EventDefinition,
    ): Pair<UmlActivityNodeKind, Map<String, KumlMetaValue>> =
        when (position) {
            EventPosition.START -> UmlActivityNodeKind.INITIAL to emptyMap()
            EventPosition.END ->
                // TERMINATE and NONE end events consume the whole activity → ActivityFinalNode.
                // All other typed end events (Message, Signal, Error, Escalation, etc.) terminate
                // only the current token → FlowFinalNode (circle with X).
                if (definition == EventDefinition.TERMINATE || definition == EventDefinition.NONE) {
                    UmlActivityNodeKind.ACTIVITY_FINAL to emptyMap()
                } else {
                    UmlActivityNodeKind.FLOW_FINAL to emptyMap()
                }

            EventPosition.INTERMEDIATE ->
                // No UML Activity equivalent for intermediate events; ACTION is the closest
                // structural proxy. Extra metadata preserves the BPMN semantics for reverse
                // mapping and downstream tools.
                UmlActivityNodeKind.ACTION to
                    mapOf(
                        META_BPMN_EVENT_POSITION to KumlMetaValue.Text("INTERMEDIATE"),
                        META_BPMN_EVENT_DEFINITION to KumlMetaValue.Text(definition.name),
                    )
        }

    /** Base metadata for any node: sourceId + optional partition from lane index. */
    private fun buildBaseMeta(
        nodeId: String,
        laneIndex: Map<String, String>,
    ): Map<String, KumlMetaValue> {
        val meta = mutableMapOf<String, KumlMetaValue>(META_BPMN_SOURCE_ID to KumlMetaValue.Text(nodeId))
        laneIndex[nodeId]?.let { laneName -> meta[META_UML_PARTITION] = KumlMetaValue.Text(laneName) }
        return meta
    }

    /** Gateway metadata: sourceId + optional inclusive type + optional partition from lane index. */
    private fun buildGatewayMeta(
        nodeId: String,
        gatewayType: GatewayType,
        laneIndex: Map<String, String>,
    ): Map<String, KumlMetaValue> {
        val meta = mutableMapOf<String, KumlMetaValue>(META_BPMN_SOURCE_ID to KumlMetaValue.Text(nodeId))
        if (gatewayType == GatewayType.INCLUSIVE) {
            meta["bpmn.gatewayType"] = KumlMetaValue.Text("INCLUSIVE")
        }
        laneIndex[nodeId]?.let { laneName -> meta[META_UML_PARTITION] = KumlMetaValue.Text(laneName) }
        return meta
    }

    /**
     * Builds a map from BPMN flow node ID to the lane name it belongs to.
     *
     * Walks lanes recursively. Innermost lane wins when a node appears in multiple
     * nesting levels (child lanes are processed after parents, overwriting).
     */
    private fun buildLaneIndex(lanes: List<BpmnLane>): Map<String, String> {
        val index = mutableMapOf<String, String>()

        fun walk(lane: BpmnLane) {
            val laneName = lane.name ?: lane.id
            for (nodeId in lane.flowNodeRefs) {
                index[nodeId] = laneName
            }
            for (child in lane.childLanes) {
                walk(child)
            }
        }
        for (lane in lanes) {
            walk(lane)
        }
        return index
    }
}
