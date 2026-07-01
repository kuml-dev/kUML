package dev.kuml.layout.bridge.bpmn

import dev.kuml.bpmn.model.BpmnCallActivity
import dev.kuml.bpmn.model.BpmnEvent
import dev.kuml.bpmn.model.BpmnGateway
import dev.kuml.bpmn.model.BpmnLane
import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.bpmn.model.BpmnParticipant
import dev.kuml.bpmn.model.BpmnSubProcess
import dev.kuml.bpmn.model.BpmnTask
import dev.kuml.bpmn.model.CallConversation
import dev.kuml.bpmn.model.ChoreographyDiagram
import dev.kuml.bpmn.model.ChoreographyEvent
import dev.kuml.bpmn.model.ChoreographyGateway
import dev.kuml.bpmn.model.ChoreographyTask
import dev.kuml.bpmn.model.CollaborationDiagram
import dev.kuml.bpmn.model.ConversationDiagram
import dev.kuml.bpmn.model.MessageFlow
import dev.kuml.bpmn.model.ProcessDiagram
import dev.kuml.bpmn.model.SequenceFlow
import dev.kuml.bpmn.model.SubConversation
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
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.SizeProvider

/**
 * Übersetzt ein [BpmnModel] + [ProcessDiagram] in einen [LayoutGraph].
 *
 * Standardgrößen (BPMN-Konvention):
 * - Task / CallActivity: 120×60
 * - Gateway: 50×50
 * - Event: 36×36
 * - DataObject: 40×55
 * - SubProcess (collapsed): 120×60
 *
 * Ist [ProcessDiagram.elementIds] **leer** (Default beim DSL-Aufruf
 * `diagram(name, processId)` ohne expliziten `include()`-Block), werden **alle**
 * Elemente des Prozesses angezeigt — Konvention wie in [dev.kuml.layout.bridge.Sysml2LayoutBridge].
 * Ist die Liste nicht leer, wirkt sie als Filter: nur die gelisteten Elemente
 * werden aufgenommen, nicht auflösbare IDs werden schweigend übersprungen.
 *
 * V3.1.3 — BPMN Process SVG-Renderer
 *
 * Beispiel:
 * ```kotlin
 * val graph = BpmnLayoutBridge.toLayoutGraph(model, processDiagram)
 * ```
 */
public object BpmnLayoutBridge {
    /** Standard-Knotengröße für BPMN-Tasks und CallActivities (Breite × Höhe in Pixeln). */
    public val DEFAULT_TASK_SIZE: Size = Size(120f, 60f)

    /** Standard-Knotengröße für BPMN-Gateways (Breite × Höhe). */
    public val DEFAULT_GATEWAY_SIZE: Size = Size(50f, 70f) // 50 shape + 20 label space

    /** Standard-Knotengröße für BPMN-Events (Breite × Höhe). */
    public val DEFAULT_EVENT_SIZE: Size = Size(36f, 56f) // 36 shape + 20 label space

    /** Tatsächliche Formhöhe eines Gateway-Diamonds (obere 50 px der 70 px-Bounds). */
    public const val BPMN_GATEWAY_SHAPE_H: Float = 50f

    /** Tatsächliche Formhöhe eines Event-Kreises (obere 36 px der 56 px-Bounds). */
    public const val BPMN_EVENT_SHAPE_H: Float = 36f

    /** Standard-Knotengröße für BPMN-DataObjects (Breite × Höhe). */
    public val DEFAULT_DATA_OBJECT_SIZE: Size = Size(40f, 55f)

    /** Standard-Knotengröße für BPMN-Choreography-Tasks (Breite × Höhe). */
    public val DEFAULT_CHOREO_TASK_SIZE: Size = Size(160f, 80f)

