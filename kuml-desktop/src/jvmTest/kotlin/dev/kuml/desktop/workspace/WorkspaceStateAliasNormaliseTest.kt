package dev.kuml.desktop.workspace

import dev.kuml.desktop.i18n.Strings
import dev.kuml.workspace.WorkspaceScanner
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files

class WorkspaceStateAliasNormaliseTest :
    FunSpec({
        fun workspace(): File {
            val root = Files.createTempDirectory("kuml-alias-normalise-test").toFile()
            File(root, "index.md").writeText("---\ntype: KumlWorkspace\n---\n# Index\n")
            File(root, "alias.md").writeText("---\ntype: Sysml2Bdd\n---\n# Alias doc\n")
            File(root, "canon.md").writeText("---\ntype: Concept\n---\n# Canon doc\n")
            return root
        }

        suspend fun open(
            root: File,
            name: String,
        ): WorkspaceState {
            val state = WorkspaceState(initialWorkspace = WorkspaceScanner.scan(root = root))
            state.select(doc = state.documents.first { it.relativePath == name }, themeName = "plain", strings = Strings.EN)
            return state
        }

        test("read-mode path replaces the buffer, marks it dirty, no notice") {
            val root = workspace()
            try {
                val state = open(root, "alias.md")
                state.normaliseAliasType(null)
                state.buffer shouldBe "---\ntype: Sysml2BlockDefinition\n---\n# Alias doc\n"
                state.isDirty shouldBe true
                state.aliasNoticeVisible shouldBe false
                File(root, "alias.md").readText() shouldBe "---\ntype: Sysml2Bdd\n---\n# Alias doc\n"
            } finally {
                root.deleteRecursively()
            }
        }

        test("undo callback is invoked once with the new text and the buffer follows") {
            val root = workspace()
            try {
                val state = open(root, "alias.md")
                val calls = mutableListOf<String>()
                state.normaliseAliasType { calls += it }
                calls shouldBe listOf("---\ntype: Sysml2BlockDefinition\n---\n# Alias doc\n")
                state.buffer shouldBe calls.single()
            } finally {
                root.deleteRecursively()
            }
        }

        test("canonical buffer: unchanged, notice visible, no callback; cleared by updateBuffer and select") {
            val root = workspace()
            try {
                val state = open(root, "canon.md")
                val before = state.buffer
                var called = false
                state.normaliseAliasType { called = true }
                called shouldBe false
                state.buffer shouldBe before
                state.isDirty shouldBe false
                state.aliasNoticeVisible shouldBe true

                state.updateBuffer(before + "x")
                state.aliasNoticeVisible shouldBe false

                state.normaliseAliasType(null)
                state.aliasNoticeVisible shouldBe true
                state.select(doc = state.documents.first { it.relativePath == "alias.md" }, themeName = "plain", strings = Strings.EN)
                state.aliasNoticeVisible shouldBe false
            } finally {
                root.deleteRecursively()
            }
        }
    })
