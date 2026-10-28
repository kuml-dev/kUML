package dev.kuml.desktop.mcp

import dev.kuml.cli.workspace.WorkspaceBlockEvaluator
import dev.kuml.cli.workspace.WorkspaceRenderer
import dev.kuml.desktop.AppState
import dev.kuml.desktop.i18n.Strings
import dev.kuml.desktop.workspace.DocumentRevision
import dev.kuml.desktop.workspace.OpenWorkspace
import dev.kuml.desktop.workspace.WorkspaceDeleteOutcome
import dev.kuml.desktop.workspace.WorkspaceState
import dev.kuml.desktop.workspace.WorkspaceTrust
import dev.kuml.desktop.workspace.WorkspaceWriteOutcome
import dev.kuml.mcp.McpContent
import dev.kuml.mcp.McpToolAnnotations
import dev.kuml.mcp.McpToolDescriptor
import dev.kuml.mcp.tools.McpTool
import dev.kuml.mcp.tools.McpToolException
import dev.kuml.renderer.theme.core.ThemeRegistry
import dev.kuml.workspace.OkfDocument
import dev.kuml.workspace.OkfFinding
import dev.kuml.workspace.OkfSeverity
import dev.kuml.workspace.OkfValidator
import dev.kuml.workspace.WorkspaceDocumentResolver
import dev.kuml.workspace.WorkspaceScanner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Renders OKF documents to files below [outputDir]; production: [WorkspaceRenderer.render]. */
internal fun interface McpWorkspaceRenderPort {
    fun render(
        documents: List<OkfDocument>,
        outputDir: File,
        format: String,
        width: Int,
        themeName: String?,
        strict: Boolean,
    ): WorkspaceRenderer.RenderReport
}

/** Evaluates the kuml blocks of documents (EXECUTES block code); production: [WorkspaceBlockEvaluator.evaluateDocuments]. */
internal fun interface McpWorkspaceEvaluatePort {
    fun evaluate(
        documents: List<OkfDocument>,
        strict: Boolean,
    ): List<OkfFinding>
}

internal val DefaultWorkspaceRenderPort =
    McpWorkspaceRenderPort { documents, outputDir, format, width, themeName, strict ->
        WorkspaceRenderer.render(
            documents = documents,
            outputDir = outputDir,
            format = format,
            width = width,
            mirror = false,
            strict = strict,
            themeName = themeName,
        )
    }

internal val DefaultWorkspaceEvaluatePort =
    McpWorkspaceEvaluatePort { documents, strict -> WorkspaceBlockEvaluator.evaluateDocuments(documents = documents, strict = strict) }

/**
 * The workspace-mode tools of the desktop MCP server: they read and edit the OKF Knowledge
 * workspace that is open in the running app (documents are Markdown files below the workspace
 * root), the counterpart of the script tools for the single-file mode.
 *
 * Security model (every layer independent): all paths pass [WorkspacePathPolicy] and then the
 * workspace resolvers (containment, symlinks, hidden/excluded dirs); writes need the workspace
 * root to be trusted by the user; writes go through the same `OkfDocumentWriter` gate as Ctrl+S
 * and never touch unsaved editor work; deleting needs an explicit `confirm:true`; sizes are capped;
 * nothing here logs content, tokens or absolute paths; errors never carry exception messages.
 */
