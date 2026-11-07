package dev.kuml.workspace

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files

class WorkspaceScannerDiagramScriptsTest :
    FunSpec({
        fun write(
            root: File,
            rel: String,
        ) {
            val f = File(root, rel)
            f.parentFile.mkdirs()
            f.writeText("// script\n")
        }

        fun rel(
            root: File,
            files: List<File>,
        ) = files.map { it.relativeTo(WorkspaceScanner.canonicalRoot(root)).invariantSeparatorsPath }

        test("nested scripts are found, sorted, and unrelated files are ignored") {
            val root = Files.createTempDirectory("kuml-scripts").toFile()
            try {
                write(root, "b.kuml.kts")
                write(root, "a/z.kuml.kts")
                write(root, "a/a.kuml.kts")
                write(root, "x.kts")
                write(root, "x.kuml.kts.bak")
                write(root, "notes.md")
                rel(root, WorkspaceScanner.diagramScripts(root = root)) shouldBe listOf("a/a.kuml.kts", "a/z.kuml.kts", "b.kuml.kts")
            } finally {
                root.deleteRecursively()
            }
        }

        test("hidden, excluded and symlinked directories are skipped") {
            val root = Files.createTempDirectory("kuml-scripts").toFile()
            val outside = Files.createTempDirectory("kuml-scripts-outside").toFile()
            try {
                write(root, "keep.kuml.kts")
                write(root, ".hidden/h.kuml.kts")
                write(root, "out/e.kuml.kts")
                write(outside, "o.kuml.kts")
                runCatching { Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath()) }
                rel(root, WorkspaceScanner.diagramScripts(root = root, excludeDirs = setOf(File(root, "out")))) shouldBe
                    listOf("keep.kuml.kts")
            } finally {
                root.deleteRecursively()
                outside.deleteRecursively()
            }
        }

        test("the file cap aborts the scan") {
            val root = Files.createTempDirectory("kuml-scripts").toFile()
            try {
                repeat(3) { write(root, "s$it.kuml.kts") }
                shouldThrow<IllegalArgumentException> {
                    WorkspaceScanner.diagramScripts(root = root, excludeDirs = emptySet(), maxFiles = 2)
                }
                WorkspaceScanner.diagramScripts(root = root, excludeDirs = emptySet(), maxFiles = 3).size shouldBe 3
            } finally {
                root.deleteRecursively()
            }
        }
    })
