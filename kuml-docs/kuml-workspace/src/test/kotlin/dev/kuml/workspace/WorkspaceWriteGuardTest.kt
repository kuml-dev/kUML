package dev.kuml.workspace

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.nio.file.Files

class WorkspaceWriteGuardTest :
    FunSpec({

        fun tempRoot(): File = Files.createTempDirectory("kuml-write-guard-test").toFile()

        test("a document inside the root, present in knownDocuments, resolves") {
            val root = tempRoot()
            try {
                val doc = File(root, "note.md").apply { writeText("body") }
                val result = WorkspaceWriteGuard.resolveWritable(root = root, target = doc, knownDocuments = listOf(doc))
                result.isSuccess shouldBe true
                result.getOrNull()?.path shouldBe doc.canonicalFile.path
            } finally {
                root.deleteRecursively()
            }
        }

        test("a document in a subfolder, present in knownDocuments, resolves") {
            val root = tempRoot()
            try {
                val sub = File(root, "articles").apply { mkdirs() }
                val doc = File(sub, "note.md").apply { writeText("body") }
                val result = WorkspaceWriteGuard.resolveWritable(root = root, target = doc, knownDocuments = listOf(doc))
                result.isSuccess shouldBe true
            } finally {
                root.deleteRecursively()
            }
        }

        test("a not-yet-existing target that IS in knownDocuments resolves (write creates it)") {
            val root = tempRoot()
            try {
                val doc = File(root, "not-yet-on-disk.md")
                val result = WorkspaceWriteGuard.resolveWritable(root = root, target = doc, knownDocuments = listOf(doc))
                result.isSuccess shouldBe true
            } finally {
                root.deleteRecursively()
            }
        }

        test("a relative-path traversal target is rejected as OUTSIDE_ROOT") {
            val root = tempRoot()
            try {
                val outside = File(root.parentFile, "etc-passwd-${root.name}.md").apply { writeText("secret") }
                val traversal = File(root, "../${outside.name}")
                val result = WorkspaceWriteGuard.resolveWritable(root = root, target = traversal, knownDocuments = listOf(outside))
                result.isFailure shouldBe true
                val rejection = (result.exceptionOrNull() as WorkspaceWriteGuard.WriteGuardException).rejection
                rejection shouldBe WorkspaceWriteGuard.Rejection.OUTSIDE_ROOT
                outside.delete()
            } finally {
                root.deleteRecursively()
            }
        }

        test("an absolute path outside the root is rejected as OUTSIDE_ROOT") {
            val root = tempRoot()
            try {
                val outside = Files.createTempDirectory("kuml-write-guard-outside").toFile()
                try {
                    val outsideDoc = File(outside, "note.md").apply { writeText("body") }
                    val result =
                        WorkspaceWriteGuard.resolveWritable(root = root, target = outsideDoc, knownDocuments = listOf(outsideDoc))
                    result.isFailure shouldBe true
                    val rejection = (result.exceptionOrNull() as WorkspaceWriteGuard.WriteGuardException).rejection
                    rejection shouldBe WorkspaceWriteGuard.Rejection.OUTSIDE_ROOT
                } finally {
                    outside.deleteRecursively()
                }
            } finally {
                root.deleteRecursively()
            }
        }

        test("the sibling-prefix trap: '/tmp/ws-evil/x.md' is rejected even though it string-prefixes '/tmp/ws'") {
            val root = Files.createTempDirectory("kuml-ws").toFile()
            try {
                val evilSibling = File(root.parentFile, "${root.name}-evil").apply { mkdirs() }
                try {
                    val evilDoc = File(evilSibling, "x.md").apply { writeText("evil") }
                    val result = WorkspaceWriteGuard.resolveWritable(root = root, target = evilDoc, knownDocuments = listOf(evilDoc))
                    result.isFailure shouldBe true
                    val rejection = (result.exceptionOrNull() as WorkspaceWriteGuard.WriteGuardException).rejection
                    rejection shouldBe WorkspaceWriteGuard.Rejection.OUTSIDE_ROOT
                } finally {
                    evilSibling.deleteRecursively()
                }
            } finally {
                root.deleteRecursively()
            }
        }

        test("a symlink file target with a destination outside the root is rejected") {
            val root = tempRoot()
            try {
                val outside = Files.createTempDirectory("kuml-write-guard-symlink-outside").toFile()
                try {
                    val secret = File(outside, "secret.md").apply { writeText("secret") }
                    val symlink = File(root, "escape.md")
                    try {
                        Files.createSymbolicLink(symlink.toPath(), secret.toPath())
                    } catch (e: UnsupportedOperationException) {
                        return@test
                    }
                    val result = WorkspaceWriteGuard.resolveWritable(root = root, target = symlink, knownDocuments = listOf(symlink))
                    result.isFailure shouldBe true
                } finally {
                    outside.deleteRecursively()
                }
            } finally {
                root.deleteRecursively()
            }
        }

        test("a symlink file target with a destination inside the root is rejected (never write THROUGH a symlink)") {
            val root = tempRoot()
            try {
                val real = File(root, "real.md").apply { writeText("real") }
                val symlink = File(root, "linked.md")
                try {
                    Files.createSymbolicLink(symlink.toPath(), real.toPath())
                } catch (e: UnsupportedOperationException) {
                    return@test
                }
                val result = WorkspaceWriteGuard.resolveWritable(root = root, target = symlink, knownDocuments = listOf(symlink, real))
                result.isFailure shouldBe true
                val rejection = (result.exceptionOrNull() as WorkspaceWriteGuard.WriteGuardException).rejection
                rejection shouldBe WorkspaceWriteGuard.Rejection.SYMLINK
            } finally {
                root.deleteRecursively()
            }
        }

        test("a symlinked directory in the path that escapes the root is rejected") {
            val root = tempRoot()
            try {
                val outside = Files.createTempDirectory("kuml-write-guard-dir-outside").toFile()
                try {
                    val symlinkDir = File(root, "linked-dir")
                    try {
                        Files.createSymbolicLink(symlinkDir.toPath(), outside.toPath())
                    } catch (e: UnsupportedOperationException) {
                        return@test
                    }
                    val target = File(symlinkDir, "note.md")
                    val result = WorkspaceWriteGuard.resolveWritable(root = root, target = target, knownDocuments = listOf(target))
                    result.isFailure shouldBe true
                    val rejection = (result.exceptionOrNull() as WorkspaceWriteGuard.WriteGuardException).rejection
                    rejection shouldBe WorkspaceWriteGuard.Rejection.OUTSIDE_ROOT
                } finally {
                    outside.deleteRecursively()
                }
            } finally {
                root.deleteRecursively()
            }
        }

        test("a symlink cycle at the target is a clean Result.failure, never an uncaught exception") {
            val root = tempRoot()
            try {
                val a = File(root, "a.md")
                val b = File(root, "b.md")
                try {
                    Files.createSymbolicLink(a.toPath(), b.toPath())
                    Files.createSymbolicLink(b.toPath(), a.toPath())
                } catch (e: UnsupportedOperationException) {
                    return@test
                }
                // The exact Rejection is platform-dependent: `a` being a symlink at all is
                // enough to be caught as SYMLINK before canonicalization ever runs into the
                // cycle on some JVM/OS combinations, while others surface the cycle itself as
                // IO_ERROR during File.getCanonicalFile(). Either is an acceptably safe
                // outcome — what this test actually guards is that NEITHER path lets an
                // IOException escape resolveWritable uncaught.
                val result = WorkspaceWriteGuard.resolveWritable(root = root, target = a, knownDocuments = listOf(a))
                result.isFailure shouldBe true
                val rejection = (result.exceptionOrNull() as WorkspaceWriteGuard.WriteGuardException).rejection
                (rejection == WorkspaceWriteGuard.Rejection.SYMLINK || rejection == WorkspaceWriteGuard.Rejection.IO_ERROR) shouldBe true
            } finally {
                root.deleteRecursively()
            }
        }

        test("IO_ERROR is returned as a clean Rejection, not an uncaught exception, when canonicalization fails") {
            // Direct unit test of the IO_ERROR branch itself (independent of whether any
            // particular OS/JVM combination happens to throw for a 2-node symlink cycle,
            // per the previous test's note): a root whose OWN path cannot be canonicalized
            // hits the guarded `root.canonicalFile` call before the target-symlink check
            // ever runs.
            val root = tempRoot()
            try {
                val loopedRoot = File(root, "looped")
                val insideLoop = File(loopedRoot, "self")
                try {
                    Files.createSymbolicLink(loopedRoot.toPath(), insideLoop.toPath())
                } catch (e: UnsupportedOperationException) {
                    return@test
                } catch (e: java.nio.file.FileAlreadyExistsException) {
                    return@test
                }
                val target = File(loopedRoot, "note.md")
                val result = WorkspaceWriteGuard.resolveWritable(root = loopedRoot, target = target, knownDocuments = listOf(target))
                result.isFailure shouldBe true
                result.exceptionOrNull().shouldBeInstanceOf<WorkspaceWriteGuard.WriteGuardException>()
            } finally {
                root.deleteRecursively()
            }
        }

        test(
            "a symlink cycle in knownDocuments (not root or target) is a clean IO_ERROR, " +
                "never an uncaught exception",
        ) {
            // Regression for a review finding: `root`/`target` canonicalization was already
            // guarded, but `knownDocuments.map { it.canonicalFile.path }` was not — a
            // pathological entry there (e.g. a symlink cycle appearing under a known
            // document between the workspace scan and this save) used to let the
            // IOException escape `resolveWritable` uncaught. Same reliable self-referencing
            // symlink technique as the "IO_ERROR is returned..." test below, applied to a
            // knownDocuments entry instead of root — the poisoned entry must sit UNDER the
            // looped symlink (a non-terminal path component actually forces the cycle to be
            // resolved; canonicalizing the bare symlink itself as a dangling leaf does not).
            val root = tempRoot()
            try {
                val target = File(root, "note.md").apply { writeText("body") }
                val loopedDir = File(root, "looped-known")
                val insideLoop = File(loopedDir, "self")
                try {
                    Files.createSymbolicLink(loopedDir.toPath(), insideLoop.toPath())
                } catch (e: UnsupportedOperationException) {
                    return@test
                } catch (e: java.nio.file.FileAlreadyExistsException) {
                    return@test
                }
                val poisonedKnownDoc = File(loopedDir, "evil.md")
                val result =
                    WorkspaceWriteGuard.resolveWritable(
                        root = root,
                        target = target,
                        knownDocuments = listOf(target, poisonedKnownDoc),
                    )
                result.isFailure shouldBe true
                val rejection = (result.exceptionOrNull() as WorkspaceWriteGuard.WriteGuardException).rejection
                rejection shouldBe WorkspaceWriteGuard.Rejection.IO_ERROR
            } finally {
                root.deleteRecursively()
            }
        }

        test("a target that is a directory is rejected as NOT_A_REGULAR_FILE") {
            val root = tempRoot()
            try {
                val dir = File(root, "a-directory").apply { mkdirs() }
                val result = WorkspaceWriteGuard.resolveWritable(root = root, target = dir, knownDocuments = listOf(dir))
                result.isFailure shouldBe true
                val rejection = (result.exceptionOrNull() as WorkspaceWriteGuard.WriteGuardException).rejection
                rejection shouldBe WorkspaceWriteGuard.Rejection.NOT_A_REGULAR_FILE
            } finally {
                root.deleteRecursively()
            }
        }

        test("an existing document not present in knownDocuments is rejected as UNKNOWN_DOCUMENT") {
            val root = tempRoot()
            try {
                val doc = File(root, "note.md").apply { writeText("body") }
                val result = WorkspaceWriteGuard.resolveWritable(root = root, target = doc, knownDocuments = emptyList())
                result.isFailure shouldBe true
                val rejection = (result.exceptionOrNull() as WorkspaceWriteGuard.WriteGuardException).rejection
                rejection shouldBe WorkspaceWriteGuard.Rejection.UNKNOWN_DOCUMENT
            } finally {
                root.deleteRecursively()
            }
        }

        test("WriteGuardException carries the rejection reason and is a real Exception") {
            val ex = WorkspaceWriteGuard.WriteGuardException(rejection = WorkspaceWriteGuard.Rejection.OUTSIDE_ROOT)
            ex.shouldBeInstanceOf<Exception>()
            ex.rejection shouldBe WorkspaceWriteGuard.Rejection.OUTSIDE_ROOT
        }
    })
