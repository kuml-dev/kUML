@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Notes N2 - placement invariants measured on the rendered SVG of the full pipeline: no two notes overlap (8 px halo),
 * no note covers a part, and no anchor line runs through another note. Geometry only, no font metrics.
 */
class NotePlacementInvariantTest :
    FunSpec({
        fun fixture(name: String): String =
            NotePlacementInvariantTest::class.java
                .getResourceAsStream("/note-kinds/$name.kuml.kts")!!
                .bufferedReader()
                .readText()

        fun svgOf(name: String): String {
            val r = VaultExampleRenderer.render(script = fixture(name), themeName = "plain")
            r.error shouldBe null
            return r.svg!!.also { SampleOutput.write("note-placement/$name.svg", it) }
        }

        val noteHead =
            Regex(
                """<g id="([^"]+)" transform="translate\(([-0-9.]+),([-0-9.]+)\)">\s*<path d="M 0 0 L [-0-9.]+ 0 L ([-0-9.]+) [-0-9.]+ L [-0-9.]+ ([-0-9.]+) L 0 [-0-9.]+ Z" class="kuml-comment[ "]""",
            )

        fun notes(svg: String): List<Pair<String, SvgRect>> =
            noteHead
                .findAll(svg)
                .map { m ->
                    val v = m.groupValues
                    v[1] to SvgRect(v[2].toDouble(), v[3].toDouble(), v[4].toDouble(), v[5].toDouble())
                }.toList()

        fun parts(svg: String): List<Pair<String, SvgRect>> =
            Regex(
                """<g id="([^"]+)" transform="translate\(([-0-9.]+),([-0-9.]+)\)">\s*<rect width="([-0-9.]+)" height="([-0-9.]+)" class="kuml-class"""",
            ).findAll(svg)
                .map { m ->
                    val v = m.groupValues
                    v[1] to SvgRect(v[2].toDouble(), v[3].toDouble(), v[4].toDouble(), v[5].toDouble())
                }.toList()

        /** The anchor lines: `<line .../>` and `<path .../>` with the class `kuml-note-anchor`. */
        fun anchors(svg: String): List<List<Pair<Double, Double>>> {
            val out = ArrayList<List<Pair<Double, Double>>>()
            for (m in Regex(
                """<line x1="([-0-9.]+)" y1="([-0-9.]+)" x2="([-0-9.]+)" y2="([-0-9.]+)" class="kuml-note-anchor"/>""",
            ).findAll(svg)) {
                val v = m.groupValues.drop(1).map { it.toDouble() }
                out += listOf(v[0] to v[1], v[2] to v[3])
            }
            for (m in Regex("""<path d="([^"]+)" class="kuml-note-anchor"/>""").findAll(svg)) {
                out +=
                    SvgGeometry.pathPoints(m.groupValues[1])
            }
            return out
        }

        fun SvgRect.inflated(d: Double) = SvgRect(x - d, y - d, w + 2 * d, h + 2 * d)

        for ((name, expectedNotes) in listOf("sysml2-bdd" to 15, "c4-container" to 7, "blueprint" to 6)) {
            test("$name: all notes placed, none overlaps another (8 px halo), none covers a part") {
                val svg = svgOf(name)
                val ns = notes(svg)
                ns.size shouldBe expectedNotes
                var pairs = 0
                for (i in ns.indices) {
                    for (j in i + 1 until ns.size) {
                        pairs++
                        val overlap = ns[i].second.inflated(4.0).overlaps(ns[j].second.inflated(4.0))
                        if (overlap) println("OVERLAP $name: ${ns[i].first} ${ns[i].second} / ${ns[j].first} ${ns[j].second}")
                        overlap shouldBe false
                    }
                }
                pairs shouldBe ns.size * (ns.size - 1) / 2
                for ((id, n) in ns) {
                    for ((pid, p) in parts(svg)) {
                        if (n.overlaps(p)) println("COVERS $name: $id $pid")
                        n.overlaps(p) shouldBe false
                    }
                }
            }

            test("$name: no anchor line runs through the inside of a note") {
                val svg = svgOf(name)
                val ns = notes(svg)
                val lines = anchors(svg)
                (lines.size >= ns.size - 1) shouldBe true
                for (l in lines) {
                    for ((id, n) in ns) {
                        val d = SvgGeometry.distance(l, n.inflated(-1.5))
                        if (d <= 0.0) println("LINE THROUGH $name: $l through $id $n")
                        (d > 0.0) shouldBe true
                    }
                }
            }

            test("$name: rendering is deterministic") {
                val a = VaultExampleRenderer.render(script = fixture(name), themeName = "plain").svg
                a shouldBe VaultExampleRenderer.render(script = fixture(name), themeName = "plain").svg
            }
        }
    })
