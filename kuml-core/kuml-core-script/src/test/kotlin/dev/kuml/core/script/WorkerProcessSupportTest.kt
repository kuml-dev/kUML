package dev.kuml.core.script

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files

/**
 * Regression tests for [WorkerProcessSupport.LaunchedWorker.cleanup] — specifically
 * the sandbox-escape hardening around the per-worker `workDir` teardown (fix on
 * `fix/workerpool-close-race`).
 *
 * `workDir` is the *only* read-write path bound into the OS cage ([OsSandbox]).
 * A hostile script that gets past the layer-1 regex guard could plant a
 * directory symlink inside it pointing anywhere on the host. The old
 * `File.deleteRecursively()` teardown follows such symlinks (verified
 * empirically — `File.isDirectory()` / `File.listFiles()` resolve through
 * them), so cleanup would recursively delete the symlink *target* with the
 * parent JVM's privileges: a full sandbox escape via "last line of defence"
 * teardown code. [WorkerProcessSupport]'s `deleteRecursivelySafely` (exercised
 * here only through the public [WorkerProcessSupport.LaunchedWorker.cleanup]
 * API) must delete the symlink itself and never descend into its target.
 */
class WorkerProcessSupportTest :
    FunSpec({

        test("cleanup() deletes a directory symlink inside workDir without following it into its target") {
            // Stand-in for an attacker-controlled directory outside the sandbox —
            // e.g. the user's home directory in the real finding.
            val victim = Files.createTempDirectory("wps-victim-").toFile()
            val precious = File(victim, "precious.txt").apply { writeText("keep me") }

            // Stand-in for the sole sandbox-writable workDir, with a symlink planted
            // inside it by a hostile script (Files.createSymbolicLink, exactly as
            // the finding's PoC does).
            val workDir = Files.createTempDirectory("wps-workdir-").toFile()
            val escapeLink = File(workDir, "escape")
            Files.createSymbolicLink(escapeLink.toPath(), victim.toPath())

            // A short-lived, already-exited process — cleanup()'s bounded waitFor
            // must not hang on it.
            val process = ProcessBuilder(WorkerProcessSupport.defaultJavaBinary(), "-version").start()
            process.waitFor()

            try {
                val launched = WorkerProcessSupport.LaunchedWorker(process = process, workDir = workDir)
                launched.cleanup()

                // The symlink target and its contents must survive completely untouched.
                victim.exists().shouldBeTrue()
                precious.exists().shouldBeTrue()
                precious.readText() shouldBe "keep me"
                victim.list()?.toList() shouldBe listOf("precious.txt")

                // The sandbox workdir itself (including the dangling link entry) is gone.
                workDir.exists().shouldBeFalse()
            } finally {
                victim.deleteRecursively()
                workDir.deleteRecursively()
            }
        }

        test("cleanup() still removes an ordinary workDir with real files and subdirectories") {
            val workDir = Files.createTempDirectory("wps-workdir-plain-").toFile()
            File(workDir, "sub").mkdir()
            File(workDir, "sub/nested.txt").writeText("scratch file")
            File(workDir, "top.txt").writeText("scratch file")

            val process = ProcessBuilder(WorkerProcessSupport.defaultJavaBinary(), "-version").start()
            process.waitFor()

            val launched = WorkerProcessSupport.LaunchedWorker(process = process, workDir = workDir)
            launched.cleanup()

            workDir.exists().shouldBeFalse()
        }

        test("cleanup() is a no-op-safe on a workDir that no longer exists") {
            val workDir = Files.createTempDirectory("wps-workdir-gone-").toFile()
            workDir.delete()
            workDir.exists().shouldBeFalse()

            val process = ProcessBuilder(WorkerProcessSupport.defaultJavaBinary(), "-version").start()
            process.waitFor()

            val launched = WorkerProcessSupport.LaunchedWorker(process = process, workDir = workDir)
            // Must not throw even though workDir was already removed out from under it.
            launched.cleanup()
        }
    })
