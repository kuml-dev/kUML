package dev.kuml.io.png.sysml2

import dev.kuml.io.png.KumlPngRenderer
import dev.kuml.io.png.PngRenderOptions
import dev.kuml.io.png.SampleOutput
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.sysml2.ActDiagram
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.ConstraintParameter
import dev.kuml.sysml2.ConstraintParameterDirection
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.ParDiagram
import dev.kuml.sysml2.ReqDiagram
import dev.kuml.sysml2.SeqDiagram
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.Sysml2Diagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.UcDiagram
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * Smoke-Tests für den V2.0.14-PNG-Export aller acht SysML-2-Diagrammtypen.
 *
 * Jeder Test baut ein minimales Modell (DSL), eine handgefertigte
 * [LayoutResult] und rastert via [KumlPngRenderer.toPng] zu PNG. Die
 * Assertions decken bewusst nur das Render-Vertrag-Minimum ab:
 *  - PNG-Magic-Bytes am Anfang (`89 50 4E 47 0D 0A 1A 0A`)
 *  - Bytes > 100 (Batik hat wirklich etwas rasterisiert)
 *  - Decoded Image-Breite ≥ 100 px (Default-Width 1024 wird ≈ respektiert)
 *
 * Visuelle Korrektheit der Stereotyp-Labels etc. ist Sache der V2.0.13-SVG-
 * Tests — wenn der SVG-Renderer das emittiert, schreibt Batik es ins PNG.
 */
private val PNG_MAGIC =
    listOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).map { it.toByte() }

private fun readImage(bytes: ByteArray): BufferedImage =
    ImageIO.read(ByteArrayInputStream(bytes))
        ?: error("ImageIO konnte die Bytes nicht lesen — kein gültiges PNG")

/** Asserts the basic PNG-render contract (magic + size + decoded width). */
private fun assertValidPng(bytes: ByteArray) {
    bytes.take(8) shouldBe PNG_MAGIC
    (bytes.size > 100) shouldBe true
    val img = readImage(bytes)
    (img.width >= 100) shouldBe true
}

private fun renderPng(
    model: Sysml2Model,
    diagram: Sysml2Diagram,
    layout: LayoutResult,
): ByteArray =
    KumlPngRenderer.toPng(
        model = model,
        diagram = diagram,
        layoutResult = layout,
        theme = PlainTheme(),
        options = PngRenderOptions.DEFAULT,
    )

private fun singleNodeLayout(
    id: String,
    width: Float = 220f,
    height: Float = 140f,
): LayoutResult =
    LayoutResult(
        engineId = LayoutEngineId("test"),
        seed = 1L,
        canvas = Size(width + 40f, height + 40f),
        nodes =
            mapOf(
                NodeId(id) to NodeLayout(bounds = Rect(Point(20f, 20f), Size(width, height))),
            ),
        edges = emptyMap(),
        groups = emptyMap(),
    )

