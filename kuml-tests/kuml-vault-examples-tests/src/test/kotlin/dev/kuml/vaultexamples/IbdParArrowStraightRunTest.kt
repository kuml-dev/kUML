@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import dev.kuml.layout.bridge.port.PortEdgeClamp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.math.hypot
import kotlin.math.max

/**
 * Straight run of an arrowhead end at a SysML 2 port (IBD) or constraint pin (PAR): the part of the edge that is
 * straight between the port border and the first rounded corner must be at least one arrowhead long, otherwise the
 * arrowhead overlaps the corner arc.
 *
 * `PortEdgeBorderContractTest` rule (c) cannot see this: it restores every arc to its sharp corner first, so the end
 * segment measures the full stub even when the arc already starts one corner radius before the corner. This test reads
 * the raw path `d` instead and measures from the border to the START of the arc.
 *
 * Only path coordinates and rectangles read from the rendered SVG are used, never text metrics, so the test is
 * independent of the installed fonts.
 */
class IbdParArrowStraightRunTest :
    FunSpec({
        val minRun = PortEdgeClamp.ARROW_LEN_PX.toDouble() - TOL

        fun script(src: String): String {
            val r = VaultExampleRenderer.render(script = src)
            r.error shouldBe null
            return r.svg!!
        }

        val vaultRuns: List<EndRun> by lazy {
            VaultExampleLoader.loadFromClasspath().flatMap { ex ->
                val r = VaultExampleRenderer.render(script = ex.kumlScript)
                r.error shouldBe null
                val svg = r.svg!!
                val family =
                    when {
                        pinsOf(svg = svg, family = "IBD").isNotEmpty() -> "IBD"
                        pinsOf(svg = svg, family = "PAR").isNotEmpty() -> "PAR"
                        else -> null
                    }
                if (family == null) emptyList() else runsOf(example = ex.fileName.take(2), family = family, svg = svg)
            }
        }

        val flows = listOf("Down", "Right", "Up", "Left")
        val syntheticRuns: List<EndRun> by lazy {
            flows.flatMap { f ->
                listOf(
                    "plain" to ibdSyntheticScript(f),
                    "mixed" to flowsMixedScript(f),
                    "opposite" to flowsOppositeScript(f),
                    "straight" to flowsStraightScript(f),
                ).flatMap { (kind, src) ->
                    val svg = script(src)
                    SampleOutput.write(filename = "arrow-straight-run/syn-$kind-$f.svg", content = svg)
                    runsOf(example = "syn-$kind-$f", family = "IBD", svg = svg)
                }
            }
        }

        fun all(): List<EndRun> = vaultRuns + syntheticRuns

        fun arrowRuns(): List<EndRun> = all().filter { it.hasArrow }

        test("oracle self test: the metric measures the straight run up to the arc, not to the sharp corner") {
            // target end: arc ends at (106, 62), the line then runs 8 px to (106, 70): too short
            val short = endRuns("M 0 0 L 0 50 A 6 6 0 0 0 6 56 L 100 56 A 6 6 0 0 1 106 62 L 106 70")
            short.second shouldBe 8.0
            (short.second < minRun) shouldBe true
            // 12 px is exactly the arrowhead length: passes
            val ok = endRuns("M 0 0 L 0 50 A 6 6 0 0 0 6 56 L 100 56 A 6 6 0 0 1 106 62 L 106 74")
            ok.second shouldBe 12.0
            (ok.second < minRun) shouldBe false
            // source end: the first L ends where the arc starts
            val src = endRuns("M 0 0 L 0 8 A 6 6 0 0 0 6 14 L 20 14")
            src.first shouldBe 8.0
            (src.first < minRun) shouldBe true
            // the sharp corner would measure 14 px here, the arc start 8 px: the old rule (c) would not see it
            endRuns("M 0 0 L 0 14 L 20 14").first shouldBe 14.0
            // a straight connection has the full length at both ends
            endRuns("M 0 0 L 40 0") shouldBe (40.0 to 40.0)
            // a plain corner without an arc: the segment length
            endRuns("M 0 0 L 0 30 L 50 30").second shouldBe 50.0
        }

        test("measurement report: straight run of every port / pin end (printed and written to build/sample-output)") {
            val rows =
                all().joinToString("\n") {
                    "%-4s %-16s %-3s %-40s %-6s arrow=%-5s corners=%d run=%6.2f %s".format(
                        java.util.Locale.ROOT,
                        it.family,
                        it.example,
                        it.role.take(3),
                        it.edgeId.take(40),
                        it.face,
                        it.hasArrow,
                        it.corners,
                        it.run,
                        if (it.hasArrow) (if (it.run < minRun) "FAIL" else "PASS") else "-",
                    )
                }
            println("[arrow-straight-run]\n$rows")
            SampleOutput.write(filename = "arrow-straight-run/report.txt", content = rows)
            all().shouldNotBeEmpty()
        }

        test("IBD / PAR: every arrowhead end has a straight run of at least one arrowhead length") {
            val bad = arrowRuns().filter { it.run < minRun }.map { "${it.family} ${it.example} ${it.edgeId} ${it.role}: ${it.run} px" }
            bad.shouldBeEmpty()
        }

        test("every arrowhead end was measured: no end silently skipped") {
            arrowRuns().size shouldBe EXPECTED_ARROW_ENDS
            // the plain synthetic script and PAR bindings carry no arrowheads, the flow scripts do
            arrowRuns().none { it.example == "syn-plain-Down" } shouldBe true
        }

        test("coverage: the arrowhead ends include bent routes with a rounded corner next to the end") {
            arrowRuns().any { it.corners > 0 } shouldBe true
            arrowRuns().any { it.corners == 0 } shouldBe true
        }
    })

