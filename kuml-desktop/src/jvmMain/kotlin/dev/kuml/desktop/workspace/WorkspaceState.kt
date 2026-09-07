package dev.kuml.desktop.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.kuml.desktop.i18n.Strings
import dev.kuml.desktop.render.DesktopRenderPipeline
import dev.kuml.desktop.render.DesktopRenderResult
import dev.kuml.markdown.CodeBlockExtractor
import dev.kuml.workspace.FrontmatterParser
import dev.kuml.workspace.FrontmatterWriter
import dev.kuml.workspace.OkfDocument
import dev.kuml.workspace.OkfDocumentParser
import dev.kuml.workspace.OkfDocumentWriter
import dev.kuml.workspace.OkfFinding
import dev.kuml.workspace.OkfSaveGate
import dev.kuml.workspace.OkfType
import dev.kuml.workspace.OkfValidator
import dev.kuml.workspace.OkfWorkspace
import dev.kuml.workspace.OkfWriteResult
import dev.kuml.workspace.WorkspaceGraphIndex
import dev.kuml.workspace.WorkspaceScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Holds an opened Knowledge-mode [OkfWorkspace], the currently selected document, its
 * in-memory edit buffer, and its render output (V3.6.4; made editable in V-next).
 *
 * Deliberately decoupled from Compose UI beyond the `mutableStateOf` properties
 * themselves — every function here is a plain `suspend`/regular function with no internal
 * coroutine launch/cancel bookkeeping, so it is directly unit-testable
 * (`WorkspaceStateTest`, `WorkspaceStateEditingTest`) via `runTest { state.select(...) }`
 * without needing a `CoroutineScope`/Job or Compose test harness. The caller (a Composable
 * with `rememberCoroutineScope`) is responsible for launching `select`/`save` in response
 * to a tree click / Ctrl+S.
 *
 * The document **set** is invariant over a [WorkspaceState]'s lifetime — this welle never
 * creates, deletes, or renames a document, only edits the content of one that already
 * exists (see [applySaved]). A future welle that adds create/delete must replace the
 * incremental [applySaved] path with a full [dev.kuml.workspace.WorkspaceScanner.scan].
 */