class Sysml2PngTest :
    StringSpec({

        // ── BDD ─────────────────────────────────────────────────────────────
        "BDD renders to valid PNG" {
            val model =
                sysml2Model("M") {
                    val vehicle = partDef("Vehicle")
                    bdd("Overview") { include(vehicle) }
                }
            val bdd = model.diagrams.filterIsInstance<BdDiagram>().single()
            val bytes = renderPng(model, bdd, singleNodeLayout("Vehicle"))
            SampleOutput.write("sysml2/bdd-vehicle.png", bytes)
            assertValidPng(bytes)
        }

        // ── IBD ─────────────────────────────────────────────────────────────
        "IBD renders to valid PNG" {
            val model =
                sysml2Model("M") {
                    val engineDef = partDef("Engine")
                    val vehicle =
                        partDef("Vehicle") {
                            part("engine", typeId = engineDef.id)
                        }
                    ibd("Vehicle wiring", owner = vehicle)
                }
            val ibd = model.diagrams.filterIsInstance<IbdDiagram>().single()
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(260f, 140f),
                    nodes =
                        mapOf(
                            NodeId("Vehicle::engine") to
                                NodeLayout(bounds = Rect(Point(20f, 20f), Size(200f, 100f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )
            val bytes = renderPng(model, ibd, layout)
            SampleOutput.write("sysml2/ibd-vehicle-engine.png", bytes)
            assertValidPng(bytes)
        }

        // ── UC ──────────────────────────────────────────────────────────────
        "UC renders to valid PNG" {
            val model =
                sysml2Model("Library") {
                    val reader = actorDef("Reader")
                    val borrow = useCaseDef("BorrowBook")
                    ucDiagram("UC") {
                        include(reader)
                        include(borrow)
                        association(reader, borrow)
                    }
                }
            val uc = model.diagrams.filterIsInstance<UcDiagram>().single()
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(400f, 200f),
                    nodes =
                        mapOf(
                            NodeId("Reader") to NodeLayout(bounds = Rect(Point(20f, 60f), Size(60f, 100f))),
                            NodeId("BorrowBook") to NodeLayout(bounds = Rect(Point(160f, 60f), Size(180f, 80f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )
            val bytes = renderPng(model, uc, layout)
            SampleOutput.write("sysml2/uc-library.png", bytes)
            assertValidPng(bytes)
        }

        // ── REQ ─────────────────────────────────────────────────────────────
        "REQ renders to valid PNG" {
            val model =
                sysml2Model("VehicleReqs") {
                    val topSpeed =
                        requirementDef(
                            "TopSpeedRequirement",
                            reqId = "R-001",
                            text = "Vehicle reaches at least 180 km/h",
                        )
                    reqDiagram("REQ") {
                        include(topSpeed)
                    }
                }
            val req = model.diagrams.filterIsInstance<ReqDiagram>().single()
            val bytes = renderPng(model, req, singleNodeLayout("TopSpeedRequirement", 240f, 140f))
            SampleOutput.write("sysml2/req-top-speed.png", bytes)
            assertValidPng(bytes)
        }

        // ── STM ─────────────────────────────────────────────────────────────
        "STM renders to valid PNG" {
            val model =
                sysml2Model("TrafficLight") {
                    val initial = stateDef("Initial", isInitial = true)
                    val red = stateDef("Red", entryAction = "switchLights('red')")
                    transition("init", initial, red)
                    stmDiagram("Phase") {
                        include(initial)
                        include(red)
                    }
                }
            val stm = model.diagrams.filterIsInstance<StmDiagram>().single()
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(320f, 160f),
                    nodes =
                        mapOf(
                            NodeId("Initial") to NodeLayout(bounds = Rect(Point(20f, 60f), Size(24f, 24f))),
                            NodeId("Red") to NodeLayout(bounds = Rect(Point(80f, 40f), Size(180f, 80f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )
            val bytes = renderPng(model, stm, layout)
            SampleOutput.write("sysml2/stm-traffic-light.png", bytes)
            assertValidPng(bytes)
        }

        // ── ACT ─────────────────────────────────────────────────────────────
        "ACT renders to valid PNG" {
            val model =
                sysml2Model("Workflow") {
                    val initial = initialNode()
                    val validate = actionDef("Validate", action = "validate(order)")
                    controlFlow("start", initial, validate)
                    actDiagram("W") {
                        include(initial)
                        include(validate)
                    }
                }
            val act = model.diagrams.filterIsInstance<ActDiagram>().single()
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(320f, 160f),
                    nodes =
                        mapOf(
                            NodeId("Initial") to NodeLayout(bounds = Rect(Point(20f, 60f), Size(28f, 28f))),
                            NodeId("Validate") to NodeLayout(bounds = Rect(Point(80f, 40f), Size(180f, 80f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )
            val bytes = renderPng(model, act, layout)
            SampleOutput.write("sysml2/act-workflow.png", bytes)
            assertValidPng(bytes)
        }

        // ── SEQ ─────────────────────────────────────────────────────────────
        "SEQ renders to valid PNG" {
            val model =
                sysml2Model("LoginFlow") {
                    val user = lifelineDef("user")
                    val browser = lifelineDef("browser")
                    message("login(user, pwd)", user, browser, seqNo = 0)
                    seqDiagram("Login") {
                        include(user)
                        include(browser)
                    }
                }
            val seq = model.diagrams.filterIsInstance<SeqDiagram>().single()
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(500f, 320f),
                    nodes =
                        mapOf(
                            NodeId("user") to NodeLayout(bounds = Rect(Point(40f, 20f), Size(140f, 240f))),
                            NodeId("browser") to NodeLayout(bounds = Rect(Point(240f, 20f), Size(140f, 240f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )
            val bytes = renderPng(model, seq, layout)
            SampleOutput.write("sysml2/seq-login.png", bytes)
            assertValidPng(bytes)
        }

        // ── PAR ─────────────────────────────────────────────────────────────
        "PAR renders to valid PNG" {
            val model =
                sysml2Model("NewtonModel") {
                    val newton =
                        constraintDef(
                            name = "NewtonsLaw",
                            expression = "F = m * a",
                            parameters =
                                listOf(
                                    ConstraintParameter("F", "Force", ConstraintParameterDirection.Out),
                                    ConstraintParameter("m", "Mass", ConstraintParameterDirection.In),
                                ),
                        )
                    parDiagram("Newton") {
                        include(newton)
                    }
                }
            val par = model.diagrams.filterIsInstance<ParDiagram>().single()
            val bytes = renderPng(model, par, singleNodeLayout("NewtonsLaw", 240f, 150f))
            SampleOutput.write("sysml2/par-newtons-law.png", bytes)
            assertValidPng(bytes)
        }

        // ── Determinism ─────────────────────────────────────────────────────
        "PNG output for identical SysML 2 input is deterministic across runs" {
            val model =
                sysml2Model("M") {
                    val vehicle = partDef("Vehicle")
                    bdd("Overview") { include(vehicle) }
                }
            val bdd = model.diagrams.filterIsInstance<BdDiagram>().single()
            val layout = singleNodeLayout("Vehicle")

            val first = renderPng(model, bdd, layout)
            val second = renderPng(model, bdd, layout)

            // SVG-Pfad ist deterministisch; Batik mit denselben Optionen sollte
            // ebenfalls byte-identische PNGs erzeugen. Sollte das in Zukunft
            // brechen (z.B. Zeitstempel im PNG-Metadaten-Chunk), fallback auf
            // strukturelle Gleichheit der gerenderten Bildgröße.
            SampleOutput.write("sysml2/bdd-determinism-run1.png", first)
            first.toList() shouldBe second.toList()
            // Sanity: we did render something
            first.size shouldNotBe 0
        }
    })
