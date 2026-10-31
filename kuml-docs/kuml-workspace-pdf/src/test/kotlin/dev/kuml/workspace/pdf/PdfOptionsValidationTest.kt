package dev.kuml.workspace.pdf

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PdfOptionsValidationTest :
    FunSpec({
        fun opts(
            orientation: PageOrientation = PageOrientation.AUTO,
            strategy: PageStrategy? = null,
            minScale: Float = 0.5f,
        ) = PdfExportOptions(
            kind = PdfExportKind.HANDBOOK,
            orientation = orientation,
            pageStrategy = strategy,
            minScale = minScale,
            producerVersion = "kUML test",
        )

        test("minScale outside 0.25..1.0 is rejected") {
            shouldThrow<IllegalArgumentException> { opts(minScale = 0.2f) }
            shouldThrow<IllegalArgumentException> { opts(minScale = 1.1f) }
            opts(minScale = 0.25f).minScale shouldBe 0.25f
            opts(minScale = 1.0f).minScale shouldBe 1.0f
        }
        test("AUTO strategy requires AUTO orientation") {
            shouldThrow<IllegalArgumentException> { opts(orientation = PageOrientation.PORTRAIT, strategy = PageStrategy.AUTO) }
        }
        test("effective strategy defaults follow the orientation") {
            opts().effectivePageStrategy shouldBe PageStrategy.AUTO
            opts(orientation = PageOrientation.LANDSCAPE).effectivePageStrategy shouldBe PageStrategy.FIXED
            opts(strategy = PageStrategy.FIXED).effectivePageStrategy shouldBe PageStrategy.FIXED
        }
    })
