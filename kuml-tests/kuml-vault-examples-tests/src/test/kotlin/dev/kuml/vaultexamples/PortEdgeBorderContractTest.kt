@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import dev.kuml.layout.bridge.port.PortEdgeClamp
import dev.kuml.layout.bridge.port.PortFace
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Contract of the port / pin edge ends over the complete pipeline (script -> layout -> routing -> SVG): an edge that
 * ends at a SysML 2 port (IBD), an action pin (ACT) or a constraint pin (PAR) ends exactly on the border of that
 * square, runs perpendicular to the face and, with an arrowhead, has an end segment of at least one arrowhead length.
 *
 * Only path coordinates and square rectangles read from the rendered SVG are compared, never text metrics, so the
 * test is independent of the installed fonts. One oracle ([edgeEndViolations]) serves all three diagram families.
 */
class PortEdgeBorderContractTest :
    FunSpec({
        val cache = HashMap<String, List<EndCheckResult>>()

        fun checksOf(
            family: String,
            key: String,
            svgProvider: () -> String,
        ): List<EndCheckResult> =
            cache.getOrPut(key) {
                val svg = svgProvider()
                SampleOutput.write(filename = "port-edge-border/$key.svg", content = svg)
                checkSvg(family = family, svg = svg)
            }

        fun example(prefix: String): String {
            val ex = VaultExampleLoader.loadFromClasspath().first { it.fileName.startsWith(prefix) }
            val r = VaultExampleRenderer.render(script = ex.kumlScript)
            r.error shouldBe null
            return r.svg!!
        }

        fun script(src: String): String {
            val r = VaultExampleRenderer.render(script = src)
            r.error shouldBe null
            return r.svg!!
        }

        val ibd27 = { checksOf("IBD", "ibd27") { example("27 ") } }
        val ibd54 = { checksOf("IBD", "ibd54") { example("54 ") } }
        val ibdFlows = listOf("Down", "Right", "Up", "Left")
        val ibdSynthetic = { ibdFlows.flatMap { f -> checksOf("IBD", "ibdSynthetic$f") { script(ibdSyntheticScript(f)) } } }
        val act49 = { checksOf("ACT", "act49") { example("49 ") } }
        val actSynthetic = { checksOf("ACT", "actSynthetic") { script(ACT_SYNTHETIC) } }
        val par51 = { checksOf("PAR", "par51") { example("51 ") } }

        fun all(): List<EndCheckResult> = ibd27() + ibd54() + ibdSynthetic() + act49() + actSynthetic() + par51()

        fun assertClean(checks: List<EndCheckResult>) {
            checks.shouldNotBeEmpty()
            val bad =
                checks
                    .filter {
                        it.violations.isNotEmpty()
                    }.map { "${it.family} ${it.edgeId} ${it.role}/${it.face}: ${it.violations}" }
            bad.shouldBeEmpty()
        }

        test("oracle self test: a correct end passes, wrong ends are caught") {
            val rect = Sq(100.0, 50.0, 10.0, 10.0)
            // Left face of the square: border at x = 100, the edge comes from the left (outside).
            val good = edgeEndViolations(Pt(100.0, 55.0), Pt(70.0, 55.0), rect, PortFace.Left, hasArrow = true)
            good.shouldBeEmpty()
            // inside the square
            edgeEndViolations(Pt(103.0, 55.0), Pt(70.0, 55.0), rect, PortFace.Left, hasArrow = true).shouldNotBeEmpty()
            // outside the border (a gap)
            edgeEndViolations(Pt(97.0, 55.0), Pt(70.0, 55.0), rect, PortFace.Left, hasArrow = true).shouldNotBeEmpty()
            // oblique end segment
            edgeEndViolations(Pt(100.0, 55.0), Pt(80.0, 70.0), rect, PortFace.Left, hasArrow = true).shouldNotBeEmpty()
            // comes from inside the square (wrong direction)
            edgeEndViolations(Pt(100.0, 55.0), Pt(130.0, 55.0), rect, PortFace.Left, hasArrow = false).shouldNotBeEmpty()
            // end segment shorter than the arrowhead
            edgeEndViolations(Pt(100.0, 55.0), Pt(92.0, 55.0), rect, PortFace.Left, hasArrow = true).shouldNotBeEmpty()
            // ... but without an arrowhead the short segment is fine
            edgeEndViolations(Pt(100.0, 55.0), Pt(92.0, 55.0), rect, PortFace.Left, hasArrow = false).shouldBeEmpty()
            // beside the face extent
            edgeEndViolations(Pt(100.0, 80.0), Pt(70.0, 80.0), rect, PortFace.Left, hasArrow = false).shouldNotBeEmpty()
            // all four faces accept a correct end
            edgeEndViolations(Pt(105.0, 50.0), Pt(105.0, 20.0), rect, PortFace.Top, hasArrow = true).shouldBeEmpty()
            edgeEndViolations(Pt(105.0, 60.0), Pt(105.0, 90.0), rect, PortFace.Bottom, hasArrow = true).shouldBeEmpty()
            edgeEndViolations(Pt(110.0, 55.0), Pt(140.0, 55.0), rect, PortFace.Right, hasArrow = true).shouldBeEmpty()
        }

        test("IBD: connectors end on the port border, perpendicular (27, 54, synthetic all-faces script)") {
            assertClean(ibd27())
            assertClean(ibd54())
            assertClean(ibdSynthetic())
        }

        test("ACT: object flows end on the pin border, perpendicular, arrowhead stub (49, synthetic all-faces script)") {
            assertClean(act49())
            assertClean(actSynthetic())
        }

        test("PAR: bindings end on the constraint pin border, perpendicular (51)") {
            assertClean(par51())
        }

        test("every pin / port end of the examples was checked: no end silently skipped") {
            // two ends per edge: 27 has 3 connectors, 54 one, 49 two flows, the synthetic scripts 5 and 4
            ibd27().size shouldBe 6
            ibd54().size shouldBe 2
            ibdSynthetic().size shouldBe 10 * ibdFlows.size
            act49().size shouldBe 4
            actSynthetic().size shouldBe 8
            // 51 has 9 bindings = 18 ends; the 4 ends at Vehicle attributes are no pins and are not checked
            par51().size shouldBe 14
        }

        test("coverage: faces x source/target actually exercised, not only claimed") {
            val covered = all().map { Triple(it.family, it.role, it.face) }.toSet()
            val required = mutableSetOf<Triple<String, String, PortFace>>()
            for (role in listOf("source", "target")) {
                for (face in PortFace.values()) {
                    required += Triple("ACT", role, face)
                    required += Triple("IBD", role, face)
                }
            }
            // PAR pins sit left (in) and right (out): source ends leave a pin to the right, target ends enter from the left.
            required += Triple("PAR", "source", PortFace.Right)
            required += Triple("PAR", "target", PortFace.Left)
            val missing = required - covered
            withClue("covered=$covered") { missing.shouldBeEmpty() }
        }
    })

