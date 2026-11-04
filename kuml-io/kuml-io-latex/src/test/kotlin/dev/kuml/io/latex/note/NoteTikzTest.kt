package dev.kuml.io.latex.note

import dev.kuml.core.model.note.NoteKind
import dev.kuml.io.latex.SampleOutput
import dev.kuml.io.latex.escapeNoteText
import dev.kuml.layout.bridge.NoteLayout
import dev.kuml.layout.bridge.note.NoteSymbols
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/** Hostile note texts: LaTeX primitives, line breaks, comment starters, catcode tricks. */
internal val HOSTILE_TEXTS: List<String> =
    listOf(
        "\\write18{rm -rf /}",
        "\\input{/etc/passwd}",
        "\\include{x}",
        "\\immediate\\write18{id}",
        "\\catcode`\\^^M=13",
        "\\def\\x{y}\\x",
        "\\csname input\\endcsname",
        "^^5c^^5cinput",
        "}\\end{tikzpicture}\\input{x}",
        "%\n\\input{x}",
        "line one\r\n\\input{x}\u0000\u007f\t\\openout",
        "\u2028\\special{x}\u2029\\pdfliteral{q}",
        "\\href{http://evil}{x} \\url{http://evil}",
    )

/** Every `\macro` name in [tex]. */
internal fun macrosIn(tex: String): Set<String> = Regex("""\\([a-zA-Z@]+)""").findAll(tex).map { it.groupValues[1] }.toSet()

/** Macros that only the note code may add on top of an output without notes. */
private val NOTE_MACROS =
    setOf(
        "textbf",
        "textit",
        "texttt",
        "underline",
        "textbackslash",
        "textasciitilde",
        "textasciicircum",
        "textless",
        "textgreater",
        "guillemotleft",
        "guillemotright",
        "ldots",
        "textbullet",
        "definecolor",
        "fill",
        "draw",
        "node",
        "tikzset",
        "sffamily",
        "fontsize",
        "selectfont",
    )

private fun bracesBalanced(tex: String): Boolean {
    var depth = 0
    var i = 0
    while (i < tex.length) {
        val c = tex[i]
        if (c == '\\' && i + 1 < tex.length) {
            i += 2
            continue
        }
        if (c == '{') depth++
        if (c == '}') depth--
        if (depth < 0) return false
        i++
    }
    return depth == 0
}

