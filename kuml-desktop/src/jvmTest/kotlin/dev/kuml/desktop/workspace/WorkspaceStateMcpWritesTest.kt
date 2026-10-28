package dev.kuml.desktop.workspace

import dev.kuml.desktop.i18n.Strings
import dev.kuml.workspace.WorkspaceScanner
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.nio.file.Files

/** [WorkspaceState.writeDocument] / [createDocument] / [deleteDocument] and the disk-conflict flag. */
class WorkspaceStateMcpWritesTest :
    FunSpec({

        val theme = "plain"
        val s = Strings.EN
        val v1 = "---\ntype: Concept\ntitle: A\n---\n# A original\n"
        val v2 = "---\ntype: Concept\ntitle: A\n---\n# A by mcp\n"

        fun newWorkspace(): File {
            val root = Files.createTempDirectory("kuml-ws-mcp-writes").toFile()
            File(root, "index.md").writeText("---\ntype: KumlWorkspace\n---\n# Index\n")
            File(root, "a.md").writeText(v1)
            return root
        }

        fun open(root: File) = WorkspaceState(initialWorkspace = WorkspaceScanner.scan(root = root))

        test("writeDocument on the selected clean document updates buffer, baseline and documents") {
            val root = newWorkspace()
            try {
                val state = open(root)
                state.select(doc = state.documents.first { it.relativePath == "a.md" }, themeName = theme, strings = s)
                val out =
                    state.writeDocument(
                        relativePath = "a.md",
                        content = v2,
                        baseRevision = DocumentRevision.of(v1),
                        themeName = theme,
                        strings = s,
                    )
                out.shouldBeInstanceOf<WorkspaceWriteOutcome.Written>().revision shouldBe DocumentRevision.of(v2)
                File(root, "a.md").readText() shouldBe v2
                state.buffer shouldBe v2
                state.isDirty shouldBe false
                state.diskConflict shouldBe false
            } finally {
                root.deleteRecursively()
            }
        }

        test("writeDocument on a dirty open document yields Dirty and leaves the file untouched") {
            val root = newWorkspace()
            try {
                val state = open(root)
                state.select(doc = state.documents.first { it.relativePath == "a.md" }, themeName = theme, strings = s)
                state.updateBuffer("---\ntype: Concept\n---\nmy edit")
                val out =
                    state.writeDocument(
                        relativePath = "a.md",
                        content = v2,
                        baseRevision = DocumentRevision.of(v1),
                        themeName = theme,
                        strings = s,
                    )
                out shouldBe WorkspaceWriteOutcome.Dirty
                File(root, "a.md").readText() shouldBe v1
                state.buffer!! shouldContain "my edit"
            } finally {
                root.deleteRecursively()
            }
        }

        test("writeDocument with a stale revision is a conflict and does not write") {
            val root = newWorkspace()
            try {
                val state = open(root)
                val out =
                    state.writeDocument(
                        relativePath = "a.md",
                        content = v2,
                        baseRevision = "deadbeefdeadbeef",
                        themeName = theme,
                        strings = s,
                    )
                out.shouldBeInstanceOf<WorkspaceWriteOutcome.RevisionConflict>().currentRevision shouldBe DocumentRevision.of(v1)
                File(root, "a.md").readText() shouldBe v1
            } finally {
                root.deleteRecursively()
            }
        }

        test("writeDocument refuses path escapes and unknown documents") {
            val root = newWorkspace()
            try {
                val state = open(root)
                state
                    .writeDocument(
                        relativePath = "../x.md",
                        content = v2,
                        baseRevision = "x",
                        themeName = theme,
                        strings = s,
                    ).shouldBeInstanceOf<WorkspaceWriteOutcome.PathRejected>()
                state.writeDocument(relativePath = "missing.md", content = v2, baseRevision = "x", themeName = theme, strings = s) shouldBe
                    WorkspaceWriteOutcome.NotFound
            } finally {
                root.deleteRecursively()
            }
        }

        test("createDocument adds a document to the set; a second create is a conflict") {
            val root = newWorkspace()
            try {
                val state = open(root)
                val out = state.createDocument(relativePath = "models/sub/new.md", content = v2, themeName = theme, strings = s)
                out.shouldBeInstanceOf<WorkspaceWriteOutcome.Written>().created shouldBe true
                state.documents.map { it.relativePath } shouldContain "models/sub/new.md"
                File(root, "models/sub/new.md").readText() shouldBe v2
                val again = state.createDocument(relativePath = "models/sub/new.md", content = v1, themeName = theme, strings = s)
                again.shouldBeInstanceOf<WorkspaceWriteOutcome.RevisionConflict>().currentRevision shouldBe DocumentRevision.of(v2)
            } finally {
                root.deleteRecursively()
            }
        }

        test("createDocument blocked by the save gate creates nothing") {
            val root = newWorkspace()
            try {
                val state = open(root)
                val out =
                    state.createDocument(
                        relativePath = "x/y.md",
                        content = "---\ntype: Quatsch\n---\nx",
                        themeName = theme,
                        strings = s,
                    )
                out.shouldBeInstanceOf<WorkspaceWriteOutcome.Blocked>()
                File(root, "x").exists() shouldBe false
            } finally {
                root.deleteRecursively()
            }
        }

        test("deleteDocument removes the file and deselects a clean selected document") {
            val root = newWorkspace()
            try {
                val state = open(root)
                state.select(doc = state.documents.first { it.relativePath == "a.md" }, themeName = theme, strings = s)
                val out =
                    state.deleteDocument(
                        relativePath = "a.md",
                        baseRevision = DocumentRevision.of(v1),
                        themeName = theme,
                        strings = s,
                    )
                out shouldBe WorkspaceDeleteOutcome.Deleted("a.md")
                File(root, "a.md").exists() shouldBe false
                state.documents.map { it.relativePath } shouldNotContain "a.md"
                state.selected shouldBe null
            } finally {
                root.deleteRecursively()
            }
        }

        test("deleteDocument: dirty target and wrong revision are refused") {
            val root = newWorkspace()
            try {
                val state = open(root)
                state
                    .deleteDocument(
                        relativePath = "a.md",
                        baseRevision = "0000000000000000",
                        themeName = theme,
                        strings = s,
                    ).shouldBeInstanceOf<WorkspaceDeleteOutcome.RevisionConflict>()
                File(root, "a.md").exists() shouldBe true
                state.select(doc = state.documents.first { it.relativePath == "a.md" }, themeName = theme, strings = s)
                state.updateBuffer("changed")
                state.deleteDocument(relativePath = "a.md", baseRevision = null, themeName = theme, strings = s) shouldBe
                    WorkspaceDeleteOutcome.Dirty
                File(root, "a.md").exists() shouldBe true
            } finally {
                root.deleteRecursively()
            }
        }

        test("external change under a dirty buffer sets diskConflict and keeps the buffer; keepMine clears it") {
            val root = newWorkspace()
            try {
                val state = open(root)
                state.select(doc = state.documents.first { it.relativePath == "a.md" }, themeName = theme, strings = s)
                state.updateBuffer("---\ntype: Concept\n---\nmine")
                File(root, "a.md").writeText(v2)
                state.refreshFromDisk(themeName = theme, strings = s)
                state.diskConflict shouldBe true
                state.buffer!! shouldContain "mine"

                state.resolveDiskConflict(keepMine = true, themeName = theme, strings = s)
                state.diskConflict shouldBe false
                state.buffer!! shouldContain "mine"
                state.isDirty shouldBe true
            } finally {
                root.deleteRecursively()
            }
        }

        test("resolveDiskConflict(keepMine=false) reloads the file from disk") {
            val root = newWorkspace()
            try {
                val state = open(root)
                state.select(doc = state.documents.first { it.relativePath == "a.md" }, themeName = theme, strings = s)
                state.updateBuffer("---\ntype: Concept\n---\nmine")
                File(root, "a.md").writeText(v2)
                state.refreshFromDisk(themeName = theme, strings = s)
                state.resolveDiskConflict(keepMine = false, themeName = theme, strings = s)
                state.buffer shouldBe v2
                state.isDirty shouldBe false
                state.diskConflict shouldBe false
            } finally {
                root.deleteRecursively()
            }
        }
    })
