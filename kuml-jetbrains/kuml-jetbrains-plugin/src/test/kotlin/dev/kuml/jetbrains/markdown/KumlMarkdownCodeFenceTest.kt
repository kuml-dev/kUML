package dev.kuml.jetbrains.markdown

import dev.kuml.jetbrains.KumlPreviewRenderer
import dev.kuml.jetbrains.preview.KumlDocPreviewCache
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.intellij.markdown.IElementType
import org.intellij.markdown.ast.ASTNode

class KumlMarkdownCodeFenceTest :
    FunSpec({
        val provider = KumlMarkdownCodeFenceProvider()

        test("isApplicable matches kuml variations case-insensitively") {
            provider.isApplicable("kuml") shouldBe true
            provider.isApplicable("KUML") shouldBe true
            provider.isApplicable("Kuml") shouldBe true
            provider.isApplicable("kuml {theme=\"plain\"}") shouldBe true
            provider.isApplicable("kuml theme=plain name=order") shouldBe true
            provider.isApplicable("kuml\tname=diag") shouldBe true

            provider.isApplicable("kotlin") shouldBe false
            provider.isApplicable("java") shouldBe false
            provider.isApplicable("kuml-custom") shouldBe false
            provider.isApplicable("") shouldBe false
        }

        test("parseAttributes handles plain, braced, and key-value formats") {
            KumlMarkdownCodeFenceProvider.parseAttributes("kuml") shouldBe emptyMap()
            KumlMarkdownCodeFenceProvider.parseAttributes("kuml ") shouldBe emptyMap()

            val braced = KumlMarkdownCodeFenceProvider.parseAttributes("""kuml {theme="plain" name="order-flow" width="800"}""")
            braced["theme"] shouldBe "plain"
            braced["name"] shouldBe "order-flow"
            braced["width"] shouldBe "800"

            val unquoted = KumlMarkdownCodeFenceProvider.parseAttributes("kuml theme=elegant name=my_diagram width=1024")
            unquoted["theme"] shouldBe "elegant"
            unquoted["name"] shouldBe "my_diagram"
            unquoted["width"] shouldBe "1024"

            val mixed = KumlMarkdownCodeFenceProvider.parseAttributes("""kuml name="auth flow" theme=playful""")
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

            val sanitized = KumlMarkdownCodeFenceProvider.sanitizeSvg(maliciousSvg)
            sanitized shouldNotContain "<script"
            sanitized shouldNotContain "alert('xss')"
            sanitized shouldNotContain "evil.com"
            sanitized shouldNotContain "onclick"
            sanitized shouldNotContain "onload"
            sanitized shouldNotContain "onmouseover"
            sanitized shouldContain "<rect width=\"100\" height=\"100\"/>"
            sanitized shouldContain "<text>Valid diagram text</text>"
        }

        fun createDummyNode(): ASTNode =
            object : ASTNode {
                override val endOffset: Int = 0
                override val startOffset: Int = 0
                override val type: IElementType = IElementType("DUMMY")
                override val children: List<ASTNode> = emptyList()
                override val parent: ASTNode? = null
            }

        test("generateHtml emits properly formatted SVG container on cache hit") {
            val dummyNode = createDummyNode()

            val source = "classDiagram { classOf(\"Order\") }"
            val theme = "elegant"
            val name = "order-domain"
            val key = KumlDocPreviewCache.computeKey(source, theme, name)
            val mockSvg = "<svg viewBox=\"0 0 100 100\"><text>Order</text></svg>"

            KumlDocPreviewCache.put(key, KumlPreviewRenderer.Outcome.Svg(mockSvg))

            val html = provider.generateHtml("kuml {theme=\"elegant\" name=\"order-domain\" width=600}", source, dummyNode)
            html shouldContain "class=\"kuml-diagram-container\""
            html shouldContain "data-kuml-name=\"order-domain\""
            html shouldContain "data-kuml-theme=\"elegant\""
            html shouldContain "max-width: 600px"
            html shouldContain "<text>Order</text>"
        }

        test("generateHtml emits error box on Failure outcome") {
            val dummyNode = createDummyNode()

            val source = "invalidDiagramDSL { }"
            val theme = "plain"
            val name = "broken-diag"
            val key = KumlDocPreviewCache.computeKey(source, theme, name)

            KumlDocPreviewCache.put(
                key,
                KumlPreviewRenderer.Outcome.Failure("Syntax error at line 1: Unresolved reference 'invalidDiagramDSL'"),
            )

            val html = provider.generateHtml("kuml theme=plain name=broken-diag", source, dummyNode)
            html shouldContain "class=\"kuml-diagram-error\""
            html shouldContain "kUML Diagram Error (broken-diag)"
            html shouldContain "Unresolved reference &#39;invalidDiagramDSL&#39;"
        }

        test("generateHtml handles empty diagram input gracefully") {
            val dummyNode = createDummyNode()

            val html = provider.generateHtml("kuml", "", dummyNode)
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
