@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * PAL wave 2C-b2b (A): pin labels of an activity and port labels of an IBD come from the label plan. Measured on the
 * rendered SVG only (`data-label-rect`, path points, node and pin rectangles, arrow polygons), halo not counted, so the
 * result does not depend on a font and holds on every machine.
 */
class Sysml2ActPinLabelInvariantTest :
    FunSpec({
        fun render(prefix: String): String {
            val ex = VaultExampleLoader.loadFromClasspath().first { it.fileName.startsWith(prefix) }
            val r = VaultExampleRenderer.render(script = ex.kumlScript, themeName = "plain")
            r.error shouldBe null
            SampleOutput.write(filename = "sysml2-b2b/${ex.sanitizedName}.svg", content = r.svg!!)
            return r.svg!!
        }

        test("example 49: pin labels and edge labels keep clear of lines, arrows, boxes, pins and each other") {
            val svg = render("49 ")
            val nodes = SvgGeometry.nodes(svg)
            // action boxes (rounded, rx = 14) and pin squares (10 x 10), absolute
            val boxes = LinkedHashMap<String, SvgRect>()
            val pins = ArrayList<SvgRect>()
            for (n in nodes) {
                Regex("""<rect width="([-0-9.]+)" height="([-0-9.]+)" rx="14"""").find(n.body)?.let {
                    boxes[n.id] = SvgRect(n.tx, n.ty, it.groupValues[1].toDouble(), it.groupValues[2].toDouble())
                }
                for (m in Regex("""<rect x="([-0-9.]+)" y="([-0-9.]+)" width="10" height="10"""").findAll(n.body)) {
                    pins += SvgRect(n.tx + m.groupValues[1].toDouble(), n.ty + m.groupValues[2].toDouble(), 10.0, 10.0)
                }
            }
            val pinLabels = SvgGeometry.labelRects(svg, "kuml-pin-label-group")
            val edgeLabels = SvgGeometry.labelRects(svg, "kuml-edge-label")
            val lines = SvgGeometry.edgePolylines(svg)
            val arrows = lines.keys.flatMap { SvgGeometry.arrowBoxes(svg, it) }
            boxes.size shouldBe 3
            pins.size shouldBe 4
            // the named labels of the plan: scrap at Recycle, both [Part], parts at Assemble
            pinLabels.map { it.first }.sorted() shouldBe
                listOf("Assemble#Input:parts", "MakeParts#Output:parts", "MakeParts#Output:scrap", "Recycle#Input:scrap")
            edgeLabels.size shouldBe 2

            val bad = ArrayList<String>()
            val all = pinLabels + edgeLabels
            for ((i, a) in all.withIndex()) {
                val (id, r) = a
                for ((eid, pts) in lines) if (SvgGeometry.distance(pts, r) < 2.0 - 0.01) bad += "$id is closer than 2 px to the line $eid"
                for (box in boxes.values) if (r.overlaps(box)) bad += "$id covers a box"
                for (p in pins) if (r.gapTo(p) < 2.0 - 0.01) bad += "$id is closer than 2 px to a pin"
                for (t in arrows) if (r.gapTo(t) < 2.0 - 0.01) bad += "$id is closer than 2 px to an arrow head"
                for (j in i + 1 until all.size) if (r.gapTo(all[j].second) < 2.0 - 0.01) bad += "$id is closer than 2 px to ${all[j].first}"
            }
            // a pin label stays within 12 px of its pin
            for ((id, r) in pinLabels) {
                (pins.minOf { it.gapTo(r) } <= 12.0) shouldBe true
                (id.isNotEmpty()) shouldBe true
            }
            bad.joinToString("\n") shouldBe ""
        }

        for (prefix in listOf("27 ", "54 ")) {
            test("example ${prefix.trim()}: port labels keep 4 to 12 px from their port and clear of connectors and boxes") {
                val svg = render(prefix)
                val nodes = SvgGeometry.nodes(svg).filter { !it.id.startsWith("conn:") }
                val boxes = ArrayList<SvgRect>()
                val ports = ArrayList<SvgRect>()
                val labels = ArrayList<SvgRect>()
                for (n in nodes) {
                    Regex("""<rect width="([-0-9.]+)" height="([-0-9.]+)"[^>]*class="kuml-class"""").find(n.body)?.let {
                        boxes += SvgRect(n.tx, n.ty, it.groupValues[1].toDouble(), it.groupValues[2].toDouble())
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
                    labels += SvgGeometry.labelRects(n.body, "kuml-port-label-group").map { it.second.translated(n.tx, n.ty) }
                }
                val lines = SvgGeometry.edgePolylines(svg).filterKeys { it.startsWith("conn:") }
                (labels.size >= 2) shouldBe true
                val bad = ArrayList<String>()
                for ((i, r) in labels.withIndex()) {
                    val gap = ports.minOf { it.gapTo(r) }
                    if (gap < 4.0 - 0.01 || gap > 12.0 + 0.01) bad += "label $i is $gap px from the nearest port"
                    for (b in boxes) if (r.overlaps(b)) bad += "label $i covers a box"
                    for ((eid, pts) in lines) if (SvgGeometry.distance(pts, r) < 2.0 - 0.01) bad += "label $i is closer than 2 px to $eid"
                    for (j in i + 1 until labels.size) {
                        if (r.gapTo(labels[j]) <
                            2.0 - 0.01
                        ) {
                            bad += "label $i is closer than 2 px to label $j"
                        }
                    }
                }
                bad.joinToString("\n") shouldBe ""
            }
        }
    })
