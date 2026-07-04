package dev.kuml.core.script

import java.io.BufferedReader
import java.io.File
import java.nio.file.Files

/**
 * Shared helpers for launching and talking to a script-evaluation child JVM
 * ([ScriptWorkerMain]). Used by both the cold-start [ChildProcessScriptEvaluator]
 * (Welle 2) and the warm-pool [WarmScriptWorker] / [WorkerPool] (Welle 3), so the
 * process-launch hardening (fixed argv, minimal environment, capped heap) lives
 * in exactly one place and cannot drift between the two paths.
 *
 * V0.23.3 — Welle 3.
 */
internal object WorkerProcessSupport {
    const val WORKER_MAIN_CLASS: String = "dev.kuml.core.script.ScriptWorkerMain"

    /** 32 MiB — large legitimate models are allowed, unbounded gibberish is not. */
    const val MAX_RESPONSE_LENGTH: Int = 32 * 1024 * 1024

    /**
     * A launched worker process together with the per-worker temp directory that
     * is its **only** writable location under the OS sandbox (Welle 4). The
     * directory is created before launch and must be deleted by the caller when
     * the worker is done (via [LaunchedWorker.cleanup]).
     */
    class LaunchedWorker(
        val process: Process,
        private val workDir: File,
    ) {
        /** Recursively removes the per-worker temp directory. Idempotent, best-effort. */
        fun cleanup() {
            runCatching { workDir.deleteRecursively() }
        }
    }

    /**
     * Launches a worker JVM with a **fixed argument list** (never a shell string,
     * so there is no command-injection surface — nothing from the untrusted
     * script influences argv) and a **minimal environment** (the parent's env,
     * which may carry API keys / tokens, is NOT inherited; only `PATH` is
     * restored so the JVM can boot; `TMPDIR` is pinned to the per-worker
     * sandbox-writable [LaunchedWorker] directory so the child's own temp files
     * land inside the OS cage).
     *
     * On macOS the command is additionally wrapped in `sandbox-exec` with the
     * strict seatbelt profile ([OsSandbox]); the per-worker temp directory is the
     * sole writable path. If OS isolation is `required` (default on macOS) but
     * cannot be applied, this throws [SandboxUnavailableException] — the caller
     * must fail closed and never launch an un-caged worker.
     *
     * @param warm when true, passes [ScriptWorkerMain.ARG_WARM] so the child
     *   pre-warms and emits a ready line before consuming a request.
     */
    fun launch(
        javaBinary: String,
        classpath: String,
        maxHeapMb: Int,
        warm: Boolean,
    ): LaunchedWorker {
        // Per-worker temp dir: created up front, used as the JVM temp dir AND as
        // the sole file-write-allowed subpath in the OS sandbox profile.
        //
        // Canonicalize the path: on macOS the system temp lives under
        // /var/folders/… which is a symlink to /private/var/folders/…. The
        // seatbelt kernel evaluates the *canonical* path, but the JVM writes via
        // the path we hand it. If the sandbox `subpath` param used the
        // non-canonical /var/… form, a legitimate write to the canonical
        // /private/var/… path would be denied. Using the canonical path for both
        // the sandbox param and java.io.tmpdir keeps them in lockstep.
        val workDir =
            Files
                .createTempDirectory("kuml-worker-work-")
                .toRealPath()
                .toFile()
                .apply { deleteOnExit() }

        val bareCommand =
            buildList {
                add(javaBinary)
                add("-Xmx${maxHeapMb}m")
                // Keep the child headless & quiet; a conservative, fixed set of
                // flags so behaviour cannot be perturbed by inherited JVM options.
                add("-XX:+UseSerialGC")
                add("-Djava.awt.headless=true")
                // Pin the JVM temp dir into the sandbox-writable workdir so the
                // Kotlin compiler / scripting host can write their scratch files.
                add("-Djava.io.tmpdir=${workDir.absolutePath}")
                add("-cp")
                add(classpath)
                add(WORKER_MAIN_CLASS)
                if (warm) add(ScriptWorkerMain.ARG_WARM)
            }

        val command =
            try {
                OsSandbox.wrap(bareCommand, workDir)
            } catch (e: SandboxUnavailableException) {
                runCatching { workDir.deleteRecursively() }
                throw e
            }

        val builder = ProcessBuilder(command)
        builder.environment().clear()
        System.getenv("PATH")?.let { builder.environment()["PATH"] = it }
        // TMPDIR is pinned to the writable workdir (not the parent's TMPDIR),
        // so any tool that honours $TMPDIR also stays inside the cage.
        builder.environment()["TMPDIR"] = workDir.absolutePath
        builder.redirectErrorStream(false)
        return try {
            LaunchedWorker(builder.start(), workDir)
        } catch (e: Exception) {
            runCatching { workDir.deleteRecursively() }
            throw e
        }
    }

    /**
     * Reads up to [MAX_RESPONSE_LENGTH] characters, stopping at the first
     * newline. Returns null on EOF-before-any-data, `""` for an empty first line,
     * or the (bounded) content otherwise. Never buffers unboundedly — a hostile
     * child that writes a huge blob with no newline must never OOM the *parent*.
     */
    fun readBoundedLine(reader: BufferedReader): String? =
        try {
            val sb = StringBuilder()
            var sawAny = false
            while (sb.length < MAX_RESPONSE_LENGTH) {
                val c = reader.read()
                if (c < 0) break // EOF
                sawAny = true
                if (c == '\n'.code) break
                sb.append(c.toChar())
            }
            if (!sawAny) null else sb.toString()
        } catch (_: Exception) {
            null
        }

    /** Absolute path to the `java` binary of the *currently running* JVM. */
    fun defaultJavaBinary(): String {
        val javaHome = System.getProperty("java.home")
        val exe = if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "java.exe" else "java"
        return if (javaHome != null) {
            File(javaHome, "bin${File.separator}$exe").absolutePath
        } else {
            exe // last-resort: rely on PATH
        }
    }
}
