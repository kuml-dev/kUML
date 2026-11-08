package dev.kuml.io.svg.sysml2

import dev.kuml.core.model.layout.LayoutFlow
import dev.kuml.core.model.note.NoteKind
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.LayoutSpecRunner
import dev.kuml.layout.bridge.NoteLayout
import dev.kuml.layout.bridge.SpecDiagramKind
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.bridge.layoutHintsFor
import dev.kuml.layout.bridge.note.AnchorGeometry
import dev.kuml.layout.bridge.note.NoteMeasure
import dev.kuml.layout.bridge.note.Sysml2NoteAnchorExtras
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.layout.polylinePoints
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.UcDiagram
import dev.kuml.sysml2.dsl.sysml2Model
import dev.kuml.sysml2.edge.BddEdgeAdapter
import dev.kuml.sysml2.edge.IbdEdgeAdapter
import dev.kuml.sysml2.edge.UcEdgeAdapter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

/**
 * Nacharbeit 38: the notes of BDD, BDD with allocations and IBD follow the strict placement rules (16 px clearance to
 * edges, nodes, ports, labels and other notes, 6 px for anchor lines, frame invariant, nothing truncated) and prefer the
 * sides across the layout flow. Geometry only and independent of any font: note boxes come from a fixed measure and the
 * layout from the real pipeline, the notes through the same helpers the renderer uses.
 */
