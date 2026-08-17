package dev.kuml.jetbrains.preview

import dev.kuml.jetbrains.KumlPreviewRenderer
import dev.kuml.jetbrains.asciidoc.KumlAsciidocHtmlRewriter
import dev.kuml.jetbrains.markdown.KumlMarkdownCodeFenceProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.intellij.markdown.IElementType
import org.intellij.markdown.ast.ASTNode
import java.nio.file.Files
import java.nio.file.Path

class KumlDocPreviewCacheSharingTest :
    FunSpec({
        beforeEach {
            KumlDocPreviewCache.clear()
        }

        fun createDummyNode(): ASTNode =
            object : ASTNode {
                override val endOffset: Int = 0
                override val startOffset: Int = 0
                override val type: IElementType = IElementType("DUMMY")
                override val children: List<ASTNode> = emptyList()
                override val parent: ASTNode? = null
            }

        test("Markdown and AsciiDoc share ONE KumlDocPreviewCache LRU") {
            val source = "classDiagram { classOf(\"Shared\") }"
            val theme = "plain"
            val name = "shared-diagram"
            val key = KumlDocPreviewCache.computeKey(source, theme, name)
            val cachedSvg = "<svg>CACHED_CONTENT</svg>"

            // 1. Seed the cache manually
            KumlDocPreviewCache.put(key, KumlPreviewRenderer.Outcome.Svg(cachedSvg))
            KumlDocPreviewCache.size() shouldBe 1

            // 2. Verify Markdown uses the cached entry
            val mdProvider = KumlMarkdownCodeFenceProvider()
            val mdHtml = mdProvider.generateHtml("kuml name=\"shared-diagram\" theme=plain", source, createDummyNode())
            mdHtml shouldContain cachedSvg

            // 3. Verify AsciiDoc uses the same cached entry
            val adoc =
                """
                [source,kuml,name="shared-diagram",theme=plain]
                ----
                $source
                ----
                """.trimIndent()
            val adocHtml =
                """
                <div class="listingblock">
                    <div class="content">
                        <pre class="highlight"><code class="language-kuml" data-lang="kuml">${KumlAsciidocHtmlRewriter.escapeForHtmlMatch(
                    source,
                )}</code></pre>
                    </div>
                </div>
                """.trimIndent()

            val baseDir: Path = Files.createTempDirectory("kuml-shared-cache")
            val rewrittenHtml = KumlAsciidocHtmlRewriter.rewrite(adocHtml, adoc, baseDir)

            rewrittenHtml shouldContain cachedSvg

            // Final check: cache size should still be 1 (no re-renders added new entries)
            KumlDocPreviewCache.size() shouldBe 1
        }
    })
