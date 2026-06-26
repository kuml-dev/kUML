package dev.kuml.io.svg.stm.smil

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
import dev.kuml.uml.PseudostateKind
import dev.kuml.uml.UmlFinalState
import dev.kuml.uml.UmlPseudostate
import dev.kuml.uml.UmlState
import dev.kuml.uml.UmlStateMachine
import dev.kuml.uml.UmlTransition
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Tests for [StmSmilRenderer].
 *
 * V3.1.31 — STM + Activity SMIL Renderers
 */
class StmSmilRendererTest :
    FunSpec({

        // ── Fixtures ──────────────────────────────────────────────────────────

        val initialState =
            UmlPseudostate(id = "initial", name = "initial", kind = PseudostateKind.INITIAL)
        val stateA = UmlState(id = "stateA", name = "Idle")
        val stateB = UmlState(id = "stateB", name = "Running")
        val finalState = UmlFinalState(id = "final", name = "Done")

        val transAB =
            UmlTransition(
                id = "t_initial_A",
                sourceId = "initial",
                targetId = "stateA",
            )
        val transBC =
            UmlTransition(
                id = "t_A_B",
                sourceId = "stateA",
                targetId = "stateB",
                trigger = "start()",
            )
        val transEnd =
            UmlTransition(
                id = "t_B_final",
                sourceId = "stateB",
                targetId = "final",
            )

        val sm =
            UmlStateMachine(
                id = "sm1",
                name = "OrderMachine",
                vertices = listOf(initialState, stateA, stateB, finalState),
                transitions = listOf(transAB, transBC, transEnd),
            )

        val diagram =
            KumlDiagram(
                name = "OrderMachine",
                type = DiagramType.STATE,
                elements = listOf(sm),
            )

        val options = SvgRenderOptions(paddingPx = 64f)

        fun buildLayout(): LayoutResult {
            val p = options.paddingPx
            return LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = null,
                canvas = Size(500f, 400f),
                nodes =
                    mapOf(
                        NodeId("sm1") to NodeLayout(Rect(Point(0f, 0f), Size(500f, 400f))),
                        NodeId("initial") to NodeLayout(Rect(Point(20f - p, 20f - p), Size(24f, 24f))),
                        NodeId("stateA") to NodeLayout(Rect(Point(100f - p, 50f - p), Size(120f, 60f))),
                        NodeId("stateB") to NodeLayout(Rect(Point(250f - p, 50f - p), Size(120f, 60f))),
                        NodeId("final") to NodeLayout(Rect(Point(400f - p, 50f - p), Size(24f, 24f))),
                    ),
                edges =
                    mapOf(
                        EdgeId("t_initial_A") to
                            EdgeRoute.Direct(
                                source = Point(20f - p, 32f - p),
                                target = Point(100f - p, 80f - p),
                            ),
                        EdgeId("t_A_B") to
                            EdgeRoute.Direct(
                                source = Point(220f - p, 80f - p),
                                target = Point(250f - p, 80f - p),
                            ),
                        EdgeId("t_B_final") to
                            EdgeRoute.Direct(
                                source = Point(370f - p, 80f - p),
                                target = Point(400f - p, 62f - p),
                            ),
                    ),
                groups = emptyMap(),
            )
        }

        val layoutResult = buildLayout()

        fun stateEnteredTrace(vararg vertexIds: String): TraceFile =
            TraceFile(
                entries =
                    vertexIds.mapIndexed { idx, vid ->
                        TraceEntry.StateEntered(
                            seqNo = idx.toLong(),
                            timestamp = "2026-06-26T00:00:0${idx}Z",
                            vertexId = vid,
                        )
                    },
            )

        // ── (1) Null trace → byte-identical to KumlSvgRenderer.toSvg ──

        test("null trace produces output byte-identical to KumlSvgRenderer.toSvg") {
            val expected = KumlSvgRenderer.toSvg(diagram, layoutResult, PlainTheme(), options)
            val result = StmSmilRenderer.render(diagram, sm, layoutResult, options = options, trace = null)

            result.hasAnimation.shouldBeFalse()
            result.svg shouldBe expected
        }

        // ── (2) Empty trace → byte-identical ──

        test("empty trace produces output byte-identical to KumlSvgRenderer.toSvg") {
            val expected = KumlSvgRenderer.toSvg(diagram, layoutResult, PlainTheme(), options)
            val result =
                StmSmilRenderer.render(
                    diagram,
                    sm,
                    layoutResult,
                    options = options,
                    trace = TraceFile(entries = emptyList()),
                )

            result.hasAnimation.shouldBeFalse()
            result.svg shouldBe expected
        }

        // ── (3) StateEntered trace → contains <animate attributeName="fill" and overlay rect id ──

        test("StateEntered trace produces animate fill and highlight overlay rect") {
            val trace = stateEnteredTrace("stateA", "stateB")
            val result = StmSmilRenderer.render(diagram, sm, layoutResult, options = options, trace = trace)

            result.hasAnimation.shouldBeTrue()
            result.svg shouldContain "attributeName=\"fill\""
            result.svg shouldContain "smil-stm-hl-stateA"
        }

        // ── (4) TransitionFired → contains two stroke-width pulse animations on overlay path ──

        test("TransitionFired trace produces two stroke-width pulse animations on overlay path") {
            val trace =
                TraceFile(
                    entries =
                        listOf(
                            TraceEntry.StateEntered(seqNo = 0, timestamp = "T0", vertexId = "stateA"),
                            TraceEntry.TransitionFired(
                                seqNo = 1,
                                timestamp = "T1",
                                transitionId = "t_A_B",
                                fromVertexId = "stateA",
                                toVertexId = "stateB",
                            ),
                            TraceEntry.StateEntered(seqNo = 2, timestamp = "T2", vertexId = "stateB"),
                        ),
                )
            val result = StmSmilRenderer.render(diagram, sm, layoutResult, options = options, trace = trace)

            result.hasAnimation.shouldBeTrue()
            result.svg shouldContain "attributeName=\"stroke-width\""
            result.svg shouldContain "smil-stm-trans-t_A_B"
        }

        // ── (5) Terminated → contains opacity animate on final state overlay ──

        test("Terminated trace produces opacity animation on final state overlay") {
            val trace =
                TraceFile(
                    entries =
                        listOf(
                            TraceEntry.StateEntered(seqNo = 0, timestamp = "T0", vertexId = "stateA"),
                            TraceEntry.Terminated(
                                seqNo = 1,
                                timestamp = "T1",
                                finalVertexId = "final",
                            ),
                        ),
                )
            val result = StmSmilRenderer.render(diagram, sm, layoutResult, options = options, trace = trace)

            result.hasAnimation.shouldBeTrue()
            result.svg shouldContain "smil-stm-hl-final"
            result.svg shouldContain "attributeName=\"opacity\""
        }

        // ── (6) SpeedFactor 2.0 halves begin/dur vs 1.0 ──

        test("speedFactor 2.0 halves begin and dur values compared to 1.0") {
            val trace = stateEnteredTrace("stateA", "stateB")
            val ctx1x = StmAnimationContext(speedFactor = SpeedFactor(1.0))
            val ctx2x = StmAnimationContext(speedFactor = SpeedFactor(2.0))

            val result1x = StmSmilRenderer.render(diagram, sm, layoutResult, options = options, trace = trace, context = ctx1x)
            val result2x = StmSmilRenderer.render(diagram, sm, layoutResult, options = options, trace = trace, context = ctx2x)

            result1x.hasAnimation.shouldBeTrue()
            result2x.hasAnimation.shouldBeTrue()

            val beginRegex = Regex("""begin="(\d+)ms"""")
            val animTagRegex = Regex("<(animate|set)[ >]")
            // Both renders must have the same count of animation elements
            animTagRegex.findAll(result1x.svg).count() shouldBe animTagRegex.findAll(result2x.svg).count()

            val nonZero1x =
                beginRegex
                    .findAll(result1x.svg)
                    .map { it.groupValues[1].toLong() }
                    .firstOrNull { it > 0 }
            val nonZero2x =
                beginRegex
                    .findAll(result2x.svg)
                    .map { it.groupValues[1].toLong() }
                    .firstOrNull { it > 0 }
            if (nonZero1x != null && nonZero2x != null) {
                nonZero1x shouldBe nonZero2x * 2
            }
        }

        // ── (7) StmAnimationContext rejects injection payload ──

        test("StmAnimationContext rejects injection payload in highlightColor") {
            shouldThrow<IllegalArgumentException> {
                StmAnimationContext(highlightColor = "\"/><script>alert(1)</script>")
            }
        }

        // ── (8) Trace exceeding MAX_ANIMATIONS throws ──

        test("trace exceeding MAX_ANIMATIONS throws IllegalArgumentException") {
            val oversized =
                TraceFile(
                    entries =
                        (0..(StmAnimationContext.MAX_ANIMATIONS + 10)).map { idx ->
                            TraceEntry.StateEntered(
                                seqNo = idx.toLong(),
                                timestamp = "T$idx",
                                vertexId = "stateA",
                            )
                        },
                )
            shouldThrow<IllegalArgumentException> {
                StmSmilRenderer.render(diagram, sm, layoutResult, options = options, trace = oversized)
            }
        }

        // ── (9) Transition with no layout edge route is skipped (no crash) ──

        test("transition with no layout edge route is skipped without crash") {
            // Only layout stateA and stateB, but NOT the transition between them
            val minimalLayout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(500f, 400f),
                    nodes =
                        mapOf(
                            NodeId("sm1") to NodeLayout(Rect(Point(0f, 0f), Size(500f, 400f))),
                            NodeId("stateA") to NodeLayout(Rect(Point(100f, 50f), Size(120f, 60f))),
                            NodeId("stateB") to NodeLayout(Rect(Point(250f, 50f), Size(120f, 60f))),
                        ),
                    edges = emptyMap(), // no edge routes
                    groups = emptyMap(),
                )

            val trace =
                TraceFile(
                    entries =
                        listOf(
                            TraceEntry.TransitionFired(
                                seqNo = 0,
                                timestamp = "T0",
                                transitionId = "t_A_B",
                                fromVertexId = "stateA",
                                toVertexId = "stateB",
                            ),
                        ),
                )

            // Should not crash; transition is skipped since no layout edge exists
            val result = StmSmilRenderer.render(diagram, sm, minimalLayout, options = options, trace = trace)
            // No animation produced since the transition path is unresolvable
            result.svg shouldNotContain "smil-stm-trans-t_A_B"
        }

        // ── (10) BPMN-style token trace (no STM entries) → hasAnimation=false, byte-identical ──

        test("BPMN-style token trace (no StateEntered/TransitionFired entries) yields hasAnimation=false") {
            val bpmnTrace =
                TraceFile(
                    entries =
                        listOf(
                            TraceEntry.TokenPlaced(seqNo = 0, timestamp = "T0", nodeId = "node1", clock = 0),
                            TraceEntry.TokenConsumed(seqNo = 1, timestamp = "T1", nodeId = "node1", clock = 1),
                        ),
                )

            val expected = KumlSvgRenderer.toSvg(diagram, layoutResult, PlainTheme(), options)
            val result = StmSmilRenderer.render(diagram, sm, layoutResult, options = options, trace = bpmnTrace)

            result.hasAnimation.shouldBeFalse()
            result.svg shouldBe expected
        }

        // ── (11) Well-formed: single </svg> ──

        test("output contains exactly one closing svg tag") {
            val trace = stateEnteredTrace("stateA", "stateB")
            val result = StmSmilRenderer.render(diagram, sm, layoutResult, options = options, trace = trace)

            result.hasAnimation.shouldBeTrue()
            val svgCloseCount = Regex("</svg>").findAll(result.svg).count()
            svgCloseCount shouldBe 1
        }

        // ── StmAnimationContext color validation ──

        test("StmAnimationContext accepts valid hex colors and named colors") {
            StmAnimationContext(highlightColor = "#ffd54a")
            StmAnimationContext(highlightColor = "#f00")
            StmAnimationContext(normalColor = "white")
            StmAnimationContext(normalColor = "#ffffff")
        }

        test("StmAnimationContext rejects invalid color values") {
            shouldThrow<IllegalArgumentException> {
                StmAnimationContext(highlightColor = "javascript:void(0)")
            }
            shouldThrow<IllegalArgumentException> {
                StmAnimationContext(normalColor = "rgb(255,0,0)")
            }
        }

        // ── StmTransitionPathResolver ──

        test("StmTransitionPathResolver buildTransitionPaths produces Direct path for simple route") {
            val singleTransition =
                UmlTransition(
                    id = "t1",
                    sourceId = "stateA",
                    targetId = "stateB",
                )
            val minimalLayout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(300f, 200f),
                    nodes = emptyMap(),
                    edges =
                        mapOf(
                            EdgeId("t1") to
                                EdgeRoute.Direct(
                                    source = Point(10f, 20f),
                                    target = Point(100f, 20f),
                                ),
                        ),
                    groups = emptyMap(),
                )

            val paths = StmTransitionPathResolver.buildTransitionPaths(listOf(singleTransition), minimalLayout, padding = 0f)
            paths["t1"] shouldBe "M 10 20 L 100 20"
        }

        test("StmStateTimelineBuilder sanitizeId replaces special chars") {
            val sanitized = StmStateTimelineBuilder.sanitizeId("state<A>&B")
            sanitized shouldBe "state_A__B"
        }
    })
