package dev.kuml.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Paths

class FormatResolverTest :
    FunSpec({

        val anyFile = File("any.kuml.kts")

        test("FormatResolver picks format from --format option") {
            FormatResolver.resolve(format = "png", output = null, input = anyFile) shouldBe "png"
        }

        test("FormatResolver picks format from output suffix when --format absent") {
            FormatResolver.resolve(format = null, output = Paths.get("x.png"), input = anyFile) shouldBe "png"
        }

        test("FormatResolver falls back to svg") {
            FormatResolver.resolve(format = null, output = null, input = anyFile) shouldBe "svg"
        }

        // ── V2.0.2: LaTeX/TikZ ────────────────────────────────────────────
        test("FormatResolver picks 'latex' from --format=latex") {
            FormatResolver.resolve(format = "latex", output = null, input = anyFile) shouldBe "latex"
        }

        test("FormatResolver treats --format=tex as an alias for latex") {
            // Users will think of the on-disk artefact as a `.tex` file —
            // accept that as the format flag for symmetry.
            FormatResolver.resolve(format = "tex", output = null, input = anyFile) shouldBe "latex"
        }

        test("FormatResolver infers latex from a .tex output suffix") {
            FormatResolver.resolve(format = null, output = Paths.get("diagram.tex"), input = anyFile) shouldBe "latex"
        }

        test("FormatResolver default-output uses .tex extension for latex format") {
            // `latex` is the internal key, `.tex` is what lands on disk.
            val out = FormatResolver.defaultOutput(input = File("/tmp/order.kuml.kts"), format = "latex")
            out.fileName.toString() shouldBe "order.tex"
        }
    })
