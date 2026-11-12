package dev.kuml.cli

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files

/**
 * Nacharbeit 83: the W003 `actual` of a SysML 2 block definition diagram with an explicit aspectRatio is measured on
 * the final image, so it equals the SVG viewBox ratio. Geometry only, no text widths.
 */
class BddFinalAspectCliTest :
    FunSpec({
        fun render(
            layoutBody: String,
            multiplicity: String = "KermlMultiplicity.EXACTLY_ONE",
        ): Pair<List<String>, Double> {
            val dir = Files.createTempDirectory("kuml-bdd-aspect").toFile()
            try {
                val input =
                    File(dir, "a.kuml.kts").also {
                        it.writeText(
                            """
                            import dev.kuml.kerml.KermlMultiplicity
                            import dev.kuml.sysml2.dsl.sysml2Model
                            sysml2Model(name = "M") {
                                val parts = (1..8).map { partDef(name = "P${'$'}it") }
                                val hub =
                                    partDef(name = "Hub") {
                                        for (i in 1..8) refPart(name = "p${'$'}i", typeId = "P${'$'}i", multiplicity = $multiplicity)
                                    }
                                bdd(name = "D") {
                                    include(hub)
                                    parts.forEach { include(it) }
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

        fun actualOf(message: String) = Regex("""actual ([0-9.]+)""").find(message)!!.groupValues[1].toDouble()

        test("(a) explicit aspectRatio: reported actual equals the viewBox ratio") {
            val (findings, ratio) = render("aspectRatio = 1.0")
            withClue(findings.toString()) {
                findings.size shouldBe 1
                actualOf(findings.single()) shouldBe (ratio plusOrMinus 0.01)
            }
        }

        test("(b) explicit direction together with aspectRatio names the collision") {
            val (findings, _) = render("direction = Down; aspectRatio = 4.0")
            withClue(findings.toString()) {
                findings.size shouldBe 1
                findings.single() shouldContain "direction is set explicitly"
            }
        }

        test("(c) without aspectRatio the runner behaviour is unchanged (no hint)") {
            val (findings, _) = render("direction = Down")
            findings.none { it.contains("direction is set explicitly") } shouldBe true
        }

        test("(d) edge labels (multiplicities): the reported actual still equals the viewBox ratio") {
            val (findings, ratio) = render("aspectRatio = 1.0", multiplicity = "KermlMultiplicity.ZERO_OR_MORE")
            withClue(findings.toString()) {
                findings.size shouldBe 1
                actualOf(findings.single()) shouldBe (ratio plusOrMinus 0.01)
            }
        }
    })
