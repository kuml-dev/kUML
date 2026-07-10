package dev.kuml.langsupport.diagnostics

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Unit tests for the TSV parser of `kuml diagnostics` output.
 * Pure parsing — no IntelliJ runtime or CLI process required.
 */
class KumlDiagnosticTsvParserTest :
    FunSpec({

        test("parses a single error line with full location") {
            val tsv = "ERROR\t3\t5\t3\t21\tUnresolved reference 'foo'."
            val result = KumlDiagnosticTsvParser.parse(tsv)
            result.size shouldBe 1
            result[0].severity shouldBe KumlDiagnostic.Severity.ERROR
            result[0].startLine shouldBe 3
            result[0].startCol shouldBe 5
            result[0].endLine shouldBe 3
            result[0].endCol shouldBe 21
            result[0].message shouldBe "Unresolved reference 'foo'."
        }

        test("parses multiple lines") {
            val tsv =
                "ERROR\t1\t1\t1\t4\tfirst\n" +
                    "WARNING\t10\t2\t10\t8\tsecond"
            val result = KumlDiagnosticTsvParser.parse(tsv)
            result.size shouldBe 2
            result[0].message shouldBe "first"
            result[1].severity shouldBe KumlDiagnostic.Severity.WARNING
            result[1].startLine shouldBe 10
        }

        test("empty output yields no diagnostics") {
            KumlDiagnosticTsvParser.parse("").size shouldBe 0
            KumlDiagnosticTsvParser.parse("\n  \n").size shouldBe 0
        }

        test("FATAL maps to ERROR severity") {
            val result = KumlDiagnosticTsvParser.parse("FATAL\t1\t1\t\t\tboom")
            result[0].severity shouldBe KumlDiagnostic.Severity.ERROR
        }

        test("missing location fields fall back to 1,1 and end falls back to start") {
            val result = KumlDiagnosticTsvParser.parse("ERROR\t\t\t\t\twhole-file failure")
            result.size shouldBe 1
            result[0].startLine shouldBe 1
            result[0].startCol shouldBe 1
            result[0].endLine shouldBe 1
            result[0].endCol shouldBe 1
            result[0].message shouldBe "whole-file failure"
        }

        test("malformed line with too few fields is skipped") {
            KumlDiagnosticTsvParser.parse("ERROR\t1\t1").size shouldBe 0
        }

        test("message keeps embedded tabs intact via split limit") {
            // CLI sanitises tabs, but the parser must not split the message field
            // even if one slipped through — split(limit = 6) keeps the tail whole.
            val result = KumlDiagnosticTsvParser.parse("ERROR\t2\t3\t2\t9\ta\tb")
            result.size shouldBe 1
            result[0].message shouldBe "a\tb"
        }

        test("parses distinct end location different from start") {
            val result = KumlDiagnosticTsvParser.parse("WARNING\t5\t2\t7\t14\tmulti-line span")
            result.size shouldBe 1
            result[0].startLine shouldBe 5
            result[0].startCol shouldBe 2
            result[0].endLine shouldBe 7
            result[0].endCol shouldBe 14
        }
    })
