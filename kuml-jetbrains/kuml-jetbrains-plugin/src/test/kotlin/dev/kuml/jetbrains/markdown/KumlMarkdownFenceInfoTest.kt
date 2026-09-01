package dev.kuml.jetbrains.markdown

import dev.kuml.jetbrains.KumlPreviewRenderer
import dev.kuml.jetbrains.preview.KumlDocPreviewCache
import dev.kuml.jetbrains.preview.KumlPreviewHtml
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class KumlMarkdownFenceInfoTest :
    FunSpec({
        test("isKumlFence matches kuml variations case-insensitively") {
            KumlMarkdownFenceInfo.isKumlFence("kuml") shouldBe true
            KumlMarkdownFenceInfo.isKumlFence("KUML") shouldBe true
            KumlMarkdownFenceInfo.isKumlFence("Kuml") shouldBe true
            KumlMarkdownFenceInfo.isKumlFence("kuml {theme=\"plain\"}") shouldBe true
            KumlMarkdownFenceInfo.isKumlFence("kuml theme=plain name=order") shouldBe true
            KumlMarkdownFenceInfo.isKumlFence("kuml\tname=diag") shouldBe true

            KumlMarkdownFenceInfo.isKumlFence("kotlin") shouldBe false
            KumlMarkdownFenceInfo.isKumlFence("java") shouldBe false
            KumlMarkdownFenceInfo.isKumlFence("kuml-custom") shouldBe false
            KumlMarkdownFenceInfo.isKumlFence("") shouldBe false
        }

        test("parseAttributes handles plain, braced, and key-value formats") {
            KumlMarkdownFenceInfo.parseAttributes("kuml") shouldBe emptyMap()
            KumlMarkdownFenceInfo.parseAttributes("kuml ") shouldBe emptyMap()

            val braced = KumlMarkdownFenceInfo.parseAttributes("""kuml {theme="plain" name="order-flow" width="800"}""")
            braced["theme"] shouldBe "plain"
            braced["name"] shouldBe "order-flow"
            braced["width"] shouldBe "800"

            val unquoted = KumlMarkdownFenceInfo.parseAttributes("kuml theme=elegant name=my_diagram width=1024")
            unquoted["theme"] shouldBe "elegant"
            unquoted["name"] shouldBe "my_diagram"
            unquoted["width"] shouldBe "1024"

            val mixed = KumlMarkdownFenceInfo.parseAttributes("""kuml name="auth flow" theme=playful""")
            mixed["name"] shouldBe "auth flow"
            mixed["theme"] shouldBe "playful"
        }

        test("sanitizeSvg strips script tags and event handlers") {
            val maliciousSvg =
                """
                <svg xmlns="http://www.w3.org/2000/svg">
                    <script>alert('xss')</script>
                    <script src="https://evil.com/payload.js"></script>
                    <rect width="100" height="100" onclick="alert(1)" onload="evil()" onmouseover="hack()"/>
                    <text>Valid diagram text</text>
                </svg>
                """.trimIndent()

            val sanitized = KumlPreviewHtml.sanitizeSvg(maliciousSvg)
            sanitized shouldNotContain "<script"
            sanitized shouldNotContain "alert('xss')"
            sanitized shouldNotContain "evil.com"
            sanitized shouldNotContain "onclick"
            sanitized shouldNotContain "onload"
            sanitized shouldNotContain "onmouseover"
            sanitized shouldContain "<rect width=\"100\" height=\"100\"/>"
            sanitized shouldContain "<text>Valid diagram text</text>"
        }

        test("render emits properly formatted SVG container on cache hit") {
            val source = "classDiagram { classOf(\"Order\") }"
            val theme = "elegant"
            val name = "order-domain"
            val key = KumlDocPreviewCache.computeKey(source, theme, name)
            val mockSvg = "<svg viewBox=\"0 0 100 100\"><text>Order</text></svg>"

            KumlDocPreviewCache.put(key, KumlPreviewRenderer.Outcome.Svg(mockSvg))

            val html = KumlMarkdownFenceHtml.render("kuml {theme=\"elegant\" name=\"order-domain\" width=600}", source)
            html shouldContain "class=\"kuml-diagram-container\""
            html shouldContain "data-kuml-name=\"order-domain\""
            html shouldContain "data-kuml-theme=\"elegant\""
            html shouldContain "max-width: 600px"
            html shouldContain "<text>Order</text>"
        }

        test("render emits error box on Failure outcome") {
            val source = "invalidDiagramDSL { }"
            val theme = "plain"
            val name = "broken-diag"
            val key = KumlDocPreviewCache.computeKey(source, theme, name)

            KumlDocPreviewCache.put(
                key,
                KumlPreviewRenderer.Outcome.Failure("Syntax error at line 1: Unresolved reference 'invalidDiagramDSL'"),
            )

            val html = KumlMarkdownFenceHtml.render("kuml theme=plain name=broken-diag", source)
            html shouldContain "class=\"kuml-diagram-error\""
            html shouldContain "kUML Diagram Error (broken-diag)"
            html shouldContain "Unresolved reference &#39;invalidDiagramDSL&#39;"
        }

        test("render handles empty diagram input gracefully") {
            val html = KumlMarkdownFenceHtml.render("kuml", "")
            html shouldContain "class=\"kuml-diagram-empty\""
            html shouldContain "(Empty kUML diagram)"
        }

        test("KumlMarkdownCodeFenceLanguageProvider maps kuml to Kotlin") {
            val langProvider = KumlMarkdownCodeFenceLanguageProvider()
            val kotlinLang = langProvider.getLanguageByInfoString("kuml")
            kotlinLang.shouldNotBeNull()
            kotlinLang.id.lowercase() shouldBe "kotlin"

            val kotlinLangWithAttrs = langProvider.getLanguageByInfoString("kuml {theme=\"plain\"}")
            kotlinLangWithAttrs.shouldNotBeNull()
            kotlinLangWithAttrs.id.lowercase() shouldBe "kotlin"

            langProvider.getLanguageByInfoString("python") shouldBe null
        }

        test("resolveFence trusts the ordinal when the browser's source matches the PSI fence there") {
            val fences =
                listOf(
                    "kuml theme=plain name=order" to "classDiagram { classOf(\"Order\") }",
                    "kuml" to "classDiagram { classOf(\"Invoice\") }",
                )
            KumlMarkdownFenceInfo.resolveFence(fences, 0, "classDiagram { classOf(\"Order\") }") shouldBe fences[0]
            KumlMarkdownFenceInfo.resolveFence(fences, 1, "classDiagram { classOf(\"Invoice\") }") shouldBe fences[1]
        }

        test("resolveFence recovers by content when the ordinal is stale (JS/PSI ordinal drift)") {
            // Simulates Fehlerszenario A from the MAJOR finding this closes: the browser
            // skipped an earlier (still being typed / misclassified) fence, so its
            // ordinal for the Invoice fence (the SECOND real kuml fence in the document)
            // is 0 instead of 1 — but it still sends the Invoice fence's actual source.
            val fences =
                listOf(
                    "kuml theme=plain name=order" to "classDiagram { classOf(\"Order\") }",
                    "kuml" to "classDiagram { classOf(\"Invoice\") }",
                )
            val resolved = KumlMarkdownFenceInfo.resolveFence(fences, 0, "classDiagram { classOf(\"Invoice\") }")
            resolved shouldBe fences[1]
        }

        test("resolveFence never returns a fence whose source differs from the browser's") {
            // Regression guard. Returning the positional guess here would hand the caller
            // a DIFFERENT fence's source, which KumlMarkdownFenceHtml.render then renders
            // — i.e. someone else's diagram under this fence. Reachable whenever the
            // preview DOM lags the PSI (mid-typing debounce), so the browser's source
            // legitimately matches no PSI fence yet.
            val fences = listOf("kuml" to "classDiagram { classOf(\"Order\") }")
            KumlMarkdownFenceInfo.resolveFence(fences, 0, "some stale source that matches nothing") shouldBe null
        }

        test("resolveFence never mixes one fence's info string with another fence's source") {
            val fences =
                listOf(
                    "kuml theme=plain name=order" to "classDiagram { classOf(\"Order\") }",
                    "kuml theme=dark name=invoice" to "classDiagram { classOf(\"Invoice\") }",
                )
            // Whatever it returns, the returned source must be the one asked about.
            listOf(
                "classDiagram { classOf(\"Order\") }",
                "classDiagram { classOf(\"Invoice\") }",
                "classDiagram { classOf(\"NotInTheDocumentYet\") }",
            ).forEach { browserSource ->
                (0..3).forEach { ordinal ->
                    val resolved = KumlMarkdownFenceInfo.resolveFence(fences, ordinal, browserSource)
                    if (resolved != null) {
                        resolved.second shouldBe browserSource
                    }
                }
            }
        }

        test("resolveFence returns null when the ordinal is out of range and no content matches") {
            val fences = listOf("kuml" to "classDiagram { classOf(\"Order\") }")
            KumlMarkdownFenceInfo.resolveFence(fences, 5, "unrelated source") shouldBe null
        }

        test("resolveFence returns null for an empty fence list") {
            KumlMarkdownFenceInfo.resolveFence(emptyList(), 0, "anything") shouldBe null
        }

        test("KumlMarkdownCodeFenceLanguageProvider declares completion lookup variant for kuml") {
            val langProvider = KumlMarkdownCodeFenceLanguageProvider()
            val method =
                langProvider::class.java.getMethod(
                    "getCompletionVariantsForInfoString",
                    com.intellij.codeInsight.completion.CompletionParameters::class.java,
                )
            method.shouldNotBeNull()
            method.returnType shouldBe List::class.java
        }
    })
