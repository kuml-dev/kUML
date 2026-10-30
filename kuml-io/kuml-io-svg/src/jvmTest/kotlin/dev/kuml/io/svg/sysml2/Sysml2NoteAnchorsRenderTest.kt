package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SampleOutput
import dev.kuml.layout.LayoutEngineRegistry
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.elk.ElkLayoutEngineProvider
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.sysml2.ActDiagram
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.ParDiagram
import dev.kuml.sysml2.SeqDiagram
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.Sysml2Diagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.constraint.Sysml2NoteChecker
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** P-COM-01: notes anchored to transitions, ports, connections, owners, partitions and nested constraints are drawn. */
class Sysml2NoteAnchorsRenderTest :
    FunSpec(body = {

        fun layout(
            model: Sysml2Model,
            diagram: Sysml2Diagram,
        ): LayoutResult {
            LayoutEngineRegistry.clear()
            LayoutEngineRegistry.register(ElkLayoutEngineProvider())
            val engine = LayoutEngineRegistry.get("elk.layered")!!
            val graph =
                when (diagram) {
                    is dev.kuml.sysml2.BdDiagram -> Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram)
                    is IbdDiagram -> Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram)
                    is dev.kuml.sysml2.UcDiagram -> Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram)
                    is dev.kuml.sysml2.ReqDiagram -> Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram)
                    is StmDiagram -> Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram)
                    is ActDiagram -> Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram)
                    is SeqDiagram -> Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram)
                    is ParDiagram -> Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram)
                }
            return engine.layout(graph = graph, hints = LayoutHints.DEFAULT)
        }

        fun render(
            model: Sysml2Model,
            diagram: Sysml2Diagram,
            sample: String,
        ): String {
            val lr = layout(model, diagram)
            val svg =
                when (diagram) {
                    is dev.kuml.sysml2.BdDiagram ->
                        KumlSvgRenderer.toSvg(
                            model = model,
                            diagram = diagram,
                            layoutResult = lr,
                            theme = PlainTheme(),
                        )
                    is IbdDiagram -> KumlSvgRenderer.toSvg(model = model, diagram = diagram, layoutResult = lr, theme = PlainTheme())
                    is dev.kuml.sysml2.UcDiagram ->
                        KumlSvgRenderer.toSvg(
                            model = model,
                            diagram = diagram,
                            layoutResult = lr,
                            theme = PlainTheme(),
                        )
                    is dev.kuml.sysml2.ReqDiagram ->
                        KumlSvgRenderer.toSvg(
                            model = model,
                            diagram = diagram,
                            layoutResult = lr,
                            theme = PlainTheme(),
                        )
                    is StmDiagram -> KumlSvgRenderer.toSvg(model = model, diagram = diagram, layoutResult = lr, theme = PlainTheme())
                    is ActDiagram -> KumlSvgRenderer.toSvg(model = model, diagram = diagram, layoutResult = lr, theme = PlainTheme())
                    is SeqDiagram -> KumlSvgRenderer.toSvg(model = model, diagram = diagram, layoutResult = lr, theme = PlainTheme())
                    is ParDiagram -> KumlSvgRenderer.toSvg(model = model, diagram = diagram, layoutResult = lr, theme = PlainTheme())
                }
            SampleOutput.write(filename = "sysml2/anchors-$sample.svg", content = svg)
            return svg
        }

        fun anchorCount(svg: String) = Regex("class=\"kuml-note-anchor\"").findAll(svg).count()

        test("stm: a note anchored to a transition is drawn with its anchor line") {
            val model =
                sysml2Model(name = "M") {
                    val auf = stateDef(name = "Auf", isInitial = true)
                    val zu = stateDef(name = "Zu")
                    transition(name = "t1", source = auf, target = zu, trigger = "x")
                    val t2 = transition(name = "t2", source = zu, target = auf, trigger = "y")
                    stmDiagram(name = "S") {
                        include(auf)
                        include(zu)
                        comment(text = "Note on the second transition", firstAnchor = t2, id = "nt")
                    }
                }
            val d = model.diagrams.single()
            Sysml2NoteChecker.check(model = model, diagram = d).shouldBeEmpty()
            val svg = render(model, d, "stm-transition")
            svg shouldContain "id=\"nt\""
            svg shouldContain "Note on the second transition"
            anchorCount(svg) shouldBe 1
        }

        test("ibd: notes anchored to a part port, a connection and the owner are drawn") {
            val model =
                sysml2Model(name = "M") {
                    val powerLine = connectionDef(name = "PowerLine")
                    val dcPort = portDef(name = "DcPort")
                    val battery = partDef(name = "Battery") { port(name = "dcOut", typeId = dcPort.id) }
                    val motor = partDef(name = "ElectricMotor") { port(name = "dcIn", typeId = dcPort.id) }
                    val hybrid =
                        partDef(name = "HybridVehicle") {
                            part(name = "battery", typeId = battery.id)
                            part(name = "electricMotor", typeId = motor.id)
                            connect(
                                name = "batteryToMotor",
                                typeId = powerLine.id,
                                sourceEndId = "HybridVehicle::battery::dcOut",
                                targetEndId = "HybridVehicle::electricMotor::dcIn",
                            )
                        }
                    comment(text = "On the port", "Battery::dcOut", id = "np")
                    comment(text = "On the connection", "HybridVehicle::batteryToMotor", id = "nc")
                    comment(text = "On the owner", "HybridVehicle", id = "no")
                    ibd(name = "I", owner = hybrid)
                }
            val d = model.diagrams.single()
            Sysml2NoteChecker.check(model = model, diagram = d).shouldBeEmpty()
            val svg = render(model, d, "ibd-port-connection-owner")
            listOf("np", "nc", "no").forEach { svg shouldContain "id=\"$it\"" }
            anchorCount(svg) shouldBe 3
        }

        test("act: a note anchored to a partition is drawn") {
            val model =
                sysml2Model(name = "M") {
                    val customer = activityPartition(name = "Customer")
                    val sys = activityPartition(name = "OrderSystem")
                    val place = actionDef(name = "PlaceOrder", partition = customer)
                    val validate = actionDef(name = "ValidateOrder", partition = sys)
                    controlFlow(name = "p2v", source = place, target = validate)
                    comment(text = "On the lane", firstAnchor = customer, id = "nl")
                    actDiagram(name = "A") {
                        include(place)
                        include(validate)
                    }
                }
            val svg = render(model, model.diagrams.single(), "act-partition")
            svg shouldContain "id=\"nl\""
            anchorCount(svg) shouldBe 1
        }

        test("par: a note anchored to a nested constraint is drawn") {
            val model =
                sysml2Model(name = "M") {
                    val outer = constraintDef(name = "Outer", expression = "a == b")
                    val inner = constraintDef(name = "Inner", expression = "c == d", parent = outer)
                    comment(text = "On the nested one", firstAnchor = inner, id = "nn")
                    parDiagram(name = "P") {
                        include(outer)
                        include(inner)
                    }
                }
            val svg = render(model, model.diagrams.single(), "par-nested")
            svg shouldContain "id=\"nn\""
            anchorCount(svg) shouldBe 1
        }

        test("seq: a note anchored to a lifeline is drawn") {
            val model =
                sysml2Model(name = "M") {
                    val a = lifelineDef(name = "A")
                    val b = lifelineDef(name = "B")
                    val c = lifelineDef(name = "C")
                    message(label = "ping", source = a, target = b, seqNo = 0)
                    message(label = "pong", source = b, target = c, seqNo = 1)
                    comment(text = "On the inner lifeline", firstAnchor = b, id = "nb")
                    seqDiagram(name = "S") {
                        include(a)
                        include(b)
                        include(c)
                    }
                }
            val svg = render(model, model.diagrams.single(), "seq-inner")
            svg shouldContain "id=\"nb\""
            anchorCount(svg) shouldBe 1
        }
    })
