package dev.kuml.io.latex.note

import dev.kuml.blueprint.model.Actor
import dev.kuml.blueprint.model.ActorRole
import dev.kuml.blueprint.model.BlueprintDiagramFull
import dev.kuml.blueprint.model.BlueprintLayer
import dev.kuml.blueprint.model.BlueprintModel
import dev.kuml.blueprint.model.JourneyDiagram
import dev.kuml.blueprint.model.JourneyStep
import dev.kuml.blueprint.model.Phase
import dev.kuml.blueprint.model.Sentiment
import dev.kuml.blueprint.model.StepConnection
import dev.kuml.bpmn.model.BpmnAssociation
import dev.kuml.bpmn.model.BpmnCollaboration
import dev.kuml.bpmn.model.BpmnEvent
import dev.kuml.bpmn.model.BpmnGateway
import dev.kuml.bpmn.model.BpmnLane
import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.bpmn.model.BpmnParticipant
import dev.kuml.bpmn.model.BpmnProcess
import dev.kuml.bpmn.model.BpmnTask
import dev.kuml.bpmn.model.BpmnTextAnnotation
import dev.kuml.bpmn.model.CollaborationDiagram
import dev.kuml.bpmn.model.EventBehaviour
import dev.kuml.bpmn.model.EventDefinition
import dev.kuml.bpmn.model.EventPosition
import dev.kuml.bpmn.model.GatewayType
import dev.kuml.bpmn.model.MessageFlow
import dev.kuml.bpmn.model.ProcessDiagram
import dev.kuml.bpmn.model.SequenceFlow
import dev.kuml.c4.model.C4Model
import dev.kuml.c4.model.C4Person
import dev.kuml.c4.model.C4Relationship
import dev.kuml.c4.model.C4SoftwareSystem
import dev.kuml.c4.model.SystemContextDiagram
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.note.KumlNote
import dev.kuml.core.model.note.KumlNoteLink
import dev.kuml.core.model.note.NoteKind
import dev.kuml.io.latex.KumlLatexRenderer
import dev.kuml.kerml.KermlSpecialization
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.PartDefinition
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.Sysml2Comment
import dev.kuml.sysml2.Sysml2CommentLink
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.sysml2Model
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlComment
import dev.kuml.uml.UmlCommentLink

