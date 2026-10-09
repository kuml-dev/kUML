package dev.kuml.desktop.mcp

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import dev.kuml.desktop.AppState
import dev.kuml.mcp.McpRequestHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/** Lifecycle state of the local MCP server as shown in the dialog and the status bar. */
internal sealed interface McpServerStatus {
    data object Off : McpServerStatus

    data class Running(
        val port: Int,
        val portChanged: Boolean,
    ) : McpServerStatus

    data class Error(
        val message: String,
    ) : McpServerStatus
}

/**
 * Starts/stops the [DesktopMcpServer] according to [AppState.mcpServerEnabled], owns the bearer
 * token and exposes [status] for Compose.
 *
 * Port-squatting defence: the port is remembered across restarts so the `claude mcp add …`
 * command stays valid. If the remembered port is taken when the server starts, a different
 * process may be holding it on purpose, so the token is regenerated as well -- a client
 * configured for the old port can then never hand its old token to the squatter's successor.
 *
 * Note: the JDK HttpServer's dispatcher thread is NOT a daemon thread. [stop] must run on every
 * exit path, otherwise the JVM does not terminate after the window closes.
 */
internal class DesktopMcpServerManager(
    private val state: AppState,
    private val tokenStore: McpTokenStore,
    private val handlerFactory: () -> McpRequestHandler,
) {
    // Single-threaded: apply()/regenerateToken() jobs run strictly in call order (FIFO), so a quick
    // on/off flip can never be reordered by a multi-threaded pool.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val lock = Any()
    private var server: DesktopMcpServer? = null

    /** Set (under [lock]) by [stop]; a start job that was already queued must then not start anything. */
    private var stopped = false

    @Volatile
    private var token: String? = null
    private val statusState = mutableStateOf<McpServerStatus>(McpServerStatus.Off)

    val status: State<McpServerStatus> get() = statusState

    /** Starts or stops the server (asynchronously, off the UI thread). */
    fun apply(enabled: Boolean) {
        scope.launch {
            if (enabled) startServer() else stopServer()
        }
    }

    /** Invalidates the current token immediately; connected clients must be reconfigured. */
    fun regenerateToken() {
        scope.launch {
            synchronized(lock) {
                try {
                    val fresh = tokenStore.regenerate()
                    if (server != null) token = fresh
                } catch (e: Exception) {
                    // Fail closed: the user wants the old token dead. If it cannot be replaced, stop serving.
                    logger.warn("MCP token regeneration failed: {}", e.javaClass.simpleName)
                    stopServer()
                    statusState.value =
                        McpServerStatus.Error(
                            message = "Token could not be regenerated, server stopped: ${e.message ?: e.javaClass.simpleName}",
                        )
                }
            }
        }
    }

    /** The token currently accepted by the running server, or `null` while stopped. */
    fun currentToken(): String? = token

    /** Stops the server and releases its threads. Safe to call repeatedly and from any thread. */
    fun stop() {
        synchronized(lock) {
            stopped = true
            stopServer()
        }
        scope.cancel()
    }

    private fun startServer() {
        synchronized(lock) {
            if (stopped) return
            server?.stop()
            server = null
            var created: DesktopMcpServer? = null
            try {
                token = tokenStore.loadOrCreate()
                created =
                    DesktopMcpServer(
                        handler = handlerFactory(),
                        tokenProvider = { token },
                    )
                val result = created.start(preferredPort = state.mcpServerPort)
                if (result.portChanged) token = tokenStore.regenerate()
                server = created
                state.mcpServerPort = result.port
                statusState.value = McpServerStatus.Running(port = result.port, portChanged = result.portChanged)
            } catch (e: Exception) {
                logger.warn("MCP server failed to start: {}", e.javaClass.simpleName)
                // Never leave a listening, unreferenced server behind.
                runCatching { created?.stop() }
                server = null
                token = null
                statusState.value = McpServerStatus.Error(message = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun stopServer() {
        synchronized(lock) {
            server?.stop()
            server = null
            token = null
            statusState.value = McpServerStatus.Off
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger("dev.kuml.desktop.mcp")
    }
}
