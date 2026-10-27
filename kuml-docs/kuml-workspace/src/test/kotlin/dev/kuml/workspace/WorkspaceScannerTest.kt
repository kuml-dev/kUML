package dev.kuml.workspace

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotStartWith
import java.io.File
import java.nio.file.Files

class WorkspaceScannerTest :
    FunSpec({

        fun fixture(): File {
            val root = Files.createTempDirectory("kuml-scan-test").toFile()
            File(root, "index.md").writeText("---\ntype: KumlWorkspace\n---\n# Index\n")
            File(root, "models").mkdirs()
            File(root, "models/a.md").writeText("---\ntype: Concept\n---\nA\n")
            File(root, "models/b.md").writeText("---\ntype: Concept\n---\nB\n")
            return root
        }

        val expected = listOf("index.md", "models/a.md", "models/b.md")

        fun scanned(root: File) = WorkspaceScanner.scan(root = root).documents.map { it.relativePath.replace('\\', '/') }

        test("a root given as 'dir/.' finds all documents") {
            val tmp = fixture()
            try {
                scanned(File(tmp, ".")) shouldContainExactlyInAnyOrder expected
            } finally {
                tmp.deleteRecursively()
            }
        }

        test("a root given as 'dir/sub/..' finds all documents") {
            val tmp = fixture()
            try {
                scanned(File(tmp, "models/..")) shouldContainExactlyInAnyOrder expected
            } finally {
                tmp.deleteRecursively()
            }
        }

        test("a root that is itself a symlink is scanned") {
            val tmp = fixture()
            val link = File(tmp.parentFile, "kuml-scan-link-${System.nanoTime()}")
            try {
                val created = runCatching { Files.createSymbolicLink(link.toPath(), tmp.toPath()) }.isSuccess
                if (created) scanned(link) shouldContainExactlyInAnyOrder expected
            } finally {
                Files.deleteIfExists(link.toPath())
                tmp.deleteRecursively()
            }
        }

        test("a root whose own name starts with a dot is scanned") {
            val parent = Files.createTempDirectory("kuml-scan-dot").toFile()
            val root = File(parent, ".notes").apply { mkdirs() }
            File(root, "x.md").writeText("---\ntype: Concept\n---\nX\n")
            try {
                scanned(root) shouldBe listOf("x.md")
            } finally {
                parent.deleteRecursively()
            }
        }

        test("hidden sub-directories are not scanned") {
            val tmp = fixture()
            try {
                File(tmp, ".hidden").mkdirs()
                File(tmp, ".hidden/x.md").writeText("---\ntype: Concept\n---\nX\n")
                scanned(tmp) shouldContainExactlyInAnyOrder expected
            } finally {
                tmp.deleteRecursively()
            }
        }

        test("symlinked sub-directories are not scanned") {
            val tmp = fixture()
            val outside = Files.createTempDirectory("kuml-scan-outside").toFile()
            try {
                File(outside, "o.md").writeText("---\ntype: Concept\n---\nO\n")
                val created =
                    runCatching { Files.createSymbolicLink(File(tmp, "linked").toPath(), outside.toPath()) }.isSuccess
                if (created) scanned(tmp) shouldContainExactlyInAnyOrder expected
            } finally {
                tmp.deleteRecursively()
                outside.deleteRecursively()
            }
        }

        test("relative paths have no './' prefix and the workspace root is normalised") {
            val tmp = fixture()
            try {
                val ws = WorkspaceScanner.scan(root = File(tmp, "."))
                ws.documents.forEach { it.relativePath shouldNotStartWith "." }
                ws.root shouldBe tmp.absoluteFile.normalize()
            } finally {
                tmp.deleteRecursively()
            }
        }

        test("scanDocument parses exactly one document without scanning the rest") {
            val tmp = fixture()
            try {
                File(tmp, "huge-broken.md").writeText("---\ntype: Concept\n---\nok\n")
                val doc = WorkspaceScanner.scanDocument(root = tmp, relativePath = "models/a.md").getOrThrow()
                doc.relativePath.replace('\\', '/') shouldBe "models/a.md"
            } finally {
                tmp.deleteRecursively()
            }
        }
    })
