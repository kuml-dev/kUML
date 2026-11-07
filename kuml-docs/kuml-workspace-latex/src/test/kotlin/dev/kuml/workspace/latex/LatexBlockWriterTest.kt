package dev.kuml.workspace.latex

import dev.kuml.workspace.latex.LatexTestSupport.main
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class LatexBlockWriterTest :
    FunSpec({
        fun export(
            body: String,
            more: Map<String, String> = emptyMap(),
        ): PreparedLatexKnowledge {
            val ws =
                LatexTestSupport.workspace(
                    files =
                        mapOf(
                            "index.md" to LatexTestSupport.index(links = listOf("a.md")),
                            "a.md" to LatexTestSupport.doc(title = "Titel", body = body),
                        ) +
                            more,
                )
            return LatexTestSupport.prepare(ws = ws)
        }

        test("heading levels follow the document level and stop at subparagraph") {
            val main = export("## H2\n\n### H3\n\n#### H4\n\n##### H5\n\n###### H6\n").main()
            main shouldContain "\\chapter{Titel}"
            main shouldContain "\\section{H2}"
            main shouldContain "\\subsection{H3}"
            main shouldContain "\\subsubsection{H4}"
            main shouldContain "\\paragraph{H5}"
            main shouldContain "\\subparagraph{H6}"
        }

        test("nested lists deeper than four levels are flattened and reported, the output keeps balanced environments") {
            var s = ""
            for (i in 0 until 7) s += "    ".repeat(i) + "- level $i\n"
            val p = export(s)
            val main = p.main()
            Regex("\\\\begin\\{itemize}").findAll(main).count() shouldBe 4
            Regex("\\\\end\\{itemize}").findAll(main).count() shouldBe 4
            main shouldContain "level 6"
            p.issues.map { it.message } shouldContain "nesting deeper than 4 levels is flattened"
        }

        test("a list inside a quote inside a list stays well formed") {
            val main = export("- a\n  > quote\n  > - inner\n").main()
            Regex("\\\\begin\\{itemize}").findAll(main).count() shouldBe Regex("\\\\end\\{itemize}").findAll(main).count()
            Regex("\\\\begin\\{quote}").findAll(main).count() shouldBe Regex("\\\\end\\{quote}").findAll(main).count()
        }

        test("a table with different row lengths is padded; a table in a list is a tabular, not a longtable") {
            val main =
                export(
                    "| A | B | C |\n|---|---|---|\n| 1 |\n| 1 | 2 | 3 |\n\n- item\n\n  | X | Y |\n  |---|---|\n  | 1 | 2 |\n",
                ).main()
            main shouldContain "\\begin{longtable}"
            main shouldContain "\\endfirsthead"
            main shouldContain "\\begin{tabular}"
            main shouldContain "{1} & {} & {} \\\\ \\hline"
        }

        test("a cell that starts with a bracket cannot open an optional argument") {
            val main = export("| A |\n|---|\n| [x] |\n").main()
            main shouldContain "{{[}x{]}}"
        }

        test("code that contains end markers cannot leave its environment") {
            val code = "\\end{verbatim}\n\\end{lstlisting}\n\\end{kumlcode}\n} % x \\\\ [1cm]\n"
            val main = export("```text\n$code```\n").main()
            Regex("\\\\end\\{kumlcode}").findAll(main).count() shouldBe 1
            main shouldNotContain "\\end{verbatim}"
            main shouldNotContain "\\end{lstlisting}"
            main shouldContain "\\kumlcodeinfo{text}"
        }

        test("a callout label is escaped and shown as a bold line") {
            val main = export("> [!warning] Achtung\n> Text\n").main()
            main shouldContain "\\textbf{Warning}"
        }

        test("internal links become hyperref to counter labels, external ones href plus a footnote, bad schemes plain text") {
            val p =
                export(
                    "[intern](b.md) [extern](https://example.org/x) [bad](javascript:alert(1)) [file](file:///etc/passwd)\n",
                    more = mapOf("b.md" to LatexTestSupport.doc(title = "B", body = "x")),
                )
            val main = p.main()
            Regex("\\\\hyperref\\[kuml-l\\d+]\\{intern}").containsMatchIn(main) shouldBe true
            main shouldContain "\\href{https://example.org/x}{extern}"
            main shouldContain "\\footnote{\\texttt{"
            main shouldNotContain "javascript"
            main shouldNotContain "\\href{file"
            main shouldContain "bad"
        }

        test("a link to a document outside the export is plain text with a suffix") {
            val ws =
                LatexTestSupport.workspace(
                    files =
                        mapOf(
                            "index.md" to LatexTestSupport.index(links = listOf("a.md", "sub/b.md")),
                            "a.md" to LatexTestSupport.doc(title = "A", body = "[zu b](sub/b.md)"),
                            "sub/b.md" to LatexTestSupport.doc(title = "B", body = "x"),
                        ),
                )
            val p =
                LatexTestSupport.prepare(
                    ws = ws,
                    options =
                        LatexTestSupport.options {
                            copy(
                                scope =
                                    dev.kuml.workspace.pdf.ExportScope
                                        .Documents(listOf("a.md")),
                            )
                        },
                )
            p.main() shouldContain "zu b (nicht im Export)"
            p.main() shouldNotContain "\\hyperref"
        }

        test("raw HTML is shown as escaped text with a note") {
            val p = export("<script>alert(1)</script>\n")
            p.main() shouldContain "\\textless{}script\\textgreater{}"
            p.issues.any { it.level == LatexIssueLevel.NOTE && it.message.contains("HTML") } shouldBe true
        }

        test("an empty list item keeps the environment valid") {
            val main = export("-\n- b\n").main()
            Regex("\\\\item\\s*\\\\item").containsMatchIn(main) shouldBe false
        }
    })