class NoteTikzTest :
    StringSpec({

        "escapeNoteText escapes every special character and drops control characters" {
            escapeNoteText("# \$ % & _ { } ~ ^ \\") shouldBe
                "\\# \\$ \\% \\& \\_ \\{ \\} \\textasciitilde{} \\textasciicircum{} \\textbackslash{}"
            escapeNoteText("<>\u00ab\u00bb") shouldBe "\\textless{}\\textgreater{}\\guillemotleft{}\\guillemotright{}"
            escapeNoteText("a\u2026b\u2022c") shouldBe "a\\ldots{}b\\textbullet{}c"
            escapeNoteText("a\nb\rc\td\u0000e\u007ff\u0085g\u2028h\u2029i") shouldBe "a b c d e f g h i"
        }

        "a note body with every special character yields balanced braces and no raw line break" {
            val tex = NoteFixtures.renderUml(body = "# \$ % & _ { } ~ ^ \\ <> \u00ab\u00bb\u2026\u2022\n\r\t\u0000\u007f x")
            bracesBalanced(tex) shouldBe true
            tex shouldNotContain "\u0000"
            tex shouldNotContain "\u007f"
            tex shouldNotContain "\r"
            tex shouldContain "\\#"
            tex shouldContain "\\textbackslash{}"
        }

        "hostile note texts add no macro beyond the renderer's own, in every notation" {
            val renderers: Map<String, (String) -> String> =
                mapOf(
                    "uml" to { b -> NoteFixtures.renderUml(body = b) },
                    "bdd" to { b -> NoteFixtures.renderBdd(body = b) },
                    "stm" to { b -> NoteFixtures.renderStm(body = b) },
                    "c4" to { b -> NoteFixtures.renderC4(body = b) },
                )
            for ((name, render) in renderers) {
                val baseline = macrosIn(render("plain"))
                val allowed = baseline + NOTE_MACROS
                for (hostile in HOSTILE_TEXTS + HOSTILE_TEXTS.map { "**$it**" } + HOSTILE_TEXTS.map { "[$it](https://a.example)" } +
                    HOSTILE_TEXTS.map { "`$it`" } + listOf("[x](javascript:alert(1))", "- \\input{x}\n1. \\write18")) {
                    val tex = render(hostile)
                    withClue("$name / ${hostile.take(30)}") {
                        (macrosIn(tex) - allowed).shouldBeEmpty()
                        bracesBalanced(tex) shouldBe true
                        tex shouldNotContain "^^"
                        tex shouldNotContain "\\href"
                        tex shouldNotContain "\\url"
                        tex shouldNotContain "hyperref"
                        // No unescaped percent sign outside of the renderer's own comment lines.
                        tex.lines().filterNot { it.trimStart().startsWith("%") }.forEach { line ->
                            Regex("""(?<!\\)%""").containsMatchIn(line) shouldBe false
                        }
                    }
                }
            }
        }

        "every note kind draws its symbol, accent and colour" {
            for (kind in NoteKind.entries) {
                val tex = NoteFixtures.renderUml(body = "Body of ${kind.name}", kind = kind)
                if (!kind.hasIcon) {
                    tex shouldNotContain "\\definecolor{kumlNoteTone"
                    tex shouldNotContain "even odd rule"
                    tex shouldNotContain "rectangle ("
                } else {
                    val tone = NoteSymbols.tone(kind)
                    val name = "kumlNoteTone" + tone.key.replaceFirstChar { it.uppercaseChar() }
                    val hex = (NoteSymbols.LIGHT_RGB[tone] ?: 0).toString(16).uppercase().padStart(6, '0')
                    tex shouldContain "\\definecolor{$name}{HTML}{$hex}"
                    Regex("""\\definecolor""").findAll(tex).count() shouldBe 1
                    tex shouldContain "\\fill[$name] "
                    val symbol = tex.lines().single { "even odd rule" in it }
                    val polygons = NoteSymbols.path(kind).split(' ').count { it == "Z" }
                    Regex("cycle").findAll(symbol).count() shouldBe polygons
                    symbol shouldContain "\\fill[$name, even odd rule]"
                }
                bracesBalanced(tex) shouldBe true
            }
        }

        "formatted text: bold, italic, code, link, lists and truncation" {
            val body = "**bold** and *italic* and `code` and [link](https://example.org/a)\n- first\n- second\n1. one\n2. two"
            val tex = NoteFixtures.renderUml(body = body)
            tex shouldContain "\\textbf{bold}"
            tex shouldContain "\\textit{italic}"
            tex shouldContain "\\texttt{code}"
            tex shouldContain "\\underline{link}"
            tex shouldNotContain "example.org"
            tex shouldNotContain "\\href"
            tex shouldContain "\\textbullet{}"
            tex shouldContain "{1.}"
            tex shouldContain "{2.}"

            val long = NoteFixtures.renderUml(body = "word ".repeat(1000))
            long shouldContain "\\ldots{}"
            // One node per displayed line: never more than the display limit.
            val textNodes = long.lines().count { "kuml-note-text" in it && "\\node" in it }
            textNodes shouldBe
                NoteLayout
                    .displayText(
                        body = "word ".repeat(1000),
                        maxInnerWidth = 140f - NoteLayout.H_PADDING,
                    ).lines.size
                    .coerceAtLeast(textNodes)
            textNodes shouldBeGreaterThan 1
        }

        "the note text has one node per wrapped line" {
            val body = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu xi omicron"
            val tex = NoteFixtures.renderUml(body = body)
            val expected = NoteLayout.displayText(body = body, maxInnerWidth = 160f - NoteLayout.H_PADDING + 0.5f).lines.size
            tex.lines().count { "\\node[kuml-note-text]" in it } shouldBe expected
        }

        "UML comment shows its body, not its id, and a dashed anchor with a dot" {
            val tex = NoteFixtures.renderUml(body = "Encapsulates the order lifecycle.")
            tex shouldContain "Encapsulates"
            tex shouldNotContain "{c1}"
            tex shouldContain "kuml-note-anchor"
            tex shouldContain "circle (2.000pt)"
            tex shouldNotContain "\\draw[kuml-edge-plain"
            tex shouldContain "kuml-note/.style"
        }

        "a note without a layout route for its anchor is connected between the named nodes" {
            val (d, l) = NoteFixtures.uml(body = "x")
            val noRoute = l.copy(edges = l.edges.filterKeys { it.value != "l1" })
            val tex =
                dev.kuml.io.latex.KumlLatexRenderer
                    .toLatex(diagram = d, layoutResult = noRoute)
            tex shouldContain "\\draw[kuml-note-anchor] (n_c1) -- (n_A);"
        }

        "SysML 2 BDD comment is placed, drawn with symbol and a dashed anchor, inside the picture" {
            val tex = NoteFixtures.renderBdd(body = "Abstract base of all vehicles.", kind = NoteKind.INFO)
            tex shouldContain "Abstract"
            tex shouldContain "\\definecolor{kumlNoteToneInfo}"
            tex shouldContain "kuml-note-anchor"
            tex shouldNotContain "\\draw[kuml-sysml2-edge-solid"
            bracesBalanced(tex) shouldBe true
        }

        "SysML 2 STM comment is drawn" {
            val tex = NoteFixtures.renderStm(body = "Switches after the timer.", kind = NoteKind.WARNING)
            tex shouldContain "Switches"
            tex shouldContain "\\definecolor{kumlNoteToneWarning}"
            tex shouldContain "kuml-note-anchor"
        }

        "C4 note is placed, drawn and anchored; relationships keep their plain arrows" {
            val tex = NoteFixtures.renderC4(body = "Owned by the platform team.", kind = NoteKind.HINT)
            tex shouldContain "Owned"
            tex shouldContain "\\definecolor{kumlNoteToneHint}"
            tex shouldContain "kuml-note-anchor"
            tex shouldContain "kuml-edge-plain"
            tex.lines().count { "kuml-edge-plain" in it && "\\draw" in it } shouldBe 1
        }

        "output with notes is deterministic" {
            NoteFixtures.renderUml(body = "**a** b", kind = NoteKind.RISK) shouldBe
                NoteFixtures.renderUml(body = "**a** b", kind = NoteKind.RISK)
            NoteFixtures.renderBdd(body = "x") shouldBe NoteFixtures.renderBdd(body = "x")
            NoteFixtures.renderC4(body = "x") shouldBe NoteFixtures.renderC4(body = "x")
            NoteFixtures.renderStm(body = "x") shouldBe NoteFixtures.renderStm(body = "x")
        }

        "write samples" {
            SampleOutput.write(
                filename = "note/uml-note.tex",
                content = NoteFixtures.renderUml(body = "**Bold** note", kind = NoteKind.WARNING, standalone = true),
            )
            SampleOutput.write(
                filename = "note/bdd-note.tex",
                content = NoteFixtures.renderBdd(body = "A note", kind = NoteKind.INFO, standalone = true),
            )
            SampleOutput.write(
                filename = "note/stm-note.tex",
                content = NoteFixtures.renderStm(body = "A note", kind = NoteKind.RISK, standalone = true),
            )
            SampleOutput.write(
                filename = "note/c4-note.tex",
                content = NoteFixtures.renderC4(body = "A note", kind = NoteKind.HINT, standalone = true),
            )
        }
    })