    /**
     * Übersetzt [diagram] mithilfe von [model] als Lookup-Kontext in einen [LayoutGraph].
     *
     * @param model Das BPMN-Modell mit allen Prozessen und Elementen.
     * @param diagram Das [ProcessDiagram], das festlegt, welche Elemente angezeigt werden.
     * @param sizeProvider Optionaler SizeProvider; überschreibt die Standardgrößen falls angegeben.
     */
    public fun toLayoutGraph(
        model: BpmnModel,
        diagram: ProcessDiagram,
        sizeProvider: SizeProvider? = null,
    ): LayoutGraph {
        // Prozess ermitteln
        val process = model.processes.firstOrNull { it.id == diagram.processId }

        // Vollständiger Elementindex über alle Prozesse (Flow-Nodes + Flows + DataObjects)
        val flowNodeIndex =
            model.processes.flatMap { it.flowNodes }.associateBy { it.id } +
                model.processes
                    .flatMap { p ->
                        p.flowNodes
                            .filterIsInstance<BpmnSubProcess>()
                            .flatMap { it.flowElementNodes }
                    }.associateBy { it.id }

        val seqFlowIndex =
            model.processes.flatMap { it.sequenceFlows }.associateBy { it.id } +
                model.processes
                    .flatMap { p ->
                        p.flowNodes
                            .filterIsInstance<BpmnSubProcess>()
                            .flatMap { it.innerSequenceFlows }
                    }.associateBy { it.id }

        val dataObjectIndex =
            model.processes.flatMap { it.dataObjects }.associateBy { it.id } +
                model.processes
                    .flatMap { p ->
                        p.flowNodes
                            .filterIsInstance<BpmnSubProcess>()
                            .flatMap { it.innerDataObjects }
                    }.associateBy { it.id }

        val nodes = mutableListOf<LayoutNode>()
        val edges = mutableListOf<LayoutEdge>()
        val groups = mutableListOf<LayoutGroup>()

        // Alle FlowNode-IDs aus dem Diagram-Scope
        val allFlowNodeIds: List<String>
        val allSeqFlowIds: List<String>
        val allDataObjectIds: List<String>

        // Leere elementIds = "alle Elemente anzeigen" (Konvention wie in
        // Sysml2LayoutBridge). Das DSL `diagram(name, processId)` ohne expliziten
        // include()-Block erzeugt ein ProcessDiagram mit leerer elementIds-Liste —
        // ohne diesen Default bliebe das gerenderte Diagramm komplett leer.
        val filterIds: Set<String>? = diagram.elementIds.takeIf { it.isNotEmpty() }?.toSet()

        if (process != null) {
            // Wenn ein Prozess zugewiesen ist, zeige nur dessen Elemente
            val processFlowNodeIds = process.flowNodes.map { it.id }
            val processSeqFlowIds = process.sequenceFlows.map { it.id }
            val processDataObjIds = process.dataObjects.map { it.id }
            allFlowNodeIds = if (filterIds == null) processFlowNodeIds else processFlowNodeIds.filter { it in filterIds }
            allSeqFlowIds = if (filterIds == null) processSeqFlowIds else processSeqFlowIds.filter { it in filterIds }
            allDataObjectIds = if (filterIds == null) processDataObjIds else processDataObjIds.filter { it in filterIds }
        } else {
            // Fallback: alle in elementIds referenzierten Elemente suchen
            // (bei leerer Liste der gesamte Modell-Index)
            allFlowNodeIds = if (filterIds == null) flowNodeIndex.keys.toList() else diagram.elementIds.filter { it in flowNodeIndex }
            allSeqFlowIds = if (filterIds == null) seqFlowIndex.keys.toList() else diagram.elementIds.filter { it in seqFlowIndex }
            allDataObjectIds = if (filterIds == null) dataObjectIndex.keys.toList() else diagram.elementIds.filter { it in dataObjectIndex }
        }

        // Expanded SubProcesses → LayoutGroups + child nodes with groupId.
        //
        // An expanded BpmnSubProcess is rendered as a framed container by the
        // SVG renderer (renderBpmnProcess iterates layoutResult.groups).  The
        // bridge must therefore:
        //   (1) add a LayoutGroup for every expanded SubProcess in scope;
        //   (2) register the SubProcess's child flow-nodes as LayoutNodes whose
        //       groupId points to the parent group;
        //   (3) add the inner SequenceFlows as LayoutEdges so the layout engine
        //       can route connections between children correctly.
        //
        // Child nodes and inner flows are collected into separate sets so the
        // main loop below can skip them (they are not top-level elements).
        // The expanded SubProcess IDs themselves are also tracked so the main
        // loop does not emit a redundant top-level LayoutNode for them —
        // the LayoutGroup already represents the SubProcess boundary.
        val childNodeIds = mutableSetOf<String>()
        val innerFlowIds = mutableSetOf<String>()
        val expandedSubProcessGroupIds = mutableSetOf<String>()

        for (nodeId in allFlowNodeIds) {
            val sp = flowNodeIndex[nodeId] as? BpmnSubProcess ?: continue
            if (!sp.expanded) continue

            val groupId = GroupId(sp.id)
            expandedSubProcessGroupIds.add(sp.id)
            groups.add(
                LayoutGroup(
                    id = groupId,
                    parent = null,
                    padding = Insets(top = 20f, right = 20f, bottom = 20f, left = 20f),
                    layoutAsCompound = true,
                ),
            )

            // NOTE: No phantom node is emitted for the expanded SubProcess.
            // Outer SequenceFlows whose sourceRef/targetRef equals sp.id are
            // wired to the LayoutGroup *compound* instead: the ELK graph builder
            // resolves an edge endpoint via nodeMap first, then falls back to
            // groupMap[GroupId(id)] (same convention as C4 container/system
            // boundaries). Connecting to the compound makes ELK route the edge
            // to the SubProcess frame border, rather than to an interior 0×0
            // phantom — the latter caused outer flows to pierce straight through
            // the frame and overlap the inner nodes / the "Review Cycle" label.
            // sp.id is registered as a valid edge endpoint via
            // `expandedSubProcessGroupIds` further below (see nodeIdSet).

            // Child flow-nodes inside the expanded SubProcess.
            // filterIds == null (leere elementIds) ⇒ alle Kinder anzeigen.
            for (child in sp.flowElementNodes) {
                if (filterIds != null && child.id !in filterIds) continue
                childNodeIds.add(child.id)
                val defaultSize =
                    when (child) {
                        is BpmnGateway -> DEFAULT_GATEWAY_SIZE
                        is BpmnEvent -> DEFAULT_EVENT_SIZE
                        is BpmnTask -> DEFAULT_TASK_SIZE
                        is BpmnSubProcess -> DEFAULT_TASK_SIZE
                        is BpmnCallActivity -> DEFAULT_TASK_SIZE
                    }
                val size =
                    sizeProvider?.sizeOf(child.id, child::class.simpleName ?: "Unknown") ?: defaultSize
                nodes.add(LayoutNode(id = NodeId(child.id), intrinsicSize = size, groupId = groupId))
            }

            // Inner SequenceFlows (filterIds == null ⇒ alle anzeigen).
            for (innerFlow in sp.innerSequenceFlows) {
                if (filterIds != null && innerFlow.id !in filterIds) continue
                innerFlowIds.add(innerFlow.id)
            }
        }

        // Flow-Nodes → LayoutNodes (top-level only; child nodes already added above)
        for (nodeId in allFlowNodeIds) {
            if (nodeId in childNodeIds) continue // already added as grouped child
            if (nodeId in expandedSubProcessGroupIds) continue // represented by LayoutGroup
            val element = flowNodeIndex[nodeId] ?: continue
            val defaultSize =
                when (element) {
                    is BpmnGateway -> DEFAULT_GATEWAY_SIZE
                    is BpmnEvent -> DEFAULT_EVENT_SIZE
                    is BpmnTask -> DEFAULT_TASK_SIZE
                    is BpmnSubProcess -> DEFAULT_TASK_SIZE
                    is BpmnCallActivity -> DEFAULT_TASK_SIZE
                }
            val size =
                sizeProvider?.sizeOf(nodeId, element::class.simpleName ?: "Unknown") ?: defaultSize
            nodes.add(LayoutNode(id = NodeId(nodeId), intrinsicSize = size))
        }

        // DataObjects → LayoutNodes
        for (dataId in allDataObjectIds) {
            dataObjectIndex[dataId] ?: continue
            val size =
                sizeProvider?.sizeOf(dataId, "BpmnDataObject") ?: DEFAULT_DATA_OBJECT_SIZE
            nodes.add(LayoutNode(id = NodeId(dataId), intrinsicSize = size))
        }

        // SequenceFlows → LayoutEdges
        // Expanded SubProcess ids are valid edge endpoints even though they are
        // not emitted as LayoutNodes — they resolve to the compound LayoutGroup
        // boundary in the ELK graph builder (groupMap fallback).
        val nodeIdSet = nodes.map { it.id.value }.toSet() + expandedSubProcessGroupIds
        for (flowId in allSeqFlowIds) {
            val flow = seqFlowIndex[flowId] ?: continue
            // Nur emittieren, wenn beide Endpunkte im Graphen vorhanden sind
            if (flow.sourceRef !in nodeIdSet || flow.targetRef !in nodeIdSet) continue
            edges.add(
                LayoutEdge(
                    id = EdgeId(flowId),
                    source = EndpointRef(nodeId = NodeId(flow.sourceRef)),
                    target = EndpointRef(nodeId = NodeId(flow.targetRef)),
                    hints = EdgeHints.NONE,
                ),
            )
        }

        // Inner SequenceFlows of expanded SubProcesses → LayoutEdges
        for (flowId in innerFlowIds) {
            val flow = seqFlowIndex[flowId] ?: continue
            if (flow.sourceRef !in nodeIdSet || flow.targetRef !in nodeIdSet) continue
            edges.add(
                LayoutEdge(
                    id = EdgeId(flowId),
                    source = EndpointRef(nodeId = NodeId(flow.sourceRef)),
                    target = EndpointRef(nodeId = NodeId(flow.targetRef)),
                    hints = EdgeHints.NONE,
                ),
            )
        }

        // Boundary-Events: als zusätzliche Knoten mit Attachment-Kante zur Host-Activity.
        // (Boundary-Events haben attachedToRef gesetzt.)
        //
        // Wichtig: ohne eine LayoutEdge zwischen Boundary-Event und Host positioniert
        // die Layout-Engine das Event unabhängig irgendwo auf dem Canvas — es
        // „floated" statt am Rand der Host-Activity zu kleben. Die Attachment-Kante
        // signalisiert dem ELK-Algorithmus, dass das Event in der Nähe des Hosts
        // platziert werden soll; der Renderer snapped es anschließend visuell an
        // den Host-Rand. Ohne Kante wäre das Ergebnis eine willkürliche Position.
        if (process != null) {
            // nodeIdSet ist hier noch das Set vor dem Hinzufügen der Boundary-Nodes —
            // wird unten nach dem Aufbau erweitert, damit die Kante beide Endpunkte findet.
            val mutableNodeIdSet = nodeIdSet.toMutableSet()
            val boundaryEvents =
                process.flowNodes
                    .filterIsInstance<BpmnEvent>()
                    .filter { it.attachedToRef != null }
            for (be in boundaryEvents) {
                if (filterIds != null && be.id !in filterIds) continue
                if (be.id in mutableNodeIdSet) continue // bereits hinzugefügt
                val size =
                    sizeProvider?.sizeOf(be.id, "BpmnEvent") ?: DEFAULT_EVENT_SIZE
                nodes.add(LayoutNode(id = NodeId(be.id), intrinsicSize = size))
                mutableNodeIdSet.add(be.id)

                // Attachment-Kante: Boundary-Event → Host-Activity.
                // Nur emittieren, wenn der Host auch im Layout-Graph vorhanden ist.
                val hostId = be.attachedToRef!!
                if (hostId in mutableNodeIdSet) {
                    edges.add(
                        LayoutEdge(
                            id = EdgeId("boundary-attach-${be.id}"),
                            source = EndpointRef(nodeId = NodeId(be.id)),
                            target = EndpointRef(nodeId = NodeId(hostId)),
                            hints = EdgeHints.NONE,
                        ),
                    )
                }
            }
        }

        return LayoutGraph(nodes = nodes, edges = edges, groups = groups)
    }

