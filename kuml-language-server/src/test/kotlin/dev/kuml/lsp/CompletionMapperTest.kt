package dev.kuml.lsp

import dev.kuml.langsupport.completion.KumlCompletionItems
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.InsertTextFormat

/**
 * Direct unit tests of the pure [CompletionMapper] mapping — no CLI, no wire.
 */
class CompletionMapperTest :
    FunSpec({

        test("toLsp maps all 38 items with the correct Kind per Group") {
            val mapped = KumlCompletionItems.ALL.map { it to CompletionMapper.toLsp(it) }
            mapped shouldHaveSize 38

            mapped.forEach { (item, lsp) ->
                val expectedKind =
                    when (item.group) {
                        KumlCompletionItems.Group.ENTRY -> CompletionItemKind.Function
                        KumlCompletionItems.Group.UML -> CompletionItemKind.Class
                        KumlCompletionItems.Group.SYSML2 -> CompletionItemKind.Class
                        KumlCompletionItems.Group.C4 -> CompletionItemKind.Class
                        KumlCompletionItems.Group.SHARED -> CompletionItemKind.Value
                    }
                lsp.kind shouldBe expectedKind
            }

            // Every mapped item in a group shares one Kind (locks in the per-Group decision).
            mapped
                .groupBy({ it.first.group }, { it.second.kind })
                .forEach { (_, kinds) -> kinds.toSet() shouldHaveSize 1 }
        }

        test("label/detail/labelDetails wiring") {
            val item = KumlCompletionItems.byName("classDiagram")
            item.shouldNotBeNull()
            val lsp = CompletionMapper.toLsp(item)

            lsp.label shouldBe "classDiagram"
            lsp.detail shouldBe "UML class diagram"
            lsp.labelDetails.shouldNotBeNull()
            lsp.labelDetails.detail shouldBe "(name: String) { … }"
        }

        test("block item carries Snippet format with a tab stop") {
            val item = KumlCompletionItems.byName("umlModel")
            item.shouldNotBeNull()
            val lsp = CompletionMapper.toLsp(item)

            lsp.insertTextFormat shouldBe InsertTextFormat.Snippet
            lsp.insertText shouldContain "\$1"
            lsp.insertText shouldEndWith "\$0"
            lsp.insertText shouldContain "\\}"
        }

        test("snippet: block body gets \$1, closing brace escaped, \$0 appended") {
            CompletionMapper.toSnippet("umlModel {\n    \n}") shouldBe "umlModel {\n    \$1\n\\}\$0"
        }

        test("snippet: empty string args become sequential tab stops") {
            CompletionMapper.toSnippet(
                "association(source = \"\", target = \"\")",
            ) shouldBe "association(source = \"\$1\", target = \"\$2\")\$0"
        }

        test("snippet: arg then block body number sequentially") {
            CompletionMapper.toSnippet(
                "c4Model(name = \"\") {\n    \n}",
            ) shouldBe "c4Model(name = \"\$1\") {\n    \$2\n\\}\$0"
        }

        test("snippet: literal \$ and } are escaped") {
            // Synthetic input — the real catalogue contains no such literals, this
            // is defensive-escaping coverage for the LSP snippet text grammar.
            CompletionMapper.toSnippet("a}b\$c") shouldBe "a\\}b\\\$c\$0"
        }

        test("resolve fills documentation for a known label") {
            val unresolved = CompletionItem("classOf")
            val resolved = CompletionMapper.resolve(unresolved)

            val doc = resolved.documentation
            doc.shouldNotBeNull()
            doc.right.shouldNotBeNull()
            doc.right.kind shouldBe "markdown"
            doc.right.value shouldContain "UML class definition"
        }

        test("resolve leaves an unknown label untouched") {
            val unresolved = CompletionItem("notAnItem")
            val resolved = CompletionMapper.resolve(unresolved)

            resolved.documentation.shouldBeNull()
        }

        test("every mapped item is Snippet with a trailing \$0 and at least one \$1") {
            KumlCompletionItems.ALL.forEach { item ->
                val lsp = CompletionMapper.toLsp(item)
                lsp.insertTextFormat shouldBe InsertTextFormat.Snippet
                lsp.insertText shouldEndWith "\$0"
                lsp.insertText shouldContain "\$1"
            }
        }
    })