private const val TOL = 0.01

/** An end counts as a pin end when it lies within this distance of a pin square (squares are 8 to 12 px wide). */
private const val PIN_END_REACH = 10.0

/** Number of arrowhead ends at ports / pins the test inputs produce; a change means an input or the renderer changed. */
private const val EXPECTED_ARROW_ENDS = 32

private data class RunPt(
    val x: Double,
    val y: Double,
)

private data class RunSq(
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
)

private data class EndRun(
    val family: String,
    val example: String,
    val edgeId: String,
    val role: String,
    val face: String,
    val hasArrow: Boolean,
    /** Number of path corners (arcs) of the whole edge. */
    val corners: Int,
    /** Straight length from the port border to the start of the first arc (or the next corner). */
    val run: Double,
)

private val RUN_NUM = Regex("-?\\d+(?:\\.\\d+)?")

/** Straight run at the (source, target) end of the raw path [d]: border point to the end point of the neighbouring command. */
private fun endRuns(d: String): Pair<Double, Double> {
    val ends = ArrayList<RunPt>()
    for (m in Regex("([MLA])([^MLA]*)").findAll(d)) {
        val n = RUN_NUM.findAll(m.groupValues[2]).map { it.value.toDouble() }.toList()
        if (n.size >= 2) ends += RunPt(n[n.size - 2], n[n.size - 1])
    }
    val first = hypot(ends[1].x - ends[0].x, ends[1].y - ends[0].y)
    val last = hypot(ends[ends.size - 1].x - ends[ends.size - 2].x, ends[ends.size - 1].y - ends[ends.size - 2].y)
    return first to last
}

private fun pinsOf(
    svg: String,
    family: String,
): List<RunSq> {
    val out = ArrayList<RunSq>()
    val rect = Regex("""<rect x="(-?[0-9.]+)" y="(-?[0-9.]+)" width="([0-9.]+)" height="([0-9.]+)"([^>]*)/>""")
    val marker = if (family == "IBD") "kuml-port" else "kuml-par-pin"
    for (n in SvgGeometry.nodes(svg)) {
        for (m in rect.findAll(n.body)) {
            if (m.groupValues[5].contains(marker)) {
                out +=
                    RunSq(
                        x = n.tx + m.groupValues[1].toDouble(),
                        y = n.ty + m.groupValues[2].toDouble(),
                        w = m.groupValues[3].toDouble(),
                        h = m.groupValues[4].toDouble(),
                    )
            }
        }
    }
    return out
}

private fun distanceTo(
    p: RunPt,
    r: RunSq,
): Double {
    val dx = max(max(r.x - p.x, 0.0), p.x - (r.x + r.w))
    val dy = max(max(r.y - p.y, 0.0), p.y - (r.y + r.h))
    return hypot(dx, dy)
}

/** Which face line of [pin] the point [p] is closest to. */
private fun faceOf(
    p: RunPt,
    pin: RunSq,
): String {
    val cands =
        listOf(
            "Top" to kotlin.math.abs(p.y - pin.y),
            "Bottom" to kotlin.math.abs(p.y - (pin.y + pin.h)),
            "Left" to kotlin.math.abs(p.x - pin.x),
            "Right" to kotlin.math.abs(p.x - (pin.x + pin.w)),
        )
    return cands.minBy { it.second }.first
}

