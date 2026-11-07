package dev.kuml.workspace.latex

import dev.kuml.io.latex.LatexFamily
import dev.kuml.workspace.latex.LatexTestSupport.main
import dev.kuml.workspace.latex.LatexTestSupport.text
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class LatexFigureCollectorTest :
    FunSpec({
        fun ws() =
            LatexTestSupport.workspace(
                files =
                    mapOf(
                        "index.md" to LatexTestSupport.index(links = listOf("m.md")),
                        "m.md" to LatexTestSupport.doc(title = "Modell Ä", body = "```kuml {name=\"Mein Diagramm & Co\"}\nx\n```\n"),
                    ),
            )

        fun run(outcome: LatexDiagramOutcome) = LatexTestSupport.prepare(ws = ws(), diagrams = { _, _, _ -> outcome })

        test("a rendered diagram is written under figures/ and included with an escaped caption") {
            val p =
                run(
                    LatexDiagramOutcome.Rendered(
                        tex = LatexTestSupport.GOOD_TEX,
                        family = LatexFamily.UML,
                        diagramCount = 1,
                        diagramName = null,
                    ),
                )
            val path = p.files.map { it.path }.single { it.startsWith("figures/") }
            path shouldBe "figures/f001-mein-diagramm-co.tex"
            p.main() shouldContain "\\kumlfigure{figures/f001-mein-diagramm-co}{Mein Diagramm \\& Co}"
            p.text(path) shouldBe LatexTestSupport.GOOD_TEX
            p.figureCount shouldBe 1
            p.excludedFigureCount shouldBe 0
        }

        test("a snippet with an undefined node reference is written but excluded, with a visible placeholder") {
            val p =
                run(
                    LatexDiagramOutcome.Rendered(
                        tex = LatexTestSupport.DANGLING_TEX,
                        family = LatexFamily.UML,
                        diagramCount = 1,
                        diagramName = null,
                    ),
                )
            p.main() shouldNotContain "\\kumlfigure{figures"
            p.main() shouldContain "% excluded: figures/f001-mein-diagramm-co.tex - undefined TikZ node reference(s): n_missing"
            p.main() shouldContain "\\kumlmissing{Mein Diagramm \\& Co}"
            p.files.any { it.path == "figures/f001-mein-diagramm-co.tex" } shouldBe true
            p.excludedFigureCount shouldBe 1
            p.figureCount shouldBe 0
            p.issues.single { it.level == LatexIssueLevel.WARN }.source shouldBe "m.md:7"
        }

        test("a block that holds several diagrams exports the first and says so") {
            val p =
                run(
                    LatexDiagramOutcome.Rendered(
                        tex = LatexTestSupport.GOOD_TEX,
                        family = LatexFamily.UML,
                        diagramCount = 2,
                        diagramName = "x",
                    ),
                )
            p.issues.any { it.level == LatexIssueLevel.NOTE && it.message.startsWith("2 diagrams") } shouldBe true
        }

        test("skipped and failed diagrams give a placeholder and an issue of the right level") {
            val skipped = run(LatexDiagramOutcome.Skipped(reason = "no LaTeX output"))
            skipped.issues.single().level shouldBe LatexIssueLevel.SKIP
            skipped.main() shouldContain "\\kumlmissing{"
            val failed = run(LatexDiagramOutcome.Failed(reason = "boom"))
            failed.issues.single().level shouldBe LatexIssueLevel.ERROR
            failed.main() shouldContain "\\kumlmissing{"
            failed.files.none { it.path.startsWith("figures/") } shouldBe true
        }

        test("a hostile block name only reaches the document as escaped text and a safe file name") {
            val w =
                LatexTestSupport.workspace(
                    files =
                        mapOf(
                            "index.md" to LatexTestSupport.index(links = listOf("m.md")),
                            "m.md" to LatexTestSupport.doc(title = "M", body = "```kuml {name=\"../../x\\write18{y}\"}\nx\n```\n"),
                        ),
                )
            val p = LatexTestSupport.prepare(ws = w)
            p.files
                .map { it.path }
                .filter { it.startsWith("figures/") }
                .single() shouldBe "figures/f001-x-write18-y.tex"
            p.main() shouldNotContain "\\write18"
        }
    })
