package dev.kuml.vaultexamples

import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutEngineRegistry
import dev.kuml.layout.NodeId
import dev.kuml.layout.Point
import dev.kuml.layout.PortId
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.bridge.layoutHintsFor
import dev.kuml.sysml2.BindingConnectorUsage
import dev.kuml.sysml2.ConstraintDefinition
import dev.kuml.sysml2.ParDiagram
import dev.kuml.sysml2.Sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.math.abs
import kotlin.script.experimental.api.ResultWithDiagnostics

/** Wave C4 — nested constraints and pin bindings (PAR), plus the SEQ and REQ examples 50 and 52, full pipeline. */
class Sysml2C4RenderTest :
    FunSpec({
        fun script(name: String): String =
            Sysml2C4RenderTest::class.java
                .getResourceAsStream("/sysml2-c4/$name.kuml.kts")!!
                .bufferedReader()
                .readText()

        fun eval(code: String): ExtractedDiagram.Sysml2 {
            VaultExampleRenderer.init()
            val ok = KumlScriptHost.eval(code = code) as ResultWithDiagnostics.Success
            return DiagramExtractor.extractAny(returnValue = ok.value.returnValue, input = java.io.File("inline.kuml.kts"))
                as ExtractedDiagram.Sysml2
        }

        fun pts(r: EdgeRoute): List<Point> =
            when (r) {
                is EdgeRoute.Direct -> listOf(r.source, r.target)
                is EdgeRoute.OrthogonalRounded -> listOf(r.source) + r.waypoints + listOf(r.target)
                is EdgeRoute.TreeRounded -> listOf(r.source) + r.waypoints + listOf(r.target)
                is EdgeRoute.Bezier -> listOf(r.source) + r.controlPoints + listOf(r.target)
            }

        for (n in listOf("par-nested", "seq-nested", "req-wrap")) {
            test("render $n without error") {
                val r = VaultExampleRenderer.render(script = script(n))
                r.error shouldBe null
                SampleOutput.write(filename = "sysml2-c4/$n.svg", content = r.svg!!)
            }
        }

        test("par: pins sit on the frame at the parameter row and bindings end exactly at the pins") {
            val ex = eval(script("par-nested"))
            val model: Sysml2Model = ex.model
            val d = ex.diagram as ParDiagram
            val elk = LayoutEngineRegistry.get("elk.layered")!!
            val layout =
                Sysml2LayoutBridge.parEnrich(
                    model = model,
                    diagram = d,
                    layoutResult =
                        elk.layout(
                            graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = d),
                            hints = Sysml2LayoutBridge.layoutHintsFor(model = model, diagram = d),
                        ),
                )
            val constraints = model.definitions.filterIsInstance<ConstraintDefinition>().associateBy { it.id }
            // every pin: x on the frame, y at the expected row
            for (c in constraints.values) {
                val nl = layout.nodes.getValue(NodeId(c.id))
                c.parameters.forEachIndexed { i, p ->
                    val pin = nl.ports.getValue(PortId("${c.id}::${p.name}"))
                    val expectedY =
                        nl.bounds.origin.y +
                            dev.kuml.layout.bridge.ParConstraintGeometry
                                .pinY(hasExpression = c.expression.isNotEmpty(), index = i)
                    abs(pin.y - expectedY) shouldBeLessThanOrEqual 0.5f
                    val frameX =
                        if (p.direction == dev.kuml.sysml2.ConstraintParameterDirection.In) {
                            nl.bounds.origin.x
                        } else {
                            nl.bounds.origin.x + nl.bounds.size.width
                        }
                    abs(pin.x - frameX) shouldBeLessThanOrEqual 0.5f
                }
            }
            // nested boxes lie completely inside the owner box
            val owner = layout.nodes.getValue(NodeId("VehicleDynamics")).bounds
            for (child in listOf("Drag", "NewtonsLaw")) {
                val b = layout.nodes.getValue(NodeId(child)).bounds
                (b.origin.x >= owner.origin.x && b.origin.y >= owner.origin.y) shouldBe true
                (b.origin.x + b.size.width <= owner.origin.x + owner.size.width) shouldBe true
                (b.origin.y + b.size.height <= owner.origin.y + owner.size.height) shouldBe true
            }

            // every binding endpoint that names a pin ends within 1 px of that pin
            fun pinOf(endpoint: String): Point? {
                val cid = constraints.keys.filter { endpoint.startsWith("$it::") }.maxByOrNull { it.length } ?: return null
                return layout.nodes[NodeId(cid)]?.ports?.get(PortId(endpoint))
            }
            val bindings = model.usages.filterIsInstance<BindingConnectorUsage>()
            for (b in bindings) {
                val route = pts(layout.edges.getValue(dev.kuml.layout.EdgeId(b.id)))
                pinOf(b.sourceEndId)?.let { p ->
                    abs(route.first().x - p.x) shouldBeLessThanOrEqual 1f
                    abs(route.first().y - p.y) shouldBeLessThanOrEqual 1f
                }
                pinOf(b.targetEndId)?.let { p ->
                    abs(route.last().x - p.x) shouldBeLessThanOrEqual 1f
                    abs(route.last().y - p.y) shouldBeLessThanOrEqual 1f
                }
            }
            // inner bindings stay inside the owner box
            for (id in listOf("F_to_drag", "v_to_drag", "m_to_newton", "net_to_newton", "newton_to_a")) {
                val b = bindings.first { it.name == id }
                for (p in pts(layout.edges.getValue(dev.kuml.layout.EdgeId(b.id)))) {
                    (p.x >= owner.origin.x - 0.5f && p.x <= owner.origin.x + owner.size.width + 0.5f) shouldBe true
                    (p.y >= owner.origin.y - 0.5f && p.y <= owner.origin.y + owner.size.height + 0.5f) shouldBe true
                }
            }
        }

        test("par: svg draws one hollow pin per parameter and escapes parameter names") {
            val svg = VaultExampleRenderer.render(script = script("par-nested")).svg!!
            Regex("kuml-par-pin").findAll(svg).count() shouldBe 10
            val hostile =
                script(
                    "par-nested",
                ).replace("typeId = \"Acceleration\", direction = Out", "typeId = \"A<&\\\"\", direction = Out")
            // a hostile name must never appear unescaped in the svg
            val out = VaultExampleRenderer.render(script = hostile)
            out.error shouldBe null
            out.svg!! shouldNotContain "A<&\""
            out.svg!! shouldContain "A&lt;&amp;"
        }

        test("par: an old flat diagram keeps its node positions") {
            // Flat model, no parent and no pin binding: every node is positioned by the engine from the same sizes as before.
            val ex = eval(script("par-nested").replace("        parent = dynamics,\n", ""))
            val d = ex.diagram as ParDiagram
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = ex.model, diagram = d)
            graph.nodes.size shouldBe 4
            graph.nodes
                .first { it.id.value == "Drag" }
                .intrinsicSize.height shouldBe
                Sysml2LayoutBridge
                    .parContentAwareSizeProvider(ex.model)
                    .sizeOf(elementId = "Drag", elementKind = "ConstraintDefinition")
                    .height
        }

        test("req: wrapColumns = 4 places twelve requirements in four columns") {
            val ex = eval(script("req-wrap"))
            val d = ex.diagram
            val elk = LayoutEngineRegistry.get("elk.layered")!!
            val layout =
                elk.layout(
                    graph = Sysml2LayoutBridge.toLayoutGraph(model = ex.model, diagram = d as dev.kuml.sysml2.ReqDiagram),
                    hints = Sysml2LayoutBridge.layoutHintsFor(model = ex.model, diagram = d),
                )
            val rows = layout.nodes.values.groupBy { Math.round(it.bounds.origin.y / 20f) }
            // twelve nodes, at most four per row, and more than one row
            layout.nodes.size shouldBe 12
            (rows.size >= 3) shouldBe true
            (rows.values.maxOf { it.size } <= 4) shouldBe true
        }

        test("seq: four lifelines keep their declaration order") {
            val svg = VaultExampleRenderer.render(script = script("seq-nested")).svg!!
            val xs =
                listOf("Customer", "Shop", "Payment", "Bank").map {
                    Regex("""<g id="${Regex.escape(it)}" transform="translate\(([-0-9.]+),""").find(svg)!!.groupValues[1].toFloat()
                }
            xs shouldBe xs.sorted()
        }
    })
