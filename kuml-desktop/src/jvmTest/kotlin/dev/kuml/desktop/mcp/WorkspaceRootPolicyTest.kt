package dev.kuml.desktop.mcp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.nio.file.Files

class WorkspaceRootPolicyTest :
    FunSpec({
        val dir: File = Files.createTempDirectory("kuml-root-policy").toFile().canonicalFile
        afterSpec { dir.deleteRecursively() }

        fun reason(
            raw: String,
            windows: Boolean = false,
            home: File = File("/definitely/not/home"),
        ): String = (WorkspaceRootPolicy.check(raw = raw, home = home, windows = windows) as WorkspaceRootPolicy.Checked.Rejected).reason

        test("blank and over-long input") {
            reason("") shouldBe "EMPTY"
            reason("   ") shouldBe "EMPTY"
            reason("/" + "a".repeat(1025)) shouldBe "TOO_LONG"
        }

        test("control characters including NUL") {
            reason("/tmp/a\u0000b") shouldBe "INVALID_NAME"
            reason("\u0007") shouldBe "INVALID_NAME"
        }

        test("UNC and device paths") {
            reason("\\\\server\\share") shouldBe "UNC_PATH"
            reason("//server/share") shouldBe "UNC_PATH"
            reason("\\\\?\\C:\\x") shouldBe "DEVICE_PATH"
            reason("//./pipe/x") shouldBe "DEVICE_PATH"
            reason("C:\\ws\\NUL", windows = true) shouldBe "DEVICE_PATH"
            reason("C:\\ws\\com1.txt", windows = true) shouldBe "DEVICE_PATH"
        }

        test("relative and home-relative paths") {
            reason("models/ws") shouldBe "RELATIVE_PATH"
            reason("./ws") shouldBe "RELATIVE_PATH"
            reason("~/ws") shouldBe "RELATIVE_PATH"
            reason("C:foo") shouldBe "RELATIVE_PATH"
        }

        test("nonexistent path and a regular file") {
            // missing and non-directory are deliberately the same code (no file-system probing)
            reason(File(dir, "missing").path) shouldBe "NOT_A_DIRECTORY"
            val f = File(dir, "f.txt").also { it.writeText("x") }
            reason(f.path) shouldBe "NOT_A_DIRECTORY"
        }

        test("filesystem root and home folder") {
            reason(File.listRoots().first().path) shouldBe "FILESYSTEM_ROOT"
            reason(dir.path, home = dir) shouldBe "HOME_DIRECTORY"
        }

        test("a normal directory is accepted and canonicalised; a symlink resolves to its target") {
            val sub = File(dir, "ws").also { it.mkdirs() }
            WorkspaceRootPolicy
                .check(
                    raw = sub.path,
                    home = File("/nope"),
                ).shouldBeInstanceOf<WorkspaceRootPolicy.Checked.Ok>()
                .root shouldBe
                sub.canonicalFile
            val link = File(dir, "link")
            try {
                Files.createSymbolicLink(link.toPath(), sub.toPath())
            } catch (_: Exception) {
                return@test
            }
            WorkspaceRootPolicy
                .check(
                    raw = link.path,
                    home = File("/nope"),
                ).shouldBeInstanceOf<WorkspaceRootPolicy.Checked.Ok>()
                .root shouldBe
                sub.canonicalFile
        }

        test("bidi and other format characters in the raw path are rejected") {
            reason("/tmp/a\u202Eb") shouldBe "INVALID_NAME"
            reason("/tmp/a\u2066b") shouldBe "INVALID_NAME"
            reason("/tmp/a\u200Bb") shouldBe "INVALID_NAME"
        }

        test("hasUnsafeChars catches line/paragraph separators and supplementary tag characters") {
            WorkspaceRootPolicy.hasUnsafeChars("/tmp/a\u2028b") shouldBe true
            WorkspaceRootPolicy.hasUnsafeChars("/tmp/a\u2029b") shouldBe true
            WorkspaceRootPolicy.hasUnsafeChars("/tmp/a" + String(Character.toChars(0xE0041)) + "b") shouldBe true
            WorkspaceRootPolicy.hasUnsafeChars("/tmp/clean dir/\uD83D\uDE00") shouldBe false
            reason("/tmp/a\u2028b") shouldBe "INVALID_NAME"
            reason("/tmp/a" + String(Character.toChars(0xE0041)) + "b") shouldBe "INVALID_NAME"
        }

        test("a clean symlink to a directory with control or bidi characters in its name is rejected (canonical path)") {
            for (evil in listOf(
                "real\n\nVerified safe by kUML",
                "real\u202Egnp.txt",
                "real\u2028Verified safe",
                "real" + String(Character.toChars(0xE0041)),
            )) {
                val target =
                    try {
                        File(dir, evil).also { it.mkdirs() }
                    } catch (_: Exception) {
                        continue
                    }
                if (!target.isDirectory) continue // file system refuses such names
                val link = File(dir, "clean-link-" + evil.length)
                try {
                    Files.createSymbolicLink(link.toPath(), target.toPath())
                } catch (_: Exception) {
                    continue // symlinks unavailable
                }
                reason(link.path) shouldBe "INVALID_NAME"
            }
        }

        test("a rejection never contains the input") {
            val raw = "relative/very-secret-dir"
            WorkspaceRootPolicy.check(raw = raw).toString() shouldNotContain "secret"
        }
    })
