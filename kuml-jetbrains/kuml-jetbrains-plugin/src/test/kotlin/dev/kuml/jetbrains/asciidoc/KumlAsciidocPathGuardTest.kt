package dev.kuml.jetbrains.asciidoc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path

class KumlAsciidocPathGuardTest :
    FunSpec({
        val adocParent = Path.of("/project/docs").toAbsolutePath().normalize()
        val projectBase = Path.of("/project").toAbsolutePath().normalize()

        test("accepts a normal relative path inside baseDir") {
            val result = KumlAsciidocPathGuard.resolve("diagrams/login.kuml.kts", adocParent, projectBase)
            val ok = result.shouldBeInstanceOf<KumlAsciidocPathGuard.Result.Ok>()
            ok.resolvedPath shouldBe adocParent.resolve("diagrams/login.kuml.kts").normalize()
        }

        test("rejects path traversal outside project") {
            val result = KumlAsciidocPathGuard.resolve("../../../etc/passwd", adocParent, projectBase)
            result.shouldBeInstanceOf<KumlAsciidocPathGuard.Result.Rejected>()
        }

        test("accepts relative path outside adoc dir but inside project") {
            val result = KumlAsciidocPathGuard.resolve("../README.kuml.kts", adocParent, projectBase)
            val ok = result.shouldBeInstanceOf<KumlAsciidocPathGuard.Result.Ok>()
            ok.resolvedPath shouldBe projectBase.resolve("README.kuml.kts").normalize()
        }

        test("rejects absolute path outside project") {
            val result = KumlAsciidocPathGuard.resolve("/tmp/x.kuml.kts", adocParent, projectBase)
            result.shouldBeInstanceOf<KumlAsciidocPathGuard.Result.Rejected>()
        }

        test("rejects https scheme") {
            val result = KumlAsciidocPathGuard.resolve("https://evil/x", adocParent, projectBase)
            result.shouldBeInstanceOf<KumlAsciidocPathGuard.Result.Rejected>()
        }

        test("rejects http scheme") {
            val result = KumlAsciidocPathGuard.resolve("http://evil/x", adocParent, projectBase)
            result.shouldBeInstanceOf<KumlAsciidocPathGuard.Result.Rejected>()
        }

        test("rejects ftp scheme") {
            val result = KumlAsciidocPathGuard.resolve("ftp://evil/x", adocParent, projectBase)
            result.shouldBeInstanceOf<KumlAsciidocPathGuard.Result.Rejected>()
        }

        test("rejects file scheme") {
            val result = KumlAsciidocPathGuard.resolve("file:///etc/passwd", adocParent, projectBase)
            result.shouldBeInstanceOf<KumlAsciidocPathGuard.Result.Rejected>()
        }

        test("rejects blank path") {
            val result = KumlAsciidocPathGuard.resolve("   ", adocParent, projectBase)
            result.shouldBeInstanceOf<KumlAsciidocPathGuard.Result.Rejected>()
        }

        test("without project base still rejects escape above adoc parent") {
            val result = KumlAsciidocPathGuard.resolve("../../etc/passwd", adocParent, projectBaseDir = null)
            result.shouldBeInstanceOf<KumlAsciidocPathGuard.Result.Rejected>()
        }

        // Real filesystem tests: a lexical-only normalize()+startsWith() check never
        // resolves symlinks, so these exercise the toRealPath()-based containment check
        // that closes the symlink-escape hole (see KumlAsciidocPathGuard KDoc).
        test("rejects a symlink inside the project that targets a file outside it") {
            val root = Files.createTempDirectory("kuml-path-guard-symlink-test")
            try {
                val realProjectBase = Files.createDirectories(root.resolve("project"))
                val realAdocParent = Files.createDirectories(realProjectBase.resolve("docs"))
                val outside = Files.createDirectories(root.resolve("outside"))
                val secret = Files.writeString(outside.resolve("secret.txt"), "top secret content")

                val symlink = realAdocParent.resolve("escape.kuml.kts")
                try {
                    Files.createSymbolicLink(symlink, secret)
                } catch (e: UnsupportedOperationException) {
                    // Symlinks unsupported on this filesystem/platform — nothing to verify.
                    return@test
                }

                val result = KumlAsciidocPathGuard.resolve("escape.kuml.kts", realAdocParent, realProjectBase)
                result.shouldBeInstanceOf<KumlAsciidocPathGuard.Result.Rejected>()
            } finally {
                root.toFile().deleteRecursively()
            }
        }

        test("accepts a symlink inside the project that targets a file also inside it") {
            val root = Files.createTempDirectory("kuml-path-guard-symlink-test")
            try {
                val realProjectBase = Files.createDirectories(root.resolve("project"))
                val realAdocParent = Files.createDirectories(realProjectBase.resolve("docs"))
                val target = Files.writeString(realProjectBase.resolve("shared.kuml.kts"), "class Foo")

                val symlink = realAdocParent.resolve("linked.kuml.kts")
                try {
                    Files.createSymbolicLink(symlink, target)
                } catch (e: UnsupportedOperationException) {
                    return@test
                }

                val result = KumlAsciidocPathGuard.resolve("linked.kuml.kts", realAdocParent, realProjectBase)
                result.shouldBeInstanceOf<KumlAsciidocPathGuard.Result.Ok>()
            } finally {
                root.toFile().deleteRecursively()
            }
        }

        test("accepts a target path that does not exist yet (no content to leak)") {
            val root = Files.createTempDirectory("kuml-path-guard-symlink-test")
            try {
                val realProjectBase = Files.createDirectories(root.resolve("project"))
                val realAdocParent = Files.createDirectories(realProjectBase.resolve("docs"))

                val result = KumlAsciidocPathGuard.resolve("not-yet-created.kuml.kts", realAdocParent, realProjectBase)
                result.shouldBeInstanceOf<KumlAsciidocPathGuard.Result.Ok>()
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    })
