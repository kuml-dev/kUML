package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SampleOutput
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.sysml2.ActorDefinition
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.UcAssociation
import dev.kuml.sysml2.UcDiagram
import dev.kuml.sysml2.UcExtend
import dev.kuml.sysml2.UcInclude
import dev.kuml.sysml2.UseCaseDefinition
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * V2.0.13 — verifies that the SVG renderer emits `«include»` and `«extend»`
 * stereotype labels and dashed line styling for UC edges. The V2.0.7 test
 * intentionally allowed both edges to render as plain solid lines; with the
 * `Sysml2EdgeAdapter` in place every UC stereotype shows up in the SVG.
 */
class Sysml2UcEdgeLabelSvgTest :
    StringSpec({

        val model =
            Sysml2Model(
                name = "Library",
                definitions =
                    listOf(
                        ActorDefinition(id = "Reader", name = "Reader"),
                        UseCaseDefinition(id = "BorrowBook", name = "BorrowBook"),
                        UseCaseDefinition(id = "Authenticate", name = "Authenticate"),
                        UseCaseDefinition(id = "PayLateFee", name = "PayLateFee"),
                    ),
            )
        val diagram =
            UcDiagram(
                name = "UC",
                elementIds = listOf("Reader", "BorrowBook", "Authenticate", "PayLateFee"),
                associations =
                    listOf(
                        UcAssociation(id = "assoc:Reader::BorrowBook", actorId = "Reader", useCaseId = "BorrowBook"),
                    ),
                includes =
                    listOf(
                        UcInclude(
                            id = "include:BorrowBook::Authenticate",
                            sourceUseCaseId = "BorrowBook",
                            targetUseCaseId = "Authenticate",
                        ),
                    ),
                extends =
                    listOf(
                        UcExtend(
                            id = "extend:PayLateFee::BorrowBook",
                            sourceUseCaseId = "PayLateFee",
                            targetUseCaseId = "BorrowBook",
                        ),
                    ),
            )
        val layout =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = 1L,
                canvas = Size(800f, 220f),
                nodes =
                    mapOf(
                        NodeId("Reader") to NodeLayout(bounds = Rect(Point(20f, 60f), Size(60f, 100f))),
                        NodeId("BorrowBook") to NodeLayout(bounds = Rect(Point(160f, 30f), Size(160f, 70f))),
                        NodeId("Authenticate") to NodeLayout(bounds = Rect(Point(420f, 30f), Size(160f, 70f))),
                        NodeId("PayLateFee") to NodeLayout(bounds = Rect(Point(160f, 130f), Size(160f, 70f))),
                    ),
                edges =
                    mapOf(
                        EdgeId("assoc:Reader::BorrowBook") to
                            EdgeRoute.Direct(source = Point(80f, 110f), target = Point(160f, 65f)),
                        EdgeId("include:BorrowBook::Authenticate") to
                            EdgeRoute.Direct(source = Point(320f, 65f), target = Point(420f, 65f)),
                        EdgeId("extend:PayLateFee::BorrowBook") to
                            EdgeRoute.Direct(source = Point(240f, 130f), target = Point(240f, 100f)),
                    ),
                groups = emptyMap(),
            )

        "«include» stereotype appears in the SVG output" {
            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())
            svg shouldContain "«include»"
            SampleOutput.write("sysml2-edge-labels/uc-include-extend.svg", svg)
        }

        "«extend» stereotype appears in the SVG output" {
            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())
            svg shouldContain "«extend»"
        }

        "include + extend edges are styled dashed (stroke-dasharray)" {
            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())
            svg shouldContain "stroke-dasharray"
        }

        "plain association edge does not produce stereotype label" {
            // We can't easily assert "this one edge has no label" globally,
            // but we can confirm that no «association» literal appears
            // (the metadata for associations has stereotype=null).
            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())
            svg shouldNotContain "«association»"
        }
    })
