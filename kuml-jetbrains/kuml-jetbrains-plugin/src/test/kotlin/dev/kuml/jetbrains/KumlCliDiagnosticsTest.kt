package dev.kuml.jetbrains

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Unit tests for the TSV parser of `kuml diagnostics` output.
 * Pure parsing — no IntelliJ runtime or CLI process required.
 */
class KumlCliDiagnosticsTest :
    FunSpec({

        test("parses a single error line with full location") {
            val tsv = "ERROR\t3\t5\t3\t21\tUnresolved reference 'foo'."
            val result = KumlCliDiagnostics.parse(tsv)
            result.size shouldBe 1
            result[0].severity shouldBe KumlDiagnostic.DiagnosticSeverity.ERROR
            result[0].line shouldBe 3
            result[0].column shouldBe 5
            result[0].message shouldBe "Unresolved reference 'foo'."
        }

        test("parses multiple lines") {
            val tsv =
                "ERROR\t1\t1\t1\t4\tfirst\n" +
                    "WARNING\t10\t2\t10\t8\tsecond"
            val result = KumlCliDiagnostics.parse(tsv)
            result.size shouldBe 2
            result[0].message shouldBe "first"
            result[1].severity shouldBe KumlDiagnostic.DiagnosticSeverity.WARNING
            result[1].line shouldBe 10
        }

        test("empty output yields no diagnostics") {
            KumlCliDiagnostics.parse("").size shouldBe 0
            KumlCliDiagnostics.parse("\n  \n").size shouldBe 0
        }

        test("FATAL maps to ERROR severity") {
            val result = KumlCliDiagnostics.parse("FATAL\t1\t1\t\t\tboom")
            result[0].severity shouldBe KumlDiagnostic.DiagnosticSeverity.ERROR
        }

        test("missing location fields fall back to 1,1") {
            val result = KumlCliDiagnostics.parse("ERROR\t\t\t\t\twhole-file failure")
            result.size shouldBe 1
            result[0].line shouldBe 1
            result[0].column shouldBe 1
            result[0].message shouldBe "whole-file failure"
        }

        test("malformed line with too few fields is skipped") {
            KumlCliDiagnostics.parse("ERROR\t1\t1").size shouldBe 0
        }

        test("message keeps embedded tabs intact via split limit") {
            // CLI sanitises tabs, but the parser must not split the message field
            // even if one slipped through — split(limit = 6) keeps the tail whole.
            val result = KumlCliDiagnostics.parse("ERROR\t2\t3\t2\t9\ta\tb")
            result.size shouldBe 1
            result[0].message shouldBe "a\tb"
        }
    })
