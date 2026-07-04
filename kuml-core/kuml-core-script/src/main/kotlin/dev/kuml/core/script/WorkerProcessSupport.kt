package dev.kuml.core.script

import java.io.BufferedReader
import java.io.File

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
     * Launches a worker JVM with a **fixed argument list** (never a shell string,
     * so there is no command-injection surface — nothing from the untrusted
     * script influences argv) and a **minimal environment** (the parent's env,
     * which may carry API keys / tokens, is NOT inherited; only `PATH` + `TMPDIR`
     * are restored so the JVM can boot and write its own temp files).
     *
     * @param warm when true, passes [ScriptWorkerMain.ARG_WARM] so the child
     *   pre-warms and emits a ready line before consuming a request.
     */
    fun launch(
        javaBinary: String,
        classpath: String,
        maxHeapMb: Int,
        warm: Boolean,
    ): Process {
        val command =
            buildList {
                add(javaBinary)
                add("-Xmx${maxHeapMb}m")
                // Keep the child headless & quiet; a conservative, fixed set of
                // flags so behaviour cannot be perturbed by inherited JVM options.
                add("-XX:+UseSerialGC")
                add("-Djava.awt.headless=true")
                add("-cp")
                add(classpath)
                add(WORKER_MAIN_CLASS)
                if (warm) add(ScriptWorkerMain.ARG_WARM)
            }
        val builder = ProcessBuilder(command)
        builder.environment().clear()
        System.getenv("PATH")?.let { builder.environment()["PATH"] = it }
        System.getenv("TMPDIR")?.let { builder.environment()["TMPDIR"] = it }
        builder.redirectErrorStream(false)
        return builder.start()
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
