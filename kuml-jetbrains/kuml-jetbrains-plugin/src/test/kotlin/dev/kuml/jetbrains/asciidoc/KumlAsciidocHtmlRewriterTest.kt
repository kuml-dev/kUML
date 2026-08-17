package dev.kuml.jetbrains.asciidoc

import dev.kuml.jetbrains.KumlPreviewRenderer
import dev.kuml.jetbrains.preview.KumlDocPreviewCache
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class KumlAsciidocHtmlRewriterTest :
    FunSpec({
        beforeEach {
            KumlDocPreviewCache.clear()
        }

        val baseDir: Path = Files.createTempDirectory("kuml-adoc-rewrite")

        test("listing with data-lang=kuml and valid DSL yields svg container") {
            val source = """classDiagram(name = "X") { classOf("A") }"""
            val theme = "kuml"
            val name = "asciidoc-diagram"
            val key = KumlDocPreviewCache.computeKey(source, theme, name)
            KumlDocPreviewCache.put(key, KumlPreviewRenderer.Outcome.Svg("<svg viewBox=\"0 0 10 10\"><text>X</text></svg>"))

            val adoc =
                """
                [source,kuml]
                ----
                classDiagram(name = "X") { classOf("A") }
                ----
                """.trimIndent()

            val html =
                """
                <div class="listingblock">
                <div class="content">
                <pre class="highlight"><code class="language-kuml" data-lang="kuml">classDiagram(name = "X") { classOf("A") }</code></pre>
                </div>
                </div>
                """.trimIndent()

            val out = KumlAsciidocHtmlRewriter.rewrite(html, adoc, baseDir)
            out shouldContain "<svg"
            out shouldContain "class=\"kuml-diagram-container\""
            out shouldNotContain "language-kuml"
        }

        test("unresolved macro paragraph HTML becomes error container mentioning path") {
            val adoc = "kuml::https://evil/x.kuml.kts[]\n"
            val html = """<div class="paragraph"><p>kuml::https://evil/x.kuml.kts[]</p></div>"""
            val out = KumlAsciidocHtmlRewriter.rewrite(html, adoc, baseDir)
            out shouldContain "class=\"kuml-diagram-error\""
            out shouldContain "Ungültiger oder nicht erlaubter Pfad"
        }

        test("missing macro file yields error box") {
            val adoc = "kuml::diagrams/missing.kuml.kts[]\n"
            val html = """<div class="paragraph"><p>kuml::diagrams/missing.kuml.kts[]</p></div>"""
            val out = KumlAsciidocHtmlRewriter.rewrite(html, adoc, baseDir)
            out shouldContain "class=\"kuml-diagram-error\""
            out shouldContain "missing.kuml.kts"
        }

        test("empty listing yields empty container") {
            val adoc =
                """
                [source,kuml]
                ----
                ----
                """.trimIndent()
            val html =
                """
                <div class="listingblock">
                <div class="content">
                <pre class="highlight"><code class="language-kuml" data-lang="kuml"></code></pre>
                </div>
                </div>
                """.trimIndent()
            val out = KumlAsciidocHtmlRewriter.rewrite(html, adoc, baseDir)
            out shouldContain "class=\"kuml-diagram-empty\""
            out shouldContain "(Empty kUML diagram)"
        }

        test("unmatched HTML is left unchanged") {
            val html = """<div class="paragraph"><p>Hello world</p></div>"""
            val adoc = "= Title\n\nHello world\n"
            val out = KumlAsciidocHtmlRewriter.rewrite(html, adoc, baseDir)
            out shouldBe html
        }

        test("readable macro file is rendered via cache") {
            val diagrams = baseDir.resolve("diagrams").createDirectories()
            val scriptFile = diagrams.resolve("login.kuml.kts")
            val scriptText = """classDiagram(name = "Login") { classOf("User") }"""
            scriptFile.writeText(scriptText)

            val theme = "kuml"
            val name = "diagrams/login.kuml.kts"
            val key = KumlDocPreviewCache.computeKey(scriptText, theme, name)
            KumlDocPreviewCache.put(key, KumlPreviewRenderer.Outcome.Svg("<svg><text>Login</text></svg>"))

            val adoc = "kuml::diagrams/login.kuml.kts[]\n"
            val html = """<div class="paragraph"><p>kuml::diagrams/login.kuml.kts[]</p></div>"""
            val out = KumlAsciidocHtmlRewriter.rewrite(html, adoc, baseDir)
            out shouldContain "class=\"kuml-diagram-container\""
            out shouldContain "<text>Login</text>"
        }

        test(
            "real AsciidoctorJ coderay output (plugin's own preview default) is matched and rendered",
        ) {
            // Captured verbatim from a real org.asciidoctor.Asciidoctor.convert() call using the
            // exact asciidoctorj-3.0.0.jar bundled in asciidoctor-intellij-plugin 0.43.6, with
            // Attributes.builder().sourceHighlighter("coderay") — which is what
            // org.asciidoc.intellij.AsciiDocWrapper hardcodes as the plugin's own preview default
            // (.sourceHighlighter("coderay@"), a soft default) when no explicit :source-highlighter:
            // is set. This is NOT a synthetic fixture.
            val source = """classDiagram(name = "X") { classOf("A") }"""
            val html =
                """<div class="listingblock">
<div class="content">
<pre class="CodeRay highlight"><code data-lang="kuml">classDiagram(name = &quot;X&quot;) { classOf(&quot;A&quot;) }</code></pre>
</div>
</div>"""
            val out =
                KumlAsciidocHtmlRewriter.replaceListingFragment(
                    html,
                    source,
                    "<div class=\"kuml-diagram-container\"><svg>REPLACED</svg></div>",
                )
            out shouldBe "<div class=\"kuml-diagram-container\"><svg>REPLACED</svg></div>"
        }

        test("content match without listingblock wrapper: <pre data-lang=\"kuml\"> ancestor fallback") {
            // NOTE: despite the "data-lang" html here, this is matched by generic Tier B
            // content-matching (find the literal, non-blank source text, then walk up to the
            // nearest pre/code ancestor) -- the data-lang attribute itself is never inspected
            // by that code path. Marker-specific matching (Tier E) only ever applies to a
            // BLANK source; see the "Tier E" tests below for that.
            val source = """classDiagram(name = "X") { classOf("A") }"""
            val theme = "kuml"
            val name = "asciidoc-diagram"
            KumlDocPreviewCache.put(
                KumlDocPreviewCache.computeKey(source, theme, name),
                KumlPreviewRenderer.Outcome.Svg("<svg>X</svg>"),
            )
            val adoc = "[source,kuml]\n----\n$source\n----"
            val html = """<pre data-lang="kuml">$source</pre>"""

            val out = KumlAsciidocHtmlRewriter.rewrite(html, adoc, baseDir)
            out shouldContain "class=\"kuml-diagram-container\""
            KumlDocPreviewCache.size() shouldBe 1
        }

        test("content match without listingblock wrapper: <code class=\"language-kuml\"> ancestor fallback") {
            // Same caveat as above: matched via generic Tier B content-matching, not by
            // inspecting the class="language-kuml" marker itself.
            val source = """classDiagram(name = "X") { classOf("A") }"""
            val theme = "kuml"
            val name = "asciidoc-diagram"
            KumlDocPreviewCache.put(
                KumlDocPreviewCache.computeKey(source, theme, name),
                KumlPreviewRenderer.Outcome.Svg("<svg>X</svg>"),
            )
            val adoc = "[source,kuml]\n----\n$source\n----"
            val html = """<code class="language-kuml">$source</code>"""

            val out = KumlAsciidocHtmlRewriter.rewrite(html, adoc, baseDir)
            out shouldContain "class=\"kuml-diagram-container\""
            KumlDocPreviewCache.size() shouldBe 1
        }

        test("Tier E blank-source marker: data-lang=\"kuml\" (no language-kuml class present)") {
            // Pins the marker-inspecting code path itself (replaceListingFragment's Tier E
            // regex), which only ever runs for a BLANK source. The html below deliberately
            // carries data-lang="kuml" WITHOUT any class="language-kuml", so this test would
            // fail if the data-lang alternative were removed from the Tier E regex.
            val adoc = "[source,kuml]\n----\n----"
            val html =
                """
                <div class="listingblock">
                <div class="content">
                <pre data-lang="kuml"></pre>
                </div>
                </div>
                """.trimIndent()

            val out = KumlAsciidocHtmlRewriter.rewrite(html, adoc, baseDir)
            out shouldContain "class=\"kuml-diagram-empty\""
            out shouldNotContain "data-lang"
        }

        test("Tier E blank-source marker: class=\"language-kuml\" (no data-lang attribute present)") {
            // Mirrors the test above for the other Tier E alternative: html deliberately
            // carries class="language-kuml" WITHOUT any data-lang="kuml", so this test would
            // fail if the language-kuml class alternative were removed from the Tier E regex.
            val adoc = "[source,kuml]\n----\n----"
            val html =
                """
                <div class="listingblock">
                <div class="content">
                <pre><code class="language-kuml"></code></pre>
                </div>
                </div>
                """.trimIndent()

            val out = KumlAsciidocHtmlRewriter.rewrite(html, adoc, baseDir)
            out shouldContain "class=\"kuml-diagram-empty\""
            out shouldNotContain "language-kuml"
        }

        test("highlighter variant: generic listingblock + escaped pre fallback") {
            val source = """classDiagram(name = "X") { classOf("Map<K, V> & More") }"""
            val escapedSource = KumlAsciidocHtmlRewriter.escapeForHtmlMatch(source)
            val theme = "kuml"
            val name = "asciidoc-diagram"
            KumlDocPreviewCache.put(
                KumlDocPreviewCache.computeKey(source, theme, name),
                KumlPreviewRenderer.Outcome.Svg("<svg>MAP</svg>"),
            )
            val adoc = "[source,kuml]\n----\n$source\n----"
            // No kuml markers, but inside listingblock
            val html =
                """
                <div class="listingblock">
                    <div class="content">
                        <pre>$escapedSource</pre>
                    </div>
                </div>
                """.trimIndent()

            val out = KumlAsciidocHtmlRewriter.rewrite(html, adoc, baseDir)
            out shouldContain "class=\"kuml-diagram-container\""
            out shouldNotContain escapedSource
            KumlDocPreviewCache.size() shouldBe 1
        }

        test("mixed success/failure in one page") {
            val diagrams = baseDir.resolve("mixed").createDirectories()
            val validMacro = diagrams.resolve("valid.kuml.kts")
            validMacro.writeText("classDiagram(name = \"Valid\") { classOf(\"Valid\") }")

            val adoc =
                """
                [source,kuml,name="listing-ok"]
                ----
                classDiagram(name = "Listing") { classOf("Listing") }
                ----

                kuml::mixed/valid.kuml.kts[name="macro-ok"]

                kuml::/absolute/path/outside[name="macro-rejected"]

                kuml::mixed/missing.kuml.kts[name="macro-missing"]
                """.trimIndent()

            val html =
                """
                <div class="listingblock"><pre class="highlight"><code class="language-kuml">classDiagram(name = "Listing") { classOf("Listing") }</code></pre></div>
                <div class="paragraph"><p>kuml::mixed/valid.kuml.kts[name="macro-ok"]</p></div>
                <div class="paragraph"><p>kuml::/absolute/path/outside[name="macro-rejected"]</p></div>
                <div class="paragraph"><p>kuml::mixed/missing.kuml.kts[name="macro-missing"]</p></div>
                <div class="paragraph"><p>Unrelated text remains.</p></div>
                """.trimIndent()

            // Seed cache for success cases
            KumlDocPreviewCache.put(
                KumlDocPreviewCache.computeKey("classDiagram(name = \"Listing\") { classOf(\"Listing\") }", "kuml", "listing-ok"),
                KumlPreviewRenderer.Outcome.Svg("<svg>LISTING</svg>"),
            )
            KumlDocPreviewCache.put(
                KumlDocPreviewCache.computeKey("classDiagram(name = \"Valid\") { classOf(\"Valid\") }", "kuml", "macro-ok"),
                KumlPreviewRenderer.Outcome.Svg("<svg>MACRO</svg>"),
            )

            val out = KumlAsciidocHtmlRewriter.rewrite(html, adoc, baseDir)

            // 1. Listing Success
            out shouldContain "<svg>LISTING</svg>"
            out shouldContain "data-kuml-name=\"listing-ok\""

            // 2. Macro Success
            out shouldContain "<svg>MACRO</svg>"
            out shouldContain "data-kuml-name=\"macro-ok\""

            // 3. Macro Rejected (path guard)
            out shouldContain "class=\"kuml-diagram-error\""
            out shouldContain "data-kuml-name=\"macro-rejected\""
            out shouldContain "Ungültiger oder nicht erlaubter Pfad"

            // 4. Macro Missing (file not found)
            out shouldContain "class=\"kuml-diagram-error\""
            out shouldContain "data-kuml-name=\"macro-missing\""
            out shouldContain "Datei nicht gefunden oder nicht lesbar"

            // Unrelated text
            out shouldContain "Unrelated text remains."

            // Hermeticity guard: only the two seeded entries — proves the real CLI was
            // never invoked (guard-rejected / file-not-found never reach the cache).
            KumlDocPreviewCache.size() shouldBe 2
        }

        test(
            "multiple listings: earlier block's rendered SVG containing later block's short " +
                "source text must not swallow the later block (silent non-render regression)",
        ) {
            // Regression test for the multi-listing match-selection bug: both listings share
            // the literal source "X", and the first listing's rendered SVG happens to contain
            // the literal text "X" too (exactly what a diagram would render for that source).
            // A naive "first occurrence only, give up on rejection" search finds "X" inside the
            // FIRST block's already-substituted SVG, gets rejected by the ancestor-chain guard
            // (the SVG's <text> sits inside a kuml-diagram-container div, not a listing
            // container) and then stops instead of continuing to the second block's own raw
            // listing HTML further down -- leaving it entirely unrendered.
            val theme = "kuml"
            KumlDocPreviewCache.put(
                KumlDocPreviewCache.computeKey("X", theme, "d1"),
                KumlPreviewRenderer.Outcome.Svg("<svg><text>X</text></svg>"),
            )
            KumlDocPreviewCache.put(
                KumlDocPreviewCache.computeKey("X", theme, "d2"),
                KumlPreviewRenderer.Outcome.Svg("<svg><text>D2</text></svg>"),
            )

            val adoc =
                """
                [source,kuml,name="d1"]
                ----
                X
                ----

                [source,kuml,name="d2"]
                ----
                X
                ----
                """.trimIndent()

            val html =
                """
                <div class="listingblock">
                <div class="content">
                <pre class="highlight"><code class="language-kuml" data-lang="kuml">X</code></pre>
                </div>
                </div>
                <div class="listingblock">
                <div class="content">
                <pre class="highlight"><code class="language-kuml" data-lang="kuml">X</code></pre>
                </div>
                </div>
                """.trimIndent()

            val out = KumlAsciidocHtmlRewriter.rewrite(html, adoc, baseDir)

            // Both blocks must be rendered -- no leftover raw markup for either.
            out shouldNotContain "language-kuml"
            out shouldContain "<svg><text>X</text></svg>"
            out shouldContain "data-kuml-name=\"d1\""
            out shouldContain "<svg><text>D2</text></svg>"
            out shouldContain "data-kuml-name=\"d2\""
        }

        test(
            "multiple listings: an earlier failed block's error message containing a later " +
                "block's source text must not corrupt the earlier block (content-corruption regression)",
        ) {
            // Regression test for the second, more severe failure mode: the FIRST listing fails
            // to render, and its error message (rendered into a real <pre> inside the error
            // container) happens to contain the literal token "Y". The SECOND listing's source
            // is exactly "Y". A naive search finds "Y" inside the first block's error <pre> --
            // which IS a real, genuine pre/code ancestor -- and would splice the second block's
            // SVG into the first block's error container, while leaving the second block's own
            // listing HTML unrendered. The ancestor-chain guard against `kuml-diagram-*`
            // containers must reject that match and keep searching for the second block's own
            // (unprocessed) listing HTML instead.
            val theme = "kuml"
            KumlDocPreviewCache.put(
                KumlDocPreviewCache.computeKey("BAD", theme, "d1"),
                KumlPreviewRenderer.Outcome.Failure("Error near token Y"),
            )
            KumlDocPreviewCache.put(
                KumlDocPreviewCache.computeKey("Y", theme, "d2"),
                KumlPreviewRenderer.Outcome.Svg("<svg><text>Y-OK</text></svg>"),
            )

            val adoc =
                """
                [source,kuml,name="d1"]
                ----
                BAD
                ----

                [source,kuml,name="d2"]
                ----
                Y
                ----
                """.trimIndent()

            val html =
                """
                <div class="listingblock">
                <div class="content">
                <pre class="highlight"><code class="language-kuml" data-lang="kuml">BAD</code></pre>
                </div>
                </div>
                <div class="listingblock">
                <div class="content">
                <pre class="highlight"><code class="language-kuml" data-lang="kuml">Y</code></pre>
                </div>
                </div>
                """.trimIndent()

            val out = KumlAsciidocHtmlRewriter.rewrite(html, adoc, baseDir)

            // The first block's error container and its exact message must be intact --
            // NOT overwritten by the second block's SVG.
            out shouldContain "class=\"kuml-diagram-error\""
            out shouldContain "data-kuml-name=\"d1\""
            out shouldContain "Error near token Y"

            // The second block must be rendered as its OWN SVG, in its own container.
            out shouldContain "<svg><text>Y-OK</text></svg>"
            out shouldContain "data-kuml-name=\"d2\""

            // No leftover raw markup for either block.
            out shouldNotContain "language-kuml"
        }

        test("empty listing with nothing between fences yields empty container") {
            val adoc = "[source,kuml]\n----\n----"
            val html =
                """
                <div class="listingblock">
                    <div class="content">
                        <pre class="highlight"><code class="language-kuml" data-lang="kuml"></code></pre>
                    </div>
                </div>
                """.trimIndent()

            val out = KumlAsciidocHtmlRewriter.rewrite(html, adoc, baseDir)
            out shouldContain "class=\"kuml-diagram-empty\""
            out shouldNotContain "kuml-diagram-error"
        }

        test("unmatched HTML no-op: structural mismatch returns original HTML") {
            val source = """classDiagram(name = "X") { classOf("A") }"""
            val theme = "kuml"
            val name = "asciidoc-diagram"
            KumlDocPreviewCache.put(
                KumlDocPreviewCache.computeKey(source, theme, name),
                KumlPreviewRenderer.Outcome.Svg("<svg>X</svg>"),
            )
            val adoc = "[source,kuml]\n----\n$source\n----"
            // Source exists but not in any recognizable container. Pins the guarantee that
            // a text match with no recognized code-block ancestor must not be rewritten.
            val html = "<span>$source</span>"

            val out = KumlAsciidocHtmlRewriter.rewrite(html, adoc, baseDir)
            out shouldBe html
            KumlDocPreviewCache.size() shouldBe 1
        }

        test("unmatched macro no-op: literal kuml:: not found") {
            val adoc = "kuml::missing.kuml.kts[]"
            val html = "<div>Something else entirely</div>"

            val out = KumlAsciidocHtmlRewriter.rewrite(html, adoc, baseDir)
            out shouldBe html
        }
    })