    /**
     * Bequeme Hilfsmethode, die eine vollständige Liste aller SequenceFlows
     * eines Prozesses als [SequenceFlow]-Objekte für die Übergabe an den
     * SVG-Renderer zurückgibt — geordnet nach Auftreten im Prozess.
     *
     * @param model Das BpmnModel.
     * @param processId ID des Prozesses.
     */
    public fun sequenceFlowsOf(
        model: BpmnModel,
        processId: String,
    ): List<SequenceFlow> = model.processes.firstOrNull { it.id == processId }?.sequenceFlows ?: emptyList()

    /**
     * Übersetzt ein [BpmnModel] + [CollaborationDiagram] in einen [LayoutGraph].
     *
     * Swimlane-Layout-Strategie:
     * - Jeder [BpmnParticipant] (Pool) wird als [LayoutGroup] mit großzügigem
     *   Padding registriert.
     * - Jede [BpmnLane] innerhalb eines Pools bekommt ebenfalls eine eigene
     *   [LayoutGroup] als Kind der Pool-Gruppe.
     * - Flow-Nodes des referenzierten Prozesses werden als [LayoutNode]s mit
     *   der ID der zugehörigen Lane-Gruppe registriert (wenn Lanes vorhanden
     *   sind).
     * - [MessageFlow]s werden als Container-übergreifende [LayoutEdge]s
     *   zwischen den Quell- und Zielknoten eingetragen.
     * - SequenceFlows innerhalb jedes Prozesses werden ebenfalls als Edges
     *   hinzugefügt.
     *
     * Standard-Pool-Größe: 600×200 px (wenn kein Prozess referenziert ist /
     * Black-Box-Pool).
     *
     * V3.1.4 — BPMN Collaboration Layout Bridge
     *
     * @param model Das BPMN-Modell mit Collaborations und Prozessen.
     * @param diagram Das [CollaborationDiagram], das festlegt, welche Collaboration
     *   angezeigt werden soll.
     * @param sizeProvider Optionaler SizeProvider; überschreibt die Standardgrößen.
     */
    public fun toLayoutGraph(
        model: BpmnModel,
        diagram: CollaborationDiagram,
        sizeProvider: SizeProvider? = null,
    ): LayoutGraph {
        val collaboration =
            model.collaborations.firstOrNull { it.id == diagram.collaborationId }
                ?: return LayoutGraph(nodes = emptyList(), edges = emptyList(), groups = emptyList())

        val nodes = mutableListOf<LayoutNode>()
        val edges = mutableListOf<LayoutEdge>()
        val groups = mutableListOf<LayoutGroup>()

        // Track all node IDs actually added so edge endpoints can be verified
        val addedNodeIds = mutableSetOf<String>()

        // Collect all flow nodes from all referenced processes for edge lookup
        val allFlowNodeIds: Set<String> =
            collaboration.participants
                .mapNotNull { p -> p.processRef?.let { model.processes.firstOrNull { proc -> proc.id == it } } }
                .flatMap { proc -> proc.flowNodes.map { it.id } }
                .toSet()

        // Build flat lane → pool mapping and lane → parent-lane mapping
        val laneToPoolId: MutableMap<String, String> = mutableMapOf()

        fun registerLanes(
            lanes: List<BpmnLane>,
            poolId: String,
            parentGroupId: GroupId?,
        ) {
            for (lane in lanes) {
                laneToPoolId[lane.id] = poolId
                val laneGroupId = GroupId(lane.id)
                groups.add(
                    LayoutGroup(
                        id = laneGroupId,
                        parent = parentGroupId,
                        padding =
                            Insets(
                                top = LANE_TITLE_INSET,
                                right = LANE_CONTENT_PADDING,
                                bottom = LANE_CONTENT_PADDING,
                                left = LANE_TITLE_INSET,
                            ),
                        layoutAsCompound = true,
                    ),
                )
                // Recurse into child lanes
                if (lane.childLanes.isNotEmpty()) {
                    registerLanes(lane.childLanes, poolId, laneGroupId)
                }
            }
        }

        for (participant in collaboration.participants) {
            val poolGroupId = GroupId(participant.id)

            // Pool as a compound group
            groups.add(
                LayoutGroup(
                    id = poolGroupId,
                    parent = null,
                    padding =
                        Insets(
                            top = POOL_TITLE_INSET,
                            right = POOL_CONTENT_PADDING,
                            bottom = POOL_CONTENT_PADDING,
                            left = POOL_TITLE_INSET,
                        ),
                    layoutAsCompound = true,
                ),
            )

            // Phantom node for the pool itself (as anchor for message flows targeting the pool)
            nodes.add(LayoutNode(id = NodeId(participant.id), intrinsicSize = Size(0f, 0f), groupId = poolGroupId))
            addedNodeIds.add(participant.id)

            // Register all lanes of this pool
            registerLanes(participant.lanes, participant.id, poolGroupId)

            // Build lane → flowNodeRefs index for this pool
            val laneByFlowNode: Map<String, String> =
                buildMap {
                    fun collectLaneMappings(lanes: List<BpmnLane>) {
                        for (lane in lanes) {
                            for (nodeRef in lane.flowNodeRefs) put(nodeRef, lane.id)
                            collectLaneMappings(lane.childLanes)
                        }
                    }
                    collectLaneMappings(participant.lanes)
                }

            // Get the referenced process (null for black-box pools)
            val process =
                participant.processRef?.let { ref ->
                    model.processes.firstOrNull { it.id == ref }
                }

            if (process != null) {
                // Add flow nodes — each into its lane group (if lanes exist) or directly into the pool group
                for (flowNode in process.flowNodes) {
                    val defaultSize =
                        when (flowNode) {
                            is BpmnGateway -> DEFAULT_GATEWAY_SIZE
                            is BpmnEvent -> DEFAULT_EVENT_SIZE
                            is BpmnTask -> DEFAULT_TASK_SIZE
                            is BpmnSubProcess -> DEFAULT_TASK_SIZE
                            is BpmnCallActivity -> DEFAULT_TASK_SIZE
                        }
                    val size = sizeProvider?.sizeOf(flowNode.id, flowNode::class.simpleName ?: "Unknown") ?: defaultSize
                    val assignedGroupId =
                        laneByFlowNode[flowNode.id]?.let { GroupId(it) } ?: poolGroupId
                    nodes.add(LayoutNode(id = NodeId(flowNode.id), intrinsicSize = size, groupId = assignedGroupId))
                    addedNodeIds.add(flowNode.id)
                }

                // Add sequence flows within the process
                for (sf in process.sequenceFlows) {
                    if (sf.sourceRef in addedNodeIds && sf.targetRef in addedNodeIds) {
                        edges.add(
                            LayoutEdge(
                                id = EdgeId(sf.id),
                                source = EndpointRef(nodeId = NodeId(sf.sourceRef)),
                                target = EndpointRef(nodeId = NodeId(sf.targetRef)),
                                hints = EdgeHints.NONE,
                            ),
                        )
                    }
                }
            } else {
                // Black-Box Pool — just the phantom node, no children
                // Pool size is controlled by layout engine based on the group padding
            }
        }

        // Add message flows (cross-pool edges)
        for (mf in collaboration.messageFlows) {
            // Only add if both endpoints exist in the layout graph
            // Endpoints can be pool IDs, flow-node IDs, or element IDs in the collaboration
            val srcInGraph = mf.sourceRef in addedNodeIds || mf.sourceRef in allFlowNodeIds
            val tgtInGraph = mf.targetRef in addedNodeIds || mf.targetRef in allFlowNodeIds
            if (srcInGraph && tgtInGraph) {
                edges.add(
                    LayoutEdge(
                        id = EdgeId(mf.id),
                        source = EndpointRef(nodeId = NodeId(mf.sourceRef)),
                        target = EndpointRef(nodeId = NodeId(mf.targetRef)),
                        hints = EdgeHints.NONE,
                    ),
                )
            }
        }

        return LayoutGraph(nodes = nodes, edges = edges, groups = groups)
    }

