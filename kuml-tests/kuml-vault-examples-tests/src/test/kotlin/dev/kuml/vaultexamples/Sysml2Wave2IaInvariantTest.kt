@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import dev.kuml.io.png.KumlPngRenderer
import dev.kuml.io.png.PngRenderOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * Wave 2I-a - visual invariants of package diagrams, measured on the rendered SVG of the full pipeline (never on layout
 * results). Only the background probe looks at pixels; everything else is geometry, so the checks are font independent.
 */
class Sysml2Wave2IaInvariantTest :
    FunSpec({
        fun fixture(name: String): String =
            Sysml2Wave2IaInvariantTest::class.java
                .getResourceAsStream("/wave-2ia/$name.kuml.kts")!!
                .bufferedReader()
                .readText()

        val cache = HashMap<String, String>()

        fun svgOf(name: String): String =
            cache.getOrPut(name) {
                val r = VaultExampleRenderer.render(script = fixture(name))
                r.error shouldBe null
                r.svg!!.also { SampleOutput.write("wave-2ia/invariants-$name.svg", it) }
            }

        /** Package frames: id -> rectangle. */
        fun frames(svg: String): Map<String, SvgRect> =
            Regex(
                """<g id="(pkg:[^"]+)" class="kuml-package-frame">\s*<rect x="([-0-9.]+)" y="([-0-9.]+)" width="([-0-9.]+)" height="([-0-9.]+)"""",
            ).findAll(svg)
                .associate {
                    val v = it.groupValues
                    v[1] to SvgRect(v[2].toDouble(), v[3].toDouble(), v[4].toDouble(), v[5].toDouble())
                }

        /** Name tabs of the frames: frame id -> rectangle. */
        fun tabs(svg: String): Map<String, SvgRect> =
            Regex(
                """<g id="tab:(pkg:[^"]+)" class="kuml-package-tab">\s*<rect x="([-0-9.]+)" y="([-0-9.]+)" width="([-0-9.]+)" height="([-0-9.]+)"""",
            ).findAll(svg)
                .associate {
                    val v = it.groupValues
                    v[1] to SvgRect(v[2].toDouble(), v[3].toDouble(), v[4].toDouble(), v[5].toDouble())
                }

        /** Cards (definitions, views, viewpoints, collapsed packages): id -> bounding rectangle. */
        fun cards(svg: String): Map<String, SvgRect> =
            Regex(
                """<g id="([^"]+)" transform="translate\(([-0-9.]+),([-0-9.]+)\)" class="kuml-package-card">\s*<rect (?:x="0" y="(20)" )?width="([-0-9.]+)" height="([-0-9.]+)"""",
            ).findAll(svg)
                .associate {
                    val v = it.groupValues
                    val tabH = if (v[4].isEmpty()) 0.0 else v[4].toDouble()
                    v[1] to SvgRect(v[2].toDouble(), v[3].toDouble(), v[5].toDouble(), v[6].toDouble() + tabH)
                }

        /** Edge polylines (line or path): edge id -> points. */
        fun edges(svg: String): Map<String, List<Pair<Double, Double>>> {
            val out = LinkedHashMap<String, List<Pair<Double, Double>>>()
            for (m in Regex("""<g id="((?:import|satisfy):[^"]+)">\s*<(line|path) ([^>]*?)class="kuml-edge"""").findAll(svg)) {
                val tag = m.groupValues[3]
                val d = Regex("""d="([^"]+)"""").find(tag)?.groupValues?.get(1)
                out[m.groupValues[1]] =
                    if (d != null) {
                        SvgGeometry.pathPoints(d)
                    } else {
                        fun attr(n: String) = Regex("""$n="([-0-9.]+)"""").find(tag)!!.groupValues[1].toDouble()
                        listOf(attr("x1") to attr("y1"), attr("x2") to attr("y2"))
                    }
            }
            return out
        }

        fun onBorder(
            p: Pair<Double, Double>,
            r: SvgRect,
            tol: Double = 1.0,
        ): Boolean {
            val inOuter = p.first >= r.x - tol && p.first <= r.right + tol && p.second >= r.y - tol && p.second <= r.bottom + tol
            val inInner = p.first > r.x + tol && p.first < r.right - tol && p.second > r.y + tol && p.second < r.bottom - tol
            return inOuter && !inInner
        }

        fun rectOf(
            id: String,
            svg: String,
        ): SvgRect = frames(svg)[id] ?: cards(svg).getValue(id)

        val nested = "nested-packages-imports"
        val views = "view-viewpoint"

        test("a member card lies completely inside the frame of its package, with the 16 px padding") {
            val svg = svgOf(nested)
            val f = frames(svg)
            val c = cards(svg)
            val membership =
                mapOf(
                    "Common::Mass" to "pkg:Common",
                    "Common::Voltage" to "pkg:Common",
                    "Common::PowerPort" to "pkg:Common",
                    "Vehicle::Vehicle" to "pkg:Vehicle",
                    "Vehicle::Chassis" to "pkg:Vehicle",
                    "Vehicle::Drive::Motor" to "pkg:Vehicle::Drive",
                    "Vehicle::Drive::Gearbox" to "pkg:Vehicle::Drive",
                    "Vehicle::Drive::Power::Battery" to "pkg:Vehicle::Drive::Power",
                    "Safety::Redundancy" to "pkg:Safety",
                    "pkg:Vehicle::Drive::Power::Cells" to "pkg:Vehicle::Drive::Power",
                )
            for ((card, frame) in membership) f.getValue(frame).contains(c.getValue(card), margin = 16.0) shouldBe true
        }

        test("a child frame lies inside its parent with the 16 px padding and its tab is at least 16 px below the parent's tab") {
            val svg = svgOf(nested)
            val f = frames(svg)
            val t = tabs(svg)
            val parents =
                mapOf(
                    "pkg:Vehicle::Drive" to "pkg:Vehicle",
                    "pkg:Vehicle::Drive::Power" to "pkg:Vehicle::Drive",
                )
            for ((child, parent) in parents) {
                f.getValue(parent).contains(f.getValue(child), margin = 16.0) shouldBe true
                (t.getValue(child).y - t.getValue(parent).bottom >= 16.0 - 0.01) shouldBe true
            }
        }

        test("the frames are drawn from three levels; deeper packages are cards inside the level-3 frame") {
            val svg = svgOf(nested)
            frames(svg).keys.toList().sorted() shouldBe
                listOf("pkg:Common", "pkg:Safety", "pkg:Vehicle", "pkg:Vehicle::Drive", "pkg:Vehicle::Drive::Power")
            val c = cards(svg)
            val power = frames(svg).getValue("pkg:Vehicle::Drive::Power")
            power.contains(c.getValue("pkg:Vehicle::Drive::Power::Cells"), margin = 16.0) shouldBe true
            power.contains(c.getValue("pkg:Vehicle::Drive::Power::Cells::Chemistry"), margin = 16.0) shouldBe true
            svg shouldContain "Power::Cells::Chemistry"
        }

        test("no edge crosses a name tab") {
            for (name in listOf(nested, views)) {
                val svg = svgOf(name)
                (edges(svg).size >= 2) shouldBe true
                (tabs(svg).isNotEmpty() || name == views) shouldBe true
                for ((edgeId, pts) in edges(svg)) {
                    for ((frameId, tab) in tabs(svg)) {
                        (SvgGeometry.distance(pts, tab) > 0.0) shouldBe true
                    }
                    edgeId shouldContain ":"
                }
                svg shouldNotContain "data-kuml-fallback"
            }
        }

        test("the ends of the import and satisfy arrows lie on the border of their source and target (within 1 px)") {
            val expected =
                mapOf(
                    nested to
                        mapOf(
                            "import:pkg:Vehicle::Common::*" to ("pkg:Vehicle" to "pkg:Common"),
                            "import:pkg:Vehicle::Common::PowerPort" to ("pkg:Vehicle" to "Common::PowerPort"),
                            "import:pkg:Vehicle::Drive::Common::**" to ("pkg:Vehicle::Drive" to "pkg:Common"),
                            "import:pkg:Safety::Vehicle::Drive::Motor" to ("pkg:Safety" to "Vehicle::Drive::Motor"),
                            "import:pkg:Safety::Vehicle::Drive::**" to ("pkg:Safety" to "pkg:Vehicle::Drive"),
                        ),
                    views to
                        mapOf(
                            "satisfy:view:Safety Overview::viewpoint:Safety" to ("view:Safety Overview" to "viewpoint:Safety"),
                            "satisfy:view:Bill of Materials::viewpoint:Cost" to ("view:Bill of Materials" to "viewpoint:Cost"),
                        ),
                )
            for ((name, byEdge) in expected) {
                val svg = svgOf(name)
                val es = edges(svg)
                es.size shouldBe byEdge.size
                for ((edgeId, ends) in byEdge) {
                    val pts = es.getValue(edgeId)
                    onBorder(pts.first(), rectOf(ends.first, svg)) shouldBe true
                    onBorder(pts.last(), rectOf(ends.second, svg)) shouldBe true
                }
            }
        }

        test("no two edge labels overlap, and no label sits on a card") {
            for (name in listOf(nested, views)) {
                val svg = svgOf(name)
                val ls = SvgGeometry.labelRects(svg, "kuml-edge-label").map { it.second }
                for (i in ls.indices) for (j in i + 1 until ls.size) ls[i].overlaps(ls[j], eps = 0.0) shouldBe false
                for (l in ls) for (c in cards(svg).values) l.overlaps(c, eps = 0.0) shouldBe false
            }
        }

        test("an import without a drawable target is a text line under the tab of its frame") {
            val svg = svgOf(nested)
            svg shouldContain "import Nowhere::*"
            val safety = frames(svg).getValue("pkg:Safety")
            val y =
                Regex(
                    """y="([-0-9.]+)"[^>]*kuml-package-import-line|class="kuml-body kuml-package-import-line" x="[-0-9.]+" y="([-0-9.]+)"""",
                ).find(svg)!!
            val lineY = (y.groupValues[1].ifEmpty { y.groupValues[2] }).toDouble()
            (lineY > safety.y + 20.0 && lineY < safety.y + 36.0 + 14.0) shouldBe true
            // the member card starts below the text line
            (cards(svg).getValue("Safety::Redundancy").y >= safety.y + 36.0 + 14.0 - 0.01) shouldBe true
        }

        test("the frame is not filled: the pixel inside the frame equals the pixel outside it") {
            val svg = svgOf(nested)
            val vb = Regex("""viewBox="0 0 ([0-9.]+) ([0-9.]+)"""").find(svg)!!.groupValues
            val w = vb[1].toDouble()
            val png = KumlPngRenderer.toPng(svg = svg, options = PngRenderOptions(widthPx = w.toInt()))
            val img = ImageIO.read(ByteArrayInputStream(png))
            val scale = img.width / w
            val frame = frames(svg).getValue("pkg:Vehicle")

            fun px(
                x: Double,
                y: Double,
            ) = img.getRGB((x * scale).toInt(), (y * scale).toInt())
            val inside = px(frame.right - 10.0, frame.bottom - 10.0)
            val outside = px(frame.right + 10.0, frame.bottom - 10.0)
            inside shouldBe outside
        }

        test("rendering twice gives byte-identical SVG") {
            for (name in listOf(nested, views)) {
                val again = VaultExampleRenderer.render(script = fixture(name)).svg!!
                again shouldBe svgOf(name)
            }
        }

        test("views and viewpoints: cards carry their fields, the satisfy edges are dashed 6 4 with the label") {
            val svg = svgOf(views)
            svg shouldContain "«view»"
            svg shouldContain "«viewpoint»"
            svg shouldContain "Certification authority"
            svg shouldContain "stroke-dasharray=\"6 4\""
            svg shouldContain "«satisfy»"
            abs(edges(svg).size - 2) shouldBe 0
        }
    })
