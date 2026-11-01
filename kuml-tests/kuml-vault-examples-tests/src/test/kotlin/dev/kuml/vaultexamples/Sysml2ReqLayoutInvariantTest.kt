@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.sysml2.constraint.Sysml2ReqUcChecker
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.shouldBeBetween
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.script.experimental.api.ResultWithDiagnostics

/**
 * Wave 2E-a - visual invariants of requirement diagrams, measured on the rendered SVG only (independent of fonts):
 * the group grid, determinism, the `include` order, subject edges, the containment crosshair, edge bands, label
 * geometry and the block rail.
 */
class Sysml2ReqLayoutInvariantTest :
    FunSpec({
        fun fixture(name: String): String =
            Sysml2ReqLayoutInvariantTest::class.java
                .getResourceAsStream("/sysml2-e2a/$name.kuml.kts")!!
                .bufferedReader()
                .readText()

        fun render(script: String): String {
            val r = VaultExampleRenderer.render(script = script)
            r.error shouldBe null
            return r.svg!!
        }

        val fixtures = listOf("req-grid", "req-grid-subject", "req-grid-right", "req-grid-aspect")
        val svgs = HashMap<String, String>()

        fun resourceScript(path: String): String =
            Sysml2ReqLayoutInvariantTest::class.java
                .getResourceAsStream("/$path.kuml.kts")!!
                .bufferedReader()
                .readText()

        fun svgOf(name: String): String =
            svgs.getOrPut(name) { render(fixture(name)).also { SampleOutput.write("sysml2-e2a/$name.svg", it) } }

        /** Group frames: id -> rectangle. */
        fun frames(svg: String): Map<String, SvgRect> =
            Regex(
                """<g id="(group:[^"]+)" class="kuml-req-group">\s*<rect x="([-0-9.]+)" y="([-0-9.]+)" width="([-0-9.]+)" height="([-0-9.]+)"""",
            ).findAll(svg)
                .associate {
                    val v = it.groupValues
                    v[1] to SvgRect(v[2].toDouble(), v[3].toDouble(), v[4].toDouble(), v[5].toDouble())
                }

        /** Cards (nodes with a `kuml-class` rect): id -> rectangle. */
        fun cards(svg: String): Map<String, SvgRect> =
            SvgGeometry
                .nodes(svg)
                .mapNotNull { n ->
                    Regex("""<rect width="([-0-9.]+)" height="([-0-9.]+)" class="kuml-class"""").find(n.body)?.let {
                        n.id to SvgRect(n.tx, n.ty, it.groupValues[1].toDouble(), it.groupValues[2].toDouble())
                    }
                }.toMap()

        fun canvas(svg: String): Pair<Double, Double> {
            val m = Regex("""viewBox="0 0 ([0-9.]+) ([0-9.]+)"""").find(svg)!!
            return m.groupValues[1].toDouble() to m.groupValues[2].toDouble()
        }

        /** Distinct clusters of [values] (values closer than [tol] share a cluster). */
        fun clusters(
            values: List<Double>,
            tol: Double = 8.0,
        ): List<Double> {
            val out = ArrayList<Double>()
            for (v in values.sorted()) if (out.isEmpty() || v - out.last() > tol) out += v
            return out
        }

        val top = listOf("group:Functional", "group:Safety", "group:Performance", "group:Operations")

        test("down: four groups with wrapColumns = 2 form a 2 x 2 grid in declaration order, canvas ratio in range") {
            val svg = svgOf("req-grid")
            val f = frames(svg)
            val t = top.map { f.getValue(it) }
            clusters(t.map { it.x }).size shouldBe 2
            clusters(t.map { it.y }).size shouldBe 2
            // A B / C D
            (abs(t[0].x - t[2].x) < 8.0) shouldBe true
            (abs(t[1].x - t[3].x) < 8.0) shouldBe true
            (abs(t[0].y - t[1].y) < 8.0) shouldBe true
            (abs(t[2].y - t[3].y) < 8.0) shouldBe true
            (t[0].x < t[1].x) shouldBe true
            (t[0].y < t[2].y) shouldBe true
            val (w, h) = canvas(svg)
            (w / h).shouldBeBetween(0.6, 2.4, 0.0)
        }

        test("right: the grid is transposed (A C / B D), the frames stand below each other") {
            val svg = svgOf("req-grid-right")
            val f = frames(svg)
            val t = top.map { f.getValue(it) }
            clusters(t.map { it.x }).size shouldBe 2
            clusters(t.map { it.y }).size shouldBe 2
            (abs(t[0].x - t[1].x) < 8.0) shouldBe true
            (abs(t[2].x - t[3].x) < 8.0) shouldBe true
            (abs(t[0].y - t[2].y) < 8.0) shouldBe true
            (t[0].y < t[1].y) shouldBe true
            (t[0].x < t[2].x) shouldBe true
        }

        test("aspectRatio only: the column count is chosen automatically and the canvas ratio is close to the target") {
            val (w, h) = canvas(svgOf("req-grid-aspect"))
            (w / h).shouldBeBetween(1.6 * 0.75, 1.6 * 1.25, 0.0)
        }

        test("renders are byte-identical: fixtures and every requirement vault example") {
            fun twice(script: String) {
                render(script) shouldBe render(script)
            }
            fixtures.forEach { twice(fixture(it)) }
            for (n in listOf("sysml2-c1/req", "sysml2-c4/req-wrap")) {
                twice(
                    Sysml2ReqLayoutInvariantTest::class.java
                        .getResourceAsStream("/$n.kuml.kts")!!
                        .bufferedReader()
                        .readText(),
                )
            }
            VaultExampleLoader
                .loadFromClasspath()
                .filter { "SysML 2 REQ" in it.baseName }
                .forEach { twice(it.kumlScript) }
        }

        test("the cards of every group follow the include order") {
            val svg = svgOf("req-grid")
            val c = cards(svg)
            val order =
                mapOf(
                    "group:Functional" to listOf("Boot", "Update", "Rollback"),
                    "group:Performance" to listOf("Latency", "Scale", "SampleRate"),
                    "group:Operations" to listOf("Logging", "Backup", "LocalDeadline"),
                )
            for ((g, ids) in order) {
                val frame = frames(svg).getValue(g)
                val inside = ids.map { it to c.getValue(it) }
                inside.forEach { (_, r) -> frame.contains(r, 0.0) shouldBe true }
                // reading order: rows top to bottom, within a row left to right
                val sorted = inside.sortedWith(compareBy({ Math.round(it.second.y / 20.0) }, { it.second.x })).map { it.first }
                sorted shouldBe ids
            }
        }

        test("subject: no «subject» edge by default and the text line shows the name; the opt-in edges stay in the content box") {
            val plain = svgOf("req-grid")
            plain shouldNotContain "«subject»"
            plain shouldNotContain "id=\"subject:"
            plain shouldContain "subject: Platform"
            plain shouldContain "subject: SensorUnit"

            val withEdges = svgOf("req-grid-subject")
            val ids = SvgGeometry.edgePolylines(withEdges).keys.filter { it.startsWith("subject:") }
            ids.isEmpty() shouldBe false
            // a requirement that is already satisfied by its subject gets no second line
            ids.forEach { id ->
                val (req, ref) = id.removePrefix("subject:").split("::")
                SvgGeometry.edgePolylines(withEdges).keys.contains("satisfy:$ref::$req") shouldBe false
            }
            val boxes = cards(withEdges).values + frames(withEdges).values
            val l = boxes.minOf { it.x } - 24
            val t = boxes.minOf { it.y } - 24
            val r = boxes.maxOf { it.right } + 24
            val b = boxes.maxOf { it.bottom } + 24
            for (id in ids) {
                SvgGeometry.edgePolylines(withEdges).getValue(id).forEach { (x, y) ->
                    (x in l..r && y in t..b) shouldBe true
                }
            }
        }

        test("containment is drawn as a crosshair at the parent, not as a text label; one «satisfy» label per source") {
            val svg = svgOf("req-grid")
            svg shouldNotContain "«containment»"
            Regex("kuml-containment-crosshair").findAll(svg).count() shouldBe 5
            Regex("«satisfy»").findAll(svg).count() shouldBe 2
        }

        test("no two parallel segments of different edges are closer than 6 px over more than 100 px") {
            val scripts =
                fixtures.associateWith { fixture(it) } +
                    VaultExampleLoader
                        .loadFromClasspath()
                        .filter { "SysML 2 REQ" in it.baseName }
                        .filter {
                            it.baseName.startsWith("08") ||
                                it.baseName.startsWith("46") ||
                                it.baseName.startsWith("52") ||
                                it.baseName.startsWith("60")
                        }.associate { it.baseName to it.kumlScript }
            for ((name, script) in scripts) {
                val svg = svgs.getOrPut(name) { render(script) }
                SampleOutput.write("sysml2-e2a/inv-$name.svg".replace(" ", "_").replace("–", "-"), svg)
                val edges = SvgGeometry.edgePolylines(svg)

                data class S(
                    val edge: String,
                    val horizontal: Boolean,
                    val c: Double,
                    val lo: Double,
                    val hi: Double,
                )

                val segs = ArrayList<S>()
                for ((id, pts) in edges) {
                    for (i in 0 until pts.size - 1) {
                        val (x0, y0) = pts[i]
                        val (x1, y1) = pts[i + 1]
                        if (abs(y0 - y1) < 0.6 && abs(x0 - x1) > 1.0) segs += S(id, true, y0, min(x0, x1), max(x0, x1))
                        if (abs(x0 - x1) < 0.6 && abs(y0 - y1) > 1.0) segs += S(id, false, x0, min(y0, y1), max(y0, y1))
                    }
                }
                val bad = ArrayList<String>()
                for (i in segs.indices) {
                    for (j in i + 1 until segs.size) {
                        val a = segs[i]
                        val b = segs[j]
                        if (a.edge == b.edge || a.horizontal != b.horizontal) continue
                        if (abs(a.c - b.c) >= 6.0) continue
                        if (min(a.hi, b.hi) - max(a.lo, b.lo) > 100.0) bad += "$name: ${a.edge} / ${b.edge} at ${a.c}/${b.c}"
                    }
                }
                bad.shouldBeEmpty()
            }
        }

        test("no edge label touches a card or a group frame outline") {
            for (name in fixtures) {
                val svg = svgOf(name)
                val labels =
                    Regex(
                        """<g class="kuml-edge-label" data-edge-id="[^"]*" data-label-id="([^"]*)" data-label-rect="([-0-9.]+) ([-0-9.]+) ([-0-9.]+) ([-0-9.]+)"""",
                    ).findAll(svg)
                        .map { m ->
                            val v = m.groupValues
                            v[1] to SvgRect(v[2].toDouble(), v[3].toDouble(), v[4].toDouble(), v[5].toDouble())
                        }.toList()
                (labels.size >= 2) shouldBe true
                val cs = cards(svg)
                val strips =
                    frames(svg).values.flatMap { f ->
                        listOf(
                            SvgRect(f.x, f.y - 2, f.w, 4.0),
                            SvgRect(f.x, f.bottom - 2, f.w, 4.0),
                            SvgRect(f.x - 2, f.y, 4.0, f.h),
                            SvgRect(f.right - 2, f.y, 4.0, f.h),
                        )
                    }
                for ((id, rect) in labels) {
                    cs.entries
                        .filter { rect.overlaps(it.value) }
                        .map { "$name: label $id on card ${it.key}" }
                        .shouldBeEmpty()
                    strips.filter { rect.overlaps(it) }.map { "$name: label $id on a frame outline" }.shouldBeEmpty()
                }
            }
        }

        test("no stereotype edge loses its label: one label per verify/derive/refine/trace/copy edge and per satisfy source") {
            val labelled = listOf("verify:", "derive:", "refine:", "trace:", "copy:")
            for (name in fixtures + listOf("sysml2-c1/req")) {
                val svg = if (name in fixtures) svgOf(name) else render(resourceScript(name))
                val edgeIds = SvgGeometry.edgePolylines(svg).keys
                val expected =
                    edgeIds.filter { id -> labelled.any { id.startsWith(it) } }.toSet() +
                        edgeIds
                            .filter { it.startsWith("satisfy:") }
                            .groupBy { it.removePrefix("satisfy:").substringBefore("::") }
                            .values
                            .map { ids -> ids.min() }
                val actual =
                    Regex("""data-label-id="([^"]*)"""").findAll(svg).map { it.groupValues[1] }.toSet()
                (expected - actual).toList().sorted().shouldBeEmpty()
            }
        }

        test("down: every block stands at the height of the requirements it satisfies") {
            val svg = svgOf("req-grid")
            val c = cards(svg)
            val satisfied =
                mapOf(
                    "Platform" to listOf("Boot", "Update", "Rollback", "Auth", "Audit", "Scale", "Logging", "Backup", "LocalDeadline"),
                    "SensorUnit" to listOf("FailSafe", "Watchdog", "Latency", "SampleRate"),
                )
            for ((block, reqs) in satisfied) {
                val b = c.getValue(block)
                val lo = reqs.minOf { c.getValue(it).y }
                val hi = reqs.maxOf { c.getValue(it).bottom }
                (b.y < hi && b.bottom > lo) shouldBe true
            }
        }

        test("the external requirement is a dashed card and the model check reports reqId 218 exactly once") {
            val svg = svgOf("req-grid")
            svg shouldContain "«external»"
            svg shouldContain "source: Dokument 11"
            VaultExampleRenderer.init()
            val ok = KumlScriptHost.eval(code = fixture("req-grid")) as ResultWithDiagnostics.Success
            val ex =
                DiagramExtractor.extractAny(returnValue = ok.value.returnValue, input = File("inline.kuml.kts")) as ExtractedDiagram.Sysml2
            val issues = Sysml2ReqUcChecker.check(model = ex.model, diagram = ex.diagram)
            issues.filter { it.code == 218 }.size shouldBe 1
            issues.filter { it.code == 202 }.size shouldBe 0
        }

        test("text of an external source is escaped in the SVG") {
            val script =
                """
                sysml2Model("Esc") {
                    val ext = externalRequirement("X-1", source = "<script>alert(1)</script> & Co", text = "a < b & c")
                    reqDiagram("d") {
                        include(ext)
                    }
                }
                """.trimIndent()
            val svg = render(script)
            svg shouldNotContain "<script>"
            svg shouldContain "&lt;script&gt;"
            svg shouldContain "a &lt; b &amp; c"
        }
    })