internal class DesktopMcpWorkspaceTools(
    private val state: AppState,
    private val ui: McpUiBridge,
    private val strings: () -> Strings,
    private val renderPort: McpWorkspaceRenderPort,
    private val evaluatePort: McpWorkspaceEvaluatePort,
    private val evalGate: Semaphore,
    private val onNotice: (McpNotice) -> Unit,
    evalDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val ioTimeoutMs: Long = IO_TIMEOUT_MS,
    private val renderTimeoutMs: Long = DesktopMcpTools.RENDER_TIMEOUT_MS,
    private val renderAllTimeoutMs: Long = RENDER_ALL_TIMEOUT_MS,
    private val gateWaitMs: Long = DesktopMcpTools.GATE_WAIT_MS,
) {
    private val log = LoggerFactory.getLogger("dev.kuml.desktop.mcp")
    private val evalScope = CoroutineScope(SupervisorJob() + evalDispatcher)

    val tools: List<McpTool> =
        listOf(
            tool(
                name = "kuml_desktop_workspace_info",
                description =
                    "Describes the workspace open in kUML Desktop: {name, root, mode (knowledge|engineering), trusted, " +
                        "documentCount, countsByType, openDocument, openDirty, autoRefresh}. Works for both workspace modes. " +
                        "Fails with NO_WORKSPACE_OPEN.",
                schema = schema(),
                annotations = readOnly(),
                body = ::info,
            ),
            tool(
                name = "kuml_desktop_workspace_list_documents",
                description =
                    "Lists the workspace documents: {total, offset, documents:[{path,type,rawType,title,kumlBlocks," +
                        "revision,dirty,sizeBytes}]}. " +
                        "Paths are relative to the workspace root. Paged: limit (default 500, max 2000), offset, optional path prefix. " +
                        "In engineering workspaces it lists the .kuml.kts scripts.",
                schema =
                    schema(
                        Prop(name = "offset", type = "integer", description = "Index of the first document (default 0)."),
                        Prop(name = "limit", type = "integer", description = "Page size, 1-2000 (default 500)."),
                        Prop(name = "prefix", type = "string", description = "Only documents whose relative path starts with this prefix."),
                    ),
                annotations = readOnly(),
                body = ::listDocuments,
            ),
            tool(
                name = "kuml_desktop_workspace_get_document",
                description =
                    "Returns {path, content, revision, dirty, diskRevision?}. If the document is open in the editor with " +
                        "unsaved changes, content/revision describe that unsaved buffer (dirty:true) and diskRevision the file. " +
                        "Revision = first 16 hex chars of SHA-256 of the UTF-8 content. Max 512 KiB. " +
                        "Errors: INVALID_PATH, DOCUMENT_NOT_FOUND, DOCUMENT_TOO_LARGE.",
                schema =
                    schema(
                        Prop(
                            name = "path",
                            type = "string",
                            description = "Document path relative to the workspace root (.md).",
                            required = true,
                        ),
                    ),
                annotations = readOnly(),
                body = ::getDocument,
            ),
            tool(
                name = "kuml_desktop_workspace_set_document",
                description =
                    "Writes a document to DISK (same save gate as Ctrl+S). With baseRevision (from get_document/list_documents) it " +
                        "updates an existing document and fails with REVISION_CONFLICT if the file changed. Without baseRevision it " +
                        "only creates a new document (missing folders are created) and fails with REVISION_CONFLICT{reason:EXISTS} " +
                        "if the file exists. A document with unsaved editor changes is never overwritten (DOCUMENT_DIRTY). " +
                        "Content max 512 KiB. Returns {revision, created, path, warnings}. Requires a trusted workspace. " +
                        "Errors: SAVE_BLOCKED, INVALID_PATH, CONTENT_TOO_LARGE, WORKSPACE_NOT_TRUSTED, WRITE_FAILED, " +
                        "OPERATION_TIMEOUT (outcome unknown).",
                schema =
                    schema(
                        Prop(
                            name = "path",
                            type = "string",
                            description = "Document path relative to the workspace root, ending in .md.",
                            required = true,
                        ),
                        Prop(
                            name = "content",
                            type = "string",
                            description = "The complete document text including frontmatter (max 512 KiB).",
                            required = true,
                        ),
                        Prop(
                            name = "baseRevision",
                            type = "string",
                            description = "Revision the update is based on; omit to create a new document.",
                        ),
                    ),
                annotations = McpToolAnnotations(readOnlyHint = false, destructiveHint = true, openWorldHint = false),
                body = ::setDocument,
            ),
            tool(
                name = "kuml_desktop_workspace_delete_document",
                description =
                    "PERMANENTLY deletes a document file (no trash). Requires confirm:true. Optional baseRevision guards against " +
                        "deleting a changed file. Refused with DOCUMENT_DIRTY while it has unsaved editor changes. " +
                        "Errors: CONFIRMATION_REQUIRED, DOCUMENT_NOT_FOUND, REVISION_CONFLICT, INVALID_PATH, WORKSPACE_NOT_TRUSTED.",
                schema =
                    schema(
                        Prop(
                            name = "path",
                            type = "string",
                            description = "Document path relative to the workspace root.",
                            required = true,
                        ),
                        Prop(name = "confirm", type = "boolean", description = "Must be exactly true.", required = true),
                        Prop(name = "baseRevision", type = "string", description = "Optional revision the deletion is based on."),
                    ),
                annotations = McpToolAnnotations(readOnlyHint = false, destructiveHint = true, openWorldHint = false),
                body = ::deleteDocument,
            ),
            tool(
                name = "kuml_desktop_workspace_validate",
                description =
                    "Validates the documents ON DISK (unsaved editor buffers are not included, see unsavedDocuments): " +
                        "{ok, scope, errorCount, warningCount, findings:[{code,severity,path,line,message,hint}], unsavedDocuments}. " +
                        "evaluate:true additionally EXECUTES the kuml blocks (needs a trusted workspace; may answer BUSY).",
                schema =
                    schema(
                        Prop(name = "path", type = "string", description = "Validate only this document."),
                        Prop(
                            name = "evaluate",
                            type = "boolean",
                            description = "Also evaluate kuml blocks (executes code). Default false.",
                        ),
                        Prop(
                            name = "strict",
                            type = "boolean",
                            description = "With evaluate: treat several diagrams per block as error. Default false.",
                        ),
                        Prop(
                            name = "strictVocabulary",
                            type = "boolean",
                            description = "Treat unknown type: values as errors. Default false.",
                        ),
                    ),
                annotations = McpToolAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false),
                body = ::validate,
            ),
            rawTool(
                name = "kuml_desktop_workspace_render_document",
                description =
                    "Renders one kuml block of a document (as saved ON DISK) to SVG (default) or PNG: SVG as {ok, svg, ...}, PNG as " +
                        "an image content. Executes the block's script (trusted workspace required). width 64-8192 (PNG), theme " +
                        "defaults to the active theme. Errors: BLOCK_NOT_FOUND, UNKNOWN_THEME, BUSY, RENDER_TIMEOUT, " +
                        "SVG_TOO_LARGE, PNG_TOO_LARGE.",
                schema =
                    schema(
                        Prop(
                            name = "path",
                            type = "string",
                            description = "Document path relative to the workspace root.",
                            required = true,
                        ),
                        Prop(name = "blockIndex", type = "integer", description = "0-based kuml block index (default 0)."),
                        Prop(name = "format", type = "string", description = "svg or png (default svg).", enum = listOf("svg", "png")),
                        Prop(name = "width", type = "integer", description = "PNG width in pixels, 64-8192 (default 1200)."),
                        Prop(name = "theme", type = "string", description = "Theme name (default: the active theme)."),
                    ),
                annotations =
                    McpToolAnnotations(
                        readOnlyHint = false,
                        destructiveHint = false,
                        idempotentHint = true,
                        openWorldHint = false,
                    ),
                body = ::renderDocument,
            ),
            tool(
                name = "kuml_desktop_workspace_render_all",
                description =
                    "Renders every kuml block of the workspace (as saved ON DISK) into a temporary location and reports " +
                        "{ok, renderedCount, skippedCount, failedCount, rendered:[{path,blockIndex}], skipped, failed, warnings}. " +
                        "Nothing is written into the workspace. Executes scripts (trusted workspace required); occupies the shared " +
                        "evaluation slot for up to 120 s, so other evaluating tools may answer BUSY meanwhile. Max 500 blocks.",
                schema =
                    schema(
                        Prop(name = "format", type = "string", description = "svg or png (default svg).", enum = listOf("svg", "png")),
                        Prop(name = "width", type = "integer", description = "PNG width in pixels, 64-8192 (default 1200)."),
                        Prop(name = "theme", type = "string", description = "Theme name (default: the active theme)."),
                    ),
                annotations =
                    McpToolAnnotations(
                        readOnlyHint = false,
                        destructiveHint = false,
                        idempotentHint = true,
                        openWorldHint = false,
                    ),
                body = ::renderAll,
            ),
            tool(
                name = "kuml_desktop_workspace_open_document",
                description =
                    "Opens a document in the kUML Desktop editor/preview (edit:true opens the raw editor). Refused with " +
                        "DOCUMENT_DIRTY if a different document has unsaved changes (nothing is discarded). Requires a trusted workspace.",
                schema =
                    schema(
                        Prop(
                            name = "path",
                            type = "string",
                            description = "Document path relative to the workspace root.",
                            required = true,
                        ),
                        Prop(name = "edit", type = "boolean", description = "Open in the raw editor instead of the read view."),
                    ),
                annotations = McpToolAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false),
                body = ::openDocument,
            ),
            tool(
                name = "kuml_desktop_workspace_reload",
                description =
                    "Re-scans the workspace folder (picks up files created/changed/deleted outside kUML). onDirty controls what " +
                        "happens to unsaved editor changes: fail (default, DOCUMENT_DIRTY), keep, or discard. " +
                        "Returns {documentCount, added, removed, openDocument, dirty, discarded}.",
                schema =
                    schema(
                        Prop(
                            name = "onDirty",
                            type = "string",
                            description = "fail (default), keep or discard.",
                            enum = listOf("fail", "keep", "discard"),
                        ),
                    ),
                annotations = McpToolAnnotations(readOnlyHint = false, destructiveHint = true, openWorldHint = false),
                body = ::reload,
            ),
        )

    // ── snapshot / guards ───────────────────────────────────────────────────

    private class Snapshot(
        val knowledge: WorkspaceState?,
        val root: File,
        val trusted: Boolean,
        val theme: String,
        val watermark: Boolean,
        val documents: List<OkfDocument>,
        val scriptFiles: List<File>,
        val markerName: String?,
        val selectedPath: String?,
        val dirtyBuffer: String?,
    ) {
        val engineering: Boolean get() = knowledge == null
        val dirtyPath: String? get() = if (dirtyBuffer != null) selectedPath else null
    }

    /** Reads everything needed from UI state in one UI-thread hop. Throws NO_WORKSPACE_OPEN. */
    private fun snapshot(): Snapshot =
        ui.onUi {
            when (val ws = state.openWorkspace) {
                null ->
                    throw err(
                        code = "NO_WORKSPACE_OPEN",
                        "hint" to "Open a workspace in kUML Desktop (File > Open Workspace...).",
                    )
                is OpenWorkspace.Knowledge -> {
                    val s = ws.state
                    val root = s.workspace.root
                    Snapshot(
                        knowledge = s,
                        root = root,
                        trusted = WorkspaceTrust.isTrusted(trustedPaths = state.trustedWorkspaces.toList(), root = root),
                        theme = state.theme,
                        watermark = state.showWatermark,
                        documents = s.documents.toList(),
                        scriptFiles = emptyList(),
                        markerName = s.workspace.marker?.name,
                        selectedPath = s.selected?.relativePath,
                        dirtyBuffer = if (s.isDirty) s.buffer else null,
                    )
                }
                is OpenWorkspace.Engineering ->
                    Snapshot(
                        knowledge = null,
                        root = ws.root,
                        trusted = WorkspaceTrust.isTrusted(trustedPaths = state.trustedWorkspaces.toList(), root = ws.root),
                        theme = state.theme,
                        watermark = state.showWatermark,
                        documents = emptyList(),
                        scriptFiles = ws.scriptFiles.toList(),
                        markerName = null,
                        selectedPath = null,
                        dirtyBuffer = null,
                    )
            }
        }

    private fun Snapshot.requireKnowledge(): WorkspaceState =
        knowledge
            ?: throw err(
                code = "WORKSPACE_MODE_UNSUPPORTED",
                "mode" to "engineering",
                "hint" to "This tool needs a knowledge workspace.",
                "supported" to listOf("kuml_desktop_workspace_info", "kuml_desktop_workspace_list_documents"),
            )

    private fun Snapshot.requireTrusted() {
        if (!trusted) {
            throw err(
                code = "WORKSPACE_NOT_TRUSTED",
                "hint" to "Open the workspace in kUML Desktop and trust it first; this tool executes or changes workspace content.",
            )
        }
    }

    private fun checkedPath(raw: String): String =
        when (val c = WorkspacePathPolicy.check(raw)) {
            is WorkspacePathPolicy.Checked.Ok -> c.posixPath
            is WorkspacePathPolicy.Checked.Rejected ->
                throw errJson(code = "INVALID_PATH") {
                    put("reason", c.reason)
                    put("path", WorkspacePathPolicy.sanitize(raw))
                }
        }

    private fun resolveExisting(
        snap: Snapshot,
        posix: String,
    ): File =
        WorkspaceDocumentResolver
            .resolve(root = snap.root, requested = posix)
            .getOrElse { e -> throw resolveFailure(e = e, requested = posix) }

    /** Never uses the exception's message: it contains the absolute workspace root. */
    private fun resolveFailure(
        e: Throwable,
        requested: String,
    ): McpToolException {
        val de = e as? WorkspaceDocumentResolver.DocumentResolutionException ?: return err(code = "IO_ERROR")
        return when (de.rejection) {
            WorkspaceDocumentResolver.Rejection.NOT_FOUND ->
                errJson(code = "DOCUMENT_NOT_FOUND") { de.suggestion?.let { put("suggestion", it) } }
            else ->
                errJson(code = "INVALID_PATH") {
                    put(
                        "reason",
                        if (de.rejection ==
                            WorkspaceDocumentResolver.Rejection.NOT_A_REGULAR_FILE
                        ) {
                            "NOT_A_FILE"
                        } else {
                            de.rejection.name
                        },
                    )
                    put("path", WorkspacePathPolicy.sanitize(requested))
                }
        }
    }

    // ── tools ────────────────────────────────────────────────────────────────

    private fun info(
        @Suppress("UNUSED_PARAMETER") args: JsonObject,
    ): JsonObject {
        val snap = snapshot()
        val counts = LinkedHashMap<String, Int>()
        snap.documents.forEach { d -> counts.merge(d.type?.id ?: "untyped", 1, Int::plus) }
        return buildJsonObject {
            put("name", snap.markerName ?: snap.root.name)
            put("root", snap.root.absolutePath)
            put("mode", if (snap.engineering) "engineering" else "knowledge")
            put("trusted", snap.trusted)
            put("documentCount", if (snap.engineering) snap.scriptFiles.size else snap.documents.size)
            putJsonObject("countsByType") { counts.toSortedMap().forEach { (k, v) -> put(k, v) } }
            put("openDocument", snap.selectedPath?.let { JsonPrimitive(it) } ?: JsonNull)
            put("openDirty", snap.dirtyPath != null)
            put("autoRefresh", true)
        }
    }

    private fun listDocuments(args: JsonObject): JsonObject {
        val offset = intArg(args = args, key = "offset", default = 0, min = 0, max = Int.MAX_VALUE)
        val limit = intArg(args = args, key = "limit", default = DEFAULT_LIST_LIMIT, min = 1, max = MAX_LIST_LIMIT)
        val prefix = optString(args = args, key = "prefix")
        val snap = snapshot()
        if (snap.engineering) {
            val rows =
                snap.scriptFiles
                    .map { it.relativeToOrSelf(snap.root).path.replace(File.separatorChar, '/') to it }
                    .filter { prefix == null || it.first.startsWith(prefix) }
                    .sortedBy { it.first }
            return buildJsonObject {
                put("total", rows.size)
                put("offset", offset)
                putJsonArray("documents") {
                    rows.drop(offset).take(limit).forEach { (rel, f) ->
                        add(
                            buildJsonObject {
                                put("path", rel)
                                put("type", "KumlScript")
                                put("kumlBlocks", 0)
                                put("dirty", false)
                                put("sizeBytes", f.length())
                            },
                        )
                    }
                }
            }
        }
        val filtered = snap.documents.filter { prefix == null || it.relativePath.replace('\\', '/').startsWith(prefix) }
        return buildJsonObject {
            put("total", filtered.size)
            put("offset", offset)
            putJsonArray("documents") {
                filtered.drop(offset).take(limit).forEach { d ->
                    val path = d.relativePath.replace('\\', '/')
                    val dirty = snap.dirtyPath == d.relativePath
                    val revision =
                        if (dirty) {
                            DocumentRevision.of(snap.dirtyBuffer.orEmpty())
                        } else {
                            runCatching { DocumentRevision.ofFile(d.file) }.getOrNull()
                        }
                    add(
                        buildJsonObject {
                            put("path", path)
                            put("type", d.type?.id?.let { JsonPrimitive(it) } ?: JsonNull)
                            put("rawType", d.rawType?.let { JsonPrimitive(it) } ?: JsonNull)
                            put("title", d.frontmatter.title?.let { JsonPrimitive(it) } ?: JsonNull)
                            put("kumlBlocks", d.kumlBlocks.size)
                            put("revision", revision?.let { JsonPrimitive(it) } ?: JsonNull)
                            put("dirty", dirty)
                            put("sizeBytes", runCatching { d.file.length() }.getOrDefault(0L))
                        },
                    )
                }
            }
        }
    }

    private fun getDocument(args: JsonObject): JsonObject {
        val posix = checkedPath(stringArg(args = args, key = "path"))
        val snap = snapshot()
        snap.requireKnowledge()
        val file = resolveExisting(snap = snap, posix = posix)
        val size = file.length()
        if (size > MAX_DOCUMENT_BYTES) throw err(code = "DOCUMENT_TOO_LARGE", "max" to MAX_DOCUMENT_BYTES)
        val relative = relativeOf(snap = snap, file = file)
        val diskText =
            try {
                String(Files.readAllBytes(file.toPath()), Charsets.UTF_8)
            } catch (e: java.io.IOException) {
                throw err(code = "IO_ERROR", "errorClass" to e.javaClass.simpleName)
            }
        val dirty = snap.dirtyPath == relative && snap.dirtyBuffer != null
        val content = if (dirty) snap.dirtyBuffer.orEmpty() else diskText
        if (content.toByteArray(Charsets.UTF_8).size >
            MAX_DOCUMENT_BYTES
        ) {
            throw err(code = "DOCUMENT_TOO_LARGE", "max" to MAX_DOCUMENT_BYTES)
        }
        return buildJsonObject {
            put("path", posix)
            put("content", content)
            put("revision", DocumentRevision.of(content))
            put("dirty", dirty)
            if (dirty) put("diskRevision", DocumentRevision.of(diskText))
        }
    }

    private fun setDocument(args: JsonObject): JsonObject {
        val raw = stringArg(args = args, key = "path")
        val content = stringArg(args = args, key = "content")
        val baseRevision = optString(args = args, key = "baseRevision")
        if (content.toByteArray(Charsets.UTF_8).size >
            MAX_DOCUMENT_BYTES
        ) {
            throw err(code = "CONTENT_TOO_LARGE", "max" to MAX_DOCUMENT_BYTES)
        }
        val posix = checkedPath(raw)
        val snap = snapshot()
        val ws = snap.requireKnowledge()
        snap.requireTrusted()
        val outcome =
            ui.onUiSuspend(totalTimeoutMs = ioTimeoutMs) {
                val result =
                    if (baseRevision != null) {
                        ws.writeDocument(
                            relativePath = posix,
                            content = content,
                            baseRevision = baseRevision,
                            themeName = snap.theme,
                            strings = strings(),
                            watermark = snap.watermark,
                        )
                    } else {
                        ws.createDocument(
                            relativePath = posix,
                            content = content,
                            themeName = snap.theme,
                            strings = strings(),
                            watermark = snap.watermark,
                        )
                    }
                if (result is WorkspaceWriteOutcome.Written) {
                    onNotice(McpNotice.WorkspaceDocumentWritten(path = posix, created = result.created))
                }
                result
            }
        return when (outcome) {
            is WorkspaceWriteOutcome.Written ->
                buildJsonObject {
                    put("revision", outcome.revision)
                    put("created", outcome.created)
                    put("path", posix)
                    putJsonArray("warnings") { outcome.warnings.forEach { addFinding(it) } }
                }
            is WorkspaceWriteOutcome.RevisionConflict ->
                throw errJson(code = "REVISION_CONFLICT") {
                    outcome.currentRevision?.let { put("currentRevision", it) }
                    if (baseRevision == null) put("reason", "EXISTS")
                }
            WorkspaceWriteOutcome.Dirty ->
                throw errJson(code = "DOCUMENT_DIRTY") {
                    put("hint", "The document has unsaved changes in the editor; save or discard them in kUML Desktop first.")
                }
            is WorkspaceWriteOutcome.Blocked ->
                throw errJson(code = "SAVE_BLOCKED") { putJsonArray("findings") { outcome.findings.forEach { addFinding(it) } } }
            WorkspaceWriteOutcome.NotFound -> throw errJson(
                code = "DOCUMENT_NOT_FOUND",
            ) { put("hint", "To create a document, omit baseRevision.") }
            is WorkspaceWriteOutcome.PathRejected ->
                throw errJson(code = "INVALID_PATH") {
                    put("reason", outcome.reason)
                    put("path", WorkspacePathPolicy.sanitize(raw))
                }
            is WorkspaceWriteOutcome.TooLarge -> throw err(code = "CONTENT_TOO_LARGE", "max" to outcome.limit)
            is WorkspaceWriteOutcome.Failed -> throw err(code = "WRITE_FAILED", "errorClass" to outcome.errorClass)
        }
    }

    private fun deleteDocument(args: JsonObject): JsonObject {
        val raw = stringArg(args = args, key = "path")
        val confirm = (args["confirm"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        if (confirm != true) {
            throw err(code = "CONFIRMATION_REQUIRED", "hint" to "Deleting is permanent. Pass confirm:true (boolean) to proceed.")
        }
        val baseRevision = optString(args = args, key = "baseRevision")
        val posix = checkedPath(raw)
        val snap = snapshot()
        val ws = snap.requireKnowledge()
        snap.requireTrusted()
        val outcome =
            ui.onUiSuspend(totalTimeoutMs = ioTimeoutMs) {
                val result =
                    ws.deleteDocument(
                        relativePath = posix,
                        baseRevision = baseRevision,
                        themeName = snap.theme,
                        strings = strings(),
                        watermark = snap.watermark,
                    )
                if (result is WorkspaceDeleteOutcome.Deleted) onNotice(McpNotice.WorkspaceDocumentDeleted(path = posix))
                result
            }
        return when (outcome) {
            is WorkspaceDeleteOutcome.Deleted -> buildJsonObject { put("deleted", posix) }
            WorkspaceDeleteOutcome.Dirty ->
                throw errJson(code = "DOCUMENT_DIRTY") { put("hint", "The document has unsaved changes in the editor.") }
            is WorkspaceDeleteOutcome.RevisionConflict -> throw err(
                code = "REVISION_CONFLICT",
                "currentRevision" to outcome.currentRevision,
            )
            WorkspaceDeleteOutcome.NotFound -> throw err(code = "DOCUMENT_NOT_FOUND")
            is WorkspaceDeleteOutcome.PathRejected ->
                throw errJson(code = "INVALID_PATH") {
                    put("reason", outcome.reason)
                    put("path", WorkspacePathPolicy.sanitize(raw))
                }
            is WorkspaceDeleteOutcome.Failed -> throw err(code = "WRITE_FAILED", "errorClass" to outcome.errorClass)
        }
    }

    private fun validate(args: JsonObject): JsonObject {
        val rawPath = optString(args = args, key = "path")
        val evaluate = boolArg(args = args, key = "evaluate", default = false)
        val strict = boolArg(args = args, key = "strict", default = false)
        val strictVocabulary = boolArg(args = args, key = "strictVocabulary", default = false)
        val posix = rawPath?.let { checkedPath(it) }
        val snap = snapshot()
        snap.requireKnowledge()
        if (evaluate) snap.requireTrusted()

        val findings = mutableListOf<OkfFinding>()
        val docs: List<OkfDocument>
        if (posix != null) {
            val doc =
                WorkspaceScanner
                    .scanDocument(root = snap.root, relativePath = posix)
                    .getOrElse { e -> throw resolveFailure(e = e, requested = posix) }
            docs = listOf(doc)
            findings += OkfValidator.validateDocument(root = snap.root, doc = doc, strictVocabulary = strictVocabulary)
        } else {
            val ws =
                try {
                    WorkspaceScanner.scan(root = snap.root)
                } catch (e: Exception) {
                    throw err(code = "SCAN_FAILED", "errorClass" to e.javaClass.simpleName)
                }
            docs = ws.documents
            findings += OkfValidator.validate(ws = ws, strictVocabulary = strictVocabulary)
        }
        if (evaluate) {
            findings += runGated(timeoutMs = renderTimeoutMs) { evaluatePort.evaluate(documents = docs, strict = strict) }
        }
        val errors = findings.count { it.severity == OkfSeverity.ERROR }
        val unsaved = snap.dirtyPath?.takeIf { dp -> docs.any { it.relativePath == dp } }
        return buildJsonObject {
            put("ok", errors == 0)
            put("scope", if (posix != null) "document" else "workspace")
            put("errorCount", errors)
            put("warningCount", findings.size - errors)
            putJsonArray("findings") { findings.forEach { addFinding(it) } }
            putJsonArray("unsavedDocuments") { unsaved?.let { add(JsonPrimitive(it.replace('\\', '/'))) } }
            if (!evaluate && docs.any { it.kumlBlocks.isNotEmpty() }) {
                put("note", "Diagram counts per kuml block are only checked with evaluate=true (executes block code).")
            }
        }
    }

    private class RenderOutput(
        val bytes: ByteArray?,
        val tooLarge: Boolean,
        val report: WorkspaceRenderer.RenderReport,
    )

    private fun renderDocument(args: JsonObject): List<McpContent> {
        val posix = checkedPath(stringArg(args = args, key = "path"))
        val blockIndex = intArg(args = args, key = "blockIndex", default = 0, min = 0, max = Int.MAX_VALUE)
        val format = formatArg(args)
        val width = intArg(args = args, key = "width", default = DEFAULT_PNG_WIDTH, min = MIN_PNG_WIDTH, max = MAX_PNG_WIDTH)
        val snap = snapshot()
        snap.requireKnowledge()
        snap.requireTrusted()
        val theme = themeArg(args = args, snap = snap)
        val doc =
            WorkspaceScanner
                .scanDocument(root = snap.root, relativePath = posix)
                .getOrElse { e -> throw resolveFailure(e = e, requested = posix) }
        if (blockIndex >= doc.kumlBlocks.size) throw err(code = "BLOCK_NOT_FOUND", "count" to doc.kumlBlocks.size)
        val single = doc.copy(kumlBlocks = listOf(doc.kumlBlocks[blockIndex]))
        val out =
            runGated(timeoutMs = renderTimeoutMs) {
                val tmp = Files.createTempDirectory("kuml-mcp-render").toFile()
                try {
                    val report =
                        renderPort.render(
                            documents = listOf(single),
                            outputDir = tmp,
                            format = format,
                            width = width,
                            themeName = theme,
                            strict = false,
                        )
                    val file = report.rendered.firstOrNull()
                    val tooLarge = file != null && file.length() > MAX_RENDER_BYTES
                    val bytes = if (file != null && !tooLarge) file.readBytes() else null
                    RenderOutput(bytes = bytes, tooLarge = tooLarge, report = report)
                } finally {
                    tmp.deleteRecursively()
                }
            }
        if (out.report.failures.isNotEmpty() || (out.bytes == null && !out.tooLarge)) {
            val failed =
                buildJsonObject {
                    put("ok", false)
                    put("path", posix)
                    put("blockIndex", blockIndex)
                    putJsonArray("findings") { out.report.failures.forEach { addFinding(it) } }
                }
            return listOf(McpContent(type = "text", text = failed.toString()))
        }
        if (out.tooLarge) throw err(code = if (format == "png") "PNG_TOO_LARGE" else "SVG_TOO_LARGE", "max" to MAX_RENDER_BYTES)
        val bytes = out.bytes ?: throw err(code = "RENDER_FAILED")
        val meta =
            buildJsonObject {
                put("ok", true)
                put("path", posix)
                put("blockIndex", blockIndex)
                put("diagramCount", doc.kumlBlocks.size)
                if (format == "svg") put("svg", String(bytes, Charsets.UTF_8))
                putJsonArray("warnings") { out.report.warnings.forEach { addFinding(it) } }
            }
        return if (format == "png") {
            listOf(
                McpContent(type = "text", text = meta.toString()),
                McpContent(type = "image", data = Base64.getEncoder().encodeToString(bytes), mimeType = "image/png"),
            )
        } else {
            listOf(McpContent(type = "text", text = meta.toString()))
        }
    }

    private class AllOutput(
        val rendered: List<Pair<String, Int>>,
        val skipped: List<String>,
        val failed: List<OkfFinding>,
        val warnings: List<OkfFinding>,
    )

    private fun renderAll(args: JsonObject): JsonObject {
        val format = formatArg(args)
        val width = intArg(args = args, key = "width", default = DEFAULT_PNG_WIDTH, min = MIN_PNG_WIDTH, max = MAX_PNG_WIDTH)
        val snap = snapshot()
        snap.requireKnowledge()
        snap.requireTrusted()
        val theme = themeArg(args = args, snap = snap)
        val ws =
            try {
                WorkspaceScanner.scan(root = snap.root)
            } catch (e: Exception) {
                throw err(code = "SCAN_FAILED", "errorClass" to e.javaClass.simpleName)
            }
        val blockCount = ws.documents.sumOf { it.kumlBlocks.size }
        if (blockCount > MAX_RENDER_ALL_BLOCKS) throw err(code = "TOO_MANY_BLOCKS", "max" to MAX_RENDER_ALL_BLOCKS)
        val out =
            runGated(timeoutMs = renderAllTimeoutMs) { cancelled ->
                val tmp = Files.createTempDirectory("kuml-mcp-render").toFile()
                try {
                    val rendered = mutableListOf<Pair<String, Int>>()
                    val skipped = mutableListOf<String>()
                    val failed = mutableListOf<OkfFinding>()
                    val warnings = mutableListOf<OkfFinding>()
                    for (doc in ws.documents) {
                        if (cancelled.get()) break
                        val report =
                            renderPort.render(
                                documents = listOf(doc),
                                outputDir = tmp,
                                format = format,
                                width = width,
                                themeName = theme,
                                strict = true,
                            )
                        skipped += report.skipped
                        failed += report.failures
                        warnings += report.warnings
                        val failedLines =
                            report.failures
                                .filter { it.file == doc.relativePath }
                                .map { it.line }
                                .toSet()
                        doc.kumlBlocks.forEachIndexed { i, block ->
                            if (block.startLine !in failedLines) {
                                rendered += doc.relativePath.replace('\\', '/') to i
                            }
                        }
                    }
                    AllOutput(rendered = rendered, skipped = skipped, failed = failed, warnings = warnings)
                } finally {
                    tmp.deleteRecursively()
                }
            }
        val ok = out.failed.isEmpty() && out.warnings.none { it.severity == OkfSeverity.ERROR }
        return buildJsonObject {
            put("ok", ok)
            put("renderedCount", out.rendered.size)
            put("skippedCount", out.skipped.size)
            put("failedCount", out.failed.size)
            putJsonArray("rendered") {
                out.rendered.forEach { (p, i) ->
                    add(
                        buildJsonObject {
                            put("path", p)
                            put("blockIndex", i)
                        },
                    )
                }
            }
            putJsonArray("skipped") { out.skipped.forEach { add(JsonPrimitive(it.replace('\\', '/'))) } }
            putJsonArray("failed") { out.failed.forEach { addFinding(it) } }
            putJsonArray("warnings") { out.warnings.forEach { addFinding(it) } }
        }
    }

    private fun openDocument(args: JsonObject): JsonObject {
        val posix = checkedPath(stringArg(args = args, key = "path"))
        val edit = boolArg(args = args, key = "edit", default = false)
        val snap = snapshot()
        val ws = snap.requireKnowledge()
        snap.requireTrusted()
        val file = resolveExisting(snap = snap, posix = posix)
        val relative = relativeOf(snap = snap, file = file)
        return ui.onUiSuspend(totalTimeoutMs = renderTimeoutMs + ioTimeoutMs) {
            if (ws.documents.none { it.relativePath == relative }) {
                ws.refreshFromDisk(themeName = snap.theme, strings = strings(), watermark = snap.watermark)
            }
            val doc =
                ws.documents.find { it.relativePath == relative }
                    ?: throw err(code = "DOCUMENT_NOT_FOUND")
            if (ws.isDirty && ws.selected?.relativePath != relative) {
                throw errJson(code = "DOCUMENT_DIRTY") {
                    put("hint", "Another document has unsaved changes; save or discard them in kUML Desktop first.")
                }
            }
            if (ws.selected?.relativePath != relative) {
                ws.select(doc = doc, themeName = snap.theme, strings = strings(), watermark = snap.watermark)
            }
            ws.setEditing(edit)
            buildJsonObject {
                put("path", posix)
                put("revision", DocumentRevision.of(ws.buffer.orEmpty()))
                put("dirty", ws.isDirty)
                put("rendering", ws.isRendering)
            }
        }
    }

    private fun reload(args: JsonObject): JsonObject {
        val mode = optString(args = args, key = "onDirty") ?: "fail"
        if (mode !in setOf("fail", "keep", "discard")) throw err(code = "INVALID_ARGUMENT", "argument" to "onDirty")
        val snap = snapshot()
        val ws = snap.requireKnowledge()
        snap.requireTrusted()
        return ui.onUiSuspend(totalTimeoutMs = renderTimeoutMs + ioTimeoutMs) {
            val before = ws.documents.map { it.relativePath }.toSet()
            val wasDirty = ws.isDirty
            if (wasDirty && mode == "fail") {
                throw errJson(code = "DOCUMENT_DIRTY") { put("hint", "Use onDirty=keep or onDirty=discard.") }
            }
            val discarded = wasDirty && mode == "discard"
            if (discarded) ws.discardChanges()
            ws.refreshFromDisk(themeName = snap.theme, strings = strings(), watermark = snap.watermark)
            if (discarded) {
                ws.selected?.let { ws.select(doc = it, themeName = snap.theme, strings = strings(), watermark = snap.watermark) }
            }
            val after = ws.documents.map { it.relativePath }.toSet()
            buildJsonObject {
                put("documentCount", after.size)
                putJsonArray("added") { (after - before).sorted().forEach { add(JsonPrimitive(it.replace('\\', '/'))) } }
                putJsonArray("removed") { (before - after).sorted().forEach { add(JsonPrimitive(it.replace('\\', '/'))) } }
                put("openDocument", ws.selected?.relativePath?.let { JsonPrimitive(it) } ?: JsonNull)
                put("dirty", ws.isDirty)
                put("discarded", discarded)
            }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Runs [task] on the eval dispatcher while holding the shared evaluation slot. The slot is
     * released by the task itself when it really finished (a script cannot be aborted), not when
     * the waiting caller times out.
     */
    private fun <T> runGated(
        timeoutMs: Long,
        task: (AtomicBoolean) -> T,
    ): T {
        if (!evalGate.tryAcquire(gateWaitMs, TimeUnit.MILLISECONDS)) throw err(code = "BUSY")
        val cancelled = AtomicBoolean(false)
        val job =
            evalScope.async {
                try {
                    task(cancelled)
                } finally {
                    evalGate.release()
                }
            }
        return try {
            runBlocking { withTimeout(timeoutMs) { job.await() } }
        } catch (_: TimeoutCancellationException) {
            cancelled.set(true)
            throw err(code = "RENDER_TIMEOUT")
        } catch (e: McpToolException) {
            throw e
        } catch (e: Exception) {
            throw err(code = "RENDER_FAILED", "errorClass" to e.javaClass.simpleName)
        }
    }

    private fun relativeOf(
        snap: Snapshot,
        file: File,
    ): String = posixRelativePath(root = WorkspaceScanner.canonicalRoot(snap.root), file = file)

    private fun themeArg(
        args: JsonObject,
        snap: Snapshot,
    ): String {
        val requested = optString(args = args, key = "theme") ?: return snap.theme
        if (requested !in ThemeRegistry.names()) throw err(code = "UNKNOWN_THEME", "available" to ThemeRegistry.names())
        return requested
    }

    private fun formatArg(args: JsonObject): String {
        val f = optString(args = args, key = "format") ?: return "svg"
        if (f != "svg" && f != "png") throw err(code = "INVALID_ARGUMENT", "argument" to "format")
        return f
    }

    private fun JsonArrayBuilder.addFinding(f: OkfFinding) {
        add(
            buildJsonObject {
                put("code", f.code)
                put("severity", f.severity.name.lowercase())
                put("path", f.file.replace('\\', '/'))
                put("line", f.line)
                put("message", f.message)
                f.suggestion?.let { put("hint", it) }
            },
        )
    }

    private fun stringArg(
        args: JsonObject,
        key: String,
    ): String = optString(args = args, key = key) ?: throw err(code = "INVALID_ARGUMENT", "argument" to key)

    private fun optString(
        args: JsonObject,
        key: String,
    ): String? {
        val value = args[key] ?: return null
        if (value is JsonNull) return null
        val p = value as? JsonPrimitive
        if (p == null || !p.isString) throw err(code = "INVALID_ARGUMENT", "argument" to key)
        return p.contentOrNull
    }

    private fun boolArg(
        args: JsonObject,
        key: String,
        default: Boolean,
    ): Boolean {
        val value = args[key] ?: return default
        if (value is JsonNull) return default
        val p = value as? JsonPrimitive
        val b = if (p == null || p.isString) null else p.booleanOrNull
        return b ?: throw err(code = "INVALID_ARGUMENT", "argument" to key)
    }

    private fun intArg(
        args: JsonObject,
        key: String,
        default: Int,
        min: Int,
        max: Int,
    ): Int {
        val value = args[key] ?: return default
        if (value is JsonNull) return default
        val p = value as? JsonPrimitive
        val n = if (p == null || p.isString) null else p.intOrNull
        if (n == null || n < min || n > max) throw err(code = "INVALID_ARGUMENT", "argument" to key, "min" to min, "max" to max)
        return n
    }

    private fun err(
        code: String,
        vararg extra: Pair<String, Any>,
    ): McpToolException = DesktopMcpTools.err(code = code, *extra)

    private fun errJson(
        code: String,
        build: JsonObjectBuilder.() -> Unit = {},
    ): McpToolException =
        McpToolException(
            message =
                buildJsonObject {
                    put("error", code)
                    build()
                }.toString(),
        )

    private fun tool(
        name: String,
        description: String,
        schema: JsonObject,
        annotations: McpToolAnnotations,
        body: (JsonObject) -> JsonObject,
    ): McpTool =
        rawTool(
            name = name,
            description = description,
            schema = schema,
            annotations = annotations,
        ) { args -> listOf(McpContent(type = "text", text = body(args).toString())) }

    private fun rawTool(
        name: String,
        description: String,
        schema: JsonObject,
        annotations: McpToolAnnotations,
        body: (JsonObject) -> List<McpContent>,
    ): McpTool =
        object : McpTool {
            override val descriptor =
                McpToolDescriptor(name = name, description = description, inputSchema = schema, annotations = annotations)

            override fun call(arguments: JsonObject): List<McpContent> =
                try {
                    body(arguments)
                } catch (e: McpToolException) {
                    // Code only: never the message body (could echo client-supplied paths).
                    log.info("MCP workspace tool {} failed: {}", name, errorCodeOf(e))
                    throw e
                }
        }

    private fun errorCodeOf(e: McpToolException): String =
        runCatching { Json.parseToJsonElement(e.message.orEmpty()).jsonObject["error"]?.let { (it as? JsonPrimitive)?.contentOrNull } }
            .getOrNull() ?: "UNKNOWN"

    private class Prop(
        val name: String,
        val type: String,
        val description: String,
        val required: Boolean = false,
        val enum: List<String>? = null,
    )

    private fun schema(vararg props: Prop): JsonObject =
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                props.forEach { p ->
                    putJsonObject(p.name) {
                        put("type", p.type)
                        put("description", p.description)
                        p.enum?.let { values -> put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }) }
                    }
                }
            }
            put("required", JsonArray(props.filter { it.required }.map { JsonPrimitive(it.name) }))
        }

    private fun readOnly() = McpToolAnnotations(readOnlyHint = true, openWorldHint = false)

    companion object {
        const val IO_TIMEOUT_MS = 10_000L
        const val RENDER_ALL_TIMEOUT_MS = 120_000L
        const val MAX_DOCUMENT_BYTES = 512 * 1024
        const val MAX_RENDER_BYTES = 4L * 1024 * 1024
        const val DEFAULT_LIST_LIMIT = 500
        const val MAX_LIST_LIMIT = 2000
        const val DEFAULT_PNG_WIDTH = 1200
        const val MIN_PNG_WIDTH = 64
        const val MAX_PNG_WIDTH = 8192
        const val MAX_RENDER_ALL_BLOCKS = 500

        val TOOL_NAMES =
            listOf(
                "kuml_desktop_workspace_info",
                "kuml_desktop_workspace_list_documents",
                "kuml_desktop_workspace_get_document",
                "kuml_desktop_workspace_set_document",
                "kuml_desktop_workspace_delete_document",
                "kuml_desktop_workspace_validate",
                "kuml_desktop_workspace_render_document",
                "kuml_desktop_workspace_render_all",
                "kuml_desktop_workspace_open_document",
                "kuml_desktop_workspace_reload",
            )
    }
}

private fun File.relativeToOrSelf(base: File): File = runCatching { relativeTo(base) }.getOrDefault(this)

/** Root-relative path with '/' separators on every OS, matching [OkfDocument.relativePath]. */
internal fun posixRelativePath(
    root: File,
    file: File,
): String = file.relativeTo(root).path.replace(File.separatorChar, '/')
