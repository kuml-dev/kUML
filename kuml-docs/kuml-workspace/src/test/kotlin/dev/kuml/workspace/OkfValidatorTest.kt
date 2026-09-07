package dev.kuml.workspace

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
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

            val ws = WorkspaceScanner.scan(root = root)
            val findings = OkfValidator.validate(ws = ws)
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

            val ws = WorkspaceScanner.scan(root = root)
            val findings = OkfValidator.validate(ws = ws)
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

            val ws = WorkspaceScanner.scan(root = root)
            val lenient = OkfValidator.validate(ws = ws, strictVocabulary = false)
            val strict = OkfValidator.validate(ws = ws, strictVocabulary = true)

            val lenientFinding = lenient.single { it.code == "OKF-W-002" }
            val strictFinding = strict.single { it.code == "OKF-W-002" }
            lenientFinding.severity shouldBe OkfSeverity.WARNING
            strictFinding.severity shouldBe OkfSeverity.ERROR

            root.deleteRecursively()
        }

        test("a root-absolute link ('/...') from a nested document resolves against the workspace root, not OKF-E-005") {
            val root = tempWorkspace()
            File(root, "notes").mkdir()
            File(root, "notes/target.md").writeText("Target.")
            File(root, "notes/source.md").writeText(
                """
                |[link](/notes/target.md)
                """.trimMargin(),
            )

            val ws = WorkspaceScanner.scan(root = root)
            val findings = OkfValidator.validate(ws = ws)
            findings.map { it.code } shouldNotContain "OKF-E-005"

            root.deleteRecursively()
        }

        test("a broken root-absolute link ('/...') from a nested document is still reported as OKF-E-005") {
            val root = tempWorkspace()
            File(root, "notes").mkdir()
            File(root, "notes/source.md").writeText(
                """
                |[link](/notes/missing.md)
                """.trimMargin(),
            )

            val ws = WorkspaceScanner.scan(root = root)
            val findings = OkfValidator.validate(ws = ws)
            findings.map { it.code } shouldContain "OKF-E-005"

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

            val ws = WorkspaceScanner.scan(root = root)
            val finding = OkfValidator.validate(ws = ws).single { it.code == "OKF-W-002" }
            finding.suggestion?.contains("Did you mean 'UmlClassDiagram'?") shouldBe true

            root.deleteRecursively()
        }

        test("validate(ws) equals the per-document flatMap of validateDocument, plus OKF-W-006") {
            val root = tempWorkspace()
            File(root, "checkout.md").writeText(
                """
                |---
                |type: UmlClassDiagram
                |---
                |# No diagram block at all.
                """.trimMargin(),
            )
            File(root, "typo.md").writeText(
                """
                |---
                |type: UmlClassDigram
                |---
                |Body.
                """.trimMargin(),
            )

            val ws = WorkspaceScanner.scan(root = root)
            val expected =
                ws.documents.flatMap { OkfValidator.validateDocument(root = ws.root, doc = it) } +
                    listOf(
                        OkfFinding(
                            code = "OKF-W-006",
                            severity = OkfSeverity.WARNING,
                            file = "index.md",
                            line = 1,
                            message = "Workspace root has no index.md with 'type: KumlWorkspace'.",
                            suggestion = "Add an index.md at the workspace root with 'type: KumlWorkspace' as a navigational entry point.",
                        ),
                    )

            OkfValidator.validate(ws = ws) shouldBe expected

            root.deleteRecursively()
        }

        test("validateDocument never reports OKF-W-006 (workspace-wide, not dokumentlokal)") {
            val root = tempWorkspace()
            val doc =
                OkfDocumentParser.parse(
                    root = root,
                    file = File(root, "index.md"),
                    text = "# No frontmatter, no index type.",
                )
            OkfValidator.validateDocument(root = root, doc = doc).map { it.code } shouldNotContain "OKF-W-006"

            root.deleteRecursively()
        }
    })
