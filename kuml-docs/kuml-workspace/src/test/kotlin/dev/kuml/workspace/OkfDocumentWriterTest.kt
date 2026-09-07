package dev.kuml.workspace

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class OkfDocumentWriterTest :
    FunSpec({

        fun tempWorkspace(): File = Files.createTempDirectory("kuml-okf-document-writer").toFile()

        fun isPosix(dir: File): Boolean =
            dir
                .toPath()
                .fileSystem
                .supportedFileAttributeViews()
                .contains("posix")

        test("happy path: the file contains exactly the new content, result is Written") {
            val root = tempWorkspace()
            try {
                val doc = File(root, "note.md").apply { writeText("---\ntype: Concept\n---\nold body") }
                val newContent = "---\ntype: Concept\ntitle: New\n---\nnew body"

                val result =
                    OkfDocumentWriter.save(root = root, target = doc, content = newContent, knownDocuments = listOf(doc))

                result.shouldBeInstanceOf<OkfWriteResult.Written>()
                doc.readText(Charsets.UTF_8) shouldBe newContent
            } finally {
                root.deleteRecursively()
            }
        }

        test("gate blocks the save: the file stays byte-identical, result is Blocked") {
            val root = tempWorkspace()
            try {
                val original = "---\ntype: Concept\n---\noriginal body"
                val doc = File(root, "note.md").apply { writeText(original) }
                val badContent = "---\ntype: Blödsinn\n---\nnew body"

                val result =
                    OkfDocumentWriter.save(root = root, target = doc, content = badContent, knownDocuments = listOf(doc))

                val blocked = result.shouldBeInstanceOf<OkfWriteResult.Blocked>()
                blocked.blocking.map { it.code } shouldBe listOf("OKF-W-002")
                doc.readText(Charsets.UTF_8) shouldBe original
            } finally {
                root.deleteRecursively()
            }
        }

        test("no .tmp file is left behind in the directory after a successful save") {
            val root = tempWorkspace()
            try {
                val doc = File(root, "note.md").apply { writeText("---\ntype: Concept\n---\nold") }
                OkfDocumentWriter.save(
                    root = root,
                    target = doc,
                    content = "---\ntype: Concept\n---\nnew",
                    knownDocuments = listOf(doc),
                )
                root.listFiles()?.map { it.name } shouldBe listOf("note.md")
            } finally {
                root.deleteRecursively()
            }
        }

        test("no .tmp file is left behind after a blocked save either") {
            val root = tempWorkspace()
            try {
                val doc = File(root, "note.md").apply { writeText("---\ntype: Concept\n---\nold") }
                OkfDocumentWriter.save(
                    root = root,
                    target = doc,
                    content = "---\ntype: Nonsense\n---\nnew",
                    knownDocuments = listOf(doc),
                )
                root.listFiles()?.map { it.name } shouldBe listOf("note.md")
            } finally {
                root.deleteRecursively()
            }
        }

        test("POSIX permissions of the original file survive a save") {
            val root = tempWorkspace()
            try {
                if (!isPosix(root)) return@test
                val doc = File(root, "note.md").apply { writeText("---\ntype: Concept\n---\nold") }
                Files.setPosixFilePermissions(doc.toPath(), PosixFilePermissions.fromString("rw-r--r--"))

                OkfDocumentWriter.save(
                    root = root,
                    target = doc,
                    content = "---\ntype: Concept\n---\nnew",
                    knownDocuments = listOf(doc),
                )

                val perms = Files.getPosixFilePermissions(doc.toPath())
                PosixFilePermissions.toString(perms) shouldBe "rw-r--r--"
            } finally {
                root.deleteRecursively()
            }
        }

        test("a guard rejection returns Rejected and leaves the file untouched") {
            val root = tempWorkspace()
            try {
                val original = "---\ntype: Concept\n---\noriginal"
                val doc = File(root, "note.md").apply { writeText(original) }

                // Not in knownDocuments -> UNKNOWN_DOCUMENT rejection.
                val result =
                    OkfDocumentWriter.save(
                        root = root,
                        target = doc,
                        content = "---\ntype: Concept\n---\nattempted overwrite",
                        knownDocuments = emptyList(),
                    )

                val rejected = result.shouldBeInstanceOf<OkfWriteResult.Rejected>()
                rejected.rejection shouldBe WorkspaceWriteGuard.Rejection.UNKNOWN_DOCUMENT
                doc.readText(Charsets.UTF_8) shouldBe original
            } finally {
                root.deleteRecursively()
            }
        }

        test("the 20 MiB cap is enforced on UTF-8 BYTE length, not character count") {
            val root = tempWorkspace()
            try {
                val doc = File(root, "note.md").apply { writeText("---\ntype: Concept\n---\nold") }
                // Each '€' is 3 bytes in UTF-8: 7.5M chars -> ~22.5 MB, well over the 20 MiB
                // cap, while the CHARACTER count (7.5M) is comfortably under the numeric cap
                // value -- a length-in-chars bug would wrongly let this through.
                val huge = "€".repeat(7_500_000)
                val content = "---\ntype: Concept\n---\n$huge"

                val result =
                    OkfDocumentWriter.save(root = root, target = doc, content = content, knownDocuments = listOf(doc))

                val tooLarge = result.shouldBeInstanceOf<OkfWriteResult.TooLarge>()
                tooLarge.limit shouldBe OkfDocumentParser.MAX_MD_FILE_SIZE_BYTES
                (tooLarge.bytes > tooLarge.limit) shouldBe true
                doc.readText(Charsets.UTF_8) shouldBe "---\ntype: Concept\n---\nold"
            } finally {
                root.deleteRecursively()
            }
        }

        test("CRLF content is written byte-identical") {
            val root = tempWorkspace()
            try {
                val doc = File(root, "note.md").apply { writeText("---\ntype: Concept\n---\nold") }
                val crlfContent = "---\r\ntype: Concept\r\n---\r\nnew body\r\n"

                OkfDocumentWriter.save(root = root, target = doc, content = crlfContent, knownDocuments = listOf(doc))

                val rawBytes = Files.readAllBytes(doc.toPath())
                rawBytes shouldBe crlfContent.toByteArray(StandardCharsets.UTF_8)
            } finally {
                root.deleteRecursively()
            }
        }
    })
