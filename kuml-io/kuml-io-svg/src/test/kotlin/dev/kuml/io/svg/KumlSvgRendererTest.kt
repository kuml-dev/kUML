package dev.kuml.io.svg

import dev.kuml.c4.model.C4Container
import dev.kuml.c4.model.C4Model
import dev.kuml.c4.model.C4SoftwareSystem
import dev.kuml.c4.model.ContainerDiagram
import dev.kuml.core.model.KumlDiagram
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.GroupId
import dev.kuml.layout.GroupLayout
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlClass
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class KumlSvgRendererTest :
    FunSpec({

        test("KumlSvgRenderer produces deterministic byte-identical output") {
            val diagram =
                KumlDiagram(
                    name = "Test",
                    elements = listOf(UmlClass(id = "cls1", name = "Order")),
                )
            val layoutResult =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(300f, 200f),
                    nodes =
                        mapOf(
                            NodeId("cls1") to NodeLayout(bounds = Rect(Point(10f, 10f), Size(120f, 80f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )
            val theme = PlainTheme()

            val svg1 = KumlSvgRenderer.toSvg(diagram, layoutResult, theme)
            val svg2 = KumlSvgRenderer.toSvg(diagram, layoutResult, theme)
            svg1 shouldBe svg2
        }

        test("KumlSvgRenderer renders a class diagram with 2 classes and 1 association") {
            val cls1 = UmlClass(id = "cls1", name = "Order")
            val cls2 = UmlClass(id = "cls2", name = "Customer")
            val assoc =
                UmlAssociation(
                    id = "assoc1",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "cls1"),
                            UmlAssociationEnd(typeId = "cls2"),
                        ),
                )
            val diagram =
                KumlDiagram(
                    name = "ClassDiagram",
                    elements = listOf(cls1, cls2, assoc),
                )
            val layoutResult =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(400f, 200f),
                    nodes =
                        mapOf(
                            NodeId("cls1") to NodeLayout(bounds = Rect(Point(20f, 40f), Size(120f, 80f))),
                            NodeId("cls2") to NodeLayout(bounds = Rect(Point(200f, 40f), Size(120f, 80f))),
                        ),
                    edges =
                        mapOf(
                            EdgeId("assoc1") to
                                EdgeRoute.Direct(
                                    source = Point(140f, 80f),
                                    target = Point(200f, 80f),
                                ),
                        ),
                    groups = emptyMap(),
                )

            val svg = KumlSvgRenderer.toSvg(diagram, layoutResult, PlainTheme())

            // Structural assertions: 2 class rects
            val classRectCount = "class=\"kuml-class\"".toRegex().findAll(svg).count()
            classRectCount shouldBe 2

            // The association produces a <line> with marker-end="url(#arrow-open)"
            svg shouldContain "marker-end=\"url(#arrow-open)\""

            SampleOutput.write("uml/class-diagram-with-association.svg", svg)
        }

        test("KumlSvgRenderer renders a C4 container diagram with system group and 2 containers") {
            val system = C4SoftwareSystem(id = "sys1", name = "BankSystem")
            val cont1 = C4Container(id = "cont1", name = "WebApp", system = "sys1")
            val cont2 = C4Container(id = "cont2", name = "API", system = "sys1")

            val diagram =
                ContainerDiagram(
                    id = "diag1",
                    name = "ContainerView",
                    system = "sys1",
                    elements = listOf("sys1", "cont1", "cont2"),
                )
            val model =
                C4Model(
                    id = "model1",
                    name = "Bank",
                    elements = listOf(system, cont1, cont2),
                )
            val layoutResult =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(500f, 300f),
                    nodes =
                        mapOf(
                            NodeId("cont1") to NodeLayout(bounds = Rect(Point(20f, 40f), Size(140f, 80f))),
                            NodeId("cont2") to NodeLayout(bounds = Rect(Point(200f, 40f), Size(140f, 80f))),
                        ),
                    edges = emptyMap(),
                    groups =
                        mapOf(
                            GroupId("sys1") to GroupLayout(bounds = Rect(Point(0f, 0f), Size(400f, 200f))),
                        ),
                )

            val svg = KumlSvgRenderer.toSvg(diagram, model, layoutResult, PlainTheme())

            // System group wrapper
            svg shouldContain "id=\"system-sys1\""

            // 2 container rects
            val containerRectCount = "class=\"kuml-container\"".toRegex().findAll(svg).count()
            containerRectCount shouldBe 2

            SampleOutput.write("c4/container-diagram-with-system-group.svg", svg)
        }
    })
