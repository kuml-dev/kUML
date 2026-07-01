package dev.kuml.io.svg

import dev.kuml.c4.model.C4Container
import dev.kuml.c4.model.C4Interaction
import dev.kuml.c4.model.C4Model
import dev.kuml.c4.model.C4Person
import dev.kuml.c4.model.C4SoftwareSystem
import dev.kuml.c4.model.ContainerDiagram
import dev.kuml.c4.model.DynamicDiagram
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.PackageDiagramConfig
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
import dev.kuml.uml.UmlDependency
import dev.kuml.uml.UmlPackage
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

            // The association produces a <line> (edge) + inline arrowhead <path>
            // (replaced marker-end url(#id) approach to fix Obsidian reading-mode bug).
            svg shouldContain "class=\"kuml-edge\""
            svg shouldContain "<path"

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

        test("KumlSvgRenderer renders a package diagram with folder tabs, names, and contained classes") {
            // Reproduces the rendering issue surfaced by the obsidian sample
            // `11 UML Paket – Domain Modules.md`: before V11.x the renderer
            // emitted plain `kuml-system` rectangles for each package — no
            // folder tab, no name, and the inner classes were never looked up
            // because `diagram.elements.find { it.id == nodeId }` does not
            // recurse into `UmlPackage.members`. This test pins the fix.
            val money = UmlClass(id = "Money", name = "Money")
            val customer = UmlClass(id = "Customer", name = "Customer")
            val order = UmlClass(id = "Order", name = "Order")
            val shared = UmlPackage(id = "shared", name = "shared", members = listOf(money))
            val shop = UmlPackage(id = "shop", name = "shop", members = listOf(customer, order))
            val importDep =
                UmlDependency(id = "dep1", clientId = "shop", supplierId = "shared", name = "«import»")

            val diagram =
                KumlDiagram(
                    name = "Domain Modules",
                    type = DiagramType.PACKAGE,
                    elements = listOf(shared, shop, importDep),
                    config = PackageDiagramConfig(),
                )
            val layoutResult =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(600f, 400f),
                    nodes =
                        mapOf(
                            NodeId("Money") to NodeLayout(bounds = Rect(Point(40f, 40f), Size(120f, 60f))),
                            NodeId("Customer") to NodeLayout(bounds = Rect(Point(240f, 40f), Size(120f, 60f))),
                            NodeId("Order") to NodeLayout(bounds = Rect(Point(240f, 120f), Size(120f, 60f))),
                        ),
                    edges =
                        mapOf(
                            EdgeId("dep1") to
                                EdgeRoute.Direct(source = Point(240f, 80f), target = Point(160f, 80f)),
                        ),
                    groups =
                        mapOf(
                            GroupId("shared") to GroupLayout(bounds = Rect(Point(20f, 20f), Size(180f, 120f))),
                            GroupId("shop") to GroupLayout(bounds = Rect(Point(220f, 20f), Size(180f, 200f))),
                        ),
                )

            val svg = KumlSvgRenderer.toSvg(diagram, layoutResult, PlainTheme())

            // Folder-tab groups carry the package id and the package name as
            // a `<text class="kuml-title">` payload. Both must be present.
            svg shouldContain "id=\"package-shared\""
            svg shouldContain "id=\"package-shop\""
            // Package names appear inside title text elements — match the
            // whitespace-tolerant pattern: opening tag, optional whitespace,
            // name, optional whitespace, closing tag.
            val titleText = Regex("""<text[^>]*class="kuml-title"[^>]*>\s*([^<\s][^<]*?)\s*</text>""")
            val titles = titleText.findAll(svg).map { it.groupValues[1] }.toList()
            (titles.contains("shared")) shouldBe true
            (titles.contains("shop")) shouldBe true

            // Inner classes from package.members must reach the dispatcher.
            val classRectCount = "class=\"kuml-class\"".toRegex().findAll(svg).count()
            classRectCount shouldBe 3
            svg shouldContain "id=\"Money\""
            svg shouldContain "id=\"Customer\""
            svg shouldContain "id=\"Order\""

            // The «import» dependency renders with a dashed edge.
            svg shouldContain "kuml-edge-dashed"
            svg shouldContain "«import»"

            SampleOutput.write("uml/package-diagram-domain-modules.svg", svg)
        }

        test("KumlSvgRenderer routes a package dependency around an intervening package instead of through it") {
            // Regression guard for the vault feedback on
            // `11 UML Paket – Domain Modules.md`: with three vertically stacked
            // packages (payment → shop → shared) the long `payment → shared`
            // «import» edge used to be a straight diagonal that cut right through
            // the middle `shop` box. The renderer must now detour the edge around
            // the obstacle via an orthogonal side gutter.
            val money = UmlClass(id = "Money", name = "Money")
            val customer = UmlClass(id = "Customer", name = "Customer")
            val invoice = UmlClass(id = "Invoice", name = "Invoice")
            val shared = UmlPackage(id = "shared", name = "shared", members = listOf(money))
            val shop = UmlPackage(id = "shop", name = "shop", members = listOf(customer))
            val payment = UmlPackage(id = "payment", name = "payment", members = listOf(invoice))
            val crossingDep =
                UmlDependency(id = "dep1", clientId = "payment", supplierId = "shared", name = "«import»")

            val diagram =
                KumlDiagram(
                    name = "Domain Modules",
                    type = DiagramType.PACKAGE,
                    elements = listOf(shared, shop, payment, crossingDep),
                    config = PackageDiagramConfig(),
                )

            // Vertical stack: payment (top) · shop (middle, narrower) · shared (bottom).
            val shopRect = Rect(Point(120f, 180f), Size(160f, 100f))
            val layoutResult =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(400f, 460f),
                    nodes =
                        mapOf(
                            NodeId("Invoice") to NodeLayout(bounds = Rect(Point(40f, 60f), Size(120f, 50f))),
                            NodeId("Customer") to NodeLayout(bounds = Rect(Point(140f, 200f), Size(120f, 50f))),
                            NodeId("Money") to NodeLayout(bounds = Rect(Point(40f, 380f), Size(120f, 50f))),
                        ),
                    edges =
                        mapOf(
                            EdgeId("dep1") to
                                EdgeRoute.Direct(source = Point(200f, 120f), target = Point(200f, 358f)),
                        ),
                    groups =
                        mapOf(
                            GroupId("payment") to GroupLayout(bounds = Rect(Point(20f, 20f), Size(360f, 100f))),
                            GroupId("shop") to GroupLayout(bounds = shopRect),
                            GroupId("shared") to GroupLayout(bounds = Rect(Point(20f, 340f), Size(360f, 100f))),
                        ),
                )

            val svg = KumlSvgRenderer.toSvg(diagram, layoutResult, PlainTheme())

            // The crossing edge must now be an orthogonal multi-segment dashed
            // path (a detour), not a single straight `<line>`.
            val dashedPath =
                Regex("""<path d="([^"]*)"[^>]*class="kuml-edge-dashed"""").find(svg)
            (dashedPath != null) shouldBe true

            // Parse the path vertices and assert no segment crosses the shop box.
            val coords =
                Regex("""[ML]\s*(-?[\d.]+)\s+(-?[\d.]+)""")
                    .findAll(dashedPath!!.groupValues[1])
                    .map { Point(it.groupValues[1].toFloat(), it.groupValues[2].toFloat()) }
                    .toList()
            (coords.size >= 3) shouldBe true

            fun segmentHitsShop(
                a: Point,
                b: Point,
            ): Boolean {
                // Sample the segment densely; flag if any sample lands inside shop.
                val steps = 50
                for (i in 0..steps) {
                    val t = i / steps.toFloat()
                    val x = a.x + (b.x - a.x) * t
                    val y = a.y + (b.y - a.y) * t
                    val inside =
                        x > shopRect.origin.x &&
                            x < shopRect.origin.x + shopRect.size.width &&
                            y > shopRect.origin.y &&
                            y < shopRect.origin.y + shopRect.size.height
                    if (inside) return true
                }
                return false
            }

            val anyCrossing = (1 until coords.size).any { segmentHitsShop(coords[it - 1], coords[it]) }
            anyCrossing shouldBe false
        }

        test("KumlSvgRenderer renders a C4 dynamic diagram with numbered interactions and dashed responses") {
            // Regression guard for the vault feedback in
            // "26 C4 Dynamic – Checkout Flow.md": before this fix the C4
            // dynamic-diagram renderer produced a stack of boxes with NO arrows
            // at all, because the C4 layout bridge only emitted LayoutEdges for
            // `diagram.relationships` — and a DynamicDiagram carries its edges
            // as C4Interactions, not as static C4Relationships. The bridge now
            // emits one LayoutEdge per interaction (id = interaction.id), and
            // the SVG renderer falls back to renderC4Interaction when an edge
            // id doesn't match a known relationship.
            val customer = C4Person(id = "customer", name = "Customer")
            val web = C4SoftwareSystem(id = "web", name = "WebApp")
            val api = C4SoftwareSystem(id = "api", name = "API Server")
            val db = C4SoftwareSystem(id = "db", name = "OrderDB")

            val ix1 =
                C4Interaction(
                    id = "i1",
                    source = "customer",
                    target = "web",
                    description = "Submit order",
                    sequence = 1,
                    technology = "HTTPS",
                )
            val ix2 =
                C4Interaction(
                    id = "i2",
                    source = "web",
                    target = "api",
                    description = "POST /orders",
                    sequence = 2,
                    technology = "HTTPS/JSON",
                )
            val ix3 =
                C4Interaction(id = "i3", source = "api", target = "db", description = "INSERT order", sequence = 3, technology = "JDBC")
            val ix4 = C4Interaction(id = "i4", source = "db", target = "api", description = "ok", sequence = 4, response = true)
            val ix5 = C4Interaction(id = "i5", source = "api", target = "web", description = "201 Created", sequence = 5, response = true)
            val ix6 =
                C4Interaction(id = "i6", source = "web", target = "customer", description = "Confirmation", sequence = 6, response = true)

            val diagram =
                DynamicDiagram(
                    id = "dyn1",
                    name = "Checkout Flow",
                    description = "Bestellung abschicken",
                    interactions = listOf(ix1, ix2, ix3, ix4, ix5, ix6),
                    elements = listOf("customer", "web", "api", "db"),
                )
            val model =
                C4Model(
                    id = "model1",
                    name = "Checkout",
                    elements = listOf(customer, web, api, db),
                )
            val layoutResult =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(600f, 600f),
                    nodes =
                        mapOf(
                            NodeId("customer") to NodeLayout(bounds = Rect(Point(20f, 20f), Size(140f, 80f))),
                            NodeId("web") to NodeLayout(bounds = Rect(Point(220f, 20f), Size(140f, 80f))),
                            NodeId("api") to NodeLayout(bounds = Rect(Point(220f, 160f), Size(140f, 80f))),
                            NodeId("db") to NodeLayout(bounds = Rect(Point(220f, 320f), Size(140f, 80f))),
                        ),
                    edges =
                        mapOf(
                            EdgeId("i1") to EdgeRoute.Direct(source = Point(160f, 60f), target = Point(220f, 60f)),
                            EdgeId("i2") to EdgeRoute.Direct(source = Point(290f, 100f), target = Point(290f, 160f)),
                            EdgeId("i3") to EdgeRoute.Direct(source = Point(290f, 240f), target = Point(290f, 320f)),
                            EdgeId("i4") to EdgeRoute.Direct(source = Point(310f, 320f), target = Point(310f, 240f)),
                            EdgeId("i5") to EdgeRoute.Direct(source = Point(310f, 160f), target = Point(310f, 100f)),
                            EdgeId("i6") to EdgeRoute.Direct(source = Point(220f, 80f), target = Point(160f, 80f)),
                        ),
                    groups = emptyMap(),
                )

            val svg = KumlSvgRenderer.toSvg(diagram, model, layoutResult, PlainTheme())

            // All 4 participants render (Person + 3 SoftwareSystems)
            svg shouldContain "id=\"customer\""
            svg shouldContain "id=\"web\""
            svg shouldContain "id=\"api\""
            svg shouldContain "id=\"db\""

            // Solid edges for requests (3 of them)
            val solidEdgeCount = Regex("class=\"kuml-edge\"").findAll(svg).count()
            solidEdgeCount shouldBe 3

            // Dashed edges for responses (3 of them)
            val dashedEdgeCount = Regex("class=\"kuml-edge kuml-edge-dashed\"").findAll(svg).count()
            dashedEdgeCount shouldBe 3

            // Sequence-numbered labels on every interaction
            svg shouldContain "1. Submit order"
            svg shouldContain "2. POST /orders"
            svg shouldContain "3. INSERT order"
            svg shouldContain "4. ok"
            svg shouldContain "5. 201 Created"
            svg shouldContain "6. Confirmation"

            // Technology tag on requests where set
            svg shouldContain "[HTTPS]"
            svg shouldContain "[JDBC]"

            SampleOutput.write("c4/dynamic-diagram-checkout-flow.svg", svg)
        }
    })
