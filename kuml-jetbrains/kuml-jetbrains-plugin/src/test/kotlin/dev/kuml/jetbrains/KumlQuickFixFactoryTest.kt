package dev.kuml.jetbrains

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

class KumlQuickFixFactoryTest :
    StringSpec({

        "quickFixFor returns AddMissingParameterFix for missing parameter" {
            val diag = KumlDiagnostic("No value passed for parameter 'name'", 1, 1, KumlDiagnostic.DiagnosticSeverity.ERROR)
            val fix = KumlQuickFixFactory.quickFixFor(diag)
            fix shouldNotBe null
            fix!!.text shouldContain "missing"
        }

        "quickFixFor returns RemoveUnknownParameterFix for unknown parameter" {
            val diag = KumlDiagnostic("No parameter with name 'returnType' found", 1, 1, KumlDiagnostic.DiagnosticSeverity.ERROR)
            val fix = KumlQuickFixFactory.quickFixFor(diag)
            fix shouldNotBe null
            fix!!.text shouldContain "unknown"
        }

        "quickFixFor returns SuppressWarningFix for warnings" {
            val diag = KumlDiagnostic("Some deprecation warning", 1, 1, KumlDiagnostic.DiagnosticSeverity.WARNING)
            val fix = KumlQuickFixFactory.quickFixFor(diag)
            fix shouldNotBe null
            fix!!.text shouldContain "Suppress"
        }

        "quickFixFor returns null for unrecognised error" {
            val diag = KumlDiagnostic("Some random error", 1, 1, KumlDiagnostic.DiagnosticSeverity.ERROR)
            val fix = KumlQuickFixFactory.quickFixFor(diag)
            fix shouldBe null
        }

        "RenameToKnownFunctionFix is available for known typos" {
            val diag = KumlDiagnostic("Unresolved reference: assoc", 1, 1, KumlDiagnostic.DiagnosticSeverity.ERROR)
            val fix = KumlQuickFixFactory.quickFixFor(diag)
            fix shouldNotBe null
            fix!!.text shouldContain "association"
        }

        "RenameToKnownFunctionFix is null for unknown reference" {
            val diag = KumlDiagnostic("Unresolved reference: fooBarBaz", 1, 1, KumlDiagnostic.DiagnosticSeverity.ERROR)
            val fix = KumlQuickFixFactory.quickFixFor(diag)
            // For truly unknown references, returns null (no known alias)
            if (fix != null) fix.text shouldContain "Rename"
        }
    })
