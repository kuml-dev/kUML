package dev.kuml.desktop.mcp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** One finished (or running, [ok] == `null`) MCP request as shown in the status-bar tooltip. */
internal data class McpCall(
    val id: Long,
    val label: String,
    val startedAtMillis: Long,
    val ok: Boolean?,
)

/**
 * Visual state of the status-bar MCP indicator, derived from [McpActivityTracker].
 * [Failed] stays until the next successful request, so a failure is not missed while looking away.
 */
internal enum class McpActivityPhase { Ready, Busy, Failed }

/**
 * Records authenticated MCP requests for the status bar: how many are running, the last few
 * calls and when the last one finished (to drive the short flash).
 *
 * Only labels (tool name or JSON-RPC method, already sanitized by the server) are stored --
 * never arguments, scripts or results. Safe to call from the HTTP worker threads; all state is
 * Compose snapshot state, so the UI recomposes on change.
 */
internal class McpActivityTracker(
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxRecent: Int = MAX_RECENT,
) {
    private val lock = Any()
    private var nextId = 0L

    private var runningCount by mutableIntStateOf(0)
    private var lastFailed by mutableStateOf(false)

    /** Newest first, at most [maxRecent] entries. */
    var recent by mutableStateOf<List<McpCall>>(emptyList())
        private set

    /** Changes whenever a request finishes; the UI keys its flash effect on it. */
    var finishedCounter by mutableLongStateOf(0L)
        private set

    val phase: McpActivityPhase
        get() =
            when {
                runningCount > 0 -> McpActivityPhase.Busy
                lastFailed -> McpActivityPhase.Failed
                else -> McpActivityPhase.Ready
            }

    /** Marks a request as started and returns its id for [end]. */
    fun begin(label: String): Long =
        synchronized(lock) {
            val id = nextId++
            runningCount += 1
            recent = (listOf(McpCall(id = id, label = label, startedAtMillis = clock(), ok = null)) + recent).take(maxRecent)
            id
        }

    /** Marks the request [id] as finished. Unknown ids are ignored for the list but still release the running slot. */
    fun end(
        id: Long,
        ok: Boolean,
    ) {
        synchronized(lock) {
            if (runningCount > 0) runningCount -= 1
            lastFailed = !ok
            recent = recent.map { if (it.id == id) it.copy(ok = ok) else it }
            finishedCounter += 1
        }
    }

    /** Forgets everything, e.g. when the server stops. */
    fun reset() {
        synchronized(lock) {
            runningCount = 0
            lastFailed = false
            recent = emptyList()
        }
    }

    companion object {
        const val MAX_RECENT = 5
    }
}