class Sysml2StructuralNotesClearanceTest :
    FunSpec({
        val fixedMeasure =
            object : NoteMeasure {
                override fun measure(
                    body: String,
                    maxWidth: Float,
                ): Size {
                    val width = min(NoteLayout.STM_MAX_W, 7f * body.length + 16f)
                    val perLine = max(1, ((width - 16f) / 7f).toInt())
                    val lines = ceil(body.length.toFloat() / perLine).toInt().coerceAtLeast(1)
                    return Size(width = width, height = 16f * lines + 16f)
                }

                override fun measure(
                    body: String,
                    maxWidth: Float,
                    kind: NoteKind,
                ): Size = measure(body = body, maxWidth = maxWidth)
            }

        class Placed(
            val scene: NoteGeometryAssertions.Scene,
            val layout: LayoutResult,
            val anchorBoxes: Map<String, Rect>,
        )

        fun bddModel(flow: LayoutFlow): Sysml2Model =
            sysml2Model(name = "Structural") {
                val vehicle = partDef(name = "Vehicle")
                val engine = partDef(name = "Engine", specializesId = vehicle.id)
                val battery = partDef(name = "Battery", specializesId = vehicle.id)
                val gearbox = partDef(name = "Gearbox", specializesId = vehicle.id)
                val software = partDef(name = "ControlSoftware")
                val chassis = partDef(name = "Chassis")
                allocate(from = software, to = engine)
                allocate(from = software, to = gearbox)
                comment(text = "Info: every vehicle has one engine.", firstAnchor = vehicle, kind = NoteKind.INFO)
                comment(text = "Warning: the battery is a safety part.", firstAnchor = battery, kind = NoteKind.WARNING)
                comment(text = "Decision: software runs on the engine unit.", firstAnchor = software, engine, kind = NoteKind.DECISION)
                comment(text = "Todo: model the chassis.", firstAnchor = chassis, kind = NoteKind.TODO)
                bdd(name = "BDD") {
                    include(vehicle)
                    include(engine)
                    include(battery)
                    include(gearbox)
                    include(software)
                    include(chassis)
                    layout { direction = flow }
                }
            }

        fun scene(
            model: Sysml2Model,
            placed: dev.kuml.layout.bridge.note.NotePlacementResult,
            labels: Map<String, Rect>,
            extras: Map<String, AnchorGeometry>,
            frames: Map<String, Rect> = emptyMap(),
        ): Placed {
            val l = placed.layout
            val noteIds = model.comments.map { it.id }.toSet()
            val linkIds = model.commentLinks.map { it.id }.toSet()
            val notes = noteIds.filter { NodeId(it) in l.nodes }.associateWith { l.nodes.getValue(NodeId(it)).bounds }
            val nodes =
                l.nodes
                    .filterKeys { it.value !in noteIds }
                    .mapKeys { it.key.value }
                    .mapValues { it.value.bounds }
            val edges =
                l.edges
                    .filterKeys { it.value !in linkIds }
                    .mapKeys { it.key.value }
                    .mapValues { it.value.polylinePoints() }
            val anchorLines =
                model.commentLinks
                    .mapNotNull { cl ->
                        val route = l.edges[dev.kuml.layout.EdgeId(cl.link.id)] ?: return@mapNotNull null
                        cl.link.id to
                            NoteGeometryAssertions.AnchorLine(
                                noteId = cl.link.noteId,
                                ownTargets = setOf(cl.link.targetId),
                                pts = route.polylinePoints(),
                            )
                    }.toMap()
            val ports = extras.entries.mapNotNull { (k, v) -> (v as? AnchorGeometry.Port)?.let { k to it.box } }.toMap()
            return Placed(
                scene =
                    NoteGeometryAssertions.Scene(
                        notes = notes,
                        nodes = nodes,
                        ports = ports,
                        labels =
                            labels.mapValues { (_, r) ->
                                Rect(origin = dev.kuml.layout.Point(x = r.origin.x + placed.dx, y = r.origin.y + placed.dy), size = r.size)
                            },
                        edges = edges,
                        anchorLines = anchorLines,
                        frames = frames,
                        truncated = placed.truncated,
                    ),
                layout = l,
                anchorBoxes = nodes,
            )
        }

        fun placeBdd(model: Sysml2Model): Placed {
            val d = model.diagrams.filterIsInstance<BdDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = d)
            val raw =
                LayoutSpecRunner
                    .layout(
                        engine = ElkLayoutEngine(),
                        graph = graph,
                        hints = Sysml2LayoutBridge.layoutHintsFor(model = model, diagram = d),
                        spec = d.layout,
                        kind = SpecDiagramKind.SYSML2_BDD,
                    ).result
            val adapter = BddEdgeAdapter(model = model, diagram = d)
            val labels = KumlSvgRenderer.planSysml2EdgeLabels(layout = raw, adapter = adapter)
            val noted =
                KumlSvgRenderer.placeSysml2NotesResult(
                    model = model,
                    diagram = d,
                    layout = raw,
                    adapter = adapter,
                    placedLabels = labels,
                    strictNotes = KumlSvgRenderer.structuralStrictNotePolicy(direction = d.layout?.direction),
                    measure = fixedMeasure,
                )
            return scene(model = model, placed = noted, labels = labels.mapValues { it.value.rect }, extras = emptyMap())
        }

        fun ibdModel(flow: LayoutFlow): Sysml2Model =
            sysml2Model(name = "IbdNotes") {
                val e = partDef(name = "Engine") { port(name = "out", typeId = "P") }
                val b = partDef(name = "Battery") { port(name = "inp", typeId = "P") }
                val g = partDef(name = "Gearbox") { port(name = "inp", typeId = "P") }
                val v =
                    partDef(name = "Vehicle") {
                        part(name = "engine", typeId = e.id)
                        part(name = "battery", typeId = b.id)
                        part(name = "gearbox", typeId = g.id)
                        connect(name = "wire", typeId = "C", sourceEndId = "Vehicle::engine::out", targetEndId = "Vehicle::battery::inp")
                        connect(name = "shaft", typeId = "C", sourceEndId = "Vehicle::engine::out", targetEndId = "Vehicle::gearbox::inp")
                    }
                comment(text = "Info: the engine drives the gearbox.", "Vehicle::engine", id = "n1", kind = NoteKind.INFO)
                comment(text = "Warning: the battery is live at all times.", "Vehicle::battery", id = "n2", kind = NoteKind.WARNING)
                comment(text = "Assumption: the shaft is rigid.", "Vehicle::gearbox", id = "n3", kind = NoteKind.NOTE)
                comment(text = "Todo: add the cooling circuit.", "Vehicle::engine", "Vehicle::battery", id = "n4", kind = NoteKind.TODO)
                ibd(name = "IBD", owner = v) { layout { direction = flow } }
            }

        fun placeIbd(model: Sysml2Model): Placed {
            val d = model.diagrams.filterIsInstance<IbdDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = d)
            val raw =
                LayoutSpecRunner
                    .layout(
                        engine = ElkLayoutEngine(),
                        graph = graph,
                        hints = Sysml2LayoutBridge.layoutHintsFor(model = model, diagram = d),
                        spec = d.layout,
                        kind = SpecDiagramKind.SYSML2_IBD,
                    ).result
            val nested = Sysml2LayoutBridge.addIbdNestedFrames(model = model, diagram = d, layoutResult = raw)
            val framed = Sysml2LayoutBridge.addIbdOwnerFrame(model = model, diagram = d, layoutResult = nested)
            val projection =
                dev.kuml.sysml2.projection
                    .sysml2IbdProjection(model = model, diagram = d)
            val enriched =
                if (projection.showFrame) {
                    framed
                } else {
                    Sysml2LayoutBridge.enrichIbdPortPositions(
                        model = model,
                        diagram = d,
                        layoutResult = framed,
                    )
                }
            val adapter = IbdEdgeAdapter(model = model, diagram = d)
            val extras = Sysml2NoteAnchorExtras.ibd(model = model, diagram = d, layout = enriched)
            val labels =
                KumlSvgRenderer.planSysml2EdgeLabels(
                    layout = enriched,
                    adapter = adapter,
                    configure = { o ->
                        Sysml2NoteAnchorExtras.ibdPortObstacles(
                            obstacles = o,
                            extras = extras,
                            layout = enriched,
                            portLabelRects = emptyList(),
                        )
                    },
                    portLabels = Sysml2LayoutBridge.ibdPortLabelSpecs(model = model, diagram = d, layout = enriched),
                    titleRects = Sysml2LayoutBridge.ibdTitleRects(model = model, layout = enriched),
                )
            val noted =
                KumlSvgRenderer.placeSysml2NotesResult(
                    model = model,
                    diagram = d,
                    layout = enriched,
                    adapter = adapter,
                    extras = extras,
                    localPorts = true,
                    configure = { o ->
                        Sysml2NoteAnchorExtras
                            .ibdPortObstacles(
                                obstacles = o,
                                extras = extras,
                                layout = enriched,
                                portLabelRects =
                                    labels
                                        .filterKeys {
                                            it.startsWith("port:")
                                        }.values
                                        .map { it.rect },
                            )
                    },
                    placedLabels = labels,
                    strictNotes =
                        KumlSvgRenderer.structuralStrictNotePolicy(
                            direction = d.layout?.direction,
                            layout = enriched,
                            laneGroupPrefixes = listOf("frame:"),
                        ),
                    measure = fixedMeasure,
                )
            val frames =
                noted.layout.groups
                    .filterKeys { it.value.startsWith("frame:") }
                    .map { it.key.value to it.value.bounds }
                    .toMap()
            return scene(model = model, placed = noted, labels = labels.mapValues { it.value.rect }, extras = emptyMap(), frames = frames)
        }

        fun ucModel(): Sysml2Model =
            sysml2Model(name = "UcNotes") {
                val reader = actorDef(name = "Reader")
                val librarian = actorDef(name = "Librarian")
                val borrow = useCaseDef(name = "BorrowBook")
                val returnBook = useCaseDef(name = "ReturnBook")
                val auth = useCaseDef(name = "Authenticate")
                comment(text = "Info: readers need a card.", firstAnchor = reader, kind = NoteKind.INFO)
                comment(text = "Warning: staff check the identity.", firstAnchor = librarian, kind = NoteKind.WARNING)
                comment(text = "Decision: borrowing is limited to 4 weeks.", firstAnchor = borrow, kind = NoteKind.DECISION)
                comment(text = "Todo: describe the return rules.", firstAnchor = returnBook, kind = NoteKind.TODO)
                ucDiagram(name = "UC") {
                    include(reader)
                    include(librarian)
                    include(borrow)
                    include(returnBook)
                    include(auth)
                    association(actor = reader, useCase = borrow)
                    association(actor = reader, useCase = returnBook)
                    association(actor = librarian, useCase = returnBook)
                    include(source = borrow, target = auth)
                    include(source = returnBook, target = auth)
                    subject(name = "Library") {
                        include(borrow)
                        include(returnBook)
                        include(auth)
                    }
                }
            }

        fun placeUc(model: Sysml2Model): Placed {
            val d = model.diagrams.filterIsInstance<UcDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = d)
            val raw =
                LayoutSpecRunner
                    .layout(
                        engine = ElkLayoutEngine(),
                        graph = graph,
                        hints = Sysml2LayoutBridge.layoutHintsFor(model = model, diagram = d),
                        spec = d.layout,
                        kind = SpecDiagramKind.SYSML2_UC,
                    ).result
            val adapter = UcEdgeAdapter(d)
            val labels = KumlSvgRenderer.planSysml2EdgeLabels(layout = raw, adapter = adapter)
            val noted =
                KumlSvgRenderer.placeSysml2NotesResult(
                    model = model,
                    diagram = d,
                    layout = raw,
                    adapter = adapter,
                    placedLabels = labels,
                    strictNotes =
                        KumlSvgRenderer.structuralStrictNotePolicy(
                            direction = null,
                            layout = raw,
                            laneGroupPrefixes = listOf(""),
                            flow = dev.kuml.layout.bridge.note.FlowAxis.NONE,
                        ),
                    measure = fixedMeasure,
                )
            val frames =
                noted.layout.groups
                    .map { it.key.value to it.value.bounds }
                    .toMap()
            return scene(model = model, placed = noted, labels = labels.mapValues { it.value.rect }, extras = emptyMap(), frames = frames)
        }

        fun report(
            name: String,
            p: Placed,
        ): List<String> {
            val f = NoteGeometryAssertions.failures(p.scene)
            println("[nacharbeit-38] $name: ${p.scene.notes.size} notes, ${f.size} failures")
            p.scene.notes.forEach { (id, r) -> println("[nacharbeit-38]   $id=${NoteGeometryAssertions.describe(r)}") }
            f.forEach { println("[nacharbeit-38]   $it") }
            return f
        }

        for (flow in listOf(LayoutFlow.Right, LayoutFlow.Down)) {
            test("bdd with allocations, flow $flow: four notes keep clear of edges, nodes, labels and each other")
                .config(timeout = 60.seconds) {
                    val model = bddModel(flow)
                    val p = placeBdd(model)
                    p.scene.notes.size shouldBe 4
                    report("bdd-$flow", p).shouldBeEmpty()
                }

            test("ibd, flow $flow: four notes keep clear of parts, ports, labels, edges and the frame border")
                .config(timeout = 60.seconds) {
                    val model = ibdModel(flow)
                    val p = placeIbd(model)
                    p.scene.notes.size shouldBe 4
                    report("ibd-$flow", p).shouldBeEmpty()
                }
        }

        test("use case with a subject frame: notes keep clear and lie fully inside or outside the frame")
            .config(timeout = 60.seconds) {
                val p = placeUc(ucModel())
                p.scene.notes.size shouldBe 4
                p.scene.frames.isNotEmpty() shouldBe true
                report("uc", p).shouldBeEmpty()
            }

        test("axis preference: where space exists on both axes the note goes across the flow").config(timeout = 60.seconds) {
            for ((flow, horizontal) in listOf(LayoutFlow.Right to true, LayoutFlow.Down to false)) {
                val p = placeBdd(bddModel(flow))
                val model = bddModel(flow)
                var across = 0
                var total = 0
                for (c in model.commentLinks) {
                    val note = p.scene.notes[c.link.noteId] ?: continue
                    val anchor = p.anchorBoxes[c.link.targetId] ?: continue
                    total++
                    if (NoteGeometryAssertions.acrossFlow(r = note, anchor = anchor, horizontalFlow = horizontal)) across++
                }
                println("[nacharbeit-38] axis $flow: $across of $total links across the flow")
                (total > 0) shouldBe true
                // A soft rule: most notes follow it, a short anchor may outweigh it.
                (across * 2 >= total) shouldBe true
            }
        }

        test("a crowded diagram falls back to a free place without overlap and is not truncated").config(timeout = 60.seconds) {
            val model =
                sysml2Model(name = "Crowded") {
                    val a = partDef(name = "A")
                    val b = partDef(name = "B", specializesId = a.id)
                    for (i in 1..8) {
                        comment(
                            text = "Note number $i about the block A with some longer text.",
                            firstAnchor =
                                if (i % 2 ==
                                    0
                                ) {
                                    a
                                } else {
                                    b
                                },
                            id = "c$i",
                        )
                    }
                    bdd(name = "BDD") {
                        include(a)
                        include(b)
                    }
                }
            val p = placeBdd(model)
            p.scene.notes.size shouldBe 8
            report("crowded", p).shouldBeEmpty()
        }
    })
