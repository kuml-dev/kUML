package dev.kuml.desktop.mcp

import dev.kuml.desktop.AppState
import dev.kuml.desktop.editor.EditorActions
import dev.kuml.desktop.i18n.Strings
import dev.kuml.desktop.render.DesktopRenderResult
import dev.kuml.desktop.render.DesktopValidationResult
import dev.kuml.desktop.render.RenderInputs
import dev.kuml.desktop.workspace.DocumentRevision
import dev.kuml.desktop.workspace.OpenWorkspace
import dev.kuml.mcp.McpContent
import dev.kuml.mcp.McpToolAnnotations
import dev.kuml.mcp.McpToolDescriptor
import dev.kuml.mcp.tools.KumlAuthoringTools
import dev.kuml.mcp.tools.McpTool
import dev.kuml.mcp.tools.McpToolException
import dev.kuml.mcp.tools.McpToolRegistry
import dev.kuml.renderer.theme.core.ThemeRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Asks the render controller to render [RenderInputs] right now (no debounce) and hands back the job. */
internal fun interface McpRenderPort {
    fun renderNow(inputs: RenderInputs): Deferred<DesktopRenderResult?>
}

/** Evaluates a script without touching any app state. */
internal fun interface McpValidatePort {
    fun validate(script: String): DesktopValidationResult
}

/** Transient events the UI surfaces to the user when an MCP client changed something. */
internal sealed interface McpNotice {
    /** A client wrote (or created) a workspace document on disk; informational only, there is no undo. */
    data class WorkspaceDocumentWritten(
        val path: String,
        val created: Boolean,
    ) : McpNotice

    /** A client deleted a workspace document from disk; informational only. */
    data class WorkspaceDocumentDeleted(
        val path: String,
    ) : McpNotice

    /** A client replaced the whole script; [previousScript] allows an explicit "Undo". */
    data class ScriptReplaced(
        val previousScript: String,
        val newRevision: String,
    ) : McpNotice
}

/**
 * The live-session tools of the desktop MCP server: they operate on the script that is open in
 * the running app, not on a script passed in by the client.
 *
 * All [AppState] access happens on [uiDispatcher] (the Compose/EDT thread), bounded by a timeout
 * ([UI_BUSY] on expiry) -- the same thread on which the user types, so check-and-write in
 * `set_script` has no race window. Failures are reported as `isError` tool results whose text is
 * a small JSON object `{"error":"<CODE>", ...}`.
 *
 * Nothing here ever saves a file: `set_script` only changes the in-memory buffer (and marks it
 * dirty), exactly like typing.
 */
