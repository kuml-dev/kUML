package dev.kuml.desktop.workspace

import dev.kuml.desktop.i18n.Strings
import dev.kuml.workspace.WorkspaceScanner
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files

/**
 * External changes to an open workspace: [WorkspaceChangeDetector]'s fingerprint and
 * [WorkspaceState.refreshFromDisk].
 */
class WorkspaceRefreshFromDiskTest :
    FunSpec({

        fun newWorkspace(): File {
            val root = Files.createTempDirectory("kuml-workspace-refresh-test").toFile()
            File(root, "index.md").writeText("---\ntype: KumlWorkspace\n---\n# Index\n")
            File(root, "a.md").writeText("---\ntype: Concept\ntitle: A\n---\n# A original\n")
            return root
        }

        fun open(root: File) = WorkspaceState(initialWorkspace = WorkspaceScanner.scan(root = root))

        test("fingerprint changes when a file is added, edited or removed and ignores other files") {
            val root = newWorkspace()
            try {
                val base = WorkspaceChangeDetector.fingerprint(root)
                WorkspaceChangeDetector.fingerprint(root) shouldBe base

                File(root, "notes.txt").writeText("irrelevant")
                WorkspaceChangeDetector.fingerprint(root) shouldBe base

                File(root, "b.md").writeText("---\ntype: Concept\n---\n# B\n")
                val withB = WorkspaceChangeDetector.fingerprint(root)
                withB shouldNotBe base

                File(root, "b.md").writeText("---\ntype: Concept\n---\n# B, now longer\n")
                val edited = WorkspaceChangeDetector.fingerprint(root)
                edited shouldNotBe withB

                File(root, "b.md").delete()
                WorkspaceChangeDetector.fingerprint(root) shouldBe base
            } finally {
                root.deleteRecursively()
            }
        }

        test("fingerprint also tracks kuml scripts and skips hidden directories") {
            val root = newWorkspace()
            try {
                val base = WorkspaceChangeDetector.fingerprint(root)
                File(root, ".hidden").mkdirs()
                File(root, ".hidden/x.md").writeText("# hidden")
                WorkspaceChangeDetector.fingerprint(root) shouldBe base

                File(root, "m.kuml.kts").writeText("// script")
                WorkspaceChangeDetector.fingerprint(root) shouldNotBe base
            } finally {
                root.deleteRecursively()
            }
        }

        test("refreshFromDisk adds a new document to the tree") {
            val root = newWorkspace()
            try {
                val state = open(root)
                File(root, "new.md").writeText("---\ntype: Concept\ntitle: New\n---\n# New\n")

                state.refreshFromDisk(themeName = "plain", strings = Strings.EN)

                state.documents.map { it.relativePath } shouldContain "new.md"
            } finally {
                root.deleteRecursively()
            }
        }

        test("refreshFromDisk reloads the open document when it changed on disk and the buffer is clean") {
            val root = newWorkspace()
            try {
                val state = open(root)
                state.select(doc = state.documents.first { it.relativePath == "a.md" }, themeName = "plain", strings = Strings.EN)
                File(root, "a.md").writeText("---\ntype: Concept\ntitle: A\n---\n# A changed elsewhere\n")

                state.refreshFromDisk(themeName = "plain", strings = Strings.EN)

                state.buffer!! shouldContain "changed elsewhere"
                state.isDirty shouldBe false
            } finally {
                root.deleteRecursively()
            }
        }

        test("refreshFromDisk never overwrites unsaved edits") {
            val root = newWorkspace()
            try {
                val state = open(root)
                state.select(doc = state.documents.first { it.relativePath == "a.md" }, themeName = "plain", strings = Strings.EN)
                state.updateBuffer("---\ntype: Concept\ntitle: A\n---\n# my unsaved edit\n")
                File(root, "a.md").writeText("---\ntype: Concept\ntitle: A\n---\n# changed elsewhere\n")

                state.refreshFromDisk(themeName = "plain", strings = Strings.EN)

                state.buffer!! shouldContain "my unsaved edit"
                state.isDirty shouldBe true
            } finally {
                root.deleteRecursively()
            }
        }

        test("refreshFromDisk deselects a deleted clean document and keeps a deleted dirty one open") {
            val root = newWorkspace()
            try {
                val state = open(root)
                state.select(doc = state.documents.first { it.relativePath == "a.md" }, themeName = "plain", strings = Strings.EN)
                File(root, "a.md").delete()

                state.refreshFromDisk(themeName = "plain", strings = Strings.EN)

                state.documents.map { it.relativePath } shouldNotContain "a.md"
                state.selected shouldBe null
                state.buffer shouldBe null

                File(root, "a.md").writeText("---\ntype: Concept\ntitle: A\n---\n# back\n")
                state.refreshFromDisk(themeName = "plain", strings = Strings.EN)
                state.select(doc = state.documents.first { it.relativePath == "a.md" }, themeName = "plain", strings = Strings.EN)
                state.updateBuffer("---\ntype: Concept\ntitle: A\n---\n# edited\n")
                File(root, "a.md").delete()

                state.refreshFromDisk(themeName = "plain", strings = Strings.EN)

                state.selected shouldNotBe null
                state.buffer!! shouldContain "edited"
            } finally {
                root.deleteRecursively()
            }
        }

        test("a save of our own is not mistaken for an external change") {
            val root = newWorkspace()
            try {
                val state = open(root)
                val doc = state.documents.first { it.relativePath == "a.md" }
                state.select(doc = doc, themeName = "plain", strings = Strings.EN)
                state.updateBuffer("---\ntype: Concept\ntitle: A\n---\n# saved by us\n")
                state.save(themeName = "plain", strings = Strings.EN)

                state.refreshFromDisk(themeName = "plain", strings = Strings.EN)

                state.buffer!! shouldContain "saved by us"
                state.isDirty shouldBe false
            } finally {
                root.deleteRecursively()
            }
        }
    })