private data class Pt(
    val x: Double,
    val y: Double,
)

private data class Sq(
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
)

/**
 * The tolerance for "lies on the border": [PortEdgeClamp.ON_BORDER_TOL_PX] plus 0.01 px for the two-decimal rounding of
 * the SVG coordinates (node translate is written with three, pin and path coordinates with at most two decimals, so the
 * absolute coordinates of a square border and a path end can differ by up to 0.01 px although the model coordinates are
 * identical).
 */
private const val SVG_ROUNDING_PX = 0.01
private val ON_BORDER_TOL = PortEdgeClamp.ON_BORDER_TOL_PX.toDouble() + SVG_ROUNDING_PX

/** An end counts as a pin end when it lies within this distance of a pin square (squares are 8 to 12 px wide). */
private const val PIN_END_REACH_PX = 10.0
private val ARROW_LEN = PortEdgeClamp.ARROW_LEN_PX.toDouble()

/** Shared oracle: the rules (a) to (d) of one edge end; returns the violated rules, empty if the end is correct. */
private fun edgeEndViolations(
    end: Pt,
    prev: Pt,
    rect: Sq,
    face: PortFace,
    hasArrow: Boolean,
): List<String> {
    val v = mutableListOf<String>()
    val (offBorder, inExtent) =
        when (face) {
            PortFace.Top -> (end.y - rect.y) to (end.x in rect.x - ON_BORDER_TOL..rect.x + rect.w + ON_BORDER_TOL)
            PortFace.Bottom -> (end.y - (rect.y + rect.h)) to (end.x in rect.x - ON_BORDER_TOL..rect.x + rect.w + ON_BORDER_TOL)
            PortFace.Left -> (end.x - rect.x) to (end.y in rect.y - ON_BORDER_TOL..rect.y + rect.h + ON_BORDER_TOL)
            PortFace.Right -> (end.x - (rect.x + rect.w)) to (end.y in rect.y - ON_BORDER_TOL..rect.y + rect.h + ON_BORDER_TOL)
        }
    if (abs(offBorder) > ON_BORDER_TOL) v += "(a) end is $offBorder px off the $face border"
    if (!inExtent) v += "(a) end lies beside the $face face"
    val n = PortEdgeClamp.normal(face)
    val dx = prev.x - end.x
    val dy = prev.y - end.y
    val along = dx * n.x + dy * n.y
    val across = abs(dx * n.y - dy * n.x)
    if (along <= 0.0) v += "(b) end segment does not arrive from outside the $face face"
    if (across > 0.5) v += "(b) end segment is not perpendicular to the $face face (off by $across px)"
    if (hasArrow && hypot(dx, dy) < ARROW_LEN - ON_BORDER_TOL) v += "(c) end segment ${hypot(dx, dy)} px is shorter than the arrowhead"
    if (min(abs(dx), abs(dy)) > 0.5) v += "(d) end segment is not axis parallel"
    return v
}

