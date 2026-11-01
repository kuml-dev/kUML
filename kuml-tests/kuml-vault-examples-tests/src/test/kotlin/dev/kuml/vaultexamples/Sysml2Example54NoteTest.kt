@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * PAL wave 2C-b2b (B): in example 54 notes keep 8 px from every part, each anchor ends on the border of its target
 * (port square, connector or part), anchors on one connection have their own attach points, and the port label
 * `dcOut` keeps 4 px from its port. Measured on the rendered SVG.
 */
class Sysml2Example54NoteTest :
    FunSpec({
        val ex = VaultExampleLoader.loadFromClasspath().first { it.fileName.startsWith("54 ") }
        val svg = VaultExampleRenderer.render(script = ex.kumlScript, themeName = "plain").svg!!
        val nodes = SvgGeometry.nodes(svg)
        val parts = ArrayList<Pair<String, SvgRect>>()
        val notes = ArrayList<Pair<String, SvgRect>>()
        val ports = ArrayList<SvgRect>()
        for (n in nodes) {
            Regex("""<rect width="([-0-9.]+)" height="([-0-9.]+)"[^>]*class="kuml-class"""").find(n.body)?.let {
                parts += n.id to SvgRect(n.tx, n.ty, it.groupValues[1].toDouble(), it.groupValues[2].toDouble())
            }
            Regex("""<path d="M 0 0 L [-0-9.]+ 0 L ([-0-9.]+) [-0-9.]+ L [-0-9.]+ ([-0-9.]+)""").find(n.body)?.let {
                notes += n.id to SvgRect(n.tx, n.ty, it.groupValues[1].toDouble(), it.groupValues[2].toDouble())
            }
            for (m in Regex(
                """<rect x="([-0-9.]+)" y="([-0-9.]+)" width="([-0-9.]+)" height="([-0-9.]+)" class="kuml-port """,
            ).findAll(n.body)) {
                ports +=
                    SvgRect(
                        n.tx + m.groupValues[1].toDouble(),
                        n.ty + m.groupValues[2].toDouble(),
                        m.groupValues[3].toDouble(),
                        m.groupValues[4].toDouble(),
                    )
            }
        }
        val connector =
            SvgGeometry
                .edgePolylines(svg)
                .filterKeys { it.startsWith("conn:") }
                .values
                .single()
        val anchors =
            Regex(
                """<path d="([^"]+)" class="kuml-note-anchor"/>""",
            ).findAll(svg).map { SvgGeometry.pathPoints(it.groupValues[1]) }.toList() +
                Regex(
                    """<line x1="([-0-9.]+)" y1="([-0-9.]+)" x2="([-0-9.]+)" y2="([-0-9.]+)" class="kuml-note-anchor"/>""",
                ).findAll(svg)
                    .map {
                        val v = it.groupValues.drop(1).map(String::toDouble)
                        listOf(v[0] to v[1], v[2] to v[3])
                    }.toList()

        fun onBorder(
            p: Pair<Double, Double>,
            r: SvgRect,
        ): Boolean {
            val inX = p.first >= r.x - 0.5 && p.first <= r.right + 0.5
            val inY = p.second >= r.y - 0.5 && p.second <= r.bottom + 0.5
            val onV = inY && (kotlin.math.abs(p.first - r.x) <= 0.5 || kotlin.math.abs(p.first - r.right) <= 0.5)
            val onH = inX && (kotlin.math.abs(p.second - r.y) <= 0.5 || kotlin.math.abs(p.second - r.bottom) <= 0.5)
            return onV || onH
        }

        fun onConnector(p: Pair<Double, Double>): Boolean =
            (0 until connector.size - 1).any { i ->
                val a = connector[i]
                val b = connector[i + 1]
                val minX = minOf(a.first, b.first) - 0.5
                val maxX = maxOf(a.first, b.first) + 0.5
                val minY = minOf(a.second, b.second) - 0.5
                val maxY = maxOf(a.second, b.second) + 0.5
                p.first in minX..maxX && p.second in minY..maxY
            }

        test("three notes, three anchors, two parts, two ports") {
            notes.size shouldBe 3
            anchors.size shouldBe 3
            parts.size shouldBe 2
            ports.size shouldBe 2
        }

        test("a note keeps 8 px from every part and from every other note") {
            val bad = ArrayList<String>()
            for ((nid, n) in notes) {
                for ((pid, p) in parts) if (n.gapTo(p) < 8.0 - 0.01) bad += "note $nid is ${n.gapTo(p)} px from part $pid"
                for ((oid, o) in notes) if (oid != nid && n.gapTo(o) < 8.0 - 0.01) bad += "note $nid is closer than 8 px to note $oid"
            }
            bad.joinToString("\n") shouldBe ""
        }

        test("every anchor ends on the border of its target: a port square, the connector or a part, never in empty space") {
            for (a in anchors) {
                val end = a.last()
                val ok = ports.any { onBorder(end, it) } || onConnector(end) || parts.any { onBorder(end, it.second) }
                ok shouldBe true
            }
        }

        test("anchors on one connection have their own attach points (none on the same spot, none closer than 6 px)") {
            val ends = anchors.map { it.last() }
            for (i in ends.indices) {
                for (j in i + 1 until ends.size) {
                    (kotlin.math.hypot(ends[i].first - ends[j].first, ends[i].second - ends[j].second) >= 6.0) shouldBe true
                }
            }
            // no pair of segments of different anchors runs closer than 6 px side by side (the end points are exempt)
            for (i in anchors.indices) {
                for (j in i + 1 until anchors.size) {
                    val a = anchors[i]
                    val b = anchors[j]
                    for (s in 0 until a.size - 1) {
                        for (t in 0 until b.size - 1) {
                            val horizontalA = kotlin.math.abs(a[s].second - a[s + 1].second) < 0.01
                            val horizontalB = kotlin.math.abs(b[t].second - b[t + 1].second) < 0.01
                            if (horizontalA != horizontalB) continue
                            val perp =
                                if (horizontalA) {
                                    kotlin.math.abs(a[s].second - b[t].second)
                                } else {
                                    kotlin.math.abs(
                                        a[s].first - b[t].first,
                                    )
                                }
                            val loA = if (horizontalA) minOf(a[s].first, a[s + 1].first) else minOf(a[s].second, a[s + 1].second)
                            val hiA = if (horizontalA) maxOf(a[s].first, a[s + 1].first) else maxOf(a[s].second, a[s + 1].second)
                            val loB = if (horizontalB) minOf(b[t].first, b[t + 1].first) else minOf(b[t].second, b[t + 1].second)
                            val hiB = if (horizontalB) maxOf(b[t].first, b[t + 1].first) else maxOf(b[t].second, b[t + 1].second)
                            val overlap = minOf(hiA, hiB) - maxOf(loA, loB)
                            if (overlap > 6.0) (perp >= 6.0) shouldBe true
                        }
                    }
                }
            }
        }

        test("the port label dcOut keeps at least 4 px from its port") {
            val battery = nodes.first { it.id == "HybridVehicle::battery" }
            val label =
                SvgGeometry
                    .labelRects(battery.body, "kuml-port-label-group")
                    .single()
                    .second
                    .translated(battery.tx, battery.ty)
            val port = ports.minBy { it.gapTo(label) }
            (port.gapTo(label) >= 4.0 - 0.01) shouldBe true
        }
    })