internal class DesktopMcpTools(
    private val state: AppState,
    private val render: McpRenderPort,
    private val validate: McpValidatePort,
    private val editorActions: () -> EditorActions?,
    private val onNotice: (McpNotice) -> Unit,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val evalDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val uiTimeoutMs: Long = UI_TIMEOUT_MS,
    private val renderTimeoutMs: Long = RENDER_TIMEOUT_MS,
    /** Shared by every tool that evaluates scripts (render, validate, the wrapped `kuml.*` tools). */
    val evalGate: Semaphore = Semaphore(1),
    private val gateWaitMs: Long = GATE_WAIT_MS,
    strings: () -> Strings = { Strings.EN },
    workspaceRender: McpWorkspaceRenderPort = DefaultWorkspaceRenderPort,
    workspaceEvaluate: McpWorkspaceEvaluatePort = DefaultWorkspaceEvaluatePort,
    workspacePdfExport: McpWorkspacePdfExportPort = DefaultWorkspacePdfExportPort,
) {
    private val evalScope = CoroutineScope(SupervisorJob() + evalDispatcher)
    private val ui = McpUiBridge(uiDispatcher = uiDispatcher, uiTimeoutMs = uiTimeoutMs)

    private val workspaceTools =
        DesktopMcpWorkspaceTools(
            state = state,
            ui = ui,
            strings = strings,
            renderPort = workspaceRender,
            evaluatePort = workspaceEvaluate,
            pdfExportPort = workspacePdfExport,
            evalGate = evalGate,
            onNotice = onNotice,
            evalDispatcher = evalDispatcher,
            renderTimeoutMs = renderTimeoutMs,
            gateWaitMs = gateWaitMs,
        )

    val tools: List<McpTool> =
        scriptTools() + workspaceTools.tools

    private fun scriptTools(): List<McpTool> =
        listOf(
            tool(
                name = "kuml_desktop_get_script",
                description =
                    "Returns the script currently open in kUML Desktop: {script, revision, dirty, file}. " +
                        "Pass the revision as baseRevision to kuml_desktop_set_script.",
                schema = noArgs(),
                annotations = readOnly(),
                body = ::getScript,
            ),
            tool(
                name = "kuml_desktop_set_script",
                description =
                    "Replaces the script in the open kUML Desktop editor (in memory only, never saved; the user can undo). " +
                        "Fails with CONFLICT if baseRevision is not the current revision. The diagram re-renders automatically.",
                schema =
                    objectSchema(
                        required = listOf("script", "baseRevision"),
                        "script" to "The complete new script (max 512 KiB).",
                        "baseRevision" to "Revision returned by kuml_desktop_get_script that this edit is based on.",
                    ),
                annotations = McpToolAnnotations(readOnlyHint = false, destructiveHint = true, openWorldHint = false),
                body = ::setScript,
            ),
            tool(
                name = "kuml_desktop_render",
                description = "Renders the current script now and returns {ok, svg} or {ok:false, error}.",
                schema = noArgs(),
                annotations =
                    McpToolAnnotations(
                        readOnlyHint = false,
                        destructiveHint = false,
                        idempotentHint = true,
                        openWorldHint = false,
                    ),
                body = ::renderTool,
            ),
            tool(
                name = "kuml_desktop_validate",
                description =
                    "Evaluates a script (default: the open one) without changing anything and returns {ok, diagnostics}.",
                schema = objectSchema(required = emptyList(), "script" to "Optional script to validate instead of the open one."),
                annotations = readOnly(),
                body = ::validateTool,
            ),
            tool(
                name = "kuml_desktop_get_last_error",
                description = "Returns the last render/compile error shown in kUML Desktop: {error} (null if none).",
                schema = noArgs(),
                annotations = readOnly(),
                body = ::getLastError,
            ),
            tool(
                name = "kuml_desktop_get_theme",
                description = "Returns the active theme and the available ones: {theme, available}.",
                schema = noArgs(),
                annotations = readOnly(),
                body = ::getTheme,
            ),
            tool(
                name = "kuml_desktop_set_theme",
                description = "Switches the active theme (persisted like the View menu choice). Fails with UNKNOWN_THEME.",
                schema = objectSchema(required = listOf("theme"), "theme" to "One of the names from kuml_desktop_get_theme."),
                annotations =
                    McpToolAnnotations(
                        readOnlyHint = false,
                        destructiveHint = false,
                        idempotentHint = true,
                        openWorldHint = false,
                    ),
                body = ::setTheme,
            ),
        )

    // ── tools ────────────────────────────────────────────────────────────────

    private fun getScript(
        @Suppress("UNUSED_PARAMETER") args: JsonObject,
    ): JsonObject =
        onUi {
            requireNoWorkspace()
            buildJsonObject {
                put("script", state.script)
                put("revision", revisionOf(state.script))
                put("dirty", state.isDirty)
                put("file", state.currentFile?.name?.let { JsonPrimitive(it) } ?: JsonNull)
            }
        }

    private fun setScript(args: JsonObject): JsonObject {
        val newText = stringArg(args = args, key = "script")
        val baseRevision = stringArg(args = args, key = "baseRevision")
        if (newText.toByteArray(Charsets.UTF_8).size > MAX_SCRIPT_BYTES) throw err(code = "SCRIPT_TOO_LARGE", "max" to MAX_SCRIPT_BYTES)
        return onUiMutating {
            requireNoWorkspace()
            if (state.simulation != null || state.simulationStarting) throw err(code = "SIMULATION_RUNNING")
            val current = state.script
            val currentRevision = revisionOf(current)
            if (currentRevision != baseRevision) throw err(code = "CONFLICT", "currentRevision" to currentRevision)
            val newRevision = revisionOf(newText)
            if (newText != current) {
                val actions = editorActions()
                if (actions != null && state.viewMode != AppState.ViewMode.DIAGRAM) {
                    // Through the text area's document: one undoable edit, the DocumentListener
                    // reports it like typing (dirty flag, re-render).
                    actions.replaceAllUndoable(newText)
                }
                if (state.script != newText) {
                    // No editor mounted (diagram-only view) -- same order EditorPane uses.
                    state.isDirty = true
                    state.script = newText
                }
                onNotice(McpNotice.ScriptReplaced(previousScript = current, newRevision = newRevision))
            }
            buildJsonObject { put("revision", newRevision) }
        }
    }

    private fun renderTool(
        @Suppress("UNUSED_PARAMETER") args: JsonObject,
    ): JsonObject {
        if (!evalGate.tryAcquire(gateWaitMs, TimeUnit.MILLISECONDS)) throw err(code = "BUSY")
        // Like validateTool: the slot is released when the render job itself completes, not when
        // the waiting caller gives up -- a timed-out evaluation may still be running.
        val released = AtomicBoolean(false)
        val release = { if (released.compareAndSet(false, true)) evalGate.release() }
        val deferred =
            try {
                onUi {
                    requireNoWorkspace()
                    render.renderNow(RenderInputs(script = state.script, themeName = state.theme, watermark = state.showWatermark))
                }
            } catch (e: Throwable) {
                release()
                throw e
            }
        deferred.invokeOnCompletion { release() }
        return awaitRender(deferred)
    }

    private fun awaitRender(deferred: Deferred<DesktopRenderResult?>): JsonObject {
        val result =
            try {
                runBlocking {
                    withTimeout(renderTimeoutMs) {
                        try {
                            deferred.await()
                        } catch (e: CancellationException) {
                            // Cancelled job but live caller => a newer render superseded this one.
                            if (currentCoroutineContext().isActive) throw err(code = "RENDER_SUPERSEDED")
                            throw e
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                throw err(code = "RENDER_TIMEOUT")
            }
        return when (result) {
            is DesktopRenderResult.Svg -> {
                val size = result.svg.toByteArray(Charsets.UTF_8).size
                if (size > MAX_SVG_BYTES) throw err(code = "SVG_TOO_LARGE", "size" to size)
                buildJsonObject {
                    put("ok", true)
                    put("svg", result.svg)
                    if (result.warnings.isNotEmpty()) {
                        putJsonArray("warnings") { result.warnings.forEach { add(JsonPrimitive(it)) } }
                    }
                }
            }
            is DesktopRenderResult.Error ->
                buildJsonObject {
                    put("ok", false)
                    put("error", result.message)
                }
            null ->
                buildJsonObject {
                    put("ok", false)
                    put("error", onUi { state.lastError } ?: "Unexpected render failure")
                }
        }
    }

    private fun validateTool(args: JsonObject): JsonObject {
        val explicit = optionalStringArg(args = args, key = "script")
        val script =
            explicit ?: onUi {
                requireNoWorkspace()
                state.script
            }
        if (script.toByteArray(Charsets.UTF_8).size > MAX_SCRIPT_BYTES) throw err(code = "SCRIPT_TOO_LARGE", "max" to MAX_SCRIPT_BYTES)
        if (!evalGate.tryAcquire(gateWaitMs, TimeUnit.MILLISECONDS)) throw err(code = "BUSY")
        // The gate is released by the eval task itself (not by the waiting caller): a script
        // evaluation cannot be aborted, so the slot must stay taken until it really finished.
        val job =
            evalScope.async {
                try {
                    validate.validate(script)
                } finally {
                    evalGate.release()
                }
            }
        val result =
            try {
                runBlocking { withTimeout(renderTimeoutMs) { job.await() } }
            } catch (_: TimeoutCancellationException) {
                throw err(code = "RENDER_TIMEOUT")
            }
        return buildJsonObject {
            put("ok", result.ok)
            putJsonArray("diagnostics") {
                result.diagnostics.forEach { d ->
                    add(
                        buildJsonObject {
                            put("severity", d.severity)
                            put("message", d.message)
                            d.line?.let { put("line", it) }
                            d.column?.let { put("column", it) }
                        },
                    )
                }
            }
        }
    }

    private fun getLastError(
        @Suppress("UNUSED_PARAMETER") args: JsonObject,
    ): JsonObject =
        onUi {
            val knowledge = (state.openWorkspace as? OpenWorkspace.Knowledge)?.state
            if (knowledge != null) {
                // Knowledge mode: the error lives in the document pane's state, not in the script's.
                buildJsonObject {
                    put("error", knowledge.docError?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("source", "workspace")
                    put("path", knowledge.selected?.relativePath?.let { JsonPrimitive(it) } ?: JsonNull)
                }
            } else {
                buildJsonObject {
                    put("error", state.lastError?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("source", "script")
                }
            }
        }

    private fun getTheme(
        @Suppress("UNUSED_PARAMETER") args: JsonObject,
    ): JsonObject =
        onUi {
            buildJsonObject {
                put("theme", state.theme)
                put("available", JsonArray(ThemeRegistry.names().map { JsonPrimitive(it) }))
            }
        }

    private fun setTheme(args: JsonObject): JsonObject {
        val name = stringArg(args = args, key = "theme")
        return onUi {
            if (name !in ThemeRegistry.names()) throw err(code = "UNKNOWN_THEME", "available" to ThemeRegistry.names())
            state.theme = name
            buildJsonObject { put("theme", name) }
        }
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    private fun requireNoWorkspace() {
        val ws = state.openWorkspace ?: return
        throw err(
            code = "WORKSPACE_MODE_UNSUPPORTED",
            "mode" to (if (ws is OpenWorkspace.Knowledge) "knowledge" else "engineering"),
            "hint" to "A workspace is open; use the kuml_desktop_workspace_* tools instead of the script tools.",
            "tools" to DesktopMcpWorkspaceTools.TOOL_NAMES,
        )
    }

    private fun <T> onUi(block: () -> T): T = ui.onUi(block)

    private fun <T> onUiMutating(block: () -> T): T = ui.onUiMutating(block)

    private fun tool(
        name: String,
        description: String,
        schema: JsonObject,
        annotations: McpToolAnnotations,
        body: (JsonObject) -> JsonObject,
    ): McpTool =
        object : McpTool {
            override val descriptor =
                McpToolDescriptor(name = name, description = description, inputSchema = schema, annotations = annotations)

            override fun call(arguments: JsonObject): List<McpContent> =
                listOf(McpContent(type = "text", text = body(arguments).toString()))
        }

    private fun stringArg(
        args: JsonObject,
        key: String,
    ): String = optionalStringArg(args = args, key = key) ?: throw err(code = "INVALID_ARGUMENT", "argument" to key)

    private fun optionalStringArg(
        args: JsonObject,
        key: String,
    ): String? {
        val value = args[key] ?: return null
        if (value is JsonNull) return null
        val primitive = value as? JsonPrimitive
        if (primitive == null || !primitive.isString) throw err(code = "INVALID_ARGUMENT", "argument" to key)
        return primitive.content
    }

    internal companion object {
        const val UI_TIMEOUT_MS = 5_000L
        const val RENDER_TIMEOUT_MS = 30_000L
        const val GATE_WAIT_MS = 30_000L
        const val MAX_SCRIPT_BYTES = 512 * 1024
        const val MAX_SVG_BYTES = 4 * 1024 * 1024

        /** First 16 hex chars of SHA-256 over the UTF-8 bytes (new digest instance per call -- thread safe). */
        fun revisionOf(script: String): String = DocumentRevision.of(script)

        fun err(
            code: String,
            vararg extra: Pair<String, Any>,
        ): McpToolException =
            McpToolException(
                message =
                    buildJsonObject {
                        put("error", code)
                        extra.forEach { (k, v) ->
                            when (v) {
                                is Number -> put(k, v)
                                is List<*> -> put(k, buildJsonArray { v.forEach { add(JsonPrimitive(it.toString())) } })
                                else -> put(k, v.toString())
                            }
                        }
                    }.toString(),
            )

        private fun noArgs(): JsonObject =
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {}
            }

        private fun objectSchema(
            required: List<String>,
            vararg properties: Pair<String, String>,
        ): JsonObject =
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    properties.forEach { (name, description) ->
                        putJsonObject(name) {
                            put("type", "string")
                            put("description", description)
                        }
                    }
                }
                putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
            }

        private fun readOnly() = McpToolAnnotations(readOnlyHint = true, openWorldHint = false)
    }
}

/**
 * Known limitation: a script that never terminates cannot be aborted, so it keeps [gate] (and, here,
 * an HTTP worker thread) taken until the app restarts; all eval tools then answer BUSY. Only a token
 * holder can trigger this (self-DoS); the remedy is to restart the app.
 *
 * Wraps a stateless authoring tool so that at most one script evaluation runs at a time across
 * all MCP tools (DoS bound); the descriptor is passed through unchanged.
 */
internal class SerializedTool(
    private val delegate: McpTool,
    private val gate: Semaphore,
    private val waitMs: Long = DesktopMcpTools.GATE_WAIT_MS,
) : McpTool {
    override val descriptor: McpToolDescriptor get() = delegate.descriptor

    override fun call(arguments: JsonObject): List<McpContent> {
        if (!gate.tryAcquire(waitMs, TimeUnit.MILLISECONDS)) throw DesktopMcpTools.err(code = "BUSY")
        try {
            return delegate.call(arguments)
        } finally {
            gate.release()
        }
    }
}

/** The desktop server's tool set: the live-session tools plus the (serialized) authoring tools. */
internal fun desktopMcpRegistry(tools: DesktopMcpTools): McpToolRegistry =
    McpToolRegistry(
        tools = tools.tools + KumlAuthoringTools.all.map { SerializedTool(delegate = it, gate = tools.evalGate) },
    )
