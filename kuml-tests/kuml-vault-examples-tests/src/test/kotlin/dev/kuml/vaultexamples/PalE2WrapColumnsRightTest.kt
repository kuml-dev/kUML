@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * PAL wave E2 (16c): `wrapColumns = n` limits a rank to n nodes also with `direction = Right` (a rank is a column
 * there). Measured on the SVG: nodes belong to one column when their x ranges overlap by at least half of the narrower one.
 * Regression guard: the wave could not reproduce a defect (the option works), see the wave report.
 */
class PalE2WrapColumnsRightTest :
    FunSpec({
        fun script(layoutBlock: String) =
            """
            c4Model(name = "Wrap") {
                val hub = softwareSystem(name = "Hub")
                val ts = (1..6).map { softwareSystem(name = "Target ${'$'}it") { external = true } }
                ts.forEach { relationship(source = hub, target = it) { description = "to" } }
                systemContextDiagram(name = "Wrap") {
                    include(hub, *ts.toTypedArray())
                    $layoutBlock
                }
            }
            """.trimIndent()

        fun widestColumn(svg: String): Int {
            val boxes =
                SvgGeometry.nodes(svg).filter { it.body.contains("Target") }.map {
                    val w = Regex("""<rect[^>]*?width="([-0-9.]+)"""").find(it.body)!!.groupValues[1].toDouble()
                    it.tx to it.tx + w
                }
            return boxes.maxOf { b ->
                boxes.count { o ->
                    val overlap = minOf(b.second, o.second) - maxOf(b.first, o.first)
                    overlap >= 0.5 * minOf(b.second - b.first, o.second - o.first)
                }
            }
        }

        test("direction Right without wrapColumns puts all targets in one column") {
            val r = VaultExampleRenderer.render(script = script("layout { direction = Right }"))
            r.error shouldBe null
            widestColumn(r.svg!!) shouldBe 6
        }

        test("direction Right with wrapColumns = 2 allows at most two targets per column") {
            val r = VaultExampleRenderer.render(script = script("layout { direction = Right; wrapColumns = 2 }"))
            r.error shouldBe null
            SampleOutput.write(filename = "pal-e2/wrap-right.svg", content = r.svg!!)
            (widestColumn(r.svg!!) <= 2) shouldBe true
        }
    })