private fun runsOf(
    example: String,
    family: String,
    svg: String,
): List<EndRun> {
    val pins = pinsOf(svg = svg, family = family)
    val edgesStart = svg.indexOf("<g id=\"edges\"")
    if (edgesStart < 0 || pins.isEmpty()) return emptyList()
    val edgesBody = svg.substring(edgesStart)
    val header = Regex("""<g id="([^"]+)">\s*<path d="([^"]+)" class="kuml-edge[^"]*"[^>]*/>""")
    val heads = header.findAll(edgesBody).toList()
    val out = ArrayList<EndRun>()
    for ((idx, h) in heads.withIndex()) {
        val segEnd = if (idx + 1 < heads.size) heads[idx + 1].range.first else edgesBody.length
        var seg = edgesBody.substring(h.range.last + 1, segEnd)
        val labelAt = seg.indexOf("<g class=\"kuml-edge-label")
        if (labelAt >= 0) seg = seg.substring(0, labelAt)
        val arrowVertices =
            Regex("""points="([^"]+)"""")
                .findAll(seg)
                .flatMap { m ->
                    RUN_NUM
                        .findAll(m.groupValues[1])
                        .map { it.value.toDouble() }
                        .chunked(2)
                        .filter { it.size == 2 }
                        .map { RunPt(it[0], it[1]) }
                }.toList()
        val d = h.groupValues[2]
        val pts = SvgGeometry.pathPoints(d).map { RunPt(it.first, it.second) }
        if (pts.size < 2) continue
        val (srcRun, tgtRun) = endRuns(d)
        val corners = Regex("A").findAll(d).count()
        val edgeId = h.groupValues[1].replace("&amp;", "&")
        for ((role, end, run) in listOf(Triple("source", pts.first(), srcRun), Triple("target", pts.last(), tgtRun))) {
            // The nearest pin of the whole diagram; ends at an attribute of a part are no pin ends.
            val pin = pins.minByOrNull { distanceTo(p = end, r = it) } ?: continue
            if (distanceTo(p = end, r = pin) > PIN_END_REACH) continue
            out +=
                EndRun(
                    family = family,
                    example = example,
                    edgeId = edgeId,
                    role = role,
                    face = faceOf(p = end, pin = pin),
                    hasArrow = arrowVertices.any { hypot(it.x - end.x, it.y - end.y) <= 0.05 },
                    corners = corners,
                    run = run,
                )
        }
    }
    return out
}

private fun flowsMixedScript(flow: String): String =
    """
import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model(name = "ArrowFlowsMixed") {
    val item = attributeDef(name = "Item")
    val p = portDef(name = "P")
    val a = partDef(name = "A") { port(name = "out1", typeId = p.id); port(name = "out2", typeId = p.id) }
    val b = partDef(name = "B") { port(name = "in1", typeId = p.id); port(name = "out1", typeId = p.id) }
    val c = partDef(name = "C") { port(name = "in1", typeId = p.id); port(name = "in2", typeId = p.id); port(name = "out1", typeId = p.id) }
    val d = partDef(name = "D") { port(name = "in1", typeId = p.id); port(name = "in2", typeId = p.id) }
    val sys = partDef(name = "Sys") {
        part(name = "a", typeId = a.id)
        part(name = "b", typeId = b.id)
        part(name = "c", typeId = c.id)
        part(name = "d", typeId = d.id)
        flow(item = item.id, from = "a.out1", to = "b.in1")
        flow(item = item.id, from = "a.out2", to = "c.in1")
        flow(item = item.id, from = "b.out1", to = "c.in2")
        flow(item = item.id, from = "c.out1", to = "d.in1")
        flow(item = item.id, from = "b.out1", to = "d.in2")
    }
    ibd(name = "Mixed", owner = sys) {
        layout { direction = dev.kuml.core.model.layout.LayoutFlow.$flow }
    }
}
"""

/** Two parts exchanging an item in both directions: an arrowhead at a port of each part. */
private fun flowsOppositeScript(flow: String): String =
    """
import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model(name = "ArrowFlowsOpposite") {
    val item = attributeDef(name = "Item")
    val p = portDef(name = "P")
    val a = partDef(name = "A") { port(name = "out", typeId = p.id); port(name = "in", typeId = p.id) }
    val b = partDef(name = "B") { port(name = "out", typeId = p.id); port(name = "in", typeId = p.id) }
    val sys = partDef(name = "Sys") {
        part(name = "a", typeId = a.id)
        part(name = "b", typeId = b.id)
        flow(item = item.id, from = "a.out", to = "b.in")
        flow(item = item.id, from = "b.out", to = "a.in")
    }
    ibd(name = "Opposite", owner = sys) {
        layout { direction = dev.kuml.core.model.layout.LayoutFlow.$flow }
    }
}
"""

/** One flow between two single-port parts: the straight connection without a corner. */
private fun flowsStraightScript(flow: String): String =
    """
import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model(name = "ArrowFlowsStraight") {
    val item = attributeDef(name = "Item")
    val p = portDef(name = "P")
    val a = partDef(name = "A") { port(name = "out", typeId = p.id) }
    val b = partDef(name = "B") { port(name = "in", typeId = p.id) }
    val sys = partDef(name = "Sys") {
        part(name = "a", typeId = a.id)
        part(name = "b", typeId = b.id)
        flow(item = item.id, from = "a.out", to = "b.in")
    }
    ibd(name = "Straight", owner = sys) {
        layout { direction = dev.kuml.core.model.layout.LayoutFlow.$flow }
    }
}
"""