/** Hand-built diagrams plus layouts for the note tests; every notation can be built with or without a note. */
internal object NoteFixtures {
    private fun node(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
    ) = NodeLayout(bounds = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h)))

    private fun layout(
        canvasW: Float,
        canvasH: Float,
        nodes: Map<NodeId, NodeLayout>,
        edges: Map<EdgeId, EdgeRoute> = emptyMap(),
    ) = LayoutResult(
        engineId = LayoutEngineId("test"),
        seed = 1L,
        canvas = Size(width = canvasW, height = canvasH),
        nodes = nodes,
        edges = edges,
        groups = emptyMap(),
    )

    // ─── UML ─────────────────────────────────────────────────────────────

    fun uml(
        body: String? = null,
        kind: NoteKind = NoteKind.NOTE,
    ): Pair<KumlDiagram, LayoutResult> {
        val a = UmlClass(id = "A", name = "Order")
        val b = UmlClass(id = "B", name = "Customer")
        val assoc =
            UmlAssociation(
                id = "assoc",
                ends =
                    listOf(
                        UmlAssociationEnd(typeId = "A"),
                        UmlAssociationEnd(typeId = "B"),
                    ),
            )
        val nodes =
            linkedMapOf(
                NodeId("A") to node(x = 20f, y = 20f, w = 160f, h = 80f),
                NodeId("B") to node(x = 260f, y = 20f, w = 160f, h = 80f),
            )
        val edges =
            linkedMapOf<EdgeId, EdgeRoute>(
                EdgeId("assoc") to EdgeRoute.Direct(source = Point(x = 180f, y = 60f), target = Point(x = 260f, y = 60f)),
            )
        val elements = mutableListOf<dev.kuml.core.model.KumlElement>(a, b, assoc)
        if (body != null) {
            elements += UmlComment(id = "c1", body = body, kind = kind)
            elements += UmlCommentLink(id = "l1", commentId = "c1", annotatedElementId = "A")
            nodes[NodeId("c1")] = node(x = 20f, y = 160f, w = 160f, h = 70f)
            edges[EdgeId("l1")] = EdgeRoute.Direct(source = Point(x = 100f, y = 160f), target = Point(x = 100f, y = 100f))
        }
        return KumlDiagram(name = "UML", elements = elements) to
            layout(canvasW = 460f, canvasH = 260f, nodes = nodes, edges = edges)
    }

    fun renderUml(
        body: String? = null,
        kind: NoteKind = NoteKind.NOTE,
        standalone: Boolean = false,
        shared: Boolean = false,
    ): String {
        val (d, l) = uml(body = body, kind = kind)
        return KumlLatexRenderer.toLatex(
            diagram = d,
            layoutResult = l,
            options =
                dev.kuml.io.latex
                    .LatexRenderOptions(standalone = standalone, sharedPreamble = shared),
        )
    }

    // ─── SysML 2 BDD ─────────────────────────────────────────────────────

    fun bddModel(
        body: String? = null,
        kind: NoteKind = NoteKind.NOTE,
    ): Triple<Sysml2Model, BdDiagram, LayoutResult> {
        val vehicle = PartDefinition(id = "Vehicle", name = "Vehicle", isAbstract = true)
        val hybrid =
            PartDefinition(
                id = "HybridVehicle",
                name = "HybridVehicle",
                specializations = listOf(KermlSpecialization(specificId = "HybridVehicle", generalId = "Vehicle")),
            )
        val model =
            Sysml2Model(
                name = "Spec",
                definitions = listOf(vehicle, hybrid),
                comments = if (body != null) listOf(Sysml2Comment(note = KumlNote(id = "n1", body = body, kind = kind))) else emptyList(),
                commentLinks =
                    if (body != null) {
                        listOf(Sysml2CommentLink(link = KumlNoteLink(id = "note-link:n1::Vehicle", noteId = "n1", targetId = "Vehicle")))
                    } else {
                        emptyList()
                    },
            )
        val bdd =
            BdDiagram(
                name = "Inheritance",
                elementIds = listOf("Vehicle", "HybridVehicle"),
                noteIds =
                    if (body !=
                        null
                    ) {
                        listOf("n1")
                    } else {
                        emptyList()
                    },
            )
        val l =
            layout(
                canvasW = 420f,
                canvasH = 360f,
                nodes =
                    linkedMapOf(
                        NodeId("Vehicle") to node(x = 120f, y = 20f, w = 180f, h = 100f),
                        NodeId("HybridVehicle") to node(x = 120f, y = 180f, w = 180f, h = 100f),
                    ),
                edges =
                    linkedMapOf(
                        EdgeId("gen:HybridVehicle::Vehicle") to
                            EdgeRoute.OrthogonalRounded(
                                source = Point(x = 210f, y = 180f),
                                target = Point(x = 210f, y = 120f),
                                waypoints = emptyList(),
                                cornerRadiusPx = 4f,
                            ),
                    ),
            )
        return Triple(model, bdd, l)
    }

    fun renderBdd(
        body: String? = null,
        kind: NoteKind = NoteKind.NOTE,
        standalone: Boolean = false,
        shared: Boolean = false,
    ): String {
        val (m, d, l) = bddModel(body = body, kind = kind)
        return KumlLatexRenderer.toLatex(
            model = m,
            diagram = d,
            layoutResult = l,
            options =
                dev.kuml.io.latex
                    .LatexRenderOptions(standalone = standalone, sharedPreamble = shared),
        )
    }

    // ─── SysML 2 STM ─────────────────────────────────────────────────────

    fun stmModel(
        body: String? = null,
        kind: NoteKind = NoteKind.NOTE,
    ): Triple<Sysml2Model, StmDiagram, LayoutResult> {
        val model =
            sysml2Model(name = "Light") {
                val red = stateDef(name = "Red")
                val green = stateDef(name = "Green")
                transition(name = "redToGreen", source = red, target = green, trigger = "timer")
                stmDiagram(name = "STM") {
                    include(red)
                    include(green)
                    if (body != null) comment(text = body, anchors = arrayOf("Red"), id = "n1", kind = kind)
                }
            }
        val stm = model.diagrams.filterIsInstance<StmDiagram>().single()
        val l =
            layout(
                canvasW = 500f,
                canvasH = 220f,
                nodes =
                    linkedMapOf(
                        NodeId("Red") to node(x = 20f, y = 20f, w = 160f, h = 80f),
                        NodeId("Green") to node(x = 280f, y = 20f, w = 160f, h = 80f),
                    ),
            )
        return Triple(model, stm, l)
    }

    fun renderStm(
        body: String? = null,
        kind: NoteKind = NoteKind.NOTE,
        standalone: Boolean = false,
        shared: Boolean = false,
    ): String {
        val (m, d, l) = stmModel(body = body, kind = kind)
        return KumlLatexRenderer.toLatex(
            model = m,
            diagram = d,
            layoutResult = l,
            options =
                dev.kuml.io.latex
                    .LatexRenderOptions(standalone = standalone, sharedPreamble = shared),
        )
    }

    // ─── C4 ──────────────────────────────────────────────────────────────

    fun c4(
        body: String? = null,
        kind: NoteKind = NoteKind.NOTE,
    ): Triple<C4Model, SystemContextDiagram, LayoutResult> {
        val model =
            C4Model(
                id = "m",
                name = "M",
                elements = listOf(C4Person(id = "u", name = "User"), C4SoftwareSystem(id = "sys", name = "MySystem")),
                relationships = listOf(C4Relationship(id = "rel1", source = "u", target = "sys", label = "Uses")),
                notes = if (body != null) listOf(KumlNote(id = "n1", body = body, kind = kind)) else emptyList(),
                noteLinks = if (body != null) listOf(KumlNoteLink(id = "nl1", noteId = "n1", targetId = "sys")) else emptyList(),
            )
        val diagram =
            SystemContextDiagram(
                id = "ctx",
                name = "Context",
                elements = listOf("u", "sys"),
                relationships = listOf("rel1"),
            )
        val l =
            layout(
                canvasW = 500f,
                canvasH = 220f,
                nodes =
                    linkedMapOf(
                        NodeId("u") to node(x = 20f, y = 20f, w = 160f, h = 100f),
                        NodeId("sys") to node(x = 260f, y = 20f, w = 200f, h = 100f),
                    ),
                edges =
                    linkedMapOf(
                        EdgeId("rel1") to EdgeRoute.Direct(source = Point(x = 180f, y = 70f), target = Point(x = 260f, y = 70f)),
                    ),
            )
        return Triple(model, diagram, l)
    }

    fun renderC4(
        body: String? = null,
        kind: NoteKind = NoteKind.NOTE,
        standalone: Boolean = false,
        shared: Boolean = false,
    ): String {
        val (m, d, l) = c4(body = body, kind = kind)
        return KumlLatexRenderer.toLatex(
            diagram = d,
            model = m,
            layoutResult = l,
            options =
                dev.kuml.io.latex
                    .LatexRenderOptions(standalone = standalone, sharedPreamble = shared),
        )
    }

    // ─── BPMN ────────────────────────────────────────────────────────────

    /** One annotation target list per note, e.g. `listOf("t1")`; an empty list gives a note without anchor. */
    fun bpmnProcessModel(
        notes: List<Triple<String, NoteKind, List<String>>> = emptyList(),
        flowIds: List<String> = listOf("start1", "t1", "g1", "end1"),
        noteIdOf: (Int) -> String = { "a$it" },
    ): Pair<BpmnModel, ProcessDiagram> {
        val nodes =
            flowIds.mapIndexed { i, id ->
                when {
                    i == 0 ->
                        BpmnEvent(
                            id = id,
                            name = "Start",
                            position = EventPosition.START,
                            definition = EventDefinition.NONE,
                            behaviour = EventBehaviour.CATCHING,
                        )
                    i == flowIds.lastIndex ->
                        BpmnEvent(
                            id = id,
                            name = "Done",
                            position = EventPosition.END,
                            definition = EventDefinition.NONE,
                            behaviour = EventBehaviour.THROWING,
                        )
                    i == 2 -> BpmnGateway(id = id, gatewayType = GatewayType.EXCLUSIVE)
                    else -> BpmnTask(id = id, name = "Check the incoming order request thoroughly")
                }
            }
        val flows =
            flowIds.zipWithNext().mapIndexed { i, (a, b) ->
                SequenceFlow(
                    id = "f$i",
                    sourceRef = a,
                    targetRef = b,
                    name = if (i == 0) "go" else null,
                    conditionExpression = if (i == 1) "amount > 1000" else null,
                )
            }
        val annotations =
            notes.mapIndexed {
                i,
                (body, kind, _),
                ->
                BpmnTextAnnotation(note = KumlNote(id = noteIdOf(i), body = body, kind = kind))
            }
        val associations =
            notes.flatMapIndexed { i, (_, _, targets) ->
                targets.mapIndexed { j, t ->
                    BpmnAssociation(link = KumlNoteLink(id = "as$i-$j", noteId = noteIdOf(i), targetId = t))
                }
            }
        val process =
            BpmnProcess(
                id = "proc1",
                name = "Order",
                flowNodes = nodes,
                sequenceFlows = flows,
                textAnnotations = annotations,
                associations = associations,
            )
        return BpmnModel(
            name = "Order",
            processes = listOf(process),
            diagrams = listOf(ProcessDiagram(name = "Process", processId = "proc1")),
        ) to ProcessDiagram(name = "Process", processId = "proc1")
    }

    fun renderBpmnProcess(
        notes: List<Triple<String, NoteKind, List<String>>> = emptyList(),
        flowIds: List<String> = listOf("start1", "t1", "g1", "end1"),
        standalone: Boolean = false,
        shared: Boolean = false,
        noteIdOf: (Int) -> String = { "a$it" },
    ): String {
        val (m, d) = bpmnProcessModel(notes = notes, flowIds = flowIds, noteIdOf = noteIdOf)
        return KumlLatexRenderer.toLatex(
            model = m,
            diagram = d,
            options =
                dev.kuml.io.latex
                    .LatexRenderOptions(standalone = standalone, sharedPreamble = shared),
        )
    }

    fun bpmnCollaborationModel(
        notes: List<Triple<String, NoteKind, List<String>>> = emptyList(),
        processNotes: Boolean = false,
        poolName: String = "Customer",
        messageFlow: Boolean = true,
    ): Pair<BpmnModel, CollaborationDiagram> {
        val annotations =
            notes.mapIndexed {
                i,
                (body, kind, _),
                ->
                BpmnTextAnnotation(note = KumlNote(id = "a$i", body = body, kind = kind))
            }
        val associations =
            notes.flatMapIndexed { i, (_, _, targets) ->
                targets.mapIndexed { j, t -> BpmnAssociation(link = KumlNoteLink(id = "as$i-$j", noteId = "a$i", targetId = t)) }
            }
        val process =
            BpmnProcess(
                id = "procA",
                name = "A",
                flowNodes = listOf(BpmnTask(id = "tA", name = "Work")),
                textAnnotations = if (processNotes) annotations else emptyList(),
                associations = if (processNotes) associations else emptyList(),
            )
        val collab =
            BpmnCollaboration(
                id = "collab1",
                name = "Exchange",
                participants =
                    listOf(
                        BpmnParticipant(
                            id = "poolA",
                            name = poolName,
                            processRef = "procA",
                            lanes = listOf(BpmnLane(id = "laneA", name = "Front")),
                        ),
                        BpmnParticipant(id = "poolB", name = "Vendor"),
                    ),
                messageFlows =
                    if (messageFlow) {
                        listOf(
                            MessageFlow(id = "mf1", name = "Order", sourceRef = "poolA", targetRef = "poolB"),
                        )
                    } else {
                        emptyList()
                    },
                textAnnotations = if (processNotes) emptyList() else annotations,
                associations = if (processNotes) emptyList() else associations,
            )
        val diagram = CollaborationDiagram(name = "Collab", collaborationId = "collab1")
        return BpmnModel(
            name = "Exchange",
            processes = listOf(process),
            collaborations = listOf(collab),
            diagrams = listOf(diagram),
        ) to diagram
    }

    fun renderBpmnCollaboration(
        notes: List<Triple<String, NoteKind, List<String>>> = emptyList(),
        standalone: Boolean = false,
        shared: Boolean = false,
        poolName: String = "Customer",
        messageFlow: Boolean = true,
    ): String {
        val (m, d) = bpmnCollaborationModel(notes = notes, poolName = poolName, messageFlow = messageFlow)
        return KumlLatexRenderer.toLatex(
            model = m,
            diagram = d,
            options =
                dev.kuml.io.latex
                    .LatexRenderOptions(standalone = standalone, sharedPreamble = shared),
        )
    }

    // ─── Service Blueprint ───────────────────────────────────────────────

    /** Three phases, four layers, two stacked steps in one cell ("s2a"/"s2b"), one actor, one connection. */
    fun blueprintModel(
        notes: List<Triple<String, NoteKind, List<String>>> = emptyList(),
        full: Boolean = false,
        extraSteps: List<JourneyStep> = emptyList(),
    ): BlueprintModel {
        val phases =
            listOf(
                Phase(id = "p1", name = "Discover", order = 0),
                Phase(id = "p2", name = "Order", order = 1),
                Phase(id = "p3", name = "Deliver", order = 2),
            )
        val steps =
            listOf(
                JourneyStep(
                    id = "s1",
                    name = "Browses",
                    phaseRef = "p1",
                    layer = BlueprintLayer.CUSTOMER_ACTIONS,
                    sentiment = Sentiment.POSITIVE,
                ),
                JourneyStep(
                    id = "s2a",
                    name = "Orders",
                    phaseRef = "p2",
                    layer = BlueprintLayer.CUSTOMER_ACTIONS,
                    sentiment = Sentiment.NEGATIVE,
                ),
                JourneyStep(
                    id = "s2b",
                    name = "Pays",
                    phaseRef = "p2",
                    layer = BlueprintLayer.CUSTOMER_ACTIONS,
                    sentiment = Sentiment.NEUTRAL,
                ),
                JourneyStep(id = "s3", name = "Confirms", phaseRef = "p2", layer = BlueprintLayer.FRONTSTAGE, actorRef = "act1"),
                JourneyStep(id = "s4", name = "Checks stock", phaseRef = "p2", layer = BlueprintLayer.BACKSTAGE, actorRef = "act1"),
                JourneyStep(id = "s5", name = "Ships", phaseRef = "p3", layer = BlueprintLayer.SUPPORT_PROCESSES),
            ) + extraSteps
        val diagram =
            if (full) {
                BlueprintDiagramFull(name = "Blueprint", showEmotionCurve = true)
            } else {
                JourneyDiagram(
                    name = "Journey",
                    visibleLayers = setOf(BlueprintLayer.CUSTOMER_ACTIONS, BlueprintLayer.FRONTSTAGE),
                    showEmotionCurve = true,
                )
            }
        return BlueprintModel(
            name = "Service",
            actors = listOf(Actor(id = "act1", name = "Agent", role = ActorRole.STAFF)),
            phases = phases,
            steps = steps,
            connections = listOf(StepConnection(id = "c1", sourceRef = "s1", targetRef = "s2a")),
            diagrams = listOf(diagram),
            notes = notes.mapIndexed { i, (body, kind, _) -> KumlNote(id = "n$i", body = body, kind = kind) },
            noteLinks =
                notes.flatMapIndexed { i, (_, _, targets) ->
                    targets.mapIndexed { j, t -> KumlNoteLink(id = "nl$i-$j", noteId = "n$i", targetId = t) }
                },
        )
    }

    fun renderBlueprint(
        notes: List<Triple<String, NoteKind, List<String>>> = emptyList(),
        full: Boolean = false,
        standalone: Boolean = false,
        shared: Boolean = false,
        extraSteps: List<JourneyStep> = emptyList(),
    ): String =
        KumlLatexRenderer.toLatex(
            model = blueprintModel(notes = notes, full = full, extraSteps = extraSteps),
            options =
                dev.kuml.io.latex
                    .LatexRenderOptions(standalone = standalone, sharedPreamble = shared),
        )
}
