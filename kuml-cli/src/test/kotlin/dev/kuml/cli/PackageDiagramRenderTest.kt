@file:Suppress("RequireNamedArguments")

package dev.kuml.cli

import dev.kuml.core.script.ScriptEvaluationException
import dev.kuml.io.latex.KumlLatexRenderer
import dev.kuml.layout.LayoutEngineRegistry
import dev.kuml.layout.elk.ElkLayoutEngineProvider
import dev.kuml.layout.grid.GridLayoutEngineProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files

/** Wave 2I-a - the CLI renders package diagrams to SVG and PNG and refuses LaTeX with a plain message, not a stack trace. */
class PackageDiagramRenderTest :
    FunSpec({
        beforeEach {
            LayoutEngineRegistry.clear()
            LayoutEngineRegistry.register(GridLayoutEngineProvider())
            LayoutEngineRegistry.register(ElkLayoutEngineProvider())
        }
        afterEach { LayoutEngineRegistry.clear() }

        val script =
            """
            import dev.kuml.sysml2.dsl.sysml2Model
            sysml2Model(name = "M") {
                pkg("A") { partDef("X", id = "A::X") }
                pkg("B") { imports("A::*"); partDef("Y", id = "B::Y") }
                packageDiagram("D") { includeById("pkg:A"); includeById("pkg:B") }
            }
            """.trimIndent()

        fun run(format: String): Pair<java.nio.file.Path, () -> Unit> {
            val dir = Files.createTempDirectory("kuml-pkg-cli")
            val input = dir.resolve("d.kuml.kts").toFile().also { it.writeText(script) }
            val out = dir.resolve("d.$format")
            RenderPipeline.run(input = input, output = out, format = format, width = 800, themeName = "kuml")
            return out to { dir.toFile().deleteRecursively() }
        }

        test("svg: the frames, the import arrow and its label are drawn") {
            val (out, cleanup) = run("svg")
            try {
                val svg = out.toFile().readText()
                svg shouldContain "class=\"kuml-package-frame\""
                svg shouldContain "«import» *"
            } finally {
                cleanup()
            }
        }

        test("png: a PNG is written") {
            val (out, cleanup) = run("png")
            try {
                val bytes = out.toFile().readBytes()
                (bytes.size > 100) shouldBe true
                bytes[1].toInt().toChar() shouldBe 'P'
            } finally {
                cleanup()
            }
        }

        test("latex: refused with the plain message, as a script error (no stack trace, exit code SCRIPT_ERROR in the command)") {
            val dir = Files.createTempDirectory("kuml-pkg-cli")
            try {
                val input = dir.resolve("d.kuml.kts").toFile().also { it.writeText(script) }
                val ex =
                    shouldThrow<ScriptEvaluationException> {
                        RenderPipeline.run(input = input, output = dir.resolve("d.tex"), format = "latex", width = 800, themeName = "kuml")
                    }
                ex.message shouldBe KumlLatexRenderer.PACKAGE_DIAGRAM_UNSUPPORTED
                ex.message!! shouldContain "not supported by the LaTeX renderer"
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        test("the renderer itself throws UnsupportedOperationException with the same message") {
            val model =
                dev.kuml.sysml2.dsl
                    .sysml2Model(name = "M") { packageDiagram("D") }
            val ex =
                shouldThrow<UnsupportedOperationException> {
                    KumlLatexRenderer.toLatex(
                        model = model,
                        diagram = model.diagrams.single() as dev.kuml.sysml2.Sysml2PackageDiagram,
                        layoutResult =
                            dev.kuml.layout.LayoutResult(
                                engineId = dev.kuml.layout.LayoutEngineId("t"),
                                seed = null,
                                canvas = dev.kuml.layout.Size(width = 1f, height = 1f),
                                nodes = emptyMap(),
                                edges = emptyMap(),
                                groups = emptyMap(),
                            ),
                    )
                }
            ex.message shouldBe KumlLatexRenderer.PACKAGE_DIAGRAM_UNSUPPORTED
        }
    })