private val NUM = Regex("-?\\d+(?:\\.\\d+)?")

/** Path corner polyline; an arc corner is restored to the sharp corner (the arc contributes its end point only). */
private fun sharpPathPoints(d: String): List<Pt> {
    val pts = ArrayList<Pt>()
    val tokens = Regex("[MLAQC]|-?\\d+(?:\\.\\d+)?").findAll(d).map { it.value }.toList()
    var i = 0
    var cmd = 'M'
    while (i < tokens.size) {
        val t = tokens[i]
        if (t.length == 1 && t[0].isLetter()) {
            cmd = t[0]
            i++
            continue
        }
        when (cmd) {
            'M', 'L' -> {
                pts += Pt(tokens[i].toDouble(), tokens[i + 1].toDouble())
                i += 2
            }
            'A' -> {
                val after = Pt(tokens[i + 5].toDouble(), tokens[i + 6].toDouble())
                val before = pts.removeLast()
                val prev = pts.lastOrNull() ?: before
                val horizontalPrev = abs(before.x - prev.x) > abs(before.y - prev.y)
                pts += if (horizontalPrev) Pt(after.x, before.y) else Pt(before.x, after.y)
                i += 7
            }
            else -> i++
        }
    }
    return pts
}

private class PinNode(
    val id: String,
    val body: Sq,
    val pins: List<Sq>,
)

private fun distanceToRect(
    p: Pt,
    r: Sq,
): Double {
    val dx = max(max(r.x - p.x, 0.0), p.x - (r.x + r.w))
    val dy = max(max(r.y - p.y, 0.0), p.y - (r.y + r.h))
    return hypot(dx, dy)
}

