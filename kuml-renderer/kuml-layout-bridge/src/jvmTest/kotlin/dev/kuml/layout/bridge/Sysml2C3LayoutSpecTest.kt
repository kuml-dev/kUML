package dev.kuml.layout.bridge

import dev.kuml.layout.LayerPin
import dev.kuml.layout.LayoutDirection
import dev.kuml.layout.NodeId
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.math.abs

/** C3-4 — layout steering hints: layering, wrap, sameLayer, first/last, direction. */
class Sysml2C3LayoutSpecTest :
    StringSpec({
        fun model(layoutBlock: (dev.kuml.sysml2.dsl.Sysml2LayoutBuilder.(List<dev.kuml.sysml2.PartDefinition>) -> Unit)?) =
            sysml2Model(name = "L") {
                val parts = (0 until 13).map { partDef(name = "P$it", specializesId = if (it == 0) null else "P0") }
                bdd(name = "D") {
                    parts.forEach { include(it) }
                    if (layoutBlock != null) layout { layoutBlock(parts) }
                }
            }

        "wrapLayers: longest path, back edges ignored, wrapping shifts later layers" {
            val nodes = listOf("a", "b", "c", "d", "e")
            val edges = listOf("a" to "b", "b" to "c", "c" to "a", "a" to "d") // cycle a-b-c
            val plain = wrapLayers(nodes = nodes, edges = edges, columns = null)
            plain["a"] shouldBe 0
            plain["b"] shouldBe 1
            plain["d"] shouldBe 1
            plain["c"] shouldBe 2
            plain["e"] shouldBe 0
            // layer 0 holds a and e, layer 1 holds b and d -> wrap 1 spreads each layer over two
            val wrapped = wrapLayers(nodes = nodes, edges = edges, columns = 1)
            wrapped["a"] shouldBe 0
            wrapped["e"] shouldBe 1
            wrapped["b"] shouldBe 2
            wrapped["d"] shouldBe 3
            wrapped["c"] shouldBe 4
        }

        "wrapColumns narrows a wide layer" {
            val engine = ElkLayoutEngine()
            val wide = model(null)
            val wrapped = model { wrapColumns = 4 }

            fun width(m: dev.kuml.sysml2.Sysml2Model): Float {
                val d = m.diagrams.filterIsInstance<BdDiagram>().single()
                val graph = Sysml2LayoutBridge.toLayoutGraph(model = m, diagram = d)
                return engine.layout(graph = graph, hints = Sysml2LayoutBridge.layoutHintsFor(model = m, diagram = d)).canvas.width
            }
            width(wrapped) shouldBeLessThan width(wide)
            width(wrapped) shouldBeLessThan 1600f
        }

        "sameLayer shares a coordinate, first and last are the extreme layers" {
            val m =
                model { parts ->
                    wrapColumns = 3
                    sameLayer(parts[1], parts[7])
                    first(parts[0])
                    last(parts[12])
                    direction = Right
                }
            val d = m.diagrams.filterIsInstance<BdDiagram>().single()
            Sysml2LayoutBridge.layoutHintsFor(model = m, diagram = d).direction shouldBe LayoutDirection.LeftToRight
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = m, diagram = d)
            graph.nodes
                .single { it.id.value == "P0" }
                .hints.layerIndex shouldBe 0
            graph.nodes
                .single { it.id.value == "P12" }
                .hints.layerIndex shouldBe graph.nodes.maxOf { it.hints.layerIndex ?: 0 }
            val result = ElkLayoutEngine().layout(graph = graph, hints = Sysml2LayoutBridge.layoutHintsFor(model = m, diagram = d))
            val xs = result.nodes.mapValues { it.value.bounds.origin.x }
            abs(xs.getValue(NodeId("P1")) - xs.getValue(NodeId("P7"))) shouldBeLessThanOrEqual 1f
            abs(xs.getValue(NodeId("P0")) - xs.values.min()) shouldBeLessThanOrEqual 1f
            abs(xs.getValue(NodeId("P12")) - xs.values.max()) shouldBeLessThanOrEqual 1f
        }

        "first/last without wrap or sameLayer use ELK's layer pins (when the edges allow it)" {
            val m =
                model { parts ->
                    first(parts[12])
                    last(parts[0])
                }
            val d = m.diagrams.filterIsInstance<BdDiagram>().single()
            val g = Sysml2LayoutBridge.toLayoutGraph(model = m, diagram = d)
            g.nodes
                .single { it.id.value == "P12" }
                .hints.layerPin shouldBe LayerPin.First
            g.nodes
                .single { it.id.value == "P0" }
                .hints.layerPin shouldBe LayerPin.Last
            ElkLayoutEngine().layout(graph = g, hints = Sysml2LayoutBridge.layoutHintsFor(model = m, diagram = d))
        }

        "a diagram without layout hints produces a graph without layer hints" {
            val m = model(null)
            val d = m.diagrams.filterIsInstance<BdDiagram>().single()
            Sysml2LayoutBridge
                .toLayoutGraph(
                    model = m,
                    diagram = d,
                ).nodes
                .all { it.hints.layerIndex == null && it.hints.layerPin == null } shouldBe
                true
        }
    })
