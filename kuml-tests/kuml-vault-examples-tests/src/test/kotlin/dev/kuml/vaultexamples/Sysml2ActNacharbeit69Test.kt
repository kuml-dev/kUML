@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

/**
 * Nacharbeit 69 (ACT): wrapped / hard-broken action names, guard labels that avoid lane borders and the canvas edge,
 * control-node names on a free side. Measured on SVG geometry only (translate, rect sizes, path points,
 * `data-label-rect`), never on glyph widths, so the result is the same on every machine.
 */
class Sysml2ActNacharbeit69Test :
    FunSpec({
        fun render(
            script: String,
            out: String,
        ): String {
            val r = VaultExampleRenderer.render(script = script, themeName = "plain")
            r.error shouldBe null
            SampleOutput.write(filename = "sysml2-n69/$out.svg", content = r.svg!!)
            return r.svg!!
        }

        fun twoLanes(
            actionName: String,
            guard: String,
            showName: Boolean,
        ): String =
            """
            import dev.kuml.sysml2.dsl.sysml2Model

            sysml2Model(name = "N69") {
                val a = activityPartition(name = "Operator")
                val b = activityPartition(name = "Controller")
                val i = initialNode(partition = a)
                val x = actionDef(name = "Start", partition = a)
                val d = decisionNode(name = "check", partition = a, showName = $showName)
                val hold = actionDef(name = "$actionName", partition = b)
                val other = actionDef(name = "Other", partition = b)
                val f = finalNode(partition = b)
                controlFlow(name = "s", source = i, target = x)
                controlFlow(name = "t", source = x, target = d)
                controlFlow(name = "g1", source = d, target = hold, guard = "$guard")
                controlFlow(name = "g2", source = d, target = other, guard = "else")
                controlFlow(name = "e1", source = hold, target = f)
                controlFlow(name = "e2", source = other, target = f)
                actDiagram(name = "N69") {
                    include(node = i); include(node = x); include(node = d); include(node = hold); include(node = other); include(node = f)
                }
            }
            """.trimIndent()

        fun lanes(svg: String): List<SvgRect> =
            Regex(
                """<g id="activityPartition:([^"]+)" transform="translate\(([-0-9.]+),([-0-9.]+)\)">\s*<rect width="([-0-9.]+)" height="([-0-9.]+)" class="kuml-lane"""",
            ).findAll(svg)
                .map {
                    val v = it.groupValues
                    SvgRect(v[2].toDouble(), v[3].toDouble(), v[4].toDouble(), v[5].toDouble())
                }.toList()

        fun canvasWidth(svg: String): Double = Regex("""viewBox="[-0-9.]+ [-0-9.]+ ([-0-9.]+) """).find(svg)!!.groupValues[1].toDouble()

        test("a hard line break keeps its words apart and the lane stays narrow for a long name") {
            val long = "Calibrate the sensor array and verify all channel offsets twice"
            val svg =
                render(twoLanes(actionName = "Hold damper,\\nsuspend pressure control and mixing", guard = "ok", showName = false), "d1")
            svg shouldNotContain ",suspend"
            Regex("""<text class="kuml-title"[^>]*data-line="[0-9]"[^>]*>\s*Hold damper,\s*</text>""").containsMatchIn(svg) shouldBe true

            val svg2 = render(twoLanes(actionName = long, guard = "ok", showName = false), "d2")
            val node = SvgGeometry.nodes(svg2).first { Regex("""data-line="1"""").containsMatchIn(it.body) }
            val box = Regex("""<rect width="([-0-9.]+)" height="([-0-9.]+)" rx="14"""").find(node.body)!!
            Regex("""data-line="""").findAll(node.body).count() shouldBe 3
            (box.groupValues[1].toDouble() <= 248.0) shouldBe true
            // the lane holding the action is far narrower than one single 8.4 px per character line
            (lanes(svg2).maxOf { it.w } < long.length * 8.4) shouldBe true
        }

        test("a guard across lanes stays inside the canvas and keeps clear of lane borders and lines") {
            val svg = render(twoLanes(actionName = "Hold", guard = "pressure within band", showName = false), "b1")
            val labels = SvgGeometry.labelRects(svg, "kuml-edge-label")
            labels.size shouldBe 2
            val ls = lanes(svg)
            val lines = SvgGeometry.edgePolylines(svg)
            val width = canvasWidth(svg)
            for ((_, r) in labels) {
                (r.x >= 0.0 && r.right <= width) shouldBe true
                for (lane in ls) {
                    // no lane border (left / right edge) runs through the label
                    (lane.x > r.x && lane.x < r.right) shouldBe false
                    (lane.right > r.x && lane.right < r.right) shouldBe false
                }
                for ((_, pts) in lines) (SvgGeometry.distance(pts, r) > 0.0) shouldBe true
            }
        }

        test("a control node name moves to a free side and touches no edge") {
            val svg = render(twoLanes(actionName = "Hold", guard = "ok", showName = true), "b2")
            val m = Regex("""<text class="kuml-body kuml-control-name" x="([-0-9.]+)" y="([-0-9.]+)" text-anchor="(\w+)"""").find(svg)!!
            val x = m.groupValues[1].toDouble()
            val y = m.groupValues[2].toDouble()
            val w = "check".length * 7.0
            val left =
                when (m.groupValues[3]) {
                    "start" -> x
                    "end" -> x - w
                    else -> x - w / 2
                }
            val r = SvgRect(left, y - 12.0, w, 14.0)
            for ((_, pts) in SvgGeometry.edgePolylines(svg)) (SvgGeometry.distance(pts, r) > 0.0) shouldBe true
        }
    })
