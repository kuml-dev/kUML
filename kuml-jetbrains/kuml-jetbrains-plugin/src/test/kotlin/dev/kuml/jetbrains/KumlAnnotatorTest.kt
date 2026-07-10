package dev.kuml.jetbrains

import dev.kuml.langsupport.diagnostics.KumlDiagnostic
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class KumlAnnotatorTest :
    StringSpec({

        "lineColToOffset returns correct position for first line" {
            val annotator = KumlAnnotator()
            val text = "hello world\nfoo bar"
            annotator.lineColToOffset(text, 1, 7) shouldBe 6
        }

        "lineColToOffset returns correct position for second line" {
            val annotator = KumlAnnotator()
            val text = "hello\nworld"
            annotator.lineColToOffset(text, 2, 1) shouldBe 6
        }

        "KumlDiagnostic has correct fields" {
            val d = KumlDiagnostic("test error", 3, 5, 3, 5, KumlDiagnostic.Severity.ERROR)
            d.message shouldBe "test error"
            d.startLine shouldBe 3
            d.startCol shouldBe 5
            d.severity shouldBe KumlDiagnostic.Severity.ERROR
        }
    })
