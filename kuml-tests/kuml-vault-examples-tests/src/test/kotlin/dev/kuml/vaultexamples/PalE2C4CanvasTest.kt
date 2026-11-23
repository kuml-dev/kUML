@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/** PAL wave E2 (16b): the C4 canvas keeps a 24 px margin around the content (notes included), measured on the SVG. */
class PalE2C4CanvasTest :
    FunSpec({
        fun render(script: String): String {
            val r = VaultExampleRenderer.render(script = script)
            r.error shouldBe null
            return r.svg!!
        }

        /** Content bounding box of the node groups (nodes and notes) in absolute SVG coordinates. */
        fun contentBox(svg: String): DoubleArray {
            var x0 = Double.MAX_VALUE
            var y0 = Double.MAX_VALUE
            var x1 = -Double.MAX_VALUE
            var y1 = -Double.MAX_VALUE
            for (n in SvgGeometry.nodes(svg)) {
                val path = Regex("""<path d="([^"]+)" class="kuml-comment""").find(n.body)
                val rect = Regex("""<rect[^>]*?width="([-0-9.]+)" height="([-0-9.]+)"""").find(n.body)
                val (w, h) =
                    when {
                        path != null -> {
                            val v = SvgGeometry.numbers(path.groupValues[1])
                            (v.filterIndexed { i, _ -> i % 2 == 0 }.max()) to (v.filterIndexed { i, _ -> i % 2 == 1 }.max())
                        }
                        rect != null -> rect.groupValues[1].toDouble() to rect.groupValues[2].toDouble()
                        else -> continue
                    }
                x0 = minOf(x0, n.tx)
                y0 = minOf(y0, n.ty)
                x1 = maxOf(x1, n.tx + w)
                y1 = maxOf(y1, n.ty + h)
            }
            return doubleArrayOf(x0, y0, x1, y1)
        }

        test("context diagram with notes: viewBox minus content box is 48 px (24 px per side) horizontally") {
            val svg =
                render(
                    """
                    c4Model(name = "Margin") {
                        val a = softwareSystem(name = "Own")
                        val b = softwareSystem(name = "Foreign") { external = true }
                        val p = person(name = "Insider")
                        relationship(source = p, target = a) { description = "uses" }
                        relationship(source = a, target = b) { description = "calls" }
                        note(text = "Annahme links vom Eigensystem", a, id = "n1", kind = NoteKind.ASSUMPTION)
                        note(text = "Frage rechts am Fremdsystem", b, id = "n2", kind = NoteKind.QUESTION)
                        systemContextDiagram(name = "Margin") { include(a, b, p) }
                    }
                    """.trimIndent(),
                )
            SampleOutput.write(filename = "pal-e2/c4-margin.svg", content = svg)
            val vb = Regex("""viewBox="0 0 ([-0-9.]+) ([-0-9.]+)"""").find(svg)!!.groupValues
            val c = contentBox(svg)
            val horizontal = vb[1].toDouble() - (c[2] - c[0])
            horizontal shouldBe (48.0 plusOrMinus 1.0)
            val left = c[0]
            val right = vb[1].toDouble() - c[2]
            (left >= 23.0) shouldBe true
            (right >= 23.0) shouldBe true
            (vb[2].toDouble() - (c[3] - c[1]) >= 47.0) shouldBe true
        }
    })
