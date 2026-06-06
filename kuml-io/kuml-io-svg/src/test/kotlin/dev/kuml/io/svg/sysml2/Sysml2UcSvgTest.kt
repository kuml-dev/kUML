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
import dev.kuml.sysml2.UseCaseDefinition
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Strukturelle + Determinismus-Tests für den SysML-2-UC-SVG-Renderer (V2.0.7).
 *
 * Jeder Test schreibt das produzierte SVG zusätzlich nach
 * `kuml-io-svg/build/sample-output/sysml2-uc/<name>.svg`, sodass es
 * im Browser visuell überprüft werden kann.
 */
class Sysml2UcSvgTest :
    StringSpec({

        // Tiny library system: one actor + two use cases.
        fun libraryModel(): Pair<Sysml2Model, UcDiagram> {
            val model =
                sysml2Model("Library") {
                    val reader = actorDef("Reader")
                    val borrow = useCaseDef("BorrowBook")
                    val auth = useCaseDef("Authenticate")
                    ucDiagram("UC") {
                        include(reader)
                        include(borrow)
                        include(auth)
                        association(reader, borrow)
                        include(borrow, auth)
                    }
                }
            val uc = model.diagrams.filterIsInstance<UcDiagram>().single()
            return model to uc
        }

        fun layoutFor(
            model: Sysml2Model,
            uc: UcDiagram,
        ): LayoutResult =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = 1L,
                canvas = Size(600f, 220f),
                nodes =
                    mapOf(
                        NodeId("Reader") to
                            NodeLayout(bounds = Rect(Point(20f, 60f), Size(60f, 100f))),
                        NodeId("BorrowBook") to
                            NodeLayout(bounds = Rect(Point(160f, 30f), Size(160f, 70f))),
                        NodeId("Authenticate") to
                            NodeLayout(bounds = Rect(Point(400f, 30f), Size(160f, 70f))),
                    ),
                edges =
                    mapOf(
                        EdgeId("assoc:Reader::BorrowBook") to
                            EdgeRoute.OrthogonalRounded(
                                source = Point(80f, 110f),
                                target = Point(160f, 65f),
                                waypoints = emptyList(),
                                cornerRadiusPx = 4f,
                            ),
                        EdgeId("include:BorrowBook::Authenticate") to
                            EdgeRoute.OrthogonalRounded(
                                source = Point(320f, 65f),
                                target = Point(400f, 65f),
                                waypoints = emptyList(),
                                cornerRadiusPx = 4f,
                            ),
                    ),
                groups = emptyMap(),
            ).also { _ ->
                require(model.name.isNotEmpty())
                require(uc.elementIds.isNotEmpty())
            }

        "UC renders actor as stick figure and use case as ellipse" {
            val (model, uc) = libraryModel()
            val svg = KumlSvgRenderer.toSvg(model, uc, layoutFor(model, uc), PlainTheme())

            // Stick-figure path = actor.
            svg shouldContain "kuml-actor"
            svg shouldContain "circle"
            // Ellipse = use case.
            svg shouldContain "<ellipse"
            svg shouldContain "kuml-usecase"
            // Names appear in the rendered SVG.
            svg shouldContain "Reader"
            svg shouldContain "BorrowBook"
            svg shouldContain "Authenticate"

            SampleOutput.write("sysml2-uc/library-uc.svg", svg)
        }

        "UC with association renders an edge in the SVG output" {
            val (model, uc) = libraryModel()
            val svg = KumlSvgRenderer.toSvg(model, uc, layoutFor(model, uc), PlainTheme())

            // Edge routes lower into SVG <path> elements.
            svg shouldContain "path"
            // Both nodes still present.
            svg shouldContain "Reader"
            svg shouldContain "BorrowBook"
        }

        "UC with include relationship surfaces both endpoint nodes" {
            val (model, uc) = libraryModel()
            val svg = KumlSvgRenderer.toSvg(model, uc, layoutFor(model, uc), PlainTheme())

            // V2.0.7 MVP: include edges go through the EdgeRendererDispatcher
            // but the synthetic KumlDiagram has no UmlRelationship element for
            // them, so the dispatcher's element lookup misses and the edge is
            // skipped. The «include»-stereotype label + dashed-line styling
            // land in V2.x polish (see renderer KDoc). We assert here that
            // *both* endpoint use cases at least render — that's the V2.0.7
            // structural contract for an include relationship.
            svg shouldContain "BorrowBook"
            svg shouldContain "Authenticate"
        }

        "deterministic output — same input renders byte-identically" {
            val model =
                Sysml2Model(
                    name = "Det",
                    definitions =
                        listOf(
                            ActorDefinition(id = "Reader", name = "Reader"),
                            UseCaseDefinition(id = "BorrowBook", name = "BorrowBook"),
                        ),
                )
            val uc =
                UcDiagram(
                    name = "Det",
                    elementIds = listOf("Reader", "BorrowBook"),
                    associations =
                        listOf(
                            UcAssociation(
                                id = "assoc:Reader::BorrowBook",
                                actorId = "Reader",
                                useCaseId = "BorrowBook",
                            ),
                        ),
                    includes = emptyList(),
                    extends = emptyList(),
                )

            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(400f, 200f),
                    nodes =
                        mapOf(
                            NodeId("Reader") to NodeLayout(bounds = Rect(Point(0f, 0f), Size(60f, 100f))),
                            NodeId("BorrowBook") to NodeLayout(bounds = Rect(Point(120f, 0f), Size(160f, 70f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )
            val one = KumlSvgRenderer.toSvg(model, uc, layout, PlainTheme())
            val two = KumlSvgRenderer.toSvg(model, uc, layout, PlainTheme())
            one shouldBe two

            SampleOutput.write("sysml2-uc/deterministic.svg", one)
        }
    })
