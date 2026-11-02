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
 * Notes N3 - formatted note text in the full pipeline: every text run stays inside its note, notes do not overlap, link
 * and markup injection arrives escaped, rendering is deterministic. Geometry and markup only (no font metrics).
 */
class NoteFormatSvgInvariantTest :
    FunSpec({
        fun fixture(name: String): String =
            NoteFormatSvgInvariantTest::class.java
                .getResourceAsStream("/note-kinds/$name.kuml.kts")!!
                .bufferedReader()
                .readText()

        // Test-only dark theme, as in NoteKindSvgInvariantTest (registering twice with the same name is harmless).
        // The renderer only loads the built-in themes while the registry is empty, so load them first.
        if (ThemeRegistry.names().isEmpty()) ThemeRegistry.loadFromClasspath()
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

        val formatFixtures = listOf("uml-class-format", "sysml2-bdd-format", "c4-container-format", "bpmn-format")

        class Note(
            val node: SvgNode,
            val rect: SvgRect,
            val bracket: Boolean,
        )

        fun notes(svg: String): List<Note> =
            SvgGeometry.nodes(svg).mapNotNull { n ->
                val dog =
                    Regex(
                        """d="M 0 0 L ([-0-9.]+) 0 L ([-0-9.]+) ([-0-9.]+) L ([-0-9.]+) ([-0-9.]+) L 0 ([-0-9.]+) Z"""",
                    ).find(n.body)
                val bracket = Regex("""<rect width="([-0-9.]+)" height="([-0-9.]+)" class="kuml-bpmn-text-annotation-area"""").find(n.body)
                when {
                    dog != null && Regex("""class="kuml-comment[ "]""").containsMatchIn(n.body) ->
                        Note(n, SvgRect(n.tx, n.ty, dog.groupValues[2].toDouble(), dog.groupValues[5].toDouble()), false)
                    bracket != null ->
                        Note(
                            n,
                            SvgRect(n.tx, n.ty, bracket.groupValues[1].toDouble(), bracket.groupValues[2].toDouble()),
                            true,
                        )
                    else -> null
                }
            }

        fun unescape(s: String) =
            s
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&")

        /** End x of one formatted line: the x of its last positioned tspan plus the width of the runs that follow it. */
        fun lineEnd(line: String): Double {
            val xs = Regex("""<tspan x="([-0-9.]+)"""").findAll(line).toList()
            val last = xs.last()
            var x = last.groupValues[1].toDouble()
            var rest = line.substring(last.range.last + 1)
            rest = rest.substring(rest.indexOf('>') + 1)
            val stack = ArrayList<Double>()
            for (tok in Regex("""<[^>]+>|[^<]+""").findAll(rest).map { it.value }) {
                when {
                    tok.startsWith("</") -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                    tok.startsWith("<") -> {
                        if (!tok.endsWith("/>")) {
                            stack.add(if ("font-weight=\"bold\"" in tok || "monospace" in tok) 1.1 else 1.0)
                        }
                    }
                    else -> {
                        val factor = stack.fold(1.0) { a, f -> maxOf(a, f) }
                        x += unescape(tok).length * 6.6 * factor
                    }
                }
            }
            return x
        }

        fun formattedLines(note: Note): List<String> {
            val text =
                Regex(
                    """<text class="kuml-body"[^>]*>(.*?)</text>""",
                    RegexOption.DOT_MATCHES_ALL,
                ).find(note.node.body)!!.groupValues[1]
            return text.lines().map { it.trim() }.filter {
                it.startsWith("<tspan x=") &&
                    it.endsWith("</tspan>") &&
                    it.count { c -> c == '\n' } == 0
            }
        }

        for (name in formatFixtures) {
            test("$name: formatted notes exist and every text run stays inside its note") {
                for (theme in listOf("plain", "dark-test")) {
                    val ns = notes(svgOf(name, theme))
                    (ns.size >= 3) shouldBe true
                    val formatted =
                        ns.filter {
                            "font-weight=\"bold\"" in it.node.body ||
                                "•" in it.node.body ||
                                "font-style" in it.node.body ||
                                "<a href" in it.node.body
                        }
                    (formatted.size >= 2) shouldBe true
                    for (n in formatted) {
                        val limit = if (n.bracket) n.rect.w + 0.5 else n.rect.w - 8 + 0.5
                        for (line in formattedLines(n)) {
                            (lineEnd(line) <= limit) shouldBe true
                        }
                    }
                }
            }

            test("$name: notes do not overlap and keep 8 px apart") {
                val ns = notes(svgOf(name, "plain")).map { it.rect }
                for (i in ns.indices) {
                    for (j in i + 1 until ns.size) {
                        (ns[i].gapTo(ns[j]) >= 8.0 - 0.01) shouldBe true
                    }
                }
            }

            test("$name: rendering is deterministic") {
                VaultExampleRenderer.render(script = fixture(name), themeName = "plain").svg shouldBe svgOf(name, "plain")
            }

            test("$name: no markup or attribute injection reaches the SVG") {
                val svg = svgOf(name, "plain")
                svg shouldNotContain "<script"
                svg shouldNotContain "xlink"
                svg shouldNotContain "href=\"javascript"
                svg shouldNotContain "href=\"data:"
                svg shouldNotContain "href=\"vbscript"
                svg shouldNotContain "onmouseover=\""
                svg shouldNotContain "&amp;amp;"
                svg shouldNotContain "<b>"
                Regex("""<a href="([^"]*)"""").findAll(svg).forEach { m -> m.groupValues[1].startsWith("http") shouldBe true }
            }

            test("$name: a link is an underlined anchor and styles are plain presentation attributes") {
                val svg = svgOf(name, "plain")
                svg shouldContain "<a href=\"https://"
                svg shouldContain "text-decoration=\"underline\""
                svg shouldContain "font-weight=\"bold\""
            }
        }

        test("uml-class-format: rejected links stay visible and the quote in a url is escaped") {
            val svg = svgOf("uml-class-format", "plain")
            svg shouldContain "&lt;script&gt;alert(1)&lt;/script&gt;"
            svg shouldContain "[x](javascript:alert(1))"
            svg shouldContain "href=\"https://e.x/&quot;onmouseover=&quot;a\""
        }

        test("a note with markup characters but no markup is unchanged (uml-class fixture)") {
            val r = VaultExampleRenderer.render(script = fixture("uml-class"), themeName = "plain")
            r.error shouldBe null
            val svg = r.svg!!
            svg shouldContain "&lt;b&gt;soon&lt;/b&gt; &amp; carefully"
            notes(svg).none { "font-weight=\"" in it.node.body || "font-style=\"" in it.node.body } shouldBe true
        }

        test("the style block does not change with formatted notes") {
            fun style(svg: String) = Regex("""<style[^>]*>.*?</style>""", RegexOption.DOT_MATCHES_ALL).find(svg)?.value
            val plain = VaultExampleRenderer.render(script = fixture("uml-class"), themeName = "plain").svg!!
            val formatted = svgOf("uml-class-format", "plain")
            // Same tone classes appear in both, so the rules for the shared tones must be byte-equal.
            for (tone in listOf("warning", "danger", "info", "todo")) {
                val rule = Regex("""\.kuml-note-tone-$tone \{[^}]*\}""")
                rule.find(plain)?.value shouldBe rule.find(formatted)?.value
            }
            (style(plain) != null) shouldBe true
        }
    })
