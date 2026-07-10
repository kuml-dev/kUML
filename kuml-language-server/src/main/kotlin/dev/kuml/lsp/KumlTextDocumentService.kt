package dev.kuml.lsp

import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.TextDocumentService
import java.util.concurrent.CompletableFuture

class KumlTextDocumentService(
    private val server: KumlLanguageServer,
) : TextDocumentService,
    AutoCloseable {
    private val documents = DocumentStore()
    private val debouncer = Debouncer(DEBOUNCE_MS)

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
        server.client?.publishDiagnostics(PublishDiagnosticsParams(uri, emptyList()))
    }

    override fun didSave(params: DidSaveTextDocumentParams) { /* no-op in Wave 2 */ }

    override fun completion(params: CompletionParams): CompletableFuture<Either<MutableList<CompletionItem>, CompletionList>> =
        // Wave 3 wires KumlCompletionItems; Wave 2 advertises the capability but
        // returns an empty, non-incomplete list.
        CompletableFuture.completedFuture(Either.forLeft(mutableListOf()))

    /**
     * Wave 2 placeholder: debounced, and simply publishes an empty diagnostic set
     * (clears). This exercises the push channel end-to-end without running any
     * validation; Wave 3 replaces the body with DiagnosticsRunner + RangeMapping.
     */
    private fun scheduleValidation(uri: String) {
        debouncer.submit(uri) {
            server.client?.publishDiagnostics(PublishDiagnosticsParams(uri, emptyList()))
        }
    }

    // Exposed for tests / future waves.
    internal fun documentText(uri: String): String? = documents.text(uri)

    override fun close() = debouncer.close()

    private companion object {
        const val DEBOUNCE_MS = 300L
    }
}
