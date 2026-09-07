package dev.kuml.workspace

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.nio.file.Files

class OkfSaveGateTest :
    FunSpec({

        fun tempWorkspace(): File = Files.createTempDirectory("kuml-okf-save-gate").toFile()

        fun validate(
            root: File,
            markdown: String,
        ): List<OkfFinding> {
            val doc = OkfDocumentParser.parse(root = root, file = File(root, "doc.md"), text = markdown)
            return OkfValidator.validateDocument(root = root, doc = doc, strictVocabulary = true)
        }

        test("an unrecognised type: (OKF-W-002) blocks the save") {
            val root = tempWorkspace()
            try {
                val findings = validate(root = root, markdown = "---\ntype: Blödsinn\n---\nBody")
                val decision = OkfSaveGate.decide(findings)
                val blocked = decision.shouldBeInstanceOf<OkfSaveDecision.Block>()
                blocked.blocking.map { it.code } shouldBe listOf("OKF-W-002")
            } finally {
                root.deleteRecursively()
            }
        }

        test("missing frontmatter (OKF-E-001) blocks the save") {
            val root = tempWorkspace()
            try {
                val findings = validate(root = root, markdown = "# No frontmatter at all.")
                val decision = OkfSaveGate.decide(findings)
                val blocked = decision.shouldBeInstanceOf<OkfSaveDecision.Block>()
                blocked.blocking.map { it.code } shouldBe listOf("OKF-E-001")
            } finally {
                root.deleteRecursively()
            }
        }

        test("OKF-E-003 (missing kuml block for a diagram type) does not block") {
            val root = tempWorkspace()
            try {
                val findings = validate(root = root, markdown = "---\ntype: UmlClassDiagram\n---\nNo diagram block.")
                findings.map { it.code } shouldBe listOf("OKF-E-003")
                OkfSaveGate.decide(findings).shouldBeInstanceOf<OkfSaveDecision.Allow>()
            } finally {
                root.deleteRecursively()
            }
        }

        test("OKF-W-004 (more than one kuml block) does not block") {
            val root = tempWorkspace()
            try {
                val md =
                    """
                    |---
                    |type: UmlClassDiagram
                    |---
                    |```kuml
                    |classDiagram(name = "A") { }
                    |```
                    |
                    |```kuml
                    |classDiagram(name = "B") { }
                    |```
                    """.trimMargin()
                val findings = validate(root = root, markdown = md)
                findings.map { it.code } shouldBe listOf("OKF-W-004")
                OkfSaveGate.decide(findings).shouldBeInstanceOf<OkfSaveDecision.Allow>()
            } finally {
                root.deleteRecursively()
            }
        }

        test("OKF-E-005 (a broken link) does not block") {
            val root = tempWorkspace()
            try {
                val findings =
                    validate(root = root, markdown = "---\ntype: Concept\n---\n[missing](./nowhere.md)")
                findings.map { it.code } shouldBe listOf("OKF-E-005")
                OkfSaveGate.decide(findings).shouldBeInstanceOf<OkfSaveDecision.Allow>()
            } finally {
                root.deleteRecursively()
            }
        }

        test("all 29 OkfType.id values pass with no findings when properly formed") {
            val root = tempWorkspace()
            try {
                OkfType.entries.forEach { type ->
                    val md =
                        if (type.requiresKumlBlock) {
                            "---\ntype: ${type.id}\n---\n```kuml\nclassDiagram(name = \"X\") { }\n```\n"
                        } else {
                            "---\ntype: ${type.id}\n---\nProse.\n"
                        }
                    val findings = validate(root = root, markdown = md)
                    findings.shouldBeEmpty()
                    OkfSaveGate.decide(findings).shouldBeInstanceOf<OkfSaveDecision.Allow>()
                }
            } finally {
                root.deleteRecursively()
            }
        }

        test("mixed findings: Block carries only the blocking codes, warnings carry the rest") {
            val root = tempWorkspace()
            try {
                val md =
                    """
                    |---
                    |type: Blödsinn
                    |---
                    |[missing](./nowhere.md)
                    """.trimMargin()
                val findings = validate(root = root, markdown = md)
                findings.map { it.code }.toSet() shouldBe setOf("OKF-W-002", "OKF-E-005")

                val decision = OkfSaveGate.decide(findings)
                val blocked = decision.shouldBeInstanceOf<OkfSaveDecision.Block>()
                blocked.blocking.map { it.code } shouldBe listOf("OKF-W-002")
                blocked.warnings.map { it.code } shouldBe listOf("OKF-E-005")
            } finally {
                root.deleteRecursively()
            }
        }
    })
