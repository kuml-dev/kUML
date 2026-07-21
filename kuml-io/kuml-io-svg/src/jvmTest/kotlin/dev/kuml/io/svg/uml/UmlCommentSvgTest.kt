package dev.kuml.io.svg.uml

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.UmlCommentLayout
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.uml.UmlActivityNode
import dev.kuml.uml.UmlActivityNodeKind
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlComment
import dev.kuml.uml.UmlCommentLink
import dev.kuml.uml.UmlTimingLifeline
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Renderer tests for [UmlComment] / [UmlCommentLink] (V0.23.1).
 *
 * The security test below is not a nice-to-have: [UmlComment.body] is
 * free text supplied by the diagram author and is embedded directly into
 * the SVG output, which is frequently embedded in web pages (kuml.dev
 * playground, Obsidian previews, handbook pages). If it were ever emitted
 * via `rawXml(...)` instead of `text(...)`, an attacker-controlled or
 * simply careless comment body containing `<script>` could execute in the
 * viewer's browser. [dev.kuml.io.svg.SvgBuilder.text] XML-escapes its
 * argument unconditionally — this test pins that behaviour for the comment
 * renderer specifically, independent of the general `SvgBuilder` unit
 * tests.
 */
class UmlCommentSvgTest :
    FunSpec({

        test("renders a free-standing UmlComment as a note box with folded corner") {
            val comment = UmlComment(id = "c1", body = "Encapsulates the order lifecycle.")
            val diagram =
                KumlDiagram(
                    name = "Test",
                    type = DiagramType.CLASS,
                    elements = listOf(comment),
                )
            val size = UmlCommentLayout.sizeOf(comment)
            val layoutResult =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = size,
                    nodes = mapOf(NodeId("c1") to NodeLayout(bounds = Rect(Point(10f, 10f), size))),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )

            val svg = KumlSvgRenderer.toSvg(diagram, layoutResult, PlainTheme())

            svg shouldContain "class=\"kuml-comment\""
            svg shouldContain "class=\"kuml-comment-fold\""
            svg shouldContain "Encapsulates the order lifecycle."
        }

        test("renders a UmlCommentLink as an unadorned dashed line") {
            val cls = UmlClass(id = "cls1", name = "Order")
            val comment = UmlComment(id = "c1", body = "Note")
            val link = UmlCommentLink(id = "link1", commentId = "c1", annotatedElementId = "cls1")
            val diagram =
                KumlDiagram(
                    name = "Test",
                    type = DiagramType.CLASS,
                    elements = listOf(cls, comment, link),
                )
            val commentSize = UmlCommentLayout.sizeOf(comment)
            val layoutResult =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(400f, 200f),
                    nodes =
                        mapOf(
                            NodeId("cls1") to NodeLayout(bounds = Rect(Point(20f, 40f), Size(120f, 80f))),
                            NodeId("c1") to NodeLayout(bounds = Rect(Point(200f, 40f), commentSize)),
                        ),
                    edges =
                        mapOf(
                            EdgeId("link1") to
                                EdgeRoute.Direct(
                                    source = Point(140f, 80f),
                                    target = Point(200f, 60f),
                                ),
                        ),
                    groups = emptyMap(),
                )

            val svg = KumlSvgRenderer.toSvg(diagram, layoutResult, PlainTheme())

            svg shouldContain "class=\"kuml-edge-dashed\""
            // No arrowhead / label markup for a comment link (unlike dependency/include/extend).
            svg shouldNotContain "kuml-edge-arrow"
        }

        test("XSS: comment body with embedded markup is XML-escaped, never emitted raw") {
            val malicious = UmlComment(id = "c1", body = "<script>alert(1)</script>")
            val diagram =
                KumlDiagram(
                    name = "Test",
                    type = DiagramType.CLASS,
                    elements = listOf(malicious),
                )
            val size = UmlCommentLayout.sizeOf(malicious)
            val layoutResult =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = size,
                    nodes = mapOf(NodeId("c1") to NodeLayout(bounds = Rect(Point(0f, 0f), size))),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )

            val svg = KumlSvgRenderer.toSvg(diagram, layoutResult, PlainTheme())

            svg shouldNotContain "<script>"
            svg shouldContain "&lt;script&gt;"
        }

        test("XSS: comment body with attribute-breakout payload is XML-escaped") {
            val malicious = UmlComment(id = "c1", body = "\"><img src=x onerror=alert(1)>")
            val diagram =
                KumlDiagram(
                    name = "Test",
                    type = DiagramType.CLASS,
                    elements = listOf(malicious),
                )
            val size = UmlCommentLayout.sizeOf(malicious)
            val layoutResult =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = size,
                    nodes = mapOf(NodeId("c1") to NodeLayout(bounds = Rect(Point(0f, 0f), size))),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )

            val svg = KumlSvgRenderer.toSvg(diagram, layoutResult, PlainTheme())

            svg shouldNotContain "<img src=x onerror=alert(1)>"
            svg shouldContain "&quot;&gt;&lt;img"
        }

        test("renders a UmlCommentLink on an activity diagram as a dashed line (generic comment support)") {
            val action = UmlActivityNode(id = "a1", name = "Ship Order", kind = UmlActivityNodeKind.ACTION)
            val comment = UmlComment(id = "c1", body = "Triggers the carrier integration.")
            val link = UmlCommentLink(id = "link1", commentId = "c1", annotatedElementId = "a1")
            val diagram =
                KumlDiagram(
                    name = "Order Fulfillment",
                    type = DiagramType.ACTIVITY,
                    elements = listOf(action, comment, link),
                )
            val commentSize = UmlCommentLayout.sizeOf(comment)
            val layoutResult =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(400f, 200f),
                    nodes =
                        mapOf(
                            NodeId("a1") to NodeLayout(bounds = Rect(Point(20f, 40f), Size(120f, 80f))),
                            NodeId("c1") to NodeLayout(bounds = Rect(Point(200f, 40f), commentSize)),
                        ),
                    edges =
                        mapOf(
                            EdgeId("link1") to
                                EdgeRoute.Direct(
                                    source = Point(140f, 80f),
                                    target = Point(200f, 60f),
                                ),
                        ),
                    groups = emptyMap(),
                )

            val svg = KumlSvgRenderer.toSvg(diagram, layoutResult, PlainTheme())

            svg shouldContain "class=\"kuml-comment\""
            svg shouldContain "class=\"kuml-edge-dashed\""
        }

        test("renders a UmlCommentLink on a timing diagram as a dashed line (generic comment support)") {
            val lifeline = UmlTimingLifeline(id = "l1", name = "Signal", states = listOf("low", "high"))
            val comment = UmlComment(id = "c1", body = "Rises on trigger.")
            val link = UmlCommentLink(id = "link1", commentId = "c1", annotatedElementId = "l1")
            val diagram =
                KumlDiagram(
                    name = "Signal Timing",
                    type = DiagramType.TIMING,
                    elements = listOf(lifeline, comment, link),
                )
            val commentSize = UmlCommentLayout.sizeOf(comment)
            val layoutResult =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(400f, 200f),
                    nodes =
                        mapOf(
                            NodeId("l1") to NodeLayout(bounds = Rect(Point(20f, 40f), Size(120f, 80f))),
                            NodeId("c1") to NodeLayout(bounds = Rect(Point(200f, 40f), commentSize)),
                        ),
                    edges =
                        mapOf(
                            EdgeId("link1") to
                                EdgeRoute.Direct(
                                    source = Point(140f, 80f),
                                    target = Point(200f, 60f),
                                ),
                        ),
                    groups = emptyMap(),
                )

            val svg = KumlSvgRenderer.toSvg(diagram, layoutResult, PlainTheme())

            svg shouldContain "class=\"kuml-comment\""
            svg shouldContain "class=\"kuml-edge-dashed\""
        }
    })
