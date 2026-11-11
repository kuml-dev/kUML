package dev.kuml.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files

/** Nacharbeit 64: `kuml render` prints `PAR442` for `include(attributeDef)` in a parametric diagram, once, without changing the output. */
class RenderParIncludeWarningTest :
    FunSpec({
        val script =
            """
            import dev.kuml.sysml2.ConstraintParameter
            import dev.kuml.sysml2.ConstraintParameterDirection.In
            import dev.kuml.sysml2.dsl.sysml2Model

            sysml2Model(name = "Par442") {
                val real = attributeDef(name = "Real")
                val law =
                    constraintDef(
                        name = "Law",
                        expression = "x > 0",
                        parameters = listOf(ConstraintParameter(name = "x", typeId = "Real", direction = In)),
                    )
                parDiagram(name = "Law diagram") {
                    include(definition = law)
                    include(definition = real)
                }
            }
            """.trimIndent()

        test("render prints PAR442 exactly once, still writes the SVG") {
            val dir = Files.createTempDirectory("kuml-par442").toFile()
            val originalErr = System.err
            val captured = ByteArrayOutputStream()
            try {
                val input = File(dir, "par.kuml.kts").also { it.writeText(script) }
                System.setErr(PrintStream(captured, true, Charsets.UTF_8))
                try {
                    RenderPipeline.run(
                        input = input,
                        output = dir.toPath().resolve("o.svg"),
                        format = "svg",
                        width = 800,
                        themeName = "kuml",
                    )
                } finally {
                    System.setErr(originalErr)
                }
                val err = captured.toString(Charsets.UTF_8)
                Regex("""\[PAR442]""").findAll(err).count() shouldBe 1
                err shouldContain "WARNING [PAR442] include of attributeDef 'Real' has no effect"
                err shouldContain "Hint:"
                File(dir, "o.svg").readText() shouldContain "<svg"
            } finally {
                dir.deleteRecursively()
            }
        }
    })
