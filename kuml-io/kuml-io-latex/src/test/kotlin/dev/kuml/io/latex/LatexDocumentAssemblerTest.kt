package dev.kuml.io.latex

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class LatexDocumentAssemblerTest :
    StringSpec({
        "structure, order and excluded figures" {
            val tex =
                LatexDocumentAssembler.mainDocument(
                    figures = listOf(LatexFigure(input = "a", caption = "A"), LatexFigure(input = "sub/b", caption = null)),
                    excluded = listOf(LatexExcludedFigure(input = "bad", reason = "undefined node")),
                    generator = "kUML test",
                )
            tex shouldContain "\\input{kuml-preamble}"
            tex shouldContain "\\begin{document}"
            (tex.indexOf("\\kumlfigure{a}{A}") < tex.indexOf("\\kumlfigure{sub/b}{}")) shouldBe true
            tex shouldContain "% excluded: bad.tex - undefined node"
            tex shouldNotContain "\\kumlfigure{bad}"
        }

        "hostile captions are escaped" {
            val tex =
                LatexDocumentAssembler.mainDocument(
                    figures =
                        listOf(
                            LatexFigure(input = "a", caption = "}\\input{/etc/passwd}%"),
                            LatexFigure(input = "b", caption = "\\write18{x}"),
                            LatexFigure(input = "c", caption = "line\nbreak"),
                        ),
                    excluded = emptyList(),
                    generator = "g",
                )
            tex shouldNotContain "\\input{/etc"
            tex shouldNotContain "\\write18{"
            tex shouldContain "\\textbackslash{}input"
            tex shouldContain "line break"
        }

        "unsafe input paths are rejected" {
            for (bad in listOf("../x", "/abs", "a}b", "a\\b", "", "a/../b", "a%b")) {
                shouldThrow<IllegalArgumentException> {
                    LatexDocumentAssembler.mainDocument(
                        figures = listOf(LatexFigure(input = bad, caption = null)),
                        excluded = emptyList(),
                        generator = "g",
                    )
                }
            }
        }

        "a line break in a reason cannot leave the comment" {
            val tex =
                LatexDocumentAssembler.mainDocument(
                    figures = emptyList(),
                    excluded = listOf(LatexExcludedFigure(input = "a", reason = "x\n\\input{evil}")),
                    generator = "g\n\\input{evil2}",
                )
            val offending = tex.lines().filter { "\\input{evil" in it && !it.trimStart().startsWith("%") }
            offending shouldBe emptyList()
        }
    })
