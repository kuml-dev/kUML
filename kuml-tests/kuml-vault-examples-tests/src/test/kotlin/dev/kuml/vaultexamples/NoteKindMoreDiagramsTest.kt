@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Notes N2 - note kinds in C4 and Service Blueprint diagrams: symbol, accent stripe and tone rules like in the other
 * notations, a plain note without any marker, and plain-only diagrams rendered without kind markup.
 */
class NoteKindMoreDiagramsTest :
    FunSpec({
        fun fixture(name: String): String =
            NoteKindMoreDiagramsTest::class.java
                .getResourceAsStream("/note-kinds/$name.kuml.kts")!!
                .bufferedReader()
                .readText()

        fun svgOf(script: String): String {
            val r = VaultExampleRenderer.render(script = script, themeName = "plain")
            r.error shouldBe null
            return r.svg!!
        }

        // fixture, notes, symbols (the plain NOTE has none)
        for ((name, total, kinded) in listOf(Triple("c4-container", 7, 6), Triple("blueprint", 6, 5))) {
            test("$name: $kinded of $total notes carry a symbol and an accent stripe, the plain one has neither") {
                val svg = svgOf(fixture(name))
                SvgGeometry
                    .nodes(svg)
                    .filter { Regex("""class="kuml-comment[ "]""").containsMatchIn(it.body) }
                    .let { ns ->
                        ns.size shouldBe total
                        ns.count { "kuml-note-icon" in it.body } shouldBe kinded
                        ns.count { "kuml-note-accent-" in it.body } shouldBe kinded
                        ns.count { "kuml-note-icon" !in it.body && "kuml-note-accent" !in it.body } shouldBe total - kinded
                    }
            }

            test("$name: tone rules exist for the used tones only and the text is escaped exactly once") {
                val svg = svgOf(fixture(name))
                svg shouldContain ".kuml-note-tone-warning {"
                svg shouldContain ".kuml-note-tone-danger {"
                svg shouldContain ".kuml-note-accent-warning {"
                svg shouldNotContain ".kuml-note-tone-important {"
                svg shouldContain "&lt;b&gt;always&lt;/b&gt;"
                svg shouldNotContain "&amp;amp;"
                svg shouldNotContain "<b>always"
            }

            test("$name: the symbol lies inside its note frame") {
                val svg = svgOf(fixture(name))
                for (n in SvgGeometry.nodes(svg).filter { "kuml-note-icon" in it.body }) {
                    val frame =
                        Regex(
                            """d="M 0 0 L ([-0-9.]+) 0 L ([-0-9.]+) ([-0-9.]+) L ([-0-9.]+) ([-0-9.]+) L 0 ([-0-9.]+) Z"""",
                        ).find(n.body)!!
                    val w = frame.groupValues[2].toDouble()
                    val h = frame.groupValues[5].toDouble()
                    val icon = Regex("""translate\(([-0-9.]+),([-0-9.]+)\) scale\(([-0-9.]+)\)""").find(n.body)!!
                    val ix = icon.groupValues[1].toDouble()
                    val iy = icon.groupValues[2].toDouble()
                    val size = 24 * icon.groupValues[3].toDouble()
                    (ix >= 0.0 && iy >= 0.0 && ix + size <= w && iy + size <= h) shouldBe true
                }
            }
        }

        test("a C4 diagram and a blueprint with plain notes only have no kind markup and no tone rules") {
            for (name in listOf("c4-container", "blueprint")) {
                val plain = fixture(name).replace(Regex("""kind = NoteKind\.[A-Z]+"""), "kind = NoteKind.NOTE")
                val svg = svgOf(plain)
                svg shouldNotContain "kuml-note-icon"
                svg shouldNotContain "kuml-note-tone"
                svg shouldNotContain "kuml-note-accent-"
            }
        }
    })
