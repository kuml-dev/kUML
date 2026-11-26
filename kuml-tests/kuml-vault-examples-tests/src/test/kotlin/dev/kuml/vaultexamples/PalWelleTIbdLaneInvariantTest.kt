package dev.kuml.vaultexamples

import dev.kuml.layout.bridge.IbdLaneSpreadProbe
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * PAL Welle T (P-IBD-04, Doc 42 I13 and I20): two connectors of one IBD never run side by side closer than 16 px over a
 * common length of at least 8 px, unless they leave or enter the same port (fan-out stem, Kare).
 *
 * Doc 42 (fixture `pal-ibd/42-erhitzer-ibd`, copied unchanged from the PAL workspace) had two connectors at the part
 * `erhitzer`: I13 (FMCS `stell` to erhitzer `stell`) and I20 (versorgung `leistung` to erhitzer `leistung`). The router led
 * both over the corridor between the two rows of parts and laid their inner horizontal segments on one line: distance
 * 0 px, common length about 32 px (segment index 1 of both routes, i.e. an inner segment, not an end stub). The test
 * "without the lane fix" documents that defect (the fix is switched off with [IbdLaneSpreadProbe], the same code path as
 * production); with the fix both runs of the corridor are at least 16 px apart.
 *
 * Measured on the SVG polylines (axis-parallel runs of at least 8 px; the 6 px corner arcs are shorter and ignored), in
 * the same JVM, so no byte comparison across machines or fonts is needed.
 */
class PalWelleTIbdLaneInvariantTest :
    FunSpec({
        timeout = 300_000L
        val minOverlap = 8.0
        val minGap = 16.0

        fun script() =
            PalWelleTIbdLaneInvariantTest::class.java
                .getResourceAsStream("/pal-ibd/42-erhitzer-ibd.kuml.kts")!!
                .bufferedReader(Charsets.UTF_8)
                .readText()

        class Run(
            val first: String,
            val second: String,
            val gap: Double,
            val overlap: Double,
        )

        fun violations(svg: String): List<Run> {
            val edges = SvgGeometry.edgePolylines(svg)
            val out = ArrayList<Run>()
            val ids = edges.keys.toList()
            for (i in ids.indices) {
                for (j in i + 1 until ids.size) {
                    val a = edges.getValue(ids[i])
                    val b = edges.getValue(ids[j])
                    // one end point in common = a bundle at one port (fan-out stem)
                    val bundle =
                        listOf(a.first(), a.last()).any { p ->
                            listOf(b.first(), b.last()).any { q ->
                                abs(p.first - q.first) < 0.5 &&
                                    abs(p.second - q.second) < 0.5
                            }
                        }
                    if (bundle) continue
                    for ((p0, p1) in a.zipWithNext()) {
                        for ((q0, q1) in b.zipWithNext()) {
                            val horizontal = abs(p0.second - p1.second) < 0.5 && abs(q0.second - q1.second) < 0.5
                            val vertical = abs(p0.first - p1.first) < 0.5 && abs(q0.first - q1.first) < 0.5
                            if (!horizontal && !vertical) continue
                            val (a0, a1, b0, b1) =
                                if (horizontal) {
                                    listOf(
                                        p0.first,
                                        p1.first,
                                        q0.first,
                                        q1.first,
                                    )
                                } else {
                                    listOf(p0.second, p1.second, q0.second, q1.second)
                                }
                            val overlap = min(max(a0, a1), max(b0, b1)) - max(min(a0, a1), min(b0, b1))
                            if (overlap < minOverlap) continue
                            val gap = if (horizontal) abs(p0.second - q0.second) else abs(p0.first - q0.first)
                            if (gap < minGap) out += Run(first = ids[i], second = ids[j], gap = gap, overlap = overlap)
                        }
                    }
                }
            }
            return out
        }

        fun render(
            source: String,
            spread: Boolean,
        ): String {
            val (r, _) = IbdLaneSpreadProbe.collecting(spread = spread) { VaultExampleRenderer.render(script = source) }
            r.error shouldBe null
            return r.svg!!
        }

        fun swapped(source: String): String {
            // part and port declaration order exchanged: the outcome may differ in bytes, the invariant must hold all the same
            val a = """        part(name = "versorgung", typeId = versorgung.id)"""
            val b = """        part(name = "erhitzer", typeId = erhitzer.id)"""
            val p1 = """        port(name = "leistung", typeId = portLeistung.id, direction = PortDirection.In)"""
            val p2 = """        port(name = "stell", typeId = portAnalog.id, direction = PortDirection.In)"""
            check(listOf(a, b, p1, p2).all { it in source })
            val t =
                source
                    .replace(a, "@@A")
                    .replace(b, "@@B")
                    .replace(p1, "@@P1")
                    .replace(p2, "@@P2")
            return t
                .replace("@@A", b)
                .replace("@@B", a)
                .replace("@@P1", p2)
                .replace("@@P2", p1)
        }

        test("without the lane fix I13 and I20 run on one line over their inner segment (the defect)") {
            val v = violations(render(source = script(), spread = false))
            println("[welle-t] without fix: " + v.joinToString { "${it.first} / ${it.second} gap=${it.gap} overlap=${it.overlap}" })
            v.any { it.first.endsWith("::I13") && it.second.endsWith("::I20") } shouldBe true
            v.first { it.first.endsWith("::I13") && it.second.endsWith("::I20") }.gap shouldBe 0.0
        }

        test("with the lane fix Doc 42 has no parallel pair closer than 16 px") {
            val svg = render(source = script(), spread = true)
            val v = violations(svg)
            println("[welle-t] with fix: violations=${v.size}")
            v shouldBe emptyList()
            // I13 and I20 still exist and still share no end point
            val edges = SvgGeometry.edgePolylines(svg)
            (edges.keys.any { it.endsWith("::I13") } && edges.keys.any { it.endsWith("::I20") }) shouldBe true
        }

        test("the invariant holds with exchanged part and port declaration order") {
            violations(render(source = swapped(script()), spread = true)) shouldBe emptyList()
        }

        test("the fix is deterministic") {
            render(source = script(), spread = true) shouldBe render(source = script(), spread = true)
        }
    })