    /**
     * Übersetzt ein [BpmnModel] + [ChoreographyDiagram] in einen [LayoutGraph].
     *
     * Flaches Layout ohne Groups (Choreographien haben keine Pools/Lanes):
     * - [ChoreographyTask]s → [DEFAULT_CHOREO_TASK_SIZE] (160×80 px)
     * - [ChoreographyGateway]s → [DEFAULT_GATEWAY_SIZE] (50×70 px)
     * - [ChoreographyEvent]s → [DEFAULT_EVENT_SIZE] (36×56 px)
     * - [dev.kuml.bpmn.model.ChoreographySequenceFlow]s → [LayoutEdge]
     *
     * Ist [ChoreographyDiagram.elementIds] leer, werden alle Elemente der
     * referenzierten Choreography angezeigt (Konvention wie ProcessDiagram).
     *
     * V3.2.2 — BPMN Choreography SVG-Renderer
     *
     * @param model Das BPMN-Modell mit allen Choreographien.
     * @param diagram Das [ChoreographyDiagram], das festlegt, welche Choreography
     *   und welche Elemente angezeigt werden.
     * @param sizeProvider Optionaler SizeProvider; überschreibt Standardgrößen.
     */
    @Deprecated(
        "Choreography-Diagramme verwenden seit V3.2.2 dev.kuml.layout.bridge.bpmn.ChoreographyGridLayout " +
            "(deterministisches Custom-Grid, kein ELK). Dieser ELK-Pfad wird in einer künftigen Welle entfernt.",
    )
    public fun toLayoutGraph(
        model: BpmnModel,
        diagram: ChoreographyDiagram,
        sizeProvider: SizeProvider? = null,
    ): LayoutGraph {
        val choreography =
            model.choreographies.firstOrNull { it.id == diagram.choreographyId }
                ?: return LayoutGraph(nodes = emptyList(), edges = emptyList(), groups = emptyList())

        val filterIds =
            diagram.elementIds.takeIf { it.isNotEmpty() }?.toSet()

        val nodes = mutableListOf<LayoutNode>()
        val edges = mutableListOf<LayoutEdge>()
        val addedNodeIds = mutableSetOf<String>()

        // Nodes
        for (task in choreography.tasks) {
            if (filterIds != null && task.id !in filterIds) continue
            val size =
                sizeProvider?.sizeOf(task.id, "ChoreographyTask") ?: DEFAULT_CHOREO_TASK_SIZE
            nodes.add(LayoutNode(id = NodeId(task.id), intrinsicSize = size, groupId = null))
            addedNodeIds.add(task.id)
        }
        for (gw in choreography.gateways) {
            if (filterIds != null && gw.id !in filterIds) continue
            val size =
                sizeProvider?.sizeOf(gw.id, "ChoreographyGateway") ?: DEFAULT_GATEWAY_SIZE
            nodes.add(LayoutNode(id = NodeId(gw.id), intrinsicSize = size, groupId = null))
            addedNodeIds.add(gw.id)
        }
        for (event in choreography.events) {
            if (filterIds != null && event.id !in filterIds) continue
            val size =
                sizeProvider?.sizeOf(event.id, "ChoreographyEvent") ?: DEFAULT_EVENT_SIZE
            nodes.add(LayoutNode(id = NodeId(event.id), intrinsicSize = size, groupId = null))
            addedNodeIds.add(event.id)
        }

        // Edges
        for (sf in choreography.sequenceFlows) {
            if (filterIds != null && sf.id !in filterIds) continue
            if (sf.sourceRef in addedNodeIds && sf.targetRef in addedNodeIds) {
                edges.add(
                    LayoutEdge(
                        id = EdgeId(sf.id),
                        source = EndpointRef(nodeId = NodeId(sf.sourceRef)),
                        target = EndpointRef(nodeId = NodeId(sf.targetRef)),
                        hints = EdgeHints.NONE,
                    ),
                )
            }
        }

        return LayoutGraph(nodes = nodes, edges = edges, groups = emptyList())
    }

