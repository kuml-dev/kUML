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
) = KumlDiagnostic(message = "msg", startLine = startLine, startCol = startCol, endLine = endLine, endCol = endCol, severity = severity)

class RangeMappingTest :
    FunSpec({

        test("1-based to 0-based conversion, no widening needed") {
            val doc = listOf("aaaaaaaaaa", "bbbbbbbbbb", "cccccccccc", "dddddddddd", "eeeeeeeeee").joinToString("\n")
            RangeMapping.toLspRange(d = diag(startLine = 3, startCol = 5, endLine = 3, endCol = 9), docText = doc) shouldBe
                Range(Position(2, 4), Position(2, 8))
        }

        test("end == start widens to the next whitespace/paren boundary, floored at start+1") {
            val doc = listOf("header", "class  Foo", "footer").joinToString("\n")
            // 1-based (2,3,2,3): line index 1 is "class  Foo", 0-based col 2 == the
            // second 'a' in "class"; next boundary is the space at index 5.
            RangeMapping.toLspRange(d = diag(startLine = 2, startCol = 3, endLine = 2, endCol = 3), docText = doc) shouldBe
                Range(Position(1, 2), Position(1, 5))
        }

        test("out-of-bounds start/end clamp to the last line of the document") {
            val doc = listOf("a", "bb", "ccc").joinToString("\n")
            val range = RangeMapping.toLspRange(d = diag(startLine = 1000, startCol = 1, endLine = 1000, endCol = 1), docText = doc)
            range.start.line shouldBe 2
            range.end.line shouldBe 2
            // "ccc" has no whitespace/paren boundary, so the end widens to end-of-line.
            range shouldBe Range(Position(2, 0), Position(2, 3))
        }

        test("no-location default (parser's 1,1,1,1) attaches to the file head and stays non-empty") {
            val doc = "x"
            RangeMapping.toLspRange(d = diag(startLine = 1, startCol = 1, endLine = 1, endCol = 1), docText = doc) shouldBe
                Range(Position(0, 0), Position(0, 1))
        }
    })
