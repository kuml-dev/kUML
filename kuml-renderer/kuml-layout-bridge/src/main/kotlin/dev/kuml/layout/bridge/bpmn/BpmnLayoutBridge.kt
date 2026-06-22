package dev.kuml.layout.bridge.bpmn

import dev.kuml.bpmn.model.BpmnCallActivity
import dev.kuml.bpmn.model.BpmnEvent
import dev.kuml.bpmn.model.BpmnGateway
import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.bpmn.model.BpmnSubProcess
import dev.kuml.bpmn.model.BpmnTask
import dev.kuml.bpmn.model.ProcessDiagram
import dev.kuml.bpmn.model.SequenceFlow
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
 * Nur Elemente, die in [ProcessDiagram.elementIds] gelistet sind, werden
 * in den Layout-Graphen aufgenommen. Nicht auflösbare IDs werden schweigend
 * übersprungen.
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
    public val DEFAULT_GATEWAY_SIZE: Size = Size(50f, 50f)

    /** Standard-Knotengröße für BPMN-Events (Breite × Höhe). */
    public val DEFAULT_EVENT_SIZE: Size = Size(36f, 36f)

    /** Standard-Knotengröße für BPMN-DataObjects (Breite × Höhe). */
    public val DEFAULT_DATA_OBJECT_SIZE: Size = Size(40f, 55f)

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

        if (process != null) {
            // Wenn ein Prozess zugewiesen ist, zeige nur dessen Elemente
            val processFlowNodeIds = process.flowNodes.map { it.id }.toSet()
            val processSeqFlowIds = process.sequenceFlows.map { it.id }.toSet()
            val processDataObjIds = process.dataObjects.map { it.id }.toSet()
            allFlowNodeIds = diagram.elementIds.filter { it in processFlowNodeIds }
            allSeqFlowIds = diagram.elementIds.filter { it in processSeqFlowIds }
            allDataObjectIds = diagram.elementIds.filter { it in processDataObjIds }
        } else {
            // Fallback: alle in elementIds referenzierten Elemente suchen
            allFlowNodeIds = diagram.elementIds.filter { it in flowNodeIndex }
            allSeqFlowIds = diagram.elementIds.filter { it in seqFlowIndex }
            allDataObjectIds = diagram.elementIds.filter { it in dataObjectIndex }
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

            // Phantom node for the expanded SubProcess boundary so that outer
            // SequenceFlows (whose sourceRef or targetRef equals sp.id) can be
            // wired to a real NodeId rather than being silently dropped.
            // The node has a 0×0 intrinsic size; the SVG renderer uses the
            // LayoutGroup frame for visual rendering and ignores this node.
            nodes.add(LayoutNode(id = NodeId(sp.id), intrinsicSize = Size(0f, 0f), groupId = groupId))

            // Child flow-nodes inside the expanded SubProcess
            for (child in sp.flowElementNodes) {
                if (child.id !in diagram.elementIds) continue
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

            // Inner SequenceFlows
            for (innerFlow in sp.innerSequenceFlows) {
                if (innerFlow.id !in diagram.elementIds) continue
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
        val nodeIdSet = nodes.map { it.id.value }.toSet()
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
                if (be.id !in diagram.elementIds) continue
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
}
