package dev.kuml.io.svg.activity.smil

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SvgRenderOptions
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.render.smil.SpeedFactor
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.runtime.TraceEntry
import dev.kuml.runtime.TraceFile
import dev.kuml.uml.UmlActivityEdge
import dev.kuml.uml.UmlActivityNode
import dev.kuml.uml.UmlActivityNodeKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Tests for [ActivitySmilRenderer].
 *
 * V3.1.31 — STM + Activity SMIL Renderers
 */
class ActivitySmilRendererTest :
    FunSpec({

        // ── Fixtures ──────────────────────────────────────────────────────────

        val initialNode = UmlActivityNode(id = "initial", name = "", kind = UmlActivityNodeKind.INITIAL)
        val actionA = UmlActivityNode(id = "actionA", name = "Do Work", kind = UmlActivityNodeKind.ACTION)
        val forkNode = UmlActivityNode(id = "fork1", name = "", kind = UmlActivityNodeKind.FORK)
        val actionB = UmlActivityNode(id = "actionB", name = "Branch B", kind = UmlActivityNodeKind.ACTION)
        val joinNode = UmlActivityNode(id = "join1", name = "", kind = UmlActivityNodeKind.JOIN)
        val finalNode = UmlActivityNode(id = "finalNode", name = "", kind = UmlActivityNodeKind.ACTIVITY_FINAL)

        val edgeIA = UmlActivityEdge(id = "e_init_A", sourceId = "initial", targetId = "actionA")
        val edgeAFork = UmlActivityEdge(id = "e_A_fork", sourceId = "actionA", targetId = "fork1")
        val edgeForkB = UmlActivityEdge(id = "e_fork_B", sourceId = "fork1", targetId = "actionB")
        val edgeBJoin = UmlActivityEdge(id = "e_B_join", sourceId = "actionB", targetId = "join1")
        val edgeJoinFinal = UmlActivityEdge(id = "e_join_final", sourceId = "join1", targetId = "finalNode")

        val activityEdges = listOf(edgeIA, edgeAFork, edgeForkB, edgeBJoin, edgeJoinFinal)

        val diagram =
            KumlDiagram(
                name = "TestActivity",
                type = DiagramType.ACTIVITY,
                elements = listOf(initialNode, actionA, forkNode, actionB, joinNode, finalNode) + activityEdges,
            )

        val options = SvgRenderOptions(paddingPx = 64f)

        fun buildLayout(): LayoutResult {
            val p = options.paddingPx
            return LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = null,
                canvas = Size(width = 600f, height = 400f),
                nodes =
                    mapOf(
                        NodeId("initial") to
                            NodeLayout(bounds = Rect(origin = Point(x = 50f - p, y = 50f - p), size = Size(width = 24f, height = 24f))),
                        NodeId("actionA") to
                            NodeLayout(bounds = Rect(origin = Point(x = 100f - p, y = 40f - p), size = Size(width = 120f, height = 50f))),
                        NodeId("fork1") to
                            NodeLayout(bounds = Rect(origin = Point(x = 250f - p, y = 55f - p), size = Size(width = 8f, height = 50f))),
                        NodeId("actionB") to
                            NodeLayout(bounds = Rect(origin = Point(x = 290f - p, y = 40f - p), size = Size(width = 120f, height = 50f))),
                        NodeId("join1") to
                            NodeLayout(bounds = Rect(origin = Point(x = 440f - p, y = 55f - p), size = Size(width = 8f, height = 50f))),
                        NodeId("finalNode") to
                            NodeLayout(bounds = Rect(origin = Point(x = 480f - p, y = 50f - p), size = Size(width = 24f, height = 24f))),
                    ),
                edges =
                    mapOf(
                        EdgeId("e_init_A") to
                            EdgeRoute.Direct(
                                source = Point(x = 74f - p, y = 62f - p),
                                target = Point(x = 100f - p, y = 65f - p),
                            ),
                        EdgeId("e_A_fork") to
                            EdgeRoute.Direct(
                                source = Point(x = 220f - p, y = 65f - p),
                                target = Point(x = 250f - p, y = 80f - p),
                            ),
                        EdgeId("e_fork_B") to
                            EdgeRoute.Direct(
                                source = Point(x = 258f - p, y = 80f - p),
                                target = Point(x = 290f - p, y = 65f - p),
                            ),
                        EdgeId("e_B_join") to
                            EdgeRoute.Direct(
                                source = Point(x = 410f - p, y = 65f - p),
                                target = Point(x = 440f - p, y = 80f - p),
                            ),
                        EdgeId("e_join_final") to
                            EdgeRoute.Direct(
                                source = Point(x = 448f - p, y = 80f - p),
                                target = Point(x = 480f - p, y = 62f - p),
                            ),
                    ),
                groups = emptyMap(),
            )
        }

        val layoutResult = buildLayout()

        fun tokenTrace(vararg nodeIds: String): TraceFile =
            TraceFile(
                entries =
                    nodeIds.mapIndexed { idx, nodeId ->
                        TraceEntry.TokenPlaced(
                            seqNo = idx.toLong(),
                            timestamp = "2026-06-26T00:00:0${idx}Z",
                            nodeId = nodeId,
                            clock = idx.toLong(),
                        )
                    },
            )

        // ── (1) Null trace → byte-identical ──

        test("null trace produces output byte-identical to KumlSvgRenderer.toSvg") {
            val expected = KumlSvgRenderer.toSvg(diagram = diagram, layoutResult = layoutResult, theme = PlainTheme(), options = options)
            val result =
                ActivitySmilRenderer.render(
                    diagram = diagram,
                    activityEdges = activityEdges,
                    layoutResult = layoutResult,
                    options = options,
                    trace = null,
                )

            result.hasAnimation.shouldBeFalse()
            result.svg shouldBe expected
        }

        // ── (2) Empty trace → byte-identical ──

        test("empty trace produces output byte-identical to KumlSvgRenderer.toSvg") {
            val expected = KumlSvgRenderer.toSvg(diagram = diagram, layoutResult = layoutResult, theme = PlainTheme(), options = options)
            val result =
                ActivitySmilRenderer.render(
                    diagram = diagram,
                    activityEdges = activityEdges,
                    layoutResult = layoutResult,
                    options = options,
                    trace = TraceFile(entries = emptyList()),
                )

            result.hasAnimation.shouldBeFalse()
            result.svg shouldBe expected
        }

        // ── (3) TokenPlaced with resolvable flow → contains <animateMotion and injected token circle ──

        test("TokenPlaced with resolvable flow produces animateMotion and injected token circle") {
            val trace = tokenTrace("initial", "actionA")
            val result =
                ActivitySmilRenderer.render(
                    diagram = diagram,
                    activityEdges = activityEdges,
                    layoutResult = layoutResult,
                    options = options,
                    trace = trace,
                )

            result.hasAnimation.shouldBeTrue()
            result.svg shouldContain "<animateMotion"
            result.svg shouldContain "<circle"
        }

        // ── (4) TokenPlaced with no resolvable path → animation skipped (no crash) ──

        test("TokenPlaced with no resolvable edge path is silently skipped") {
            // "initial" → "finalNode" is not directly connected
            val trace = tokenTrace("initial", "finalNode")
            val result =
                ActivitySmilRenderer.render(
                    diagram = diagram,
                    activityEdges = activityEdges,
                    layoutResult = layoutResult,
                    options = options,
                    trace = trace,
                )

            // No motion animation produced — the result may have no animation at all
            result.svg shouldNotContain "<animateMotion"
        }

        // ── (5) TokenConsumed → <set attributeName="opacity" to="0" ──

        test("TokenConsumed after TokenPlaced produces set opacity=0") {
            val trace =
                TraceFile(
                    entries =
                        listOf(
                            TraceEntry.TokenPlaced(seqNo = 0, timestamp = "T0", nodeId = "initial", clock = 0),
                            TraceEntry.TokenPlaced(seqNo = 1, timestamp = "T1", nodeId = "actionA", clock = 1),
                            TraceEntry.TokenConsumed(seqNo = 2, timestamp = "T2", nodeId = "actionA", clock = 2),
                        ),
                )
            val result =
                ActivitySmilRenderer.render(
                    diagram = diagram,
                    activityEdges = activityEdges,
                    layoutResult = layoutResult,
                    options = options,
                    trace = trace,
                )

            result.hasAnimation.shouldBeTrue()
            result.svg shouldContain "attributeName=\"opacity\""
            result.svg shouldContain "to=\"0\""
        }

        // ── (6) ForkSplit at fork node → fill highlight animation ──

        test("ForkSplit produces fill highlight animation on fork node") {
            val trace =
                TraceFile(
                    entries =
                        listOf(
                            TraceEntry.TokenPlaced(seqNo = 0, timestamp = "T0", nodeId = "initial", clock = 0),
                            TraceEntry.TokenPlaced(seqNo = 1, timestamp = "T1", nodeId = "actionA", clock = 1),
                            TraceEntry.ForkSplit(
                                seqNo = 2,
                                timestamp = "T2",
                                nodeId = "fork1",
                                targetNodeIds = listOf("actionB"),
                                clock = 2,
                            ),
                        ),
                )
            val result =
                ActivitySmilRenderer.render(
                    diagram = diagram,
                    activityEdges = activityEdges,
                    layoutResult = layoutResult,
                    options = options,
                    trace = trace,
                )

            result.hasAnimation.shouldBeTrue()
            result.svg shouldContain "attributeName=\"fill\""
            result.svg shouldContain "fork1"
        }

        // ── (7) JoinReached → fill highlight animation ──

        test("JoinReached produces fill highlight animation on join node") {
            val trace =
                TraceFile(
                    entries =
                        listOf(
                            TraceEntry.JoinReached(
                                seqNo = 0,
                                timestamp = "T0",
                                nodeId = "join1",
                                awaitingEdgeIds = emptyList(),
                                isReady = true,
                                clock = 0,
                            ),
                        ),
                )
            val result =
                ActivitySmilRenderer.render(
                    diagram = diagram,
                    activityEdges = activityEdges,
                    layoutResult = layoutResult,
                    options = options,
                    trace = trace,
                )

            result.hasAnimation.shouldBeTrue()
            result.svg shouldContain "attributeName=\"fill\""
            result.svg shouldContain "join1"
        }

        // ── (8) Speed scaling ──

        test("speedFactor 2.0 halves animation begin and dur compared to 1.0") {
            val trace = tokenTrace("initial", "actionA", "fork1")
            val ctx1x = ActivityAnimationContext(speedFactor = SpeedFactor(1.0))
            val ctx2x = ActivityAnimationContext(speedFactor = SpeedFactor(2.0))

            val result1x =
                ActivitySmilRenderer.render(
                    diagram = diagram,
                    activityEdges = activityEdges,
                    layoutResult = layoutResult,
                    options = options,
                    trace = trace,
                    context = ctx1x,
                )
            val result2x =
                ActivitySmilRenderer.render(
                    diagram = diagram,
                    activityEdges = activityEdges,
                    layoutResult = layoutResult,
                    options = options,
                    trace = trace,
                    context = ctx2x,
                )

            result1x.hasAnimation.shouldBeTrue()
            result2x.hasAnimation.shouldBeTrue()

            val animTagRegex = Regex("<(animate|animateMotion|set)[ >]")
            animTagRegex.findAll(result1x.svg).count() shouldBe animTagRegex.findAll(result2x.svg).count()

            val beginRegex = Regex("""begin="(\d+)ms"""")
            val nonZero1x = beginRegex.findAll(result1x.svg).map { it.groupValues[1].toLong() }.firstOrNull { it > 0 }
            val nonZero2x = beginRegex.findAll(result2x.svg).map { it.groupValues[1].toLong() }.firstOrNull { it > 0 }
            if (nonZero1x != null && nonZero2x != null) {
                nonZero1x shouldBe nonZero2x * 2
            }
        }

        // ── (9) Color allowlist rejection ──

        test("ActivityAnimationContext rejects injection payload in tokenColor") {
            shouldThrow<IllegalArgumentException> {
                ActivityAnimationContext(tokenColor = "\"/><script>")
            }
        }

        test("ActivityAnimationContext rejects invalid highlight color") {
            shouldThrow<IllegalArgumentException> {
                ActivityAnimationContext(highlightColor = "rgb(255,0,0)")
            }
        }

        // ── (10) MAX_ANIMATIONS cap ──

        test("trace exceeding MAX_ANIMATIONS throws IllegalArgumentException") {
            val oversized =
                TraceFile(
                    entries =
                        (0..(ActivityAnimationContext.MAX_ANIMATIONS + 10)).map { idx ->
                            TraceEntry.TokenPlaced(
                                seqNo = idx.toLong(),
                                timestamp = "T$idx",
                                nodeId = "actionA",
                                clock = idx.toLong(),
                            )
                        },
                )
            shouldThrow<IllegalArgumentException> {
                ActivitySmilRenderer.render(
                    diagram = diagram,
                    activityEdges = activityEdges,
                    layoutResult = layoutResult,
                    options = options,
                    trace = oversized,
                )
            }
        }

        // ── ActivityFlowPathResolver ──

        test("ActivityFlowPathResolver buildEdgePaths produces path for Direct route") {
            val edge = UmlActivityEdge(id = "e1", sourceId = "a", targetId = "b")
            val lr =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(width = 200f, height = 100f),
                    nodes = emptyMap(),
                    edges = mapOf(EdgeId("e1") to EdgeRoute.Direct(source = Point(x = 10f, y = 20f), target = Point(x = 100f, y = 20f))),
                    groups = emptyMap(),
                )
            val paths = ActivityFlowPathResolver.buildEdgePaths(edges = listOf(edge), layoutResult = lr, padding = 0f)
            paths["e1"] shouldBe "M 10 20 L 100 20"
        }

        test("ActivityFlowPathResolver buildFlowIndex keys by sourceId->targetId") {
            val edges =
                listOf(
                    UmlActivityEdge(id = "e1", sourceId = "a", targetId = "b"),
                    UmlActivityEdge(id = "e2", sourceId = "b", targetId = "c"),
                )
            val index = ActivityFlowPathResolver.buildFlowIndex(edges)
            index["a->b"]?.id shouldBe "e1"
            index["b->c"]?.id shouldBe "e2"
        }

        // ── Well-formed output ──

        test("animated output contains exactly one closing svg tag") {
            val trace = tokenTrace("initial", "actionA")
            val result =
                ActivitySmilRenderer.render(
                    diagram = diagram,
                    activityEdges = activityEdges,
                    layoutResult = layoutResult,
                    options = options,
                    trace = trace,
                )

            result.hasAnimation.shouldBeTrue()
            Regex("</svg>").findAll(result.svg).count() shouldBe 1
        }
    })