class WorkspaceState(
    initialWorkspace: OkfWorkspace,
) {
    var workspace: OkfWorkspace by mutableStateOf(initialWorkspace)
        private set

    /** Documents sorted by relativePath (matches [dev.kuml.workspace.WorkspaceScanner]'s order). */
    var documents: List<OkfDocument> by mutableStateOf(initialWorkspace.documents.sortedBy { it.relativePath })
        private set

    /** Bidirectional cross-link index (ADR-0011 FT-6), rebuilt in-memory after every successful [save]. */
    var graphIndex: WorkspaceGraphIndex by mutableStateOf(WorkspaceGraphIndex.build(ws = initialWorkspace))
        private set

    var selected: OkfDocument? by mutableStateOf(null)
        private set

    /** Rendered SVG for the current buffer's first kuml block; null = no diagram / error / prose. */
    var docSvg: String? by mutableStateOf(null)
        private set

    /** Render/validation error for the current buffer; null = no error. */
    var docError: String? by mutableStateOf(null)
        private set

    var isRendering: Boolean by mutableStateOf(false)
        private set

    /**
     * The selected document's full source, loaded into memory on [select] — the single
     * source of truth for BOTH the read view and the edit view (never re-read from disk
     * mid-session; see [DocumentEditorPane]'s KDoc for why the read view must project the
     * buffer, not the file). `null` only before the first [select] call.
     */
    var buffer: String? by mutableStateOf(null)
        private set

    /** The buffer's content at the last successful [select]/[save] — [isDirty]'s comparison baseline. */
    private var savedBuffer: String? by mutableStateOf(null)

    /** `true` when [buffer] has unsaved changes relative to [savedBuffer]. */
    val isDirty: Boolean get() = buffer != null && buffer != savedBuffer

    /**
     * Whether the current document is shown in the Markdown-rendered read view or the raw
     * editor. Backed by a differently-named private property — a `var editing` here would
     * generate a synthetic `setEditing(Boolean)` JVM method that collides with this class's
     * own [setEditing] function (a Kotlin "platform declaration clash", caught at compile
     * time, not a style preference).
     */
    val editing: Boolean get() = editingState
    private var editingState: Boolean by mutableStateOf(false)

    /** Remembers each document's own Read/Edit state across a selection change, by relativePath. */
    private val editModes = mutableStateMapOf<String, Boolean>()

    /** Dokumentlokal [OkfFinding]s for the current buffer, refreshed by [revalidate] and [save]. */
    var findings: List<OkfFinding> by mutableStateOf(emptyList())
        private set

    /** The subset of [findings] that would block a [save] — see [OkfSaveGate.BLOCKING_CODES]. */
    val blockingFindings: List<OkfFinding> get() = findings.filter { it.code in OkfSaveGate.BLOCKING_CODES }

    /**
     * Monotonic guard incremented ONLY by [select], so [revalidate] can detect whether the
     * document was switched out from under a still-running validation. Deliberately
     * INDEPENDENT of [renderToken] (bugfix, review finding) — the two debounced
     * `LaunchedEffect` collectors in `KnowledgeWorkspaceScreen` (buffer-changed →
     * [revalidate], kuml-block-changed → [renderCurrentBlock]) run concurrently on every
     * edit, and [renderCurrentBlock] bumping THIS same counter used to make a
     * still-in-flight [revalidate] discard its own result merely because a same-document
     * re-render happened to land in between — [findings] then never updated at all for that
     * edit (e.g. typing `type: Quatsch` never showed the OKF-W-002 banner until a failed
     * save). [revalidate] only READS this field, never writes it — [save]/[applySaved] do too
     * (bugfix, review finding, CRITICAL): [save] snapshots this field before its write starts
     * and gates every state mutation that follows (`savedBuffer`/`findings`, and — inside
     * [applySaved] — `selected` and the closing [renderCurrentBlock]) on the snapshot still
     * matching by the time each one runs, so a [select] that lands on a different document
     * while an earlier [save]'s write is still in flight can never be reverted by that save
     * once it finally resolves.
     */
    private var selectionToken = 0

    /**
     * Monotonic guard incremented by [renderCurrentBlock] on every call — including the one
     * [select] makes — so a newer render for the SAME document supersedes an older
     * still-running one (e.g. rapid edits). Separate from [selectionToken] on purpose; see
     * that field's KDoc for why conflating the two was the bug.
     */
    private var renderToken = 0

    /**
     * Selects [doc], loads its full source into [buffer], restores its remembered
     * [editing] state, and renders its first ` ```kuml ` block (if any) via
     * [renderCurrentBlock].
     *
     * [doc] is resolved against the current [documents] list by `relativePath` rather than
     * assigned to [selected] as-is (bugfix, review finding): callers can hold a stale
     * [OkfDocument] instance across a suspension — e.g. `MainWindow`'s
     * `confirmUnsavedAndThen` captures the tree row clicked *before* a pending [save]
     * completes, then invokes `select` with that pre-save instance once the save (and its
     * `applySaved` swap of a freshly reparsed instance into [documents]) has already
     * finished. Assigning such a stale instance directly would leave [selected] pointing at
     * an [OkfDocument] that is data-class-UNEQUAL to its own entry in [documents] (any
     * edited field — frontmatter, kuml blocks, links — differs), so `doc == selected`
     * checks in [dev.kuml.desktop.workspace.WorkspaceTreePane] (row highlighting, and the
     * live type badge gated on `isSelected`) would fail for every row until the user clicks
     * again. Falling back to [doc] itself when no matching entry exists yet keeps this a
     * no-op for the ordinary case (first selection right after a fresh scan, where the
     * clicked instance IS the list's instance).
     */
    suspend fun select(
        doc: OkfDocument,
        themeName: String,
        strings: Strings,
        watermark: Boolean = false,
    ) {
        val token = ++selectionToken
        val resolved = documents.find { it.relativePath == doc.relativePath } ?: doc
        selected = resolved
        editingState = editModes[resolved.relativePath] ?: false
        docSvg = null
        docError = null
        findings = emptyList()
        buffer = null
        savedBuffer = null

        val text = withContext(Dispatchers.IO) { runCatching { resolved.file.readText(Charsets.UTF_8) }.getOrDefault("") }
        if (token != selectionToken) return // superseded by a newer selection

        buffer = text
        savedBuffer = text

        renderCurrentBlock(themeName = themeName, strings = strings, watermark = watermark)
    }

    /** Toggles the Read/Edit view for the current document, remembered by [editModes]. */
    fun setEditing(value: Boolean) {
        editingState = value
        selected?.let { editModes[it.relativePath] = value }
    }

    /** Replaces [buffer] wholesale (the [dev.kuml.desktop.editor.SyntaxTextEditor]'s `onTextChange`). */
    fun updateBuffer(text: String) {
        buffer = text
    }

    /**
     * Reverts [buffer] to [savedBuffer] — i.e. discards any in-memory edits without writing
     * anything to disk (bugfix, review finding). [isDirty] is a *derived* property
     * (`buffer != savedBuffer`), so there was previously no way for `MainWindow`'s
     * "Verwerfen" (Discard) choice in `confirmUnsavedAndThen` to actually clear it for an
     * open Knowledge document: the fallthrough branch there proceeded with the requested
     * action (switch document, close workspace, open a different workspace, quit) but left
     * [buffer] — and therefore [isDirty] — untouched, so the very next tree click re-armed
     * the very same "unsaved changes" dialog the user had just dismissed. No-op before the
     * first [select] (both are `null` then).
     */
    fun discardChanges() {
        buffer = savedBuffer
    }

    /**
     * Splices `key: value` into [buffer]'s frontmatter via [FrontmatterWriter.setField] — a
     * projection into the SAME buffer, never a second, competing representation (Alan Kay's
     * "the buffer is the model"). No-op before the first [select].
     */
    fun setFrontmatterField(
        key: String,
        value: String,
    ) {
        val current = buffer ?: return
        buffer = FrontmatterWriter.setField(markdown = current, key = key, value = value)
    }

    /**
     * Re-runs [dev.kuml.workspace.OkfValidator.validateDocument] against the CURRENT
     * in-memory [buffer] (never against disk) on `Dispatchers.Default`, always with
     * `strictVocabulary = true` — the Desktop save gate treats `OKF-W-002` as blocking
     * regardless of the workspace marker's `strict` flag (see `OkfSaveGate`'s KDoc).
     * Meant to be called from a debounced collector in the hosting screen, not per
     * keystroke directly.
     */
    suspend fun revalidate() {
        val token = selectionToken
        val currentBuffer = buffer ?: return
        val currentDoc = selected ?: return
        val root = workspace.root
        val result =
            withContext(Dispatchers.Default) {
                val candidate = OkfDocumentParser.parse(root = root, file = currentDoc.file, text = currentBuffer)
                OkfValidator.validateDocument(root = root, doc = candidate, strictVocabulary = true)
            }
        if (token != selectionToken) return // a different document was selected meanwhile
        findings = result
    }

    /**
     * Renders the buffer's first ` ```kuml ` block (if any) via [DesktopRenderPipeline] on
     * `Dispatchers.IO`. The `type:` short-circuit for ERM diagrams reads the CURRENT buffer's
     * frontmatter (via [FrontmatterParser]), not [OkfDocument.type] — so switching the
     * `type:` dropdown to/from `ErmDiagram` mid-edit takes effect immediately, before a save.
     *
     * - No kuml block (prose document) → [docSvg] / [docError] both `null`.
     * - ERM diagram type → short-circuits to [docError] = [Strings.previewErmUnsupported]
     *   without evaluating the script.
     * - Otherwise → runs the block through the normal single-file render pipeline.
     */
    suspend fun renderCurrentBlock(
        themeName: String,
        strings: Strings,
        watermark: Boolean = false,
    ) {
        val token = ++renderToken
        // Reset immediately after the token bump (bugfix, review finding): this call's new
        // token already supersedes whatever render is still in flight, so any earlier call's
        // `finally` block (line ~290) is now guaranteed to lose its own `token == renderToken`
        // check and skip clearing `isRendering` itself — without this line, THIS call's three
        // early returns below (no buffer, no kuml block, ERM short-circuit) would then leave
        // `isRendering` stuck at `true` forever, with `docSvg`/`docError` both `null`, which
        // `WorkspacePreviewPane` renders as an unbounded "Rendering..." message even though
        // nothing is actually rendering. If this call proceeds past the early returns, it sets
        // `isRendering = true` again below before doing any real work.
        isRendering = false
        docSvg = null
        docError = null

        val currentBuffer = buffer ?: return
        val block = CodeBlockExtractor.extract(currentBuffer).firstOrNull() ?: return

        val currentType = OkfType.fromId(FrontmatterParser.parse(currentBuffer).type)
        if (currentType == OkfType.ERM_DIAGRAM) {
            docError = strings.previewErmUnsupported
            return
        }

        isRendering = true
        try {
            val result =
                withContext(Dispatchers.IO) {
                    DesktopRenderPipeline.render(script = block.source, themeName = themeName, watermark = watermark)
                }
            if (token != renderToken) return // superseded by a newer selection/render
            when (result) {
                is DesktopRenderResult.Svg -> docSvg = result.svg
                is DesktopRenderResult.Error -> docError = result.message
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (token == renderToken) {
                docError = "Unerwarteter Fehler: ${e.message ?: e.javaClass.simpleName}"
            }
        } finally {
            if (token == renderToken) isRendering = false
        }
    }

    /**
     * Saves [buffer] to the selected document's file via [OkfDocumentWriter], gated by
     * [dev.kuml.workspace.WorkspaceWriteGuard] and [OkfSaveGate]. On [OkfWriteResult.Written],
     * the document set is incrementally re-parsed via [applySaved] rather than a full
     * workspace re-scan (see this class's KDoc for the invariant that makes that valid).
     */
    suspend fun save(
        themeName: String,
        strings: Strings,
        watermark: Boolean = false,
    ): OkfWriteResult {
        val doc = selected ?: return OkfWriteResult.Failed(cause = IllegalStateException("No document selected"))
        val content = buffer ?: return OkfWriteResult.Failed(cause = IllegalStateException("No buffer to save"))
        // Captured BEFORE the write suspends (bugfix, review finding, CRITICAL): `select()`
        // increments `selectionToken` synchronously as its very first statement, so if a tree
        // click / wikilink / backlink navigation switches the selection while THIS save's
        // `withContext(Dispatchers.IO)` write is still in flight, `selectionToken` will have
        // moved on by the time that write returns. Everything below that would otherwise touch
        // fields now owned by the NEWLY selected document (`savedBuffer`, `findings`, and
        // `applySaved`'s `selected`/render step) is gated on `token == selectionToken` — see
        // that guard for the concrete corruption this closes (a stale `selected` reassignment
        // pointing the tree/header back at THIS document while `buffer` already holds the OTHER
        // one's text, so the next Ctrl+S would overwrite this document with the other's content).
        val token = selectionToken
        val root = workspace.root
        val knownDocuments = documents.map { it.file }

        val result =
            withContext(Dispatchers.IO) {
                OkfDocumentWriter.save(root = root, target = doc.file, content = content, knownDocuments = knownDocuments)
            }

        when (result) {
            is OkfWriteResult.Written -> {
                // `savedBuffer`/`findings` MUST be updated before `applySaved` (bugfix, review
                // finding): `applySaved` suspends twice — a `Dispatchers.IO` re-parse of the
                // just-written file, then a full `renderCurrentBlock` (script eval + ELK layout,
                // documented elsewhere as 1-3s). While either suspension is in flight, `buffer`
                // still differs from a not-yet-updated `savedBuffer`, so `isDirty` stays `true`
                // for up to seconds AFTER the bytes are already on disk. A "Verwerfen" (discard)
                // click landing in that window (e.g. the user switches to another document right
                // after Ctrl+S) would reset `buffer` to the OLD `savedBuffer`, and this
                // assignment running afterwards would then overwrite `savedBuffer` with the NEW
                // content — leaving `buffer` (old) != `savedBuffer` (new), so `isDirty` flips
                // back to `true` with stale text in the editor, and the next Ctrl+S would write
                // the old content back over the just-saved new content. Setting both fields
                // synchronously, before the first suspension point, closes that window entirely.
                //
                // Guarded by `token == selectionToken` (bugfix, review finding, CRITICAL): if the
                // selection moved on while the write above was in flight, `savedBuffer` and
                // `findings` now belong to a DIFFERENT document's in-memory state (the one
                // `select()` just loaded) — writing this document's saved content into them would
                // silently corrupt that other document's dirty-tracking and findings display.
                if (token == selectionToken) {
                    savedBuffer = content
                    findings = result.warnings
                }
                applySaved(savedFile = result.file, token = token, themeName = themeName, strings = strings, watermark = watermark)
            }
            is OkfWriteResult.Blocked -> {
                // Same guard as the `Written` branch above: a `Blocked` result still describes
                // THIS document's (unwritten) content, not whatever is selected by the time the
                // gate check returns.
                if (token == selectionToken) {
                    findings = result.blocking + result.warnings
                }
            }
            is OkfWriteResult.Rejected, is OkfWriteResult.TooLarge, is OkfWriteResult.Failed -> {
                // Surfaced by the caller (a modal dialog in MainWindow) — nothing in this
                // state needs to change, the file was never touched.
            }
        }
        return result
    }

    /**
     * Incrementally re-parses the just-saved file and swaps it into [documents]/[workspace]
     * in place, rebuilds [graphIndex] purely in-memory (no filesystem re-scan), re-points
     * [selected] at the fresh object (data-class equality would otherwise keep the tree's
     * "is this the selected row" check pinned to a stale instance), and re-renders.
     *
     * See this class's KDoc: valid ONLY because the document set itself never changes size
     * in this welle.
     *
     * Both the read and the parse run on `Dispatchers.IO` (bugfix, review finding) — this
     * function used to run [OkfDocumentParser.parseFile] on the CALLER's dispatcher, which
     * for every call site here is the Compose/Swing UI thread, freezing the UI while a large
     * document is re-read and re-parsed. The read+parse is also wrapped in [runCatching]
     * (bugfix): the write itself already succeeded by the time this runs (only called after
     * [OkfWriteResult.Written]), but the immediate re-read can still fail (e.g. the file was
     * removed or made unreadable out from under us between the write and this point) — an
     * uncaught [java.io.IOException] there used to kill the whole Compose coroutine instead
     * of degrading gracefully. On that failure, fall back to a full [WorkspaceScanner.scan]
     * (also off the UI thread, also guarded); if even that fails, the in-memory state is left
     * as-is (stale but not crashed) and only the render is retried.
     *
     * @param token The caller's [selectionToken] snapshot, taken BEFORE [save]'s write started
     *  (bugfix, review finding, CRITICAL). `documents`/`workspace`/`graphIndex` are updated
     *  unconditionally — the file on disk really did change, regardless of what is selected
     *  now — but re-pointing [selected] at [reparsed] (or the rescanned equivalent) and the
     *  closing [renderCurrentBlock] are gated on `token == selectionToken`. Without this guard,
     *  a [select] that ran concurrently with the write (e.g. a tree click right after Ctrl+S)
     *  would already have moved [selected]/[buffer] on to a DIFFERENT document, and this
     *  function would then unconditionally stomp [selected] back onto the just-saved one —
     *  leaving [selected] pointing at document A while [buffer] holds document B's text, so the
     *  next save would overwrite A on disk with B's content. [select]'s own token check (line
     *  ~152) can't catch this from the other side: it only fires when ITS OWN pending read
     *  returns, not when an unrelated in-flight [save] resolves after it.
     */
    private suspend fun applySaved(
        savedFile: File,
        token: Int,
        themeName: String,
        strings: Strings,
        watermark: Boolean,
    ) {
        val previousRelativePath = selected?.relativePath
        val reparsed =
            withContext(Dispatchers.IO) {
                runCatching { OkfDocumentParser.parseFile(root = workspace.root, file = savedFile) }
            }.getOrNull()

        if (reparsed != null) {
            val newDocs = documents.map { if (it.relativePath == reparsed.relativePath) reparsed else it }
            workspace = workspace.copy(documents = newDocs)
            documents = newDocs
            graphIndex = WorkspaceGraphIndex.build(ws = workspace)
            if (token == selectionToken) selected = reparsed
        } else {
            val rescanned =
                withContext(Dispatchers.IO) {
                    runCatching { WorkspaceScanner.scan(root = workspace.root) }
                }.getOrNull()
            if (rescanned != null) {
                workspace = rescanned
                documents = rescanned.documents.sortedBy { it.relativePath }
                graphIndex = WorkspaceGraphIndex.build(ws = rescanned)
                if (token == selectionToken) {
                    selected = documents.find { it.relativePath == previousRelativePath }
                }
            }
        }
        if (token == selectionToken) {
            renderCurrentBlock(themeName = themeName, strings = strings, watermark = watermark)
        }
    }
}
