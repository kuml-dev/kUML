package dev.kuml.workspace

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class OkfAliasRewriterTest :
    FunSpec({
        fun withRoot(block: (File) -> Unit) {
            val root = Files.createTempDirectory("kuml-alias-rewrite").toFile()
            try {
                block(root)
            } finally {
                root.deleteRecursively()
            }
        }

        fun run(
            root: File,
            dryRun: Boolean = false,
            hook: (File) -> Unit = {},
        ): List<OkfAliasRewriteResult> {
            val ws = WorkspaceScanner.scan(root = WorkspaceScanner.canonicalRoot(root))
            val targets = ws.documents.filter { OkfType.isAlias(it.rawType) }
            return OkfAliasRewriter.rewrite(
                root = ws.root,
                targets = targets,
                knownDocuments = ws.documents.map { it.file },
                dryRun = dryRun,
                beforeCommit = hook,
            )
        }

        fun File.bytes() = readBytes()

        fun symlinkOrNull(
            link: File,
            target: File,
        ): Boolean = runCatching { Files.createSymbolicLink(link.toPath(), target.toPath()) }.isSuccess

        test("alias is rewritten, all other bytes identical") {
            withRoot { root ->
                val f = File(root, "a.md")
                f.writeText("---\ntitle: T\ntype: Sysml2Bdd\n---\nbody\n")
                val r = run(root).single()
                r.shouldBeInstanceOf<OkfAliasRewriteResult.Rewritten>()
                f.readText() shouldBe "---\ntitle: T\ntype: Sysml2BlockDefinition\n---\nbody\n"
                run(root).shouldBeEmpty()
            }
        }

        test("canonical and unknown types are untouched; direct call reports Unchanged") {
            withRoot { root ->
                val c = File(root, "c.md").apply { writeText("---\ntype: Concept\n---\nx\n") }
                val u = File(root, "u.md").apply { writeText("---\ntype: Mystery\n---\nx\n") }
                val before = listOf(c.bytes(), u.bytes())
                run(root).shouldBeEmpty()
                val ws = WorkspaceScanner.scan(root = WorkspaceScanner.canonicalRoot(root))
                val results =
                    OkfAliasRewriter.rewrite(
                        root = ws.root,
                        targets = ws.documents,
                        knownDocuments =
                            ws.documents.map {
                                it.file
                            },
                        dryRun = false,
                    )
                results.all { it is OkfAliasRewriteResult.Unchanged } shouldBe true
                listOf(c.bytes(), u.bytes()).zip(before).all { (a, b) -> a.contentEquals(b) } shouldBe true
            }
        }

        test("byte preservation: CRLF, mixed endings, no trailing newline, non-ASCII, quotes, body duplicate") {
            val cases =
                mapOf(
                    "---\r\ntype: Sysml2Bdd\r\n---\r\nbody\r\n" to "---\r\ntype: Sysml2BlockDefinition\r\n---\r\nbody\r\n",
                    "---\r\ntype: Sysml2Bdd\n---\nbody\r\nmore\n" to "---\r\ntype: Sysml2BlockDefinition\n---\nbody\r\nmore\n",
                    "---\ntype: Sysml2Bdd\n---\nno newline" to "---\ntype: Sysml2BlockDefinition\n---\nno newline",
                    "---\ntype: Sysml2Bdd\n---\nÜber 😀 text\n" to "---\ntype: Sysml2BlockDefinition\n---\nÜber 😀 text\n",
                    "---\ntype: 'Sysml2Stm'\n---\nx\n" to "---\ntype: 'Sysml2StateMachine'\n---\nx\n",
                    "---\ntype: \"Sysml2Bdd\"\n---\nx\n" to "---\ntype: \"Sysml2BlockDefinition\"\n---\nx\n",
                    "---\ntype: Sysml2Bdd\n---\ntype: Sysml2Bdd\n" to "---\ntype: Sysml2BlockDefinition\n---\ntype: Sysml2Bdd\n",
                )
            for ((input, expected) in cases) {
                withRoot { root ->
                    val f = File(root, "a.md")
                    f.writeBytes(input.toByteArray(Charsets.UTF_8))
                    run(root).single().shouldBeInstanceOf<OkfAliasRewriteResult.Rewritten>()
                    f.bytes().contentEquals(expected.toByteArray(Charsets.UTF_8)) shouldBe true
                }
            }
        }

        test("UTF-8 BOM document keeps its bytes whatever the outcome") {
            withRoot { root ->
                val f = File(root, "a.md")
                val input = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "---\ntype: Sysml2Bdd\n---\nx\n".toByteArray()
                f.writeBytes(input)
                val ws = WorkspaceScanner.scan(root = WorkspaceScanner.canonicalRoot(root))
                val res =
                    OkfAliasRewriter.rewrite(
                        root = ws.root,
                        targets = ws.documents,
                        knownDocuments = ws.documents.map { it.file },
                        dryRun = false,
                    )
                if (res.single() is OkfAliasRewriteResult.Rewritten) {
                    f.bytes().take(3) shouldBe input.take(3)
                    f.readText().contains("Sysml2BlockDefinition") shouldBe true
                } else {
                    f.bytes().contentEquals(input) shouldBe true
                }
            }
        }

        test("invalid UTF-8 is skipped and left byte-identical") {
            withRoot { root ->
                val f = File(root, "a.md")
                val input = "---\ntype: Sysml2Bdd\n---\n".toByteArray() + byteArrayOf(0xC3.toByte(), 0x28)
                f.writeBytes(input)
                val ws = WorkspaceScanner.scan(root = WorkspaceScanner.canonicalRoot(root))
                val res =
                    OkfAliasRewriter.rewrite(
                        root = ws.root,
                        targets = ws.documents.filter { OkfType.isAlias(it.rawType) || true },
                        knownDocuments = ws.documents.map { it.file },
                        dryRun = false,
                    )
                res.single() shouldBe OkfAliasRewriteResult.Skipped(relativePath = "a.md", reason = OkfAliasSkipReason.NOT_UTF8)
                f.bytes().contentEquals(input) shouldBe true
            }
        }

        test("dry run writes nothing and leaves no temp file") {
            withRoot { root ->
                val f = File(root, "a.md").apply { writeText("---\ntype: Sysml2Bdd\n---\nx\n") }
                f.setLastModified(1_000_000_000_000L)
                val before = f.bytes()
                val r = run(root, dryRun = true).single()
                r.shouldBeInstanceOf<OkfAliasRewriteResult.WouldRewrite>()
                f.bytes().contentEquals(before) shouldBe true
                f.lastModified() shouldBe 1_000_000_000_000L
                root.list()!!.toList() shouldBe listOf("a.md")
            }
        }

        test("leaf symlink to an outside alias file is skipped, outside file untouched") {
            withRoot { root ->
                val outsideDir = Files.createTempDirectory("kuml-alias-outside").toFile()
                try {
                    val outside = File(outsideDir, "o.md").apply { writeText("---\ntype: Sysml2Bdd\n---\nx\n") }
                    if (!symlinkOrNull(File(root, "escape.md"), outside)) return@withRoot
                    val before = outside.bytes()
                    val res = run(root)
                    res.single().shouldBeInstanceOf<OkfAliasRewriteResult.Skipped>()
                    outside.bytes().contentEquals(before) shouldBe true
                } finally {
                    outsideDir.deleteRecursively()
                }
            }
        }

        test("symlinked directory is not scanned, outside file untouched") {
            withRoot { root ->
                val outsideDir = Files.createTempDirectory("kuml-alias-outside").toFile()
                try {
                    val outside = File(outsideDir, "o.md").apply { writeText("---\ntype: Sysml2Bdd\n---\nx\n") }
                    if (!symlinkOrNull(File(root, "linked"), outsideDir)) return@withRoot
                    val before = outside.bytes()
                    run(root).shouldBeEmpty()
                    outside.bytes().contentEquals(before) shouldBe true
                } finally {
                    outsideDir.deleteRecursively()
                }
            }
        }

        test("internal symlink to another alias document: real document rewritten once, link skipped") {
            withRoot { root ->
                val real = File(root, "real.md").apply { writeText("---\ntype: Sysml2Bdd\n---\nx\n") }
                if (!symlinkOrNull(File(root, "link.md"), real)) return@withRoot
                val res = run(root)
                res.count { it is OkfAliasRewriteResult.Rewritten } shouldBe 1
                res.count { it is OkfAliasRewriteResult.Skipped } shouldBe 1
                real.readText() shouldBe "---\ntype: Sysml2BlockDefinition\n---\nx\n"
            }
        }

        test("concurrent change between scan and write is detected") {
            withRoot { root ->
                val f = File(root, "a.md").apply { writeText("---\ntype: Sysml2Bdd\n---\nx\n") }
                val res = run(root, hook = { it.appendText("edited\n") })
                res.single() shouldBe OkfAliasRewriteResult.Skipped(relativePath = "a.md", reason = OkfAliasSkipReason.CHANGED_ON_DISK)
                f.readText() shouldBe "---\ntype: Sysml2Bdd\n---\nx\nedited\n"
                root.list()!!.toList() shouldBe listOf("a.md")
            }
        }

        test("file deleted by the hook is skipped and not recreated") {
            withRoot { root ->
                File(root, "a.md").writeText("---\ntype: Sysml2Bdd\n---\nx\n")
                val res = run(root, hook = { it.delete() })
                res.single().shouldBeInstanceOf<OkfAliasRewriteResult.Skipped>()
                File(root, "a.md").exists() shouldBe false
                root.list()!!.toList().shouldBeEmpty()
            }
        }

        test("document not in knownDocuments is skipped") {
            withRoot { root ->
                val f = File(root, "a.md").apply { writeText("---\ntype: Sysml2Bdd\n---\nx\n") }
                val ws = WorkspaceScanner.scan(root = WorkspaceScanner.canonicalRoot(root))
                val res = OkfAliasRewriter.rewrite(root = ws.root, targets = ws.documents, knownDocuments = emptyList(), dryRun = false)
                res.single() shouldBe OkfAliasRewriteResult.Skipped(relativePath = "a.md", reason = OkfAliasSkipReason.UNKNOWN_DOCUMENT)
                f.readText() shouldBe "---\ntype: Sysml2Bdd\n---\nx\n"
            }
        }

        test("POSIX permissions survive") {
            withRoot { root ->
                val f = File(root, "a.md").apply { writeText("---\ntype: Sysml2Bdd\n---\nx\n") }
                val ok = runCatching { Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-r-----")) }.isSuccess
                if (!ok) return@withRoot
                run(root).single().shouldBeInstanceOf<OkfAliasRewriteResult.Rewritten>()
                PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath())) shouldBe "rw-r-----"
            }
        }
    })
