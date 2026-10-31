package dev.kuml.layout.bridge

import dev.kuml.core.model.layout.LayoutCodes
import dev.kuml.core.model.layout.LayoutFlow
import dev.kuml.core.model.layout.LayoutLimits
import dev.kuml.core.model.layout.LayoutOption
import dev.kuml.core.model.layout.LayoutSpec
import dev.kuml.layout.DiagramKind
import dev.kuml.layout.EdgeRouteStyle
import dev.kuml.layout.KumlLayoutEngine
import dev.kuml.layout.LayoutCapabilities
import dev.kuml.layout.LayoutDirection
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutNode
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.Size
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/**
 * Fake engine: nodes of one layer (layerIndex hint; unhinted = all in layer 0) sit side by side,
 * 200 px wide and 100 px high; canvas = widest layer x layer count.
 */
private class WrapAwareEngine(
    private val nodeWidth: Float = 200f,
    private val nodeHeight: Float = 100f,
) : KumlLayoutEngine {
    var runs = 0
    val widestLayers = mutableListOf<Int>()
    var lastDirection: LayoutDirection? = null
    override val id = LayoutEngineId("fake")
    override val capabilities =
        LayoutCapabilities(
            deterministic = true,
            supportedDiagramKinds = setOf(DiagramKind.Generic),
            supportedEdgeStyles = setOf(EdgeRouteStyle.Direct),
            respectsGridHints = false,
            respectsRelativeConstraints = false,
            maxRecommendedNodes = 100,
            supportsLayerHints = true,
        )

    override fun layout(
        graph: LayoutGraph,
        hints: LayoutHints,
    ): LayoutResult {
        runs++
        lastDirection = hints.direction
        val perLayer = graph.nodes.groupingBy { it.hints.layerIndex ?: 0 }.eachCount()
        widestLayers += perLayer.values.max()
        return LayoutResult(
            engineId = id,
            seed = null,
            canvas = Size(width = nodeWidth * perLayer.values.max(), height = nodeHeight * perLayer.size),
            nodes = emptyMap(),
            edges = emptyMap(),
            groups = emptyMap(),
        )
    }
}

private fun flat(n: Int) =
    LayoutGraph(
        nodes =
            (1..n).map {
                LayoutNode(id = NodeId("n$it"), intrinsicSize = Size(width = 200f, height = 100f))
            },
        edges = emptyList(),
    )

class LayoutSpecRunnerTest :
    FunSpec({
        test("explicit aspectRatio wraps a flat graph and uses at most two layout runs") {
            val engine = WrapAwareEngine()
            val out =
                LayoutSpecRunner.layout(
                    engine = engine,
                    graph = flat(12),
                    hints = LayoutHints(),
                    spec = LayoutSpec(aspectRatio = 1.6),
                )
            engine.runs shouldBe 2
            val r = out.result.canvas.width / out.result.canvas.height
            (r < 12 * 200f / 100f) shouldBe true
            (r in 0.8f..3.2f) shouldBe true
        }

        test("maxWidth wins: the width never exceeds the limit when reachable") {
            val engine = WrapAwareEngine()
            val out = LayoutSpecRunner.layout(engine = engine, graph = flat(12), hints = LayoutHints(), spec = LayoutSpec(maxWidth = 1000))
            (out.result.canvas.width <= 1000f) shouldBe true
            out.findings.shouldBeEmpty()
        }

        test("an unreachable maxWidth is reported (W002) and the best result is kept") {
            val engine = WrapAwareEngine(nodeWidth = 400f)
            val out = LayoutSpecRunner.layout(engine = engine, graph = flat(3), hints = LayoutHints(), spec = LayoutSpec(maxWidth = 300))
            out.findings.map { it.code } shouldContain LayoutCodes.MAX_WIDTH_UNREACHED
        }

        test("pictures inside the 2.5:1 frame are not touched") {
            val engine = WrapAwareEngine(nodeHeight = 200f)
            LayoutSpecRunner.layout(engine = engine, graph = flat(2), hints = LayoutHints(), spec = null)
            engine.runs shouldBe 1
        }

        test("implicit frame wraps an extremely wide picture, autoAspect=false does not") {
            val wide = WrapAwareEngine()
            LayoutSpecRunner.layout(engine = wide, graph = flat(12), hints = LayoutHints(), spec = null)
            wide.runs shouldBe 2
            val off = WrapAwareEngine()
            LayoutSpecRunner.layout(engine = off, graph = flat(12), hints = LayoutHints(autoAspect = false), spec = null)
            off.runs shouldBe 1
        }

        test("the wrap column count is clamped to WRAP_COLUMNS_MAX and the second run actually wraps") {
            // 50 columns of 20x100 -> ratio 10; a valid target of 7 asks for ceil(50*sqrt(0.7)) = 42 columns,
            // which only the WRAP_COLUMNS_MAX clamp (32) can reduce.
            val engine = WrapAwareEngine(nodeWidth = 20f, nodeHeight = 100f)
            val out =
                LayoutSpecRunner.layout(
                    engine = engine,
                    graph = flat(50),
                    hints = LayoutHints(),
                    spec = LayoutSpec(aspectRatio = 7.0),
                )
            out.findings.map { it.code } shouldNotContain LayoutCodes.OUT_OF_RANGE
            engine.runs shouldBe 2
            engine.widestLayers[0] shouldBe 50
            engine.widestLayers[1] shouldBe LayoutLimits.WRAP_COLUMNS_MAX
        }

        test("direction is mapped onto the hints") {
            val engine = WrapAwareEngine()
            LayoutSpecRunner.layout(
                engine = engine,
                graph = flat(2),
                hints = LayoutHints(),
                spec = LayoutSpec(direction = LayoutFlow.Right),
            )
            engine.lastDirection shouldBe LayoutDirection.LeftToRight
        }

        test("FIXED diagram kinds report every set option as W001 and run once") {
            val engine = WrapAwareEngine()
            val out =
                LayoutSpecRunner.layout(
                    engine = engine,
                    graph = flat(2),
                    hints = LayoutHints(),
                    spec = LayoutSpec(direction = LayoutFlow.Right, wrapColumns = 2),
                    kind = LayoutDiagramKind.FIXED,
                )
            engine.runs shouldBe 1
            out.findings.map { it.code }.distinct() shouldBe listOf(LayoutCodes.NO_EFFECT)
            out.findings.size shouldBe 2
            LayoutSpecRunner.ignoredOptions(spec = LayoutSpec(maxWidth = 900), kind = LayoutDiagramKind.FIXED) shouldBe
                listOf(LayoutOption.MAX_WIDTH)
        }

        test("out-of-range values are dropped with E001") {
            val engine = WrapAwareEngine()
            val out =
                LayoutSpecRunner.layout(
                    engine = engine,
                    graph = flat(2),
                    hints = LayoutHints(),
                    spec = LayoutSpec(maxWidth = 5, aspectRatio = Double.NaN),
                )
            out.findings.count { it.code == LayoutCodes.OUT_OF_RANGE } shouldBe 2
        }
    })
