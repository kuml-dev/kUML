@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.projection.Sysml2PathResolver
import dev.kuml.sysml2.projection.sysml2IbdProjection
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import kotlin.script.experimental.api.ResultWithDiagnostics

/**
 * PAL wave 2C-b2 fixture "IBD Box-in-Box 3 Ebenen": line style and colour of a `connectionDef`, the attribute
 * compartment of a part, wrapped port labels, and the nested part paths of the projection.
 * The fixture lives outside `vault-examples/` because the vault sync mirrors that folder with `rsync --delete`.
 */
class Sysml2BoxInBoxTest :
    FunSpec({
        val md =
            Sysml2BoxInBoxTest::class.java
                .getResourceAsStream("/ibd-fixtures/IBD Box-in-Box 3 Ebenen.md")!!
                .bufferedReader(Charsets.UTF_8)
                .readText()
        val script = Regex("```kuml\\s*\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL).find(md)!!.groupValues[1]

        fun evalModel(code: String): dev.kuml.sysml2.Sysml2Model {
            VaultExampleRenderer.init()
            val ok = KumlScriptHost.eval(code = code) as ResultWithDiagnostics.Success
            val extracted = DiagramExtractor.extractAny(returnValue = ok.value.returnValue, input = File("inline.kuml.kts"))
            return (extracted as ExtractedDiagram.Sysml2).model
        }

        test("the fixture renders, with dashed coloured connection, attribute compartment and no raw entities") {
            val result = VaultExampleRenderer.render(script = script, themeName = "plain")
            (result.error == null) shouldBe true
            val svg = result.svg!!
            SampleOutput.write(filename = "vault-examples/kuml/ibd-box-in-box-3-ebenen.svg", content = svg)
            // D: the Feed connection is DASHED "6 4" in its own colour; the Signal style is not used here.
            svg shouldContain "stroke-dasharray=\"6 4\""
            svg shouldContain "style=\"stroke:#1D2968\""
            // C: the attribute with a value shows `name : Type = value`, the one without shows no `=`.
            svg shouldContain "weight : Mass = 12"
            svg shouldContain "serial : Mass"
            svg shouldNotContain "serial : Mass ="
            svg shouldContain "kuml-ibd-attribute"
            svg shouldNotContain "&amp;apos;"
            svg shouldNotContain "&amp;lt;"
        }

        test("without the attribute compartment (showAttributes = false) the attribute lines disappear") {
            val off = script.replace("ibd(name = \"Plant\", owner = plant)", "ibd(name = \"Plant\", owner = plant, showAttributes = false)")
            val svg = VaultExampleRenderer.render(script = off, themeName = "plain").svg!!
            svg shouldNotContain "weight : Mass"
        }

        test("the projection finds the three nested levels and resolves the deep end to the innermost part") {
            val model = evalModel(script)
            val p = sysml2IbdProjection(model = model, diagram = model.diagrams.filterIsInstance<IbdDiagram>().single())
            p.nestedPartPaths shouldContain "Plant::line::cell"
            p.nestedPartPaths shouldContain "Plant::line::cell::drive"
            val end =
                Sysml2PathResolver.resolve(
                    model = model,
                    ownerId = "Plant",
                    endpointId = "Plant::line::cell::drive::power",
                    visibleNodeIds = p.partNodes.map { it.id }.toSet(),
                    nestedNodeIds = p.nestedPartPaths.toSet(),
                )!!
            end.nodeId shouldBe "Plant::line::cell::drive"
            end.terminalPort?.name shouldBe "power"
        }

        // PAL wave 2C-b2b (E): box in box, measured on the rendered SVG.
        context("box in box (rendered SVG)") {
            val svg = VaultExampleRenderer.render(script = script, themeName = "plain").svg!!
            val nodes = SvgGeometry.nodes(svg)
            val line = nodes.first { it.id == "Plant::line" }
            val grid = nodes.first { it.id == "Plant::grid" }

            val box =
                Regex("""<rect width="([-0-9.]+)" height="([-0-9.]+)"[^>]*class="kuml-class"""").find(line.body)!!.let {
                    SvgRect(0.0, 0.0, it.groupValues[1].toDouble(), it.groupValues[2].toDouble())
                }
            val nested =
                Regex(
                    """<rect x="([-0-9.]+)" y="([-0-9.]+)" width="([-0-9.]+)" height="([-0-9.]+)" class="kuml-class kuml-ibd-nested" style="[^"]*" data-level="(\d)" data-path="([^"]+)"""",
                ).findAll(line.body)
                    .map {
                        Triple(
                            it.groupValues[5].toInt(),
                            it.groupValues[6],
                            SvgRect(
                                it.groupValues[1].toDouble(),
                                it.groupValues[2].toDouble(),
                                it.groupValues[3].toDouble(),
                                it.groupValues[4].toDouble(),
                            ),
                        )
                    }.toList()

            test("exactly three nested part frames: line contains cell contains drive") {
                nested.map { it.first } shouldBe listOf(2, 3)
                nested.map { it.second } shouldBe listOf("Plant::line::cell", "Plant::line::cell::drive")
            }

            test("each level keeps at least 16 px inside its parent") {
                box.contains(nested[0].third, margin = 16.0) shouldBe true
                nested[0].third.contains(nested[1].third, margin = 16.0) shouldBe true
            }

            test("the port power sits on the border of its own box, the drive frame") {
                val drive = nested[1].third
                val ports =
                    Regex("""<rect x="([-0-9.]+)" y="([-0-9.]+)" width="([-0-9.]+)" height="([-0-9.]+)" class="kuml-port """)
                        .findAll(line.body)
                        .map {
                            SvgRect(
                                it.groupValues[1].toDouble(),
                                it.groupValues[2].toDouble(),
                                it.groupValues[3].toDouble(),
                                it.groupValues[4].toDouble(),
                            )
                        }.toList()
                ports.size shouldBe 1
                val c = ports.single()
                val onBorder =
                    (
                        kotlin.math.abs(c.cx - drive.x) <= 0.5 ||
                            kotlin.math.abs(c.cx - drive.right) <= 0.5 ||
                            kotlin.math.abs(c.cy - drive.y) <= 0.5 ||
                            kotlin.math.abs(c.cy - drive.bottom) <= 0.5
                    )
                onBorder shouldBe true
                (c.cx >= drive.x - 0.5 && c.cx <= drive.right + 0.5 && c.cy >= drive.y - 0.5 && c.cy <= drive.bottom + 0.5) shouldBe true
            }

            test("the feed connector crosses only ancestor frames of its target and no other part") {
                val feed = SvgGeometry.edgePolylines(svg).getValue("conn:Plant::feed")
                val gridBox =
                    Regex("""<rect width="([-0-9.]+)" height="([-0-9.]+)"[^>]*class="kuml-class"""").find(grid.body)!!.let {
                        SvgRect(grid.tx, grid.ty, it.groupValues[1].toDouble(), it.groupValues[2].toDouble())
                    }
                val driveAbs = nested[1].third.translated(line.tx, line.ty)
                (SvgGeometry.distance(feed, gridBox) > 0.0) shouldBe true
                // the line ends on the drive frame and never enters it
                val inside = SvgRect(driveAbs.x + 1, driveAbs.y + 1, driveAbs.w - 2, driveAbs.h - 2)
                (SvgGeometry.distance(feed, inside) > 0.0) shouldBe true
                // and it does cross the visible part box and the cell frame
                val cellAbs = nested[0].third.translated(line.tx, line.ty)
                (SvgGeometry.distance(feed, cellAbs) == 0.0) shouldBe true
            }

            test("everything lies inside the viewBox") {
                val vb = SvgGeometry.numbers(Regex("""viewBox="([^"]+)"""").find(svg)!!.groupValues[1])
                val canvas = SvgRect(0.0, 0.0, vb[2], vb[3])
                val rects = ArrayList<SvgRect>()
                for (n in nodes) {
                    Regex("""<rect width="([-0-9.]+)" height="([-0-9.]+)"[^>]*class="kuml-class"""").find(n.body)?.let {
                        rects += SvgRect(n.tx, n.ty, it.groupValues[1].toDouble(), it.groupValues[2].toDouble())
                    }
                    rects += SvgGeometry.labelRects(n.body, "kuml-port-label-group").map { it.second.translated(n.tx, n.ty) }
                }
                for (r in rects) canvas.contains(r, margin = 0.0) shouldBe true
            }
        }
    })