    /** Standard-Knotengröße für BPMN-Conversation-Participants (Rechtecke). */
    public val DEFAULT_CONVERSATION_PARTICIPANT_SIZE: Size = Size(100f, 60f)

    /**
     * Standard-Knotengröße für BPMN-Konversationsknoten (Hexagons).
     *
     * 50×44: 40 px für die Hexagon-Form + 4 px Label-Reserve oben und unten.
     */
    public val DEFAULT_CONVERSATION_NODE_SIZE: Size = Size(50f, 44f)

    /**
     * Übersetzt ein [ConversationDiagram] + [BpmnModel] in einen [LayoutGraph].
     *
     * Participants werden als echte Rechteck-Knoten emittiert (NICHT als Pseudo-Nodes
     * mit 0×0 Größe), da sie im Conversation Diagram sichtbare Elemente sind.
     * Konversationsknoten (Hexagons) werden ebenfalls als LayoutNodes emittiert.
     * Conversation Links werden als ungerichtete Kanten ohne Pfeilkopf-Hinweis
     * emittiert (die Pfeilkopf-Unterdrückung erfolgt im SVG-Renderer).
     *
     * @param model Das BPMN-Modell mit allen Konversationen.
     * @param diagram Das [ConversationDiagram], das festlegt, welche Konversation angezeigt wird.
     * @param sizeProvider Optionaler SizeProvider; überschreibt Standardgrößen falls angegeben.
     *
     * V3.2.3 — BPMN Conversation Diagram: Layout
     */
    public fun toLayoutGraph(
        model: BpmnModel,
        diagram: ConversationDiagram,
        sizeProvider: SizeProvider? = null,
    ): LayoutGraph {
        val conversation =
            model.conversations.firstOrNull { it.id == diagram.conversationId }
                ?: return LayoutGraph(nodes = emptyList(), edges = emptyList(), groups = emptyList())

        val filterIds = diagram.elementIds.takeIf { it.isNotEmpty() }?.toSet()
        val nodes = mutableListOf<LayoutNode>()
        val edges = mutableListOf<LayoutEdge>()
        val addedNodeIds = mutableSetOf<String>()

        // 1. Participants als echte Rechteck-Knoten.
        //    Participant-Namen dienen als Knoten-IDs (analog ConversationLink.participantRef).
        for (participantName in conversation.participants) {
            if (filterIds != null && participantName !in filterIds) continue
            val size =
                sizeProvider?.sizeOf(participantName, "ConversationParticipant")
                    ?: DEFAULT_CONVERSATION_PARTICIPANT_SIZE
            nodes.add(LayoutNode(id = NodeId(participantName), intrinsicSize = size, groupId = null))
            addedNodeIds.add(participantName)
        }

        // 2. Konversationsknoten (Hexagons) — flach (SubConversation-Kinder werden in V3.2.3 nicht
        //    als separate Top-Level-Nodes emittiert; nur der Sub-Conversation-Knoten selbst erscheint).
        for (node in conversation.nodes) {
            if (filterIds != null && node.id !in filterIds) continue
            val kind =
                when (node) {
                    is CallConversation -> "CallConversation"
                    is SubConversation -> "SubConversation"
                    else -> "ConversationNode"
                }
            val size = sizeProvider?.sizeOf(node.id, kind) ?: DEFAULT_CONVERSATION_NODE_SIZE
            nodes.add(LayoutNode(id = NodeId(node.id), intrinsicSize = size, groupId = null))
            addedNodeIds.add(node.id)
        }

        // 3. Conversation Links als Kanten (Participant ↔ Konversationsknoten).
        for (link in conversation.links) {
            if (filterIds != null && link.id !in filterIds) continue
            if (link.participantRef in addedNodeIds && link.conversationNodeRef in addedNodeIds) {
                edges.add(
                    LayoutEdge(
                        id = EdgeId(link.id),
                        source = EndpointRef(nodeId = NodeId(link.participantRef)),
                        target = EndpointRef(nodeId = NodeId(link.conversationNodeRef)),
                        hints = EdgeHints.NONE,
                    ),
                )
            }
        }

        return LayoutGraph(nodes = nodes, edges = edges, groups = emptyList())
    }

    // ── Swimlane sizing constants ─────────────────────────────────────────────

    /** Inset reserved for the pool title band (left/top depending on orientation). */
    private const val POOL_TITLE_INSET = 34f

    /** General content padding inside a pool. */
    private const val POOL_CONTENT_PADDING = 20f

    /** Inset reserved for the lane title band (left/top depending on orientation). */
    private const val LANE_TITLE_INSET = 28f

    /** General content padding inside a lane. */
    private const val LANE_CONTENT_PADDING = 16f
}
