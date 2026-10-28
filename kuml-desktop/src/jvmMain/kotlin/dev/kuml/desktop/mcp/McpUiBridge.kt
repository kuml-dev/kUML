package dev.kuml.desktop.mcp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import javax.swing.SwingUtilities

/**
 * Runs blocking MCP tool code against the UI thread ([uiDispatcher], the Compose/EDT thread on
 * which the user types) with bounded waiting. Extracted from [DesktopMcpTools] so the workspace
 * tools share exactly the same plumbing; behaviour of the existing tools is unchanged.
 */
internal class McpUiBridge(
    private val uiDispatcher: CoroutineDispatcher,
    private val uiTimeoutMs: Long,
) {
    /** Owns suspend jobs that must outlive a caller that timed out (their effect may still land). */
    private val jobScope = CoroutineScope(SupervisorJob() + uiDispatcher)

    /** Runs [block] on the UI dispatcher with a timeout; `UI_BUSY` if the UI does not get to it in time. */
    fun <T> onUi(block: () -> T): T {
        checkNotEdt()
        // The job is cancelled on timeout: a task still queued behind a busy UI thread then never
        // runs, and the caller is released immediately instead of waiting for the UI thread.
        val job = jobScope.async { block() }
        return try {
            runBlocking { withTimeout(uiTimeoutMs) { job.await() } }
        } catch (_: TimeoutCancellationException) {
            job.cancel()
            throw DesktopMcpTools.err(code = "UI_BUSY")
        }
    }

    /**
     * Like [onUi], but for state-mutating blocks: the timeout only applies while the block is still
     * queued. Once it has started on the UI thread it runs to the end and its result is returned,
     * so the client never sees `UI_BUSY` for a change that was in fact applied.
     */
    fun <T> onUiMutating(block: () -> T): T {
        checkNotEdt()
        return runBlocking {
            val started = CompletableDeferred<Unit>()
            val job =
                async(uiDispatcher) {
                    started.complete(Unit)
                    block()
                }
            try {
                withTimeout(uiTimeoutMs) { started.await() }
            } catch (_: TimeoutCancellationException) {
                if (!started.isCompleted) {
                    job.cancel()
                    throw DesktopMcpTools.err(code = "UI_BUSY")
                }
            }
            job.await()
        }
    }

    /**
     * Suspend variant for operations that do disk I/O or render. The start must happen within the
     * UI timeout (else `UI_BUSY`, nothing was done); once started, the whole operation is bounded by
     * [totalTimeoutMs] (else `OPERATION_TIMEOUT` with `applied:"unknown"` -- the operation keeps
     * running and may still be applied, it is deliberately NOT cancelled half-way).
     */
    fun <T> onUiSuspend(
        totalTimeoutMs: Long,
        block: suspend () -> T,
    ): T {
        checkNotEdt()
        val started = CompletableDeferred<Unit>()
        val job =
            jobScope.async {
                started.complete(Unit)
                block()
            }
        return runBlocking {
            try {
                withTimeout(uiTimeoutMs) { started.await() }
            } catch (_: TimeoutCancellationException) {
                if (!started.isCompleted) {
                    job.cancel()
                    throw DesktopMcpTools.err(code = "UI_BUSY")
                }
            }
            try {
                withTimeout(totalTimeoutMs) { job.await() }
            } catch (_: TimeoutCancellationException) {
                throw DesktopMcpTools.err(code = "OPERATION_TIMEOUT", "applied" to "unknown")
            }
        }
    }

    private fun checkNotEdt() {
        check(!SwingUtilities.isEventDispatchThread()) { "MCP tools must not run on the event dispatch thread" }
    }
}
