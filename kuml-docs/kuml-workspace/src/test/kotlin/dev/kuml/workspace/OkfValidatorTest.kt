package dev.kuml.workspace

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files

class OkfValidatorTest :
    FunSpec({

        fun tempWorkspace(): File = Files.createTempDirectory("kuml-okf-validator").toFile()

        test("a new FT-2 diagram type (BpmnProcess) with a kuml block passes validation") {
            val root = tempWorkspace()
            File(root, "checkout.md").writeText(
                """
                |---
                |type: BpmnProcess
                |---
                |```kuml
                |classDiagram(name = "Placeholder") {
                |    val a = classOf(name = "A")
                |}
                |```
                """.trimMargin(),
            )

            val ws = WorkspaceScanner.scan(root)
            val findings = OkfValidator.validate(ws)
            findings.map { it.code } shouldNotContain "OKF-E-003"

            root.deleteRecursively()
        }

        test("a new FT-2 diagram type (BpmnProcess) without a kuml block fails with OKF-E-003") {
            val root = tempWorkspace()
            File(root, "checkout.md").writeText(
                """
                |---
                |type: BpmnProcess
                |---
                |# No diagram block at all.
                """.trimMargin(),
            )

            val ws = WorkspaceScanner.scan(root)
            val findings = OkfValidator.validate(ws)
            findings.map { it.code } shouldBe listOf("OKF-E-003", "OKF-W-006")

            root.deleteRecursively()
        }

        test("strictVocabulary escalates an unknown type from WARNING to ERROR, same code") {
            val root = tempWorkspace()
            File(root, "typo.md").writeText(
                """
                |---
                |type: UmlClassDigram
                |---
                |Body.
                """.trimMargin(),
            )

            val ws = WorkspaceScanner.scan(root)
            val lenient = OkfValidator.validate(ws, strictVocabulary = false)
            val strict = OkfValidator.validate(ws, strictVocabulary = true)

            val lenientFinding = lenient.single { it.code == "OKF-W-002" }
            val strictFinding = strict.single { it.code == "OKF-W-002" }
            lenientFinding.severity shouldBe OkfSeverity.WARNING
            strictFinding.severity shouldBe OkfSeverity.ERROR

            root.deleteRecursively()
        }

        test("a near-miss type yields a did-you-mean suggestion") {
            val root = tempWorkspace()
            File(root, "typo.md").writeText(
                """
                |---
                |type: UmlClassDigram
                |---
                |Body.
                """.trimMargin(),
            )

            val ws = WorkspaceScanner.scan(root)
            val finding = OkfValidator.validate(ws).single { it.code == "OKF-W-002" }
            finding.suggestion?.contains("Did you mean 'UmlClassDiagram'?") shouldBe true

            root.deleteRecursively()
        }
    })
