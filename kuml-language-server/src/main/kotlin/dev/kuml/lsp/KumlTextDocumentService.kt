package dev.kuml.lsp

import dev.kuml.langsupport.cli.KumlCliLocator
import dev.kuml.langsupport.diagnostics.KumlDiagnostic
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.TextDocumentService
import java.io.File
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

class KumlTextDocumentService(
    private val server: KumlLanguageServer,
    private val config: ServerConfig,
    // Test seam only: production always uses the default (KumlCliLocator.resolve).
    // Needed because the locator's PATH/common-location fallback means a bogus
    // configuredPath alone cannot deterministically simulate "CLI not found" on a
    // machine that actually has a real `kuml` installed (as this repo's dev
    // machines do, see CLAUDE.md's Homebrew-kuml note).
    private val cliResolver: (File?, String?) -> File? = KumlCliLocator::resolve,
) : TextDocumentService,
    AutoCloseable {
    private val documents = DocumentStore()
    private val debouncer = Debouncer(config.debounceMs)

    // Memoized CLI resolution — KumlCliLocator.resolve shells which/where and
    // walks up the filesystem, which is too expensive to redo per keystroke.
    // Invalidated whenever the resolution key (configured path + hint dir)
    // changes, and explicitly by onConfigChanged().
    @Volatile
    private var cachedCli: File? = null

    @Volatile
    private var cliResolvedFor: String? = null
    private val cliMissingWarned = AtomicBoolean(false)

    override fun didOpen(params: DidOpenTextDocumentParams) {
        val doc = params.textDocument
        documents.update(doc.uri, doc.text)
        scheduleValidation(doc.uri)
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        // Full sync (advertised in capabilities): the single change event carries
        // the entire new document text.
        val text = params.contentChanges.lastOrNull()?.text ?: return
        documents.update(params.textDocument.uri, text)
        scheduleValidation(params.textDocument.uri)
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        val uri = params.textDocument.uri
        debouncer.cancel(uri)
        documents.remove(uri)
        // Clear any diagnostics the editor is showing for a now-closed file.
        publish(uri, emptyList())
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
        // Save is a natural revalidation trigger; reuses the same debounced path
        // as didChange (a save right after a burst of edits still coalesces).
        scheduleValidation(params.textDocument.uri)
    }

    override fun completion(params: CompletionParams): CompletableFuture<Either<MutableList<CompletionItem>, CompletionList>> =
        // Wave 4 wires KumlCompletionItems; this wave advertises the capability
        // but returns an empty, non-incomplete list.
        CompletableFuture.completedFuture(Either.forLeft(mutableListOf()))

    /** Debounced entry point: coalesces rapid didChange/didSave bursts into one CLI run. */
    private fun scheduleValidation(uri: String) {
        debouncer.submit(uri) { runAndPublish(uri) }
    }

    private fun runAndPublish(uri: String) {
        if (!config.diagnosticsEnabled) {
            publish(uri, emptyList())
            return
        }
        val text = documents.text(uri) ?: return // doc closed mid-flight

        val cli = resolveCli(uri)
        if (cli == null) {
            publish(uri, emptyList())
            if (cliMissingWarned.compareAndSet(false, true)) {
                server.client?.showMessage(
                    MessageParams(
                        MessageType.Warning,
                        "kUML CLI not found — diagnostics are disabled. Set kuml.cliPath or install the kuml CLI.",
                    ),
                )
            }
            return
        }

        val diagnostics = DiagnosticsRunner.run(text, cli, DIAGNOSTICS_TIMEOUT_MS)
        publish(uri, diagnostics.map { toLspDiagnostic(it, text) })
    }

    private fun resolveCli(uri: String): File? {
        val hintDir = uri.toFileOrNull()?.parentFile
        val key = "${config.cliPath}|${hintDir?.absolutePath}"
        if (cliResolvedFor == key) return cachedCli

        val resolved = cliResolver(hintDir, config.cliPath)
        cachedCli = resolved
        cliResolvedFor = key
        return resolved
    }

    private fun toLspDiagnostic(
        d: KumlDiagnostic,
        text: String,
    ): Diagnostic =
        Diagnostic(RangeMapping.toLspRange(d, text), d.message).apply {
            source = "kuml"
            severity = mapSeverity(d.severity)
        }

    private fun mapSeverity(severity: KumlDiagnostic.Severity): DiagnosticSeverity =
        when (severity) {
            KumlDiagnostic.Severity.ERROR -> DiagnosticSeverity.Error
            KumlDiagnostic.Severity.WARNING -> DiagnosticSeverity.Warning
            KumlDiagnostic.Severity.INFO -> DiagnosticSeverity.Information
        }

    private fun publish(
        uri: String,
        diagnostics: List<Diagnostic>,
    ) {
        server.client?.publishDiagnostics(PublishDiagnosticsParams(uri, diagnostics))
    }

    /**
     * Called by [KumlWorkspaceService] after a `workspace/didChangeConfiguration`
     * notification updates [config]: re-syncs the debounce delay, invalidates the
     * memoized CLI resolution (a cliPath fix or newly-installed CLI must be picked
     * up immediately) and re-validates all currently open documents so an
     * enable/disable toggle reflects without waiting for the next edit.
     */
    internal fun onConfigChanged() {
        debouncer.delayMs = config.debounceMs
        cachedCli = null
        cliResolvedFor = null
        cliMissingWarned.set(false)
        documents.uris().forEach { scheduleValidation(it) }
    }

    // Exposed for tests / future waves.
    internal fun documentText(uri: String): String? = documents.text(uri)

    override fun close() = debouncer.close()

    private fun String.toFileOrNull(): File? = runCatching { File(URI(this)) }.getOrNull()?.takeIf { it.isAbsolute }

    private companion object {
        const val DIAGNOSTICS_TIMEOUT_MS = 30_000L
    }
}
