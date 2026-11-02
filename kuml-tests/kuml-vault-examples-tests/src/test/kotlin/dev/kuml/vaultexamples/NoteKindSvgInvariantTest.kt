@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import dev.kuml.renderer.theme.core.KumlColor
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.renderer.theme.core.KumlThemeProvider
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.renderer.theme.core.ThemeRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Notes N1 - visual invariants of the note kinds, measured on the rendered SVG of the full pipeline. Geometry and
 * markup only (no font metrics), so the checks hold on every machine.
 */
class NoteKindSvgInvariantTest :
    FunSpec({
        fun fixture(name: String): String =
            NoteKindSvgInvariantTest::class.java
                .getResourceAsStream("/note-kinds/$name.kuml.kts")!!
                .bufferedReader()
                .readText()

        // Test-only dark theme: there is no built-in one. Registered here only, never shipped.
        ThemeRegistry.register(
            object : KumlThemeProvider {
                override val name = "dark-test"

                override fun theme(): KumlTheme {
                    val b = PlainTheme()
                    return b.copy(
                        name = "dark-test",
                        colors =
                            b.colors.copy(
                                background = KumlColor(0x1E1E1E),
                                nodeFill = KumlColor(0x2A2A2A),
                                foreground = KumlColor(0xE6E6E6),
                                border = KumlColor(0x9AA4B2),
                                muted = KumlColor(0x9A9A9A),
                            ),
                    )
                }
            },
        )

        val cache = HashMap<String, String>()

        fun svgOf(
            name: String,
            theme: String,
        ): String =
            cache.getOrPut("$name/$theme") {
                val r = VaultExampleRenderer.render(script = fixture(name), themeName = theme)
                r.error shouldBe null
                r.svg!!.also { SampleOutput.write("note-kinds/$name-$theme.svg", it) }
            }

        val fixtures = listOf("uml-class", "sysml2-bdd")
        val labels =
            setOf(
                "Info",
                "Hint",
                "Warning",
                "Error",
                "Important",
                "Success",
                "Question",
                "Todo",
                "Decision",
                "Assumption",
                "Risk",
                "Example",
                "Reference",
                "Deprecated",
            )

        /** The note groups: the frame is the path with the `kuml-comment` class. */
        fun notes(svg: String) = SvgGeometry.nodes(svg).filter { Regex("""class="kuml-comment[ "]""").containsMatchIn(it.body) }

        for (name in fixtures) {
            test("$name: 15 notes, 14 with a symbol and an accent stripe, the plain one without") {
                val svg = svgOf(name, "plain")
                val ns = notes(svg)
                ns.size shouldBe 15
                ns.count { "kuml-note-icon" in it.body } shouldBe 14
                ns.count { "kuml-note-icon" !in it.body && "kuml-note-accent" !in it.body && "kuml-note-tone" !in it.body } shouldBe 1
                ns.count { "kuml-note-accent-" in it.body } shouldBe 14
            }

            test("$name: the symbol lies inside the note frame and the text starts right of it") {
                for (theme in listOf("plain", "dark-test")) {
                    for (n in notes(svgOf(name, theme)).filter { "kuml-note-icon" in it.body }) {
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
                        val textX = Regex("""<text class="kuml-body" x="([-0-9.]+)"""").find(n.body)!!.groupValues[1].toDouble()
                        (textX >= ix + size - 0.01) shouldBe true
                        // the text column is inside the frame as well
                        (textX < w) shouldBe true
                    }
                }
            }

            test("$name: the symbol names are the fixed ones, every symbol is evenodd, and rendering is deterministic") {
                val svg = svgOf(name, "plain")
                val found = Regex("""aria-label="([^"]*)"""").findAll(svg).map { it.groupValues[1] }.toSet()
                (found - labels).isEmpty() shouldBe true
                Regex("""class="kuml-note-icon"[^>]*>\s*<path d="[^"]+" fill-rule="evenodd"""").findAll(svg).count() shouldBe 14
                VaultExampleRenderer.render(script = fixture(name), themeName = "plain").svg shouldBe svg
            }

            test("$name: text with markup characters is escaped exactly once") {
                val svg = svgOf(name, "plain")
                svg shouldNotContain "<b>"
                svg shouldContain "&lt;b&gt;soon&lt;/b&gt;"
                svg shouldContain "&amp; carefully"
                svg shouldNotContain "&amp;amp;"
            }

            test("$name: tone rules are written for the used tones only, with distinct colours in light and dark") {
                val light = svgOf(name, "plain")
                val dark = svgOf(name, "dark-test")
                for (tone in listOf("info", "hint", "success", "warning", "danger", "important", "neutral")) {
                    light shouldContain ".kuml-note-tone-$tone {"
                    dark shouldContain ".kuml-note-tone-$tone {"
                }

                fun colorOf(
                    svg: String,
                    tone: String,
                ) = Regex("""\.kuml-note-tone-$tone \{ fill: (#[0-9A-F]{6});""").find(svg)!!.groupValues[1]
                colorOf(light, "danger") shouldBe "#C62828"
                colorOf(dark, "danger") shouldBe "#FF7B7B"
            }
        }

        test("a diagram with only plain notes has no kind markup and no tone rules") {
            val script =
                """
                classDiagram(name = "Plain") {
                    val a = classOf(name = "A")
                    comment(text = "just a note", a)
                }
                """.trimIndent()
            val svg = VaultExampleRenderer.render(script = script, themeName = "plain").svg!!
            svg shouldNotContain "kuml-note-icon"
            svg shouldNotContain "kuml-note-tone"
            svg shouldNotContain "kuml-note-accent"
            svg shouldNotContain "aria-label"
        }
    })
