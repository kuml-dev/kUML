package dev.kuml.cli

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files

/**
 * Nacharbeit 74 (c): the W003 `actual` of a SysML 2 use case diagram with an explicit aspectRatio is measured on the
 * final image, so it equals the SVG viewBox ratio. Geometry only, no text widths.
 */
class UcFinalAspectCliTest :
    FunSpec({
        fun render(layoutBody: String): Pair<List<String>, Double> {
            val dir = Files.createTempDirectory("kuml-uc-aspect").toFile()
            try {
                val input =
                    File(dir, "a.kuml.kts").also {
                        it.writeText(
                            """
                            import dev.kuml.core.model.layout.ActorSide
                            import dev.kuml.sysml2.dsl.sysml2Model
                            sysml2Model(name = "M") {
                                val op = actorDef("Operator")
                                val c1 = useCaseDef("Case1")
                                val c2 = useCaseDef("Case2")
                                val c3 = useCaseDef("Case3")
                                val c4 = useCaseDef("Case4")
                                ucDiagram("D") {
                                    include(op)
                                    subject("S") {
                                        include(c1)
                                        include(c2)
                                        include(c3)
                                        include(c4)
                                    }
                                    association(op, c1)
                                    association(op, c2)
                                    extend(c4, c1, condition = "locking yes")
                                    layout { $layoutBody }
                                }
                            }
                            """.trimIndent(),
                        )
                    }
                val out = dir.toPath().resolve("o.svg")
                val outcome = RenderPipeline.run(input = input, output = out, format = "svg", width = 800, themeName = "kuml")
                val box =
                    Regex("""viewBox="0 0 ([0-9.]+) ([0-9.]+)"""")
                        .find(File(out.toString()).readText())!!
                        .groupValues
                        .drop(1)
                        .map { it.toDouble() }
                return outcome.layoutFindings.filter { it.code == "KUML-LAYOUT-W003" }.map { it.message } to (box[0] / box[1])
            } finally {
                dir.deleteRecursively()
            }
        }

        test("explicit aspectRatio: reported actual equals the viewBox ratio") {
            val (findings, ratio) = render("actorSide = ActorSide.Both; aspectRatio = 1.0")
            withClue(findings.toString()) {
                findings.size shouldBe 1
                Regex("""actual ([0-9.]+)""").find(findings.single())!!.groupValues[1].toDouble() shouldBe (ratio plusOrMinus 0.01)
            }
        }

        test("explicit wrapColumns together with aspectRatio names the collision") {
            val (findings, _) = render("actorSide = ActorSide.Right; wrapColumns = 1; aspectRatio = 4.0")
            findings.size shouldBe 1
            findings.single() shouldContain "wrapColumns is set explicitly"
        }

        test("without aspectRatio the runner behaviour is unchanged (no hint, at most the former finding)") {
            val (findings, _) = render("actorSide = ActorSide.Right")
            findings.none { it.contains("wrapColumns is set explicitly") } shouldBe true
        }
    })
