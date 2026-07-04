package dev.kuml.core.script

/**
 * [ScriptEvaluator] backed by a [WorkerPool] of pre-started warm worker JVMs
 * (Welle 3). This is the interactive-latency evaluator: an incoming
 * [evaluate] is served by an already-warmed child process, so it pays neither
 * JVM boot nor Kotlin-compiler warm-up on the critical path.
 *
 * Security properties are inherited from [WorkerPool] / [WarmScriptWorker]:
 * use-once workers (no cross-script state leak), a hard ceiling on concurrent
 * child JVMs (fork-bomb guard), and fail-closed behaviour (never a silent
 * in-process fallback).
 *
 * The pool is created lazily on first use and torn down via [close] (wired to a
 * JVM shutdown hook and to the MCP server's shutdown path so no worker process
 * is orphaned).
 *
 * V0.23.3 — Welle 3.
 */
internal class PooledScriptEvaluator(
    private val pool: WorkerPool = WorkerPool(),
) : ScriptEvaluator,
    AutoCloseable {
    override fun evaluate(
        source: String,
        fileName: String,
    ): EvaluatedScript = pool.evaluate(source, fileName)

    override fun close() {
        pool.close()
    }
}
