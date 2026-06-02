package dev.kuml.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Paths

class FormatResolverTest :
    FunSpec({

        val anyFile = File("any.kuml.kts")

        test("FormatResolver picks format from --format option") {
            FormatResolver.resolve("png", null, anyFile) shouldBe "png"
        }

        test("FormatResolver picks format from output suffix when --format absent") {
            FormatResolver.resolve(null, Paths.get("x.png"), anyFile) shouldBe "png"
        }

        test("FormatResolver falls back to svg") {
            FormatResolver.resolve(null, null, anyFile) shouldBe "svg"
        }
    })
