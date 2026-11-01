package dev.kuml.vaultexamples

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.max

/**
 * PAL 2C-b1: in example 27 every connector ends on the border of its port square (distance <= 0.5 px, measured on the
 * SVG geometry only). The connectors carry no arrowhead, so the line end itself is the contact point.
 */
class Sysml2Example27PortDockingTest :
    FunSpec({
        data class R(
            val x: Float,
            val y: Float,
            val w: Float,
            val h: Float,
        )

        fun distance(
            px: Float,
            py: Float,
            r: R,
        ): Float {
            val dx = max(max(r.x - px, 0f), px - (r.x + r.w))
            val dy = max(max(r.y - py, 0f), py - (r.y + r.h))
            return if (dx == 0f && dy == 0f) {
                // inside or on the square: distance to the nearest border
                minOf(px - r.x, r.x + r.w - px, py - r.y, r.y + r.h - py)
            } else {
                kotlin.math.sqrt(dx * dx + dy * dy)
            }
        }

        test("every connector of example 27 ends on the border of a port square") {
            val ex = VaultExampleLoader.loadFromClasspath().first { it.fileName.startsWith("27 ") }
            val r = VaultExampleRenderer.render(script = ex.kumlScript)
            r.error shouldBe null
            val svg = r.svg!!

            // absolute port squares: <g id=".." transform="translate(x,y)"> ... <rect x y width height class="kuml-port ...">
            val ports = ArrayList<R>()
            for (g in Regex("""<g id="[^"]+" transform="translate\(([-0-9.]+),([-0-9.]+)\)">""").findAll(svg)) {
                val tx = g.groupValues[1].toFloat()
                val ty = g.groupValues[2].toFloat()
                // The node group now contains label groups (`</g>`), so it ends at the next node group instead.
                val end = svg.indexOf("<g id=", g.range.last + 1).let { if (it < 0) svg.length else it }
                val body = svg.substring(g.range.last, end)
                for (m in Regex(
                    """<rect x="([-0-9.]+)" y="([-0-9.]+)" width="([-0-9.]+)" height="([-0-9.]+)" class="kuml-port """,
                ).findAll(body)) {
                    ports +=
                        R(
                            tx + m.groupValues[1].toFloat(),
                            ty + m.groupValues[2].toFloat(),
                            m.groupValues[3].toFloat(),
                            m.groupValues[4].toFloat(),
                        )
                }
            }
            ports.size shouldBe 6

            val num = Regex("-?\\d+(?:\\.\\d+)?")
            val edgeIds = listOf("batteryToMotor", "motorToSplitter", "iceToSplitter")
            val used = HashSet<Int>()
            for (name in edgeIds) {
                val start = svg.indexOf("<g id=\"conn:HybridVehicle::$name\">")
                (start >= 0) shouldBe true
                val d = Regex("""<path d="([^"]+)"""").find(svg, start)!!.groupValues[1]
                val n = num.findAll(d).map { it.value.toFloat() }.toList()
                val ends = listOf(n[0] to n[1], n[n.size - 2] to n[n.size - 1])
                for ((x, y) in ends) {
                    val (idx, dist) = ports.mapIndexed { i, p -> i to abs(distance(x, y, p)) }.minBy { it.second }
                    (dist <= 0.5f) shouldBe true
                    used += idx
                }
            }
            used.size shouldBe 6
        }
    })