private fun pinSquares(
    family: String,
    body: String,
    tx: Double,
    ty: Double,
): List<Sq> {
    val out = ArrayList<Sq>()
    val rect = Regex("""<rect x="(-?[0-9.]+)" y="(-?[0-9.]+)" width="([0-9.]+)" height="([0-9.]+)"([^>]*)/>""")
    for (m in rect.findAll(body)) {
        val attrs = m.groupValues[5]
        val isPin =
            when (family) {
                "IBD" -> attrs.contains("kuml-port")
                "PAR" -> attrs.contains("kuml-par-pin")
                else -> attrs.contains("fill=\"white\"") && attrs.contains("rx=\"2\"")
            }
        if (isPin) {
            out +=
                Sq(
                    tx + m.groupValues[1].toDouble(),
                    ty + m.groupValues[2].toDouble(),
                    m.groupValues[3].toDouble(),
                    m.groupValues[4].toDouble(),
                )
        }
    }
    return out
}

/**
 * Reads nodes (with their pins) and edges from [svg] and checks every edge end that belongs to a node with pins
 * against its nearest pin. The face is the face line of that pin the end is closest to, so an end that is inside the
 * square, beside it or far away is reported as a violation of rule (a) and never skipped.
 */
private fun checkSvg(
    family: String,
    svg: String,
): List<EndCheckResult> {
    val nodes =
        SvgGeometry.nodes(svg).mapNotNull { n ->
            val body = Regex("""<rect width="([0-9.]+)" height="([0-9.]+)"""").find(n.body) ?: return@mapNotNull null
            PinNode(
                id = n.id,
                body = Sq(n.tx, n.ty, body.groupValues[1].toDouble(), body.groupValues[2].toDouble()),
                pins = pinSquares(family = family, body = n.body, tx = n.tx, ty = n.ty),
            )
        }
    val allPins = nodes.flatMap { it.pins }
    val edgesStart = svg.indexOf("<g id=\"edges\"")
    val edgesBody = svg.substring(edgesStart)
    val header = Regex("""<g id="([^"]+)">\s*<path d="([^"]+)" class="kuml-edge[^"]*"[^>]*/>""")
    val heads = header.findAll(edgesBody).toList()
    val out = ArrayList<EndCheckResult>()
    for ((idx, h) in heads.withIndex()) {
        val segEnd = if (idx + 1 < heads.size) heads[idx + 1].range.first else edgesBody.length
        var seg = edgesBody.substring(h.range.last + 1, segEnd)
        val labelAt = seg.indexOf("<g class=\"kuml-edge-label")
        if (labelAt >= 0) seg = seg.substring(0, labelAt)
        val arrowVertices =
            Regex("""points="([^"]+)"""")
                .findAll(seg)
                .flatMap { m ->
                    NUM
                        .findAll(m.groupValues[1])
                        .map { it.value.toDouble() }
                        .chunked(2)
                        .filter { it.size == 2 }
                        .map { Pt(it[0], it[1]) }
                }.toList()
        val pts = sharpPathPoints(h.groupValues[2])
        if (pts.size < 2) continue
        val edgeId = h.groupValues[1].replace("&amp;", "&")
        for ((role, end, prev) in listOf(Triple("source", pts.first(), pts[1]), Triple("target", pts.last(), pts[pts.size - 2]))) {
            // The nearest pin of the whole diagram; ends of a node without pins (an attribute of a part) are no pin ends.
            val pin = allPins.minByOrNull { distanceToRect(p = end, r = it) } ?: continue
            if (distanceToRect(p = end, r = pin) > PIN_END_REACH_PX) continue
            val face =
                PortFace.values().minBy { f ->
                    when (f) {
                        PortFace.Top -> abs(end.y - pin.y) + outsideSpan(end.x, pin.x, pin.x + pin.w)
                        PortFace.Bottom -> abs(end.y - (pin.y + pin.h)) + outsideSpan(end.x, pin.x, pin.x + pin.w)
                        PortFace.Left -> abs(end.x - pin.x) + outsideSpan(end.y, pin.y, pin.y + pin.h)
                        PortFace.Right -> abs(end.x - (pin.x + pin.w)) + outsideSpan(end.y, pin.y, pin.y + pin.h)
                    }
                }
            val hasArrow = arrowVertices.any { hypot(it.x - end.x, it.y - end.y) <= 0.05 }
            out +=
                EndCheckResult(
                    family = family,
                    edgeId = edgeId,
                    role = role,
                    face = face,
                    violations = edgeEndViolations(end = end, prev = prev, rect = pin, face = face, hasArrow = hasArrow),
                )
        }
    }
    return out
}

private fun outsideSpan(
    v: Double,
    lo: Double,
    hi: Double,
): Double =
    if (v < lo) {
        lo - v
    } else if (v > hi) {
        v - hi
    } else {
        0.0
    }

/** Where an edge end sits, what it was checked against and which rules it broke. */
private data class EndCheckResult(
    val family: String,
    val edgeId: String,
    val role: String,
    val face: PortFace,
    val violations: List<String>,
)

private fun ibdSyntheticScript(flow: String): String =
    """
import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model(name = "PortFaces") {
    val link = connectionDef(name = "Link")
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
        connect(name = "ab", typeId = link.id, sourceEndId = "Sys::a::out1", targetEndId = "Sys::b::in1")
        connect(name = "ac", typeId = link.id, sourceEndId = "Sys::a::out2", targetEndId = "Sys::c::in1")
        connect(name = "bc", typeId = link.id, sourceEndId = "Sys::b::out1", targetEndId = "Sys::c::in2")
        connect(name = "cd", typeId = link.id, sourceEndId = "Sys::c::out1", targetEndId = "Sys::d::in1")
        connect(name = "bd", typeId = link.id, sourceEndId = "Sys::b::out1", targetEndId = "Sys::d::in2")
    }
    ibd(name = "Faces", owner = sys) {
        layout { direction = dev.kuml.core.model.layout.LayoutFlow.$flow }
    }
}
"""

private const val ACT_SYNTHETIC = """
import dev.kuml.sysml2.ActionPin
import dev.kuml.sysml2.PinDirection
import dev.kuml.sysml2.PinSide
import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model("PinCycle") {
    val a = actionDef(
        name = "A",
        pins = listOf(
            ActionPin(name = "o", typeId = "T", direction = PinDirection.Output, side = PinSide.Bottom),
            ActionPin(name = "i", typeId = "T", direction = PinDirection.Input, side = PinSide.Right),
        ),
    )
    val b = actionDef(
        name = "B",
        pins = listOf(
            ActionPin(name = "i", typeId = "T", direction = PinDirection.Input, side = PinSide.Top),
            ActionPin(name = "o", typeId = "T", direction = PinDirection.Output, side = PinSide.Right),
        ),
    )
    val c = actionDef(
        name = "C",
        pins = listOf(
            ActionPin(name = "i", typeId = "T", direction = PinDirection.Input, side = PinSide.Left),
            ActionPin(name = "o", typeId = "T", direction = PinDirection.Output, side = PinSide.Top),
        ),
    )
    val d = actionDef(
        name = "D",
        pins = listOf(
            ActionPin(name = "i", typeId = "T", direction = PinDirection.Input, side = PinSide.Bottom),
            ActionPin(name = "o", typeId = "T", direction = PinDirection.Output, side = PinSide.Left),
        ),
    )
    objectFlow(name = "ab", source = a, target = b, objectType = "T", sourcePin = "o", targetPin = "i")
    objectFlow(name = "bc", source = b, target = c, objectType = "T", sourcePin = "o", targetPin = "i")
    objectFlow(name = "cd", source = c, target = d, objectType = "T", sourcePin = "o", targetPin = "i")
    objectFlow(name = "da", source = d, target = a, objectType = "T", sourcePin = "o", targetPin = "i")
    actDiagram("Cycle") {
        include(a)
        include(b)
        include(c)
        include(d)
    }
}
"""
