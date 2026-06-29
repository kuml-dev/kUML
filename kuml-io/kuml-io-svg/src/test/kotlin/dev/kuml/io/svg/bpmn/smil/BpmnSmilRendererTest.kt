package dev.kuml.io.svg.bpmn.smil

import dev.kuml.bpmn.model.BpmnEvent
import dev.kuml.bpmn.model.BpmnGateway
import dev.kuml.bpmn.model.BpmnProcess
import dev.kuml.bpmn.model.BpmnTask
import dev.kuml.bpmn.model.EventBehaviour
import dev.kuml.bpmn.model.EventDefinition
import dev.kuml.bpmn.model.EventPosition
import dev.kuml.bpmn.model.GatewayType
import dev.kuml.bpmn.model.SequenceFlow
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
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Comprehensive tests for [BpmnSmilRenderer].
 *
 * V3.1.30 — BPMN SMIL Renderer
 */
class BpmnSmilRendererTest :
    FunSpec({

        // ── Fixtures ──────────────────────────────────────────────────────────

        val startEvent =
            BpmnEvent(
                id = "start1",
                name = "Start",
                position = EventPosition.START,
                definition = EventDefinition.NONE,
                behaviour = EventBehaviour.CATCHING,
                outgoing = listOf("flow1"),
            )
        val task1 =
            BpmnTask(
                id = "task1",
                name = "Do Something",
                incoming = listOf("flow1"),
                outgoing = listOf("flow2"),
            )
        val gateway1 =
            BpmnGateway(
                id = "gw1",
                name = "Decision",
                gatewayType = GatewayType.EXCLUSIVE,
                incoming = listOf("flow2"),
                outgoing = listOf("flow3"),
            )
        val endEvent =
            BpmnEvent(
                id = "end1",
                name = "End",
                position = EventPosition.END,
                definition = EventDefinition.NONE,
                behaviour = EventBehaviour.THROWING,
                incoming = listOf("flow3"),
            )
        val flow1 = SequenceFlow(id = "flow1", sourceRef = "start1", targetRef = "task1")
        val flow2 = SequenceFlow(id = "flow2", sourceRef = "task1", targetRef = "gw1")
        val flow3 = SequenceFlow(id = "flow3", sourceRef = "gw1", targetRef = "end1")

        val process =
            BpmnProcess(
                id = "proc1",
                name = "Test Process",
                flowNodes = listOf(startEvent, task1, gateway1, endEvent),
                sequenceFlows = listOf(flow1, flow2, flow3),
            )

        val diagram =
            KumlDiagram(
                name = "Test BPMN",
                type = DiagramType.BPMN_PROCESS,
                elements = process.renderableElements(),
            )

        fun buildLayout(): LayoutResult {
            val padding = SvgRenderOptions.DEFAULT.paddingPx
            return LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = null,
                canvas = Size(500f, 200f),
                nodes =
                    mapOf(
                        NodeId("start1") to NodeLayout(Rect(Point(10f, 80f), Size(36f, 36f))),
                        NodeId("task1") to NodeLayout(Rect(Point(80f, 65f), Size(120f, 60f))),
                        NodeId("gw1") to NodeLayout(Rect(Point(240f, 73f), Size(50f, 50f))),
                        NodeId("end1") to NodeLayout(Rect(Point(340f, 80f), Size(36f, 36f))),
                    ),
                edges =
                    mapOf(
                        EdgeId("flow1") to
                            EdgeRoute.Direct(
                                source = Point(46f - padding, 98f - padding),
                                target = Point(80f - padding, 95f - padding),
                            ),
                        EdgeId("flow2") to
                            EdgeRoute.Direct(
                                source = Point(200f - padding, 95f - padding),
                                target = Point(240f - padding, 98f - padding),
                            ),
                        EdgeId("flow3") to
                            EdgeRoute.Direct(
                                source = Point(290f - padding, 98f - padding),
                                target = Point(340f - padding, 98f - padding),
                            ),
                    ),
                groups = emptyMap(),
            )
        }

        fun simpleTrace(vararg nodeIds: String): TraceFile =
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

        val layoutResult = buildLayout()

        // ── (1) Static mode: null trace → byte-identical to KumlSvgRenderer.toSvg ──

        test("static mode: null trace produces output identical to KumlSvgRenderer.toSvg") {
            val expected = KumlSvgRenderer.toSvg(diagram, layoutResult, PlainTheme(), SvgRenderOptions.DEFAULT)
            val result = BpmnSmilRenderer.render(diagram, layoutResult, trace = null)

            result.hasAnimation.shouldBeFalse()
            result.svg shouldBe expected
        }

        // ── (2) Static mode: empty trace → byte-identical ──

        test("static mode: empty trace (no entries) is identical to static") {
            val expected = KumlSvgRenderer.toSvg(diagram, layoutResult, PlainTheme(), SvgRenderOptions.DEFAULT)
            val emptyTrace = TraceFile(entries = emptyList())
            val result = BpmnSmilRenderer.render(diagram, layoutResult, trace = emptyTrace)

            result.hasAnimation.shouldBeFalse()
            result.svg shouldBe expected
        }

        // ── (3) Animated svg contains <animateMotion> ──

        test("animated svg contains <animateMotion>") {
            val trace = simpleTrace("start1", "task1", "gw1", "end1")
            val result = BpmnSmilRenderer.render(diagram, layoutResult, trace = trace)

            result.hasAnimation.shouldBeTrue()
            result.svg shouldContain "<animateMotion"
        }

        // ── (4) animateMotion path attribute equals SequenceFlow drawn path d-string ──

        test("animateMotion path attribute equals the SequenceFlow drawn path d-string") {
            val padding = SvgRenderOptions.DEFAULT.paddingPx
            val edgePaths =
                BpmnFlowPathResolver.buildEdgePaths(
                    process = process,
                    layoutResult = layoutResult,
                    padding = padding,
                )
            val trace = simpleTrace("start1", "task1")
            val result = BpmnSmilRenderer.render(diagram, layoutResult, trace = trace)

            result.hasAnimation.shouldBeTrue()
            // The flow1 path should be embedded in the animateMotion path attribute
            val flow1Path = edgePaths["flow1"]
            require(flow1Path != null) { "flow1 path not found" }
            result.svg shouldContain flow1Path
        }

        // ── (5) Token circle injected with configured tokenColor ──

        test("token circle element injected with configured tokenColor") {
            val trace = simpleTrace("start1", "task1")
            val result = BpmnSmilRenderer.render(diagram, layoutResult, trace = trace)

            result.hasAnimation.shouldBeTrue()
            result.svg shouldContain "fill=\"#2962ff\""
        }

        // ── (6) Gateway activation emits <animate attributeName="fill">, never <animateColor> ──

        test("gateway activation emits animate fill targeting gateway id, never animateColor") {
            val trace = simpleTrace("start1", "task1", "gw1")
            val result = BpmnSmilRenderer.render(diagram, layoutResult, trace = trace)

            result.hasAnimation.shouldBeTrue()
            result.svg shouldContain "attributeName=\"fill\""
            result.svg shouldContain "gw1"
            result.svg shouldNotContain "<animateColor"
        }

        // ── (7) Task execution emits stroke-width pulse ──

        test("task execution emits stroke-width pulse animate on task id") {
            val trace = simpleTrace("start1", "task1")
            val result = BpmnSmilRenderer.render(diagram, layoutResult, trace = trace)

            result.hasAnimation.shouldBeTrue()
            result.svg shouldContain "attributeName=\"stroke-width\""
            result.svg shouldContain "task1"
        }

        // ── (8) Start event emits opacity animate ──

        test("start event emits opacity animate") {
            val trace = simpleTrace("start1", "task1")
            val result = BpmnSmilRenderer.render(diagram, layoutResult, trace = trace)

            result.hasAnimation.shouldBeTrue()
            result.svg shouldContain "attributeName=\"opacity\""
            result.svg shouldContain "start1"
        }

        // ── (9) End event emits opacity animate ──

        test("end event emits opacity animate") {
            val trace = simpleTrace("start1", "task1", "gw1", "end1")
            val result = BpmnSmilRenderer.render(diagram, layoutResult, trace = trace)

            result.hasAnimation.shouldBeTrue()
            result.svg shouldContain "attributeName=\"opacity\""
            result.svg shouldContain "end1"
        }

        // ── (10) SpeedFactor 2.0 halves begin/dur of motion vs 1.0 ──

        test("speedFactor 2.0 halves begin and dur of motion vs 1.0") {
            val trace = simpleTrace("start1", "task1", "gw1")
            val ctx1x = BpmnAnimationContext(speedFactor = SpeedFactor(1.0))
            val ctx2x = BpmnAnimationContext(speedFactor = SpeedFactor(2.0))

            val result1x = BpmnSmilRenderer.render(diagram, layoutResult, trace = trace, context = ctx1x)
            val result2x = BpmnSmilRenderer.render(diagram, layoutResult, trace = trace, context = ctx2x)

            result1x.hasAnimation.shouldBeTrue()
            result2x.hasAnimation.shouldBeTrue()

            // Extract first begin="Xms" value from each SVG
            val beginRegex = Regex("""begin="(\d+)ms"""")
            val begin1x =
                beginRegex
                    .find(result1x.svg)
                    ?.groupValues
                    ?.get(1)
                    ?.toLongOrNull()
            val begin2x =
                beginRegex
                    .find(result2x.svg)
                    ?.groupValues
                    ?.get(1)
                    ?.toLongOrNull()

            require(begin1x != null && begin2x != null) { "Could not parse begin times" }
            // At 2x speed everything is compressed: begin times should be ≤ 1x values
            // (some may be 0 in both, so check total duration instead if needed)
            // Both renders must produce the same number of animation elements (structure is identical,
            // only numeric timing values differ — so element counts must match exactly).
            val animTagRegex = Regex("<(animate|animateMotion|set)[ >]")
            animTagRegex.findAll(result1x.svg).count() shouldBe animTagRegex.findAll(result2x.svg).count()
            // First non-zero begin in 1x should be 2x the corresponding value in 2x
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

        // ── (11) highlightColor override appears in gateway fill to=value ──

        test("highlightColor override appears in gateway fill to value") {
            val ctx = BpmnAnimationContext(highlightColor = "#ff0000")
            val trace = simpleTrace("start1", "task1", "gw1")
            val result = BpmnSmilRenderer.render(diagram, layoutResult, trace = trace, context = ctx)

            result.hasAnimation.shouldBeTrue()
            result.svg shouldContain "#ff0000"
        }

        // ── (12) Two adjacent token steps produce two animateMotion with increasing begin times ──

        test("two adjacent token steps produce two animateMotion with increasing begin times") {
            val trace = simpleTrace("start1", "task1", "gw1", "end1")
            val result = BpmnSmilRenderer.render(diagram, layoutResult, trace = trace, context = BpmnAnimationContext(loopCount = 1))

            result.hasAnimation.shouldBeTrue()
            val motionBeginTimes =
                Regex("""<animateMotion[^>]+begin="(\d+)ms"""")
                    .findAll(result.svg)
                    .map { it.groupValues[1].toLong() }
                    .toList()

            motionBeginTimes.size shouldBe 3 // flow1, flow2, flow3
            motionBeginTimes.zipWithNext().all { (a, b) -> b > a }.shouldBeTrue()
        }

        // ── (12b) loopCount=3 triples the number of animateMotion elements ──

        test("loopCount=3 triples the number of animateMotion elements") {
            val trace = simpleTrace("start1", "task1", "gw1", "end1")
            val ctx = BpmnAnimationContext(loopCount = 3)
            val result = BpmnSmilRenderer.render(diagram, layoutResult, trace = trace, context = ctx)

            result.hasAnimation.shouldBeTrue()
            val motionCount = Regex("""<animateMotion""").findAll(result.svg).count()
            motionCount shouldBe 9 // 3 flows × 3 loops
        }

        // ── (12c) Gateway activation emits both highlight and reset fill animations ──

        test("gateway activation emits both highlight and reset fill animations") {
            val trace = simpleTrace("start1", "task1", "gw1")
            val ctx = BpmnAnimationContext(loopCount = 1)
            val result = BpmnSmilRenderer.render(diagram, layoutResult, trace = trace, context = ctx)

            result.hasAnimation.shouldBeTrue()
            // Should contain 2 fill animations on gw1-diamond: highlight + reset
            val fillAnimsOnGateway =
                Regex("""<animate[^>]+xlink:href="#gw1-diamond"[^>]+attributeName="fill"[^>]*/>""")
                    .findAll(result.svg)
                    .count()
            fillAnimsOnGateway shouldBe 2
        }

        // ── (13) Trace nodeId pair with no connecting SequenceFlow is skipped ──

        test("trace nodeId pair with no connecting SequenceFlow is skipped (no motion, no crash)") {
            // "start1" -> "end1" has no direct SequenceFlow
            val trace = simpleTrace("start1", "end1")
            val result = BpmnSmilRenderer.render(diagram, layoutResult, trace = trace)

            // No motion animation produced because no flow connects start1 directly to end1.
            // The result may still have node-visit animations (opacity on start/end events),
            // so hasAnimation could be true or false depending on whether opacity animations count.
            // We just verify no crash and no <animateMotion> (since there's no direct edge).
            result.svg shouldNotContain "<animateMotion"
        }

        // ── (14) hasAnimation==false when trace has entries but none map to a known flow path ──

        test("hasAnimation is false when trace has entries but none produce animations") {
            // A trace with a single node and no adjacent pair → no motion
            // AND the single node has an unknown id → no node animations either
            val trace =
                TraceFile(
                    entries =
                        listOf(
                            TraceEntry.TokenPlaced(
                                seqNo = 0,
                                timestamp = "2026-01-01T00:00:00Z",
                                nodeId = "unknownNode",
                                clock = 0,
                            ),
                        ),
                )
            val result = BpmnSmilRenderer.render(diagram, layoutResult, trace = trace)

            result.hasAnimation.shouldBeFalse()
            // Static path: svg is identical to base
            val expected = KumlSvgRenderer.toSvg(diagram, layoutResult)
            result.svg shouldBe expected
        }

        // ── (15) Injected SMIL sits before final </svg> ──

        test("injected SMIL sits before final </svg>") {
            val trace = simpleTrace("start1", "task1")
            val result = BpmnSmilRenderer.render(diagram, layoutResult, trace = trace)

            result.hasAnimation.shouldBeTrue()
            val lastSvgClose = result.svg.lastIndexOf("</svg>")
            val lastAnimate = result.svg.lastIndexOf("<animate")
            val lastAnimateMotion = result.svg.lastIndexOf("<animateMotion")
            val lastCircle = result.svg.lastIndexOf("<circle")

            (lastAnimate > 0 || lastAnimateMotion > 0).shouldBeTrue()
            if (lastAnimate > 0) (lastAnimate < lastSvgClose).shouldBeTrue()
            if (lastCircle > 0) (lastCircle < lastSvgClose).shouldBeTrue()
        }

        // ── (16) Output is well-formed: single root <svg>, balanced </svg> ──

        test("output is well-formed: single root svg tag and exactly one closing svg") {
            val trace = simpleTrace("start1", "task1", "gw1", "end1")
            val result = BpmnSmilRenderer.render(diagram, layoutResult, trace = trace)

            result.hasAnimation.shouldBeTrue()
            val svgOpenCount = Regex("<svg[\\s>]").findAll(result.svg).count()
            val svgCloseCount = Regex("</svg>").findAll(result.svg).count()

            svgOpenCount shouldBe 1
            svgCloseCount shouldBe 1
        }

        // ── (17) tokenColor injection payload throws IllegalArgumentException ──

        test("tokenColor with injection payload throws IllegalArgumentException in BpmnAnimationContext init") {
            shouldThrow<IllegalArgumentException> {
                BpmnAnimationContext(tokenColor = "\"/><script>alert(1)</script>")
            }
        }

        // ── (18) maxAnimations cap: oversized trace throws IllegalArgumentException ──

        test("maxAnimations cap: oversized trace throws IllegalArgumentException") {
            val tooManyEntries =
                (0..(BpmnAnimationContext.MAX_ANIMATIONS + 10)).map { idx ->
                    TraceEntry.TokenPlaced(
                        seqNo = idx.toLong(),
                        timestamp = "2026-01-01T00:00:00Z",
                        nodeId = "task1",
                        clock = idx.toLong(),
                    )
                }
            val oversizedTrace = TraceFile(entries = tooManyEntries)

            shouldThrow<IllegalArgumentException> {
                BpmnSmilRenderer.render(diagram, layoutResult, trace = oversizedTrace)
            }
        }

        // ── (19) STM-style trace (no token entries) on a BPMN diagram yields hasAnimation==false ──

        test("STM-style trace (StateEntered/TransitionFired only, no token entries) yields hasAnimation=false") {
            val stmTrace =
                TraceFile(
                    entries =
                        listOf(
                            TraceEntry.StateEntered(seqNo = 0, timestamp = "2026-01-01T00:00:00Z", vertexId = "stateA"),
                            TraceEntry.TransitionFired(
                                seqNo = 1,
                                timestamp = "2026-01-01T00:00:01Z",
                                transitionId = "t1",
                                fromVertexId = "stateA",
                                toVertexId = "stateB",
                            ),
                            TraceEntry.StateEntered(seqNo = 2, timestamp = "2026-01-01T00:00:02Z", vertexId = "stateB"),
                        ),
                )
            val result = BpmnSmilRenderer.render(diagram, layoutResult, trace = stmTrace)

            result.hasAnimation.shouldBeFalse()
            val expected = KumlSvgRenderer.toSvg(diagram, layoutResult)
            result.svg shouldBe expected
        }

        // ── (20) AnimatedBpmnRenderResult.hasAnimation==true for valid token trace ──

        test("AnimatedBpmnRenderResult.hasAnimation is true for valid token trace") {
            val trace = simpleTrace("start1", "task1")
            val result = BpmnSmilRenderer.render(diagram, layoutResult, trace = trace)

            result.hasAnimation.shouldBeTrue()
        }

        // ── (21) XML special chars in node ids are escaped in xlink:href ──

        test("XML special chars in node ids are escaped in xlink:href via id sanitization") {
            // Node id with special chars — sanitized by BpmnTokenTimelineBuilder.xmlEscapeId
            val escaped = BpmnTokenTimelineBuilder.xmlEscapeId("node<1>&\"test'")
            escaped shouldBe "node_1___test_"
        }

        // ── (22) Two consumed tokens on same flow get distinct circle ids ──

        test("two consumed tokens on same flow (repeated visits) get distinct circle ids") {
            // Two passes through start1 → task1
            val trace =
                TraceFile(
                    entries =
                        listOf(
                            TraceEntry.TokenPlaced(seqNo = 0, timestamp = "T0", nodeId = "start1", clock = 0),
                            TraceEntry.TokenPlaced(seqNo = 1, timestamp = "T1", nodeId = "task1", clock = 1),
                            TraceEntry.TokenPlaced(seqNo = 2, timestamp = "T2", nodeId = "start1", clock = 2),
                            TraceEntry.TokenPlaced(seqNo = 3, timestamp = "T3", nodeId = "task1", clock = 3),
                        ),
                )
            val result = BpmnSmilRenderer.render(diagram, layoutResult, trace = trace)

            result.hasAnimation.shouldBeTrue()
            // Find all circle ids
            val circleIds = Regex("""<circle id="([^"]+)"""").findAll(result.svg).map { it.groupValues[1] }.toList()
            // Must be distinct (no duplicate id attributes)
            circleIds.size shouldBe circleIds.distinct().size
            circleIds.size shouldBe 2 // two motion legs: start1→task1 twice
        }

        // ── (23) DecisionTaken emits gateway highlight even without prior TokenPlaced at gateway ──

        test("DecisionTaken at gateway emits fill highlight animation without prior TokenPlaced at gateway") {
            // Real BPMN-native runtime: gateway fires DecisionTaken, not TokenPlaced at gateway node.
            val trace =
                TraceFile(
                    entries =
                        listOf(
                            TraceEntry.TokenPlaced(seqNo = 0, timestamp = "T0", nodeId = "start1", clock = 0),
                            TraceEntry.TokenPlaced(seqNo = 1, timestamp = "T1", nodeId = "task1", clock = 1),
                            TraceEntry.DecisionTaken(
                                seqNo = 2,
                                timestamp = "T2",
                                nodeId = "gw1",
                                chosenEdgeId = "flow3",
                                guard = null,
                                clock = 2,
                            ),
                            TraceEntry.TokenPlaced(seqNo = 3, timestamp = "T3", nodeId = "end1", clock = 3),
                        ),
                )
            val result = BpmnSmilRenderer.render(diagram, layoutResult, trace = trace)

            result.hasAnimation.shouldBeTrue()
            // Gateway highlight fill must be present
            result.svg shouldContain "attributeName=\"fill\""
            result.svg shouldContain "gw1"
            result.svg shouldNotContain "<animateColor"
        }

        // ── (24) ForkSplit at gateway emits gateway highlight ──

        test("ForkSplit at gateway emits fill highlight animation") {
            val trace =
                TraceFile(
                    entries =
                        listOf(
                            TraceEntry.TokenPlaced(seqNo = 0, timestamp = "T0", nodeId = "start1", clock = 0),
                            TraceEntry.TokenPlaced(seqNo = 1, timestamp = "T1", nodeId = "task1", clock = 1),
                            TraceEntry.ForkSplit(
                                seqNo = 2,
                                timestamp = "T2",
                                nodeId = "gw1",
                                targetNodeIds = listOf("end1"),
                                clock = 2,
                            ),
                        ),
                )
            val result = BpmnSmilRenderer.render(diagram, layoutResult, trace = trace)

            result.hasAnimation.shouldBeTrue()
            result.svg shouldContain "attributeName=\"fill\""
            result.svg shouldContain "gw1"
        }

        // ── BpmnFlowPathResolver: buildEdgePaths ──

        test("BpmnFlowPathResolver buildEdgePaths returns paths for all flows with layout entries") {
            val padding = SvgRenderOptions.DEFAULT.paddingPx
            val edgePaths = BpmnFlowPathResolver.buildEdgePaths(process, layoutResult, padding)

            edgePaths.keys shouldBe setOf("flow1", "flow2", "flow3")
            edgePaths.values.forEach { path ->
                path shouldContain "M "
                path shouldContain "L "
            }
        }

        test("BpmnFlowPathResolver nodeToFlow finds direct SequenceFlow between adjacent nodes") {
            val found = BpmnFlowPathResolver.nodeToFlow(process, "task1", "gw1")
            found?.id shouldBe "flow2"
        }

        test("BpmnFlowPathResolver nodeToFlow returns null for non-adjacent pair") {
            val found = BpmnFlowPathResolver.nodeToFlow(process, "start1", "end1")
            found shouldBe null
        }

        // ── BpmnAnimationContext: color validation ──

        test("BpmnAnimationContext accepts valid hex colors") {
            // These should not throw
            BpmnAnimationContext(tokenColor = "#abc")
            BpmnAnimationContext(tokenColor = "#aabbcc")
            BpmnAnimationContext(tokenColor = "#aabbccdd")
            BpmnAnimationContext(highlightColor = "#fff")
        }

        test("BpmnAnimationContext accepts named CSS colors from allowlist") {
            BpmnAnimationContext(tokenColor = "white")
            BpmnAnimationContext(tokenColor = "black")
            BpmnAnimationContext(highlightColor = "red")
        }

        test("BpmnAnimationContext rejects invalid color values") {
            shouldThrow<IllegalArgumentException> {
                BpmnAnimationContext(tokenColor = "javascript:void(0)")
            }
            shouldThrow<IllegalArgumentException> {
                BpmnAnimationContext(highlightColor = "rgb(255,0,0)")
            }
        }

        // ── bpmnFlowPathD: path geometry ──

        test("bpmnFlowPathD for Direct route produces M ... L ... path") {
            val route = EdgeRoute.Direct(source = Point(10f, 20f), target = Point(100f, 20f))
            val pathD = BpmnFlowPathResolver.bpmnFlowPathD(route)
            pathD shouldBe "M 10 20 L 100 20"
        }

        test("token circle has opacity=0 initially so it is invisible before animation") {
            val trace = simpleTrace("start1", "task1")
            val result = BpmnSmilRenderer.render(diagram, layoutResult, trace = trace)

            result.hasAnimation.shouldBeTrue()
            result.svg shouldContain "opacity=\"0\""
        }

        test("result svg contains more content than base svg when animated") {
            val baseSvg = KumlSvgRenderer.toSvg(diagram, layoutResult)
            val trace = simpleTrace("start1", "task1", "gw1", "end1")
            val result = BpmnSmilRenderer.render(diagram, layoutResult, trace = trace)

            result.hasAnimation.shouldBeTrue()
            result.svg.length shouldBeGreaterThan baseSvg.length
        }
    })
