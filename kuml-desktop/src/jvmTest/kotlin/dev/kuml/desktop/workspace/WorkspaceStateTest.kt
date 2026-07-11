package dev.kuml.desktop.workspace

import dev.kuml.desktop.i18n.Strings
import dev.kuml.workspace.WorkspaceScanner
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files

class WorkspaceStateTest : FunSpec({

    fun writeSampleWorkspace(): java.io.File {
        val root = Files.createTempDirectory("kuml-workspace-state-test").toFile()

        java.io.File(root, "index.md").writeText(
            """
            ---
            type: KumlWorkspace
            ---
            # Index
            """.trimIndent(),
        )

        java.io.File(root, "prose.md").writeText(
            """
            ---
            type: Article
            ---
            # Prose article — no diagram here.
            """.trimIndent(),
        )

        java.io.File(root, "diagram.md").writeText(
            """
            ---
            type: UmlClassDiagram
            ---
            # A class diagram

            ```kuml
            classDiagram(name = "Test") {
                classOf(name = "Fahrzeug") { }
            }
            ```
            """.trimIndent(),
        )

        java.io.File(root, "erm.md").writeText(
            """
            ---
            type: ErmDiagram
            ---
            # An ERM diagram (never evaluated — short-circuited before eval)

            ```kuml
            erm(name = "Test") { }
            ```
            """.trimIndent(),
        )

        return root
    }

    test("prose document selection: no svg, no error") {
        val root = writeSampleWorkspace()
        try {
            val workspace = WorkspaceScanner.scan(root)
            val state = WorkspaceState(workspace)
            val doc = state.documents.first { it.relativePath == "prose.md" }

            state.select(doc, "plain", Strings.EN)

            state.docSvg shouldBe null
            state.docError shouldBe null
            state.selected shouldBe doc
        } finally {
            root.deleteRecursively()
        }
    }

    test("class diagram document selection: non-blank svg, no error") {
        val root = writeSampleWorkspace()
        try {
            val workspace = WorkspaceScanner.scan(root)
            val state = WorkspaceState(workspace)
            val doc = state.documents.first { it.relativePath == "diagram.md" }

            state.select(doc, "plain", Strings.EN)

            state.docError shouldBe null
            val svg = state.docSvg.shouldNotBeNull()
            svg shouldContain "<svg"
        } finally {
            root.deleteRecursively()
        }
    }

    test("ERM document selection: short-circuits to previewErmUnsupported, no exception, no svg") {
        val root = writeSampleWorkspace()
        try {
            val workspace = WorkspaceScanner.scan(root)
            val state = WorkspaceState(workspace)
            val doc = state.documents.first { it.relativePath == "erm.md" }

            state.select(doc, "plain", Strings.EN)

            state.docSvg shouldBe null
            state.docError shouldBe Strings.EN.previewErmUnsupported
        } finally {
            root.deleteRecursively()
        }
    }

    test("documents are sorted by relativePath") {
        val root = writeSampleWorkspace()
        try {
            val workspace = WorkspaceScanner.scan(root)
            val state = WorkspaceState(workspace)
            state.documents.map { it.relativePath } shouldBe state.documents.map { it.relativePath }.sorted()
        } finally {
            root.deleteRecursively()
        }
    }
})
