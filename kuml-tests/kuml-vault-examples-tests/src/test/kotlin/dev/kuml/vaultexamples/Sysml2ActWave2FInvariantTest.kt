@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.math.abs

/**
 * PAL wave 2F (ACT): swimlane columns, regions, interruption glyph, merge fan, object-flow type, wrapped names.
 * Measured on the SVG geometry only (translate, rect sizes, path points), never on glyph widths, so the result is
 * the same on every machine.
 */
class Sysml2ActWave2FInvariantTest :
    FunSpec({
        fun fixture(name: String): String =
            Sysml2ActWave2FInvariantTest::class.java
                .getResourceAsStream("/sysml2-f2/$name.kuml.kts")!!
                .bufferedReader()
                .readText()

        fun renderScript(
            script: String,
            out: String,
        ): String {
            val r = VaultExampleRenderer.render(script = script, themeName = "plain")
            r.error shouldBe null
            SampleOutput.write(filename = "sysml2-f2/$out.svg", content = r.svg!!)
            return r.svg!!
        }

        fun renderExample(
            prefix: String,
            out: String,
        ): String {
            val ex = VaultExampleLoader.loadFromClasspath().first { it.fileName.startsWith(prefix) }
            return renderScript(ex.kumlScript, out)
        }

        data class Lane(
            val id: String,
            val rect: SvgRect,
        )

        fun lanes(svg: String): List<Lane> =
            Regex(
                """<g id="activityPartition:([^"]+)" transform="translate\(([-0-9.]+),([-0-9.]+)\)">\s*<rect width="([-0-9.]+)" height="([-0-9.]+)" class="kuml-lane"""",
            ).findAll(svg)
                .map {
                    val v = it.groupValues
                    Lane(v[1], SvgRect(v[2].toDouble(), v[3].toDouble(), v[4].toDouble(), v[5].toDouble()))
                }.toList()

        /** Absolute rectangles of the boxes of activity nodes (rounded actions, pseudo nodes, diamonds, bars). */
        fun nodeRects(svg: String): Map<String, SvgRect> {
            val out = LinkedHashMap<String, SvgRect>()
            val re =
                Regex(
                    """<g id="([^"]+)" transform="translate\(([-0-9.]+),([-0-9.]+)\)"[^>]*>\s*<rect width="([-0-9.]+)" height="([-0-9.]+)" rx="14"""",
                )
            for (m in re.findAll(svg)) {
                val v = m.groupValues
                out[v[1]] = SvgRect(v[2].toDouble(), v[3].toDouble(), v[4].toDouble(), v[5].toDouble())
            }
            return out
        }

        fun cy(r: SvgRect) = r.y + r.h / 2.0

        test("swimlanes are columns: equal top and bottom, every node inside its lane, flow strictly downwards") {
            val svg = renderScript(fixture("act-lanes-16"), "act-lanes-16")
            val ls = lanes(svg)
            ls.map { it.id } shouldBe listOf("Person", "Vitec", "FMCS")
            ls.map { it.rect.y }.distinct().size shouldBe 1
            ls.map { it.rect.h }.distinct().size shouldBe 1
            // lanes are side by side and do not overlap
            ls.zipWithNext().forEach { (a, b) -> abs((a.rect.x + a.rect.w) - b.rect.x) shouldBe 0.0 }
            val nodes = nodeRects(svg)
            nodes.size shouldBe 14
            val laneOf =
                mapOf(
                    "a1" to "Person",
                    "a2" to "Vitec",
                    "a3" to "Vitec",
                    "a4" to "Vitec",
                    "a5" to "FMCS",
                    "a6" to "FMCS",
                    "a7" to "FMCS",
                    "a8" to "Vitec",
                    "a9" to "Vitec",
                    "a10" to "Person",
                    "a11" to "Person",
                    "a12" to "Vitec",
                    "a13" to "Vitec",
                    "a14" to "Person",
                )
            val byName = ls.associateBy { it.id }
            val sourceNames =
                mapOf(
                    "Request opening" to "a1",
                    "Check request" to "a2",
                    "Plan route" to "a3",
                    "Send command" to "a4",
                    "Receive command" to "a5",
                    "Open door" to "a6",
                    "Report state" to "a7",
                    "Store state" to "a8",
                    "Notify person" to "a9",
                    "Show result" to "a10",
                    "Confirm result" to "a11",
                    "Archive" to "a12",
                    "Audit" to "a13",
                    "Close request" to "a14",
                )
            val chain = sourceNames.values.sortedBy { it.drop(1).toInt() }
            val ordered = chain.map { key -> nodes.entries.first { sourceNames[it.key] == key }.value }
            for ((key, r) in chain.zip(ordered)) {
                val lane = byName.getValue(laneOf.getValue(key)).rect
                (r.x >= lane.x && r.x + r.w <= lane.x + lane.w) shouldBe true
            }
            ordered.zipWithNext().forEach { (a, b) -> (cy(b) > cy(a)) shouldBe true }
            // the diagram is exactly as high as its rows need: no row is taller than its node plus the 40 px gap
            val maxH = ordered.maxOf { it.h }
            (ls.first().rect.h <= 28.0 + 24.0 + 16 * (maxH + 40.0) + 24.0 + 1.0) shouldBe true
        }

        test("lane headers are semi-bold and upright") {
            val svg = renderExample("07 SysML", "07")
            svg shouldContain "kuml-lane-header"
            val header =
                Regex(
                    """<g id="activityPartitionHeader:[^"]+"[^>]*class="kuml-lane-header">\s*<text[^>]*style="([^"]+)"""",
                ).find(svg)!!
            header.groupValues[1] shouldContain "font-weight:600"
            header.groupValues[1] shouldContain "font-style:normal"
            header.groupValues[1] shouldNotContain "italic"
            // ... and the lanes of an existing example keep equal bands
            val ls = lanes(svg)
            ls.map { it.rect.y }.distinct().size shouldBe 1
            ls.map { it.rect.h }.distinct().size shouldBe 1
        }

        test("example 48: regions nested, no foreign node inside a region frame, one glyph per interrupting edge, sameLayer centres") {
            val svg = renderExample("48 ", "48")

            fun frame(id: String): SvgRect {
                val m =
                    Regex(
                        """<g id="region:$id" class="kuml-act-region">\s*<rect x="([-0-9.]+)" y="([-0-9.]+)" width="([-0-9.]+)" height="([-0-9.]+)"""",
                    ).find(svg)!!
                val v = m.groupValues
                return SvgRect(v[1].toDouble(), v[2].toDouble(), v[3].toDouble(), v[4].toDouble())
            }
            val payment = frame("Payment")
            val retry = frame("Retry")
            // nested: the child lies at least 8 px inside the parent on the left, right and bottom
            (retry.x - payment.x >= 8.0) shouldBe true
            ((payment.x + payment.w) - (retry.x + retry.w) >= 8.0) shouldBe true
            ((payment.y + payment.h) - (retry.y + retry.h) >= 8.0) shouldBe true
            val nodes = nodeRects(svg)
            for (id in listOf("HandleError", "CancelOrder", "PackGoods", "NotifyCustomer", "CheckStock")) {
                val r = nodes.getValue(id)
                (r.overlaps(payment)) shouldBe false
            }
            for (id in listOf("RetryCharge")) retry.contains(nodes.getValue(id), 0.0) shouldBe true
            // interruption: exactly two glyphs (timedOut and the exception handler), no zigzag polyline any more
            Regex("""class="kuml-interrupt-glyph"""").findAll(svg).count() shouldBe 2
            svg shouldNotContain "kuml-edge-lightning"
            // sameLayer(pack, notify): equal centres
            abs(cy(nodes.getValue("PackGoods")) - cy(nodes.getValue("NotifyCustomer"))) shouldBe 0.0
            // the signal and time nodes are drawn, the object flow carries «Order»
            svg shouldContain "«Order»"
            svg shouldNotContain "[Order]"
        }

        test("all 2F features: merge fan, object-flow type, wrapped long name, umlauts, pin to pin clear of nodes") {
            val svg = renderScript(fixture("act-all-2f"), "act-all-2f")
            // six arrivals at one merge: pairwise distinct end points, at least 8 px apart
            val lines = SvgGeometry.edgePolylines(svg)
            val ends =
                (0 until 6).map { i ->
                    val pts = lines.entries.first { it.key.endsWith("Quelle ${i + 1}::Sammler") || it.key.contains("o$i") }.value
                    pts.last()
                }
            for (i in ends.indices) {
                for (j in i + 1 until ends.size) {
                    val d = kotlin.math.hypot(ends[i].first - ends[j].first, ends[i].second - ends[j].second)
                    (d >= 8.0 - 0.01) shouldBe true
                }
            }
            svg shouldContain "«Störmeldung»"
            svg shouldNotContain "[Störmeldung]"
            // the 50 character event name is wrapped, never shortened
            val name = "Rückmeldung der Gebäudeleittechnik zur Türöffnung."
            name.length shouldBe 50
            val textOfNode = Regex("""<tspan[^>]*>([^<]*)</tspan>""").findAll(svg).map { it.groupValues[1] }.toList()
            val joined = textOfNode.joinToString(" ")
            for (w in name.split(" ")) joined shouldContain w
            svg shouldNotContain "…"
            // umlauts survive unchanged (as text and in ids)
            svg shouldContain "Störmeldung"
            svg shouldNotContain "Stoermeldung"
            // the pin to pin flow does not cross a foreign node
            val boxes = nodeRects(svg)
            val flow = lines.entries.first { it.key.contains("Meldung erfassen") && it.key.contains("Ablegen") }.value
            for ((id, r) in boxes) {
                if (id == "Meldung erfassen" || id == "Ablegen") continue
                (SvgGeometry.distance(flow, r) > 0.0) shouldBe true
            }
        }
    })
