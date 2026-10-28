package dev.kuml.workspace

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.nio.file.Files

class WorkspaceCreateDeleteTest :
    FunSpec({

        val valid = "---\ntype: Concept\n---\nbody"

        fun ws(): File = Files.createTempDirectory("kuml-ws-create").toFile()

        fun rejectionOf(
            root: File,
            path: String,
        ): WorkspaceDocumentResolver.Rejection? =
            WorkspaceDocumentResolver
                .resolveForCreate(root = root, requested = path)
                .exceptionOrNull()
                ?.let { (it as WorkspaceDocumentResolver.DocumentResolutionException).rejection }

        fun symlinkOrNull(
            link: File,
            target: File,
        ): Boolean =
            try {
                Files.createSymbolicLink(link.toPath(), target.toPath())
                true
            } catch (_: Exception) {
                false
            }

        test("resolveForCreate rejects path-shape attacks") {
            val root = ws()
            try {
                val cases =
                    mapOf(
                        "../x.md" to WorkspaceDocumentResolver.Rejection.PARENT_SEGMENT,
                        "a/../b.md" to WorkspaceDocumentResolver.Rejection.PARENT_SEGMENT,
                        "./x.md" to WorkspaceDocumentResolver.Rejection.PARENT_SEGMENT,
                        "/abs.md" to WorkspaceDocumentResolver.Rejection.ABSOLUTE_PATH,
                        "C:\\x.md" to WorkspaceDocumentResolver.Rejection.ABSOLUTE_PATH,
                        "c:x.md" to WorkspaceDocumentResolver.Rejection.ABSOLUTE_PATH,
                        "\\\\server\\share\\x.md" to WorkspaceDocumentResolver.Rejection.ABSOLUTE_PATH,
                        "//x.md" to WorkspaceDocumentResolver.Rejection.ABSOLUTE_PATH,
                        "a\u0000b.md" to WorkspaceDocumentResolver.Rejection.EMPTY,
                        "" to WorkspaceDocumentResolver.Rejection.EMPTY,
                        "CON.md" to WorkspaceDocumentResolver.Rejection.INVALID_NAME,
                        "lpt1.md" to WorkspaceDocumentResolver.Rejection.INVALID_NAME,
                        "dir/a:b.md" to WorkspaceDocumentResolver.Rejection.INVALID_NAME,
                        "a?.md" to WorkspaceDocumentResolver.Rejection.INVALID_NAME,
                        "bad./x.md" to WorkspaceDocumentResolver.Rejection.INVALID_NAME,
                        "trail /x.md" to WorkspaceDocumentResolver.Rejection.INVALID_NAME,
                        "a//b.md" to WorkspaceDocumentResolver.Rejection.INVALID_NAME,
                        ".hidden/x.md" to WorkspaceDocumentResolver.Rejection.EXCLUDED,
                        "x.MD" to WorkspaceDocumentResolver.Rejection.NOT_MARKDOWN,
                        "x.txt" to WorkspaceDocumentResolver.Rejection.NOT_MARKDOWN,
                        "x.kuml.kts" to WorkspaceDocumentResolver.Rejection.NOT_MARKDOWN,
                        "a/b/c/d/e/f/g/h/i/j/k/l/m/n/o/p/q.md" to WorkspaceDocumentResolver.Rejection.TOO_DEEP,
                        ("a".repeat(256) + ".md") to WorkspaceDocumentResolver.Rejection.INVALID_NAME,
                    )
                cases.forEach { (path, expected) -> rejectionOf(root, path) shouldBe expected }
            } finally {
                root.deleteRecursively()
            }
        }

        test("resolveForCreate: existing target, file as directory segment, symlinks") {
            val root = ws()
            try {
                File(root, "have.md").writeText(valid)
                rejectionOf(root, "have.md") shouldBe WorkspaceDocumentResolver.Rejection.ALREADY_EXISTS
                rejectionOf(root, "have.md/x.md") shouldBe WorkspaceDocumentResolver.Rejection.INVALID_NAME
                val outside = Files.createTempDirectory("kuml-ws-outside").toFile()
                try {
                    if (symlinkOrNull(File(root, "link"), outside)) {
                        rejectionOf(root, "link/x.md") shouldBe WorkspaceDocumentResolver.Rejection.SYMLINK_ESCAPE
                    }
                    if (symlinkOrNull(File(root, "dangling.md"), File(root, "nowhere.md"))) {
                        rejectionOf(root, "dangling.md") shouldBe WorkspaceDocumentResolver.Rejection.SYMLINK_ESCAPE
                    }
                } finally {
                    outside.deleteRecursively()
                }
                WorkspaceDocumentResolver.resolveForCreate(root = root, requested = "models/sub/new.md").isSuccess shouldBe true
            } finally {
                root.deleteRecursively()
            }
        }

        test("create writes a new document including sub directories") {
            val root = ws()
            try {
                val result = OkfDocumentWriter.create(root = root, relativePath = "models/sub/new.md", content = valid)
                result.shouldBeInstanceOf<OkfWriteResult.Written>()
                File(root, "models/sub/new.md").readText() shouldBe valid
                root.walkTopDown().filter { it.name.endsWith(".tmp") }.count() shouldBe 0
            } finally {
                root.deleteRecursively()
            }
        }

        test("create blocked by the save gate creates nothing, not even directories") {
            val root = ws()
            try {
                val result = OkfDocumentWriter.create(root = root, relativePath = "deep/dir/new.md", content = "---\ntype: Quatsch\n---\nx")
                result.shouldBeInstanceOf<OkfWriteResult.Blocked>()
                File(root, "deep").exists() shouldBe false
            } finally {
                root.deleteRecursively()
            }
        }

        test("create refuses an existing document and leaves it byte-identical") {
            val root = ws()
            try {
                File(root, "a.md").writeText(valid)
                val result = OkfDocumentWriter.create(root = root, relativePath = "a.md", content = "---\ntype: Concept\n---\nother")
                val rej = result.shouldBeInstanceOf<OkfWriteResult.CreateRejected>()
                rej.rejection shouldBe WorkspaceDocumentResolver.Rejection.ALREADY_EXISTS
                File(root, "a.md").readText() shouldBe valid
            } finally {
                root.deleteRecursively()
            }
        }

        test("create refuses path traversal and writes nothing outside the root") {
            val parent = ws()
            try {
                val root = File(parent, "root").apply { mkdirs() }
                val result = OkfDocumentWriter.create(root = root, relativePath = "../escape.md", content = valid)
                result.shouldBeInstanceOf<OkfWriteResult.CreateRejected>()
                File(parent, "escape.md").exists() shouldBe false
            } finally {
                parent.deleteRecursively()
            }
        }

        test("create reports TooLarge for oversize content") {
            val root = ws()
            try {
                val big = valid + "x".repeat(OkfDocumentParser.MAX_MD_FILE_SIZE_BYTES.toInt() + 1)
                OkfDocumentWriter.create(root = root, relativePath = "big.md", content = big).shouldBeInstanceOf<OkfWriteResult.TooLarge>()
                File(root, "big.md").exists() shouldBe false
            } finally {
                root.deleteRecursively()
            }
        }

        test("delete removes a known document") {
            val root = ws()
            try {
                val doc = File(root, "a.md").apply { writeText(valid) }
                val r = OkfDocumentWriter.delete(root = root, target = doc, knownDocuments = listOf(doc))
                r.shouldBeInstanceOf<OkfDeleteResult.Deleted>()
                doc.exists() shouldBe false
            } finally {
                root.deleteRecursively()
            }
        }

        test("delete refuses an unknown document, a directory and a symlink") {
            val root = ws()
            try {
                val doc = File(root, "a.md").apply { writeText(valid) }
                val unknown = OkfDocumentWriter.delete(root = root, target = doc, knownDocuments = emptyList())
                unknown.shouldBeInstanceOf<OkfDeleteResult.Rejected>().rejection shouldBe
                    WorkspaceWriteGuard.Rejection.UNKNOWN_DOCUMENT
                doc.exists() shouldBe true

                val dir = File(root, "d.md").apply { mkdirs() }
                OkfDocumentWriter
                    .delete(root = root, target = dir, knownDocuments = listOf(dir))
                    .shouldBeInstanceOf<OkfDeleteResult.Rejected>()
                dir.exists() shouldBe true

                val link = File(root, "l.md")
                if (symlinkOrNull(link, doc)) {
                    OkfDocumentWriter
                        .delete(root = root, target = link, knownDocuments = listOf(link))
                        .shouldBeInstanceOf<OkfDeleteResult.Rejected>()
                    doc.exists() shouldBe true
                }
            } finally {
                root.deleteRecursively()
            }
        }
    })
