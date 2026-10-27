package dev.kuml.workspace

import dev.kuml.workspace.WorkspaceDocumentResolver.DocumentResolutionException
import dev.kuml.workspace.WorkspaceDocumentResolver.Rejection
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.nio.file.Files

class WorkspaceDocumentResolverTest :
    FunSpec({

        class Fx(
            val parent: File,
            val root: File,
        ) {
            fun cleanup() = parent.deleteRecursively()
        }

        fun fixture(): Fx {
            val parent = Files.createTempDirectory("kuml-resolver").toFile()
            val root = File(parent, "ws").apply { mkdirs() }
            File(root, "models").mkdirs()
            File(root, "models/x.md").writeText("---\ntype: Concept\n---\nX\n")
            File(root, "models/y.md").writeText("---\ntype: Concept\n---\nY\n")
            File(parent, "outside.md").writeText("SECRET-MARKER")
            File(parent, "ws-evil").mkdirs()
            File(parent, "ws-evil/x.md").writeText("SECRET-MARKER")
            return Fx(parent = parent, root = root)
        }

        fun rejection(
            fx: Fx,
            requested: String,
            excludes: Set<File> = emptySet(),
        ): Rejection {
            val r = WorkspaceDocumentResolver.resolve(root = fx.root, requested = requested, excludeDirs = excludes)
            return (r.exceptionOrNull() as DocumentResolutionException).rejection
        }

        fun symlinkOrSkip(
            link: File,
            target: File,
        ): Boolean = runCatching { Files.createSymbolicLink(link.toPath(), target.toPath()) }.isSuccess

        test("happy path resolves with '/' and with Windows separator") {
            val fx = fixture()
            try {
                WorkspaceDocumentResolver.resolve(root = fx.root, requested = "models/x.md").getOrThrow().name shouldBe "x.md"
                WorkspaceDocumentResolver.resolve(root = fx.root, requested = "models\\x.md").getOrThrow().name shouldBe "x.md"
                WorkspaceDocumentResolver.resolve(root = fx.root, requested = "models/../models/x.md").getOrThrow().name shouldBe "x.md"
            } finally {
                fx.cleanup()
            }
        }

        test("traversal is rejected as OUTSIDE_ROOT") {
            val fx = fixture()
            try {
                rejection(fx = fx, requested = "../outside.md") shouldBe Rejection.OUTSIDE_ROOT
                rejection(fx = fx, requested = "models/../../outside.md") shouldBe Rejection.OUTSIDE_ROOT
                rejection(fx = fx, requested = "../ws-evil/x.md") shouldBe Rejection.OUTSIDE_ROOT
            } finally {
                fx.cleanup()
            }
        }

        test("absolute paths are rejected") {
            val fx = fixture()
            try {
                rejection(fx = fx, requested = "/etc/passwd") shouldBe Rejection.ABSOLUTE_PATH
                rejection(fx = fx, requested = "C:/x.md") shouldBe Rejection.ABSOLUTE_PATH
                rejection(fx = fx, requested = "\\\\server\\share\\x.md") shouldBe Rejection.ABSOLUTE_PATH
                rejection(fx = fx, requested = File(fx.root, "models/x.md").absolutePath) shouldBe Rejection.ABSOLUTE_PATH
            } finally {
                fx.cleanup()
            }
        }

        test("empty and NUL paths are rejected") {
            val fx = fixture()
            try {
                rejection(fx = fx, requested = "") shouldBe Rejection.EMPTY
                rejection(fx = fx, requested = "   ") shouldBe Rejection.EMPTY
                rejection(fx = fx, requested = "models/x.md\u0000.txt") shouldBe Rejection.EMPTY
            } finally {
                fx.cleanup()
            }
        }

        test("symlink file pointing outside is SYMLINK_ESCAPE") {
            val fx = fixture()
            try {
                if (symlinkOrSkip(link = File(fx.root, "link.md"), target = File(fx.parent, "outside.md"))) {
                    rejection(fx = fx, requested = "link.md") shouldBe Rejection.SYMLINK_ESCAPE
                }
            } finally {
                fx.cleanup()
            }
        }

        test("symlink directory pointing outside is SYMLINK_ESCAPE") {
            val fx = fixture()
            try {
                if (symlinkOrSkip(link = File(fx.root, "linkdir"), target = File(fx.parent, "ws-evil"))) {
                    rejection(fx = fx, requested = "linkdir/x.md") shouldBe Rejection.SYMLINK_ESCAPE
                }
            } finally {
                fx.cleanup()
            }
        }

        test("symlink pointing back into the root is SYMLINK_ESCAPE (consistent with the scanner)") {
            val fx = fixture()
            try {
                if (symlinkOrSkip(link = File(fx.root, "alias.md"), target = File(fx.root, "models/x.md"))) {
                    rejection(fx = fx, requested = "alias.md") shouldBe Rejection.SYMLINK_ESCAPE
                }
            } finally {
                fx.cleanup()
            }
        }

        test("hidden and excluded directories are EXCLUDED") {
            val fx = fixture()
            try {
                File(fx.root, ".hidden").mkdirs()
                File(fx.root, ".hidden/x.md").writeText("h")
                File(fx.root, ".kuml-out").mkdirs()
                File(fx.root, ".kuml-out/x.md").writeText("h")
                File(fx.root, "rendered").mkdirs()
                File(fx.root, "rendered/x.md").writeText("h")
                rejection(fx = fx, requested = ".hidden/x.md") shouldBe Rejection.EXCLUDED
                rejection(fx = fx, requested = ".kuml-out/x.md") shouldBe Rejection.EXCLUDED
                rejection(fx = fx, requested = "rendered/x.md", excludes = setOf(File(fx.root, "rendered"))) shouldBe Rejection.EXCLUDED
            } finally {
                fx.cleanup()
            }
        }

        test("an exclude that is the scan root or one of its ancestors does not hide documents") {
            val fx = fixture()
            try {
                val ok = { ex: File ->
                    WorkspaceDocumentResolver.resolve(root = fx.root, requested = "models/x.md", excludeDirs = setOf(ex))
                }
                ok(fx.root).isSuccess shouldBe true
                ok(fx.parent).isSuccess shouldBe true
                ok(File(fx.root, "models")).isFailure shouldBe true
            } finally {
                fx.cleanup()
            }
        }

        test("directory and non-markdown files are rejected") {
            val fx = fixture()
            try {
                File(fx.root, "models/x.txt").writeText("t")
                rejection(fx = fx, requested = "models") shouldBe Rejection.NOT_A_REGULAR_FILE
                rejection(fx = fx, requested = ".") shouldBe Rejection.NOT_A_REGULAR_FILE
                rejection(fx = fx, requested = "models/x.txt") shouldBe Rejection.NOT_MARKDOWN
            } finally {
                fx.cleanup()
            }
        }

        test("a typo yields a suggestion and never leaks file content") {
            val fx = fixture()
            try {
                val ex =
                    WorkspaceDocumentResolver
                        .resolve(root = fx.root, requested = "models/z.md")
                        .exceptionOrNull() as DocumentResolutionException
                ex.rejection shouldBe Rejection.NOT_FOUND
                (ex.suggestion == "models/x.md" || ex.suggestion == "models/y.md") shouldBe true
                ex.message!! shouldNotContain "SECRET-MARKER"
            } finally {
                fx.cleanup()
            }
        }

        test("control characters in the requested path are not echoed raw") {
            val fx = fixture()
            try {
                val ex =
                    WorkspaceDocumentResolver
                        .resolve(
                            root = fx.root,
                            requested = "a\nb.md",
                        ).exceptionOrNull() as DocumentResolutionException
                ex.message!! shouldNotContain "\n"
            } finally {
                fx.cleanup()
            }
        }
    })
