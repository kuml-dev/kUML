@file:Suppress("RequireNamedArguments")

package dev.kuml.core.model.note

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.system.measureTimeMillis

private fun NoteDoc.spans(): List<NoteSpan> = blocks.flatMap { it.spans }

private fun NoteDoc.shownText(): String = blocks.joinToString("\n") { b -> b.spans.joinToString("") { it.text } }

class NoteMarkupTest :
    FunSpec(body = {

        context("plain text stays plain") {
            val plain =
                listOf(
                    "",
                    "Todo: add the discount rules, <b>soon</b> & carefully.",
                    "2*3*4",
                    "0..*",
                    "a * b",
                    "snake_case_name",
                    "List<T>",
                    "C:\\Users",
                    "e.g. 1.5",
                    "-x",
                    " - x",
                    "  1. x",
                    "1.x",
                    "[only brackets]",
                    "[x] (y)",
                    "1000. x",
                    "**unclosed",
                    "`unclosed",
                    "**bold te",
                    "a **b ** c",
                    "[](https://x.y)",
                )
            plain.forEach { text ->
                test("plain: $text") {
                    val doc = NoteMarkup.parse(text)
                    doc.isPlain shouldBe true
                    doc.shownText() shouldBe text
                }
            }
        }

        test("bold, italic, bold-italic and code") {
            NoteMarkup.parse("a **b** *c* ***d*** `e`").spans() shouldBe
                listOf(
                    NoteSpan("a "),
                    NoteSpan("b", bold = true),
                    NoteSpan(" "),
                    NoteSpan("c", italic = true),
                    NoteSpan(" "),
                    NoteSpan("d", bold = true, italic = true),
                    NoteSpan(" "),
                    NoteSpan("e", code = true),
                )
        }

        test("code content stays literal") {
            val doc = NoteMarkup.parse("`a *b* \\* c`")
            doc.spans() shouldBe listOf(NoteSpan("a *b* \\* c", code = true))
        }

        test("adjacent spans with the same style merge") {
            NoteMarkup.parse("**a\\*b**").spans() shouldBe listOf(NoteSpan("a*b", bold = true))
        }

        test("inner stars of a different length nest") {
            NoteMarkup.parse("**a *b* c**").spans() shouldBe
                listOf(NoteSpan("a ", bold = true), NoteSpan("b", bold = true, italic = true), NoteSpan(" c", bold = true))
        }

        test("links") {
            NoteMarkup.parse("see [kuml](https://kuml.dev) now").spans() shouldBe
                listOf(NoteSpan("see "), NoteSpan("kuml", href = "https://kuml.dev"), NoteSpan(" now"))
            NoteMarkup.parse("[a](http://x.y)").spans() shouldBe listOf(NoteSpan("a", href = "http://x.y"))
            NoteMarkup.parse("[a](HTTPS://X.Y)").spans() shouldBe listOf(NoteSpan("a", href = "HTTPS://X.Y"))
            NoteMarkup.parse("[**b**](https://x.y)").spans() shouldBe listOf(NoteSpan("b", bold = true, href = "https://x.y"))
        }

        test("code inside link text is plain and a nested link does not nest") {
            NoteMarkup.parse("[a `b` c](https://x.y)").spans() shouldBe listOf(NoteSpan("a `b` c", href = "https://x.y"))
            val nested = NoteMarkup.parse("[a [b](https://y.y) c](https://x.y)").spans()
            nested.count { it.href != null } shouldBe 1
            nested.joinToString("") { it.text } shouldBe "a [b c](https://x.y)"
        }

        context("rejected links are shown literally") {
            listOf(
                "javascript:alert(1)",
                "JaVaScRiPt:alert(1)",
                "data:text/html,x",
                "vbscript:x",
                "ftp://x.y",
                "mailto:a@b.c",
                "/relative/path",
                "",
                "https://a b",
                "https://",
                "https://x.y/\u202E",
                "https://münchen.de",
            ).forEach { url ->
                test("rejected: '$url'") {
                    val text = "[t]($url)"
                    val doc = NoteMarkup.parse(text)
                    doc.spans().none { it.href != null } shouldBe true
                    doc.shownText() shouldBe text
                }
            }
        }

        test("a quote in the url is allowed by the parser (escaped by the renderer)") {
            NoteMarkup
                .parse("[y](https://e.x/\"onmouseover=\"a)")
                .spans()
                .single()
                .href shouldBe "https://e.x/\"onmouseover=\"a"
        }

        test("bullet list") {
            val doc = NoteMarkup.parse("- a\n- **b**")
            doc.isPlain shouldBe false
            doc.blocks shouldBe
                listOf(
                    NoteListItem(false, 0, listOf(NoteSpan("a"))),
                    NoteListItem(false, 0, listOf(NoteSpan("b", bold = true))),
                )
        }

        test("numbered list counts on from the first number") {
            NoteMarkup.parse("1. a\n1. b\n1. c").blocks.map { (it as NoteListItem).number } shouldBe listOf(1, 2, 3)
            NoteMarkup.parse("3. a\n7. b").blocks.map { (it as NoteListItem).number } shouldBe listOf(3, 4)
        }

        test("numbering resets after a paragraph, a blank line or a bullet") {
            NoteMarkup.parse("1. a\nx\n5. b").blocks[2].let { (it as NoteListItem).number } shouldBe 5
            NoteMarkup.parse("1. a\n\n5. b").blocks[2].let { (it as NoteListItem).number } shouldBe 5
            NoteMarkup.parse("1. a\n- x\n5. b").blocks[2].let { (it as NoteListItem).number } shouldBe 5
        }

        test("escapes") {
            val doc = NoteMarkup.parse("\\*x\\*")
            doc.shownText() shouldBe "*x*"
            doc.isPlain shouldBe false
            NoteMarkup.parse("a\\b").isPlain shouldBe true
        }

        test("span budget: 300 bold words give exactly 256 formatted spans") {
            val doc = NoteMarkup.parse((1..300).joinToString(" ") { "**a**" })
            doc.spans().count { it.bold } shouldBe KumlNoteLimits.MAX_SPANS
            doc
                .spans()
                .filter { !it.bold }
                .last()
                .text
                .contains("**a**") shouldBe true
        }

        test("nesting limit: the innermost level stays literal") {
            val doc = NoteMarkup.parse("[***a *b* c***](https://x.y)")
            doc.spans() shouldBe listOf(NoteSpan("a *b* c", bold = true, italic = true, href = "https://x.y"))
        }

        test("url length") {
            val long = "https://x.y/" + "a".repeat(KumlNoteLimits.MAX_URL_LEN)
            NoteMarkup.isAllowedHref(long) shouldBe false
            NoteMarkup.parse("[t]($long)").spans().none { it.href != null } shouldBe true
            NoteMarkup.isAllowedHref("https://x") shouldBe true
        }

        test("text beyond the parse limit is never interpreted") {
            val text = "a".repeat(KumlNoteLimits.MAX_PARSE_CHARS) + " **late** and\n**next**"
            val doc = NoteMarkup.parse(text)
            doc.isPlain shouldBe true
            doc.shownText() shouldBe text
            doc.blocks shouldHaveSize 2
        }

        test("a note cut inside markup stays plain") {
            NoteMarkup.parse("x ".repeat(998) + "**bold te").isPlain shouldBe true
        }

        context("adversarial input stays fast") {
            listOf("*a ".repeat(2000), "[".repeat(2000), "`*".repeat(1000), "**a ".repeat(500), "[a](".repeat(500)).forEach { text ->
                test("no blow-up for ${text.take(6)}...") {
                    val ms = measureTimeMillis { NoteMarkup.parse(text) }
                    (ms < 30_000) shouldBe true
                }
            }
        }
    })
