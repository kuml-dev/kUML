package dev.kuml.lsp

import dev.kuml.langsupport.diagnostics.KumlDiagnostic
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

private fun diag(
    startLine: Int,
    startCol: Int,
    endLine: Int,
    endCol: Int,
    severity: KumlDiagnostic.Severity = KumlDiagnostic.Severity.ERROR,
) = KumlDiagnostic("msg", startLine, startCol, endLine, endCol, severity)

class RangeMappingTest :
    FunSpec({

        test("1-based to 0-based conversion, no widening needed") {
            val doc = listOf("aaaaaaaaaa", "bbbbbbbbbb", "cccccccccc", "dddddddddd", "eeeeeeeeee").joinToString("\n")
            RangeMapping.toLspRange(diag(3, 5, 3, 9), doc) shouldBe
                Range(Position(2, 4), Position(2, 8))
        }

        test("end == start widens to the next whitespace/paren boundary, floored at start+1") {
            val doc = listOf("header", "class  Foo", "footer").joinToString("\n")
            // 1-based (2,3,2,3): line index 1 is "class  Foo", 0-based col 2 == the
            // second 'a' in "class"; next boundary is the space at index 5.
            RangeMapping.toLspRange(diag(2, 3, 2, 3), doc) shouldBe
                Range(Position(1, 2), Position(1, 5))
        }

        test("out-of-bounds start/end clamp to the last line of the document") {
            val doc = listOf("a", "bb", "ccc").joinToString("\n")
            val range = RangeMapping.toLspRange(diag(1000, 1, 1000, 1), doc)
            range.start.line shouldBe 2
            range.end.line shouldBe 2
            // "ccc" has no whitespace/paren boundary, so the end widens to end-of-line.
            range shouldBe Range(Position(2, 0), Position(2, 3))
        }

        test("no-location default (parser's 1,1,1,1) attaches to the file head and stays non-empty") {
            val doc = "x"
            RangeMapping.toLspRange(diag(1, 1, 1, 1), doc) shouldBe
                Range(Position(0, 0), Position(0, 1))
        }
    })
