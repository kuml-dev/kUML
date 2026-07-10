package dev.kuml.lsp

import org.eclipse.lsp4j.CompletionOptions
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.ServerInfo
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.util.concurrent.CompletableFuture

class KumlLanguageServer :
    LanguageServer,
    LanguageClientAware {
    @Volatile
    var client: LanguageClient? = null
        private set

    private val textDocumentService = KumlTextDocumentService(this)
    private val workspaceService = KumlWorkspaceService()

    override fun connect(client: LanguageClient) {
        this.client = client
    }

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        val capabilities =
            ServerCapabilities().apply {
                // Full sync: didChange delivers the entire document text.
                setTextDocumentSync(TextDocumentSyncKind.Full)
                // Completion advertised now; handler is a Wave 2 stub.
                completionProvider = CompletionOptions(false, listOf(".", " "))
                // NOTE: push diagnostics need no capability entry (no diagnosticProvider).
            }
        val info = ServerInfo("kuml-lsp", serverVersion())
        return CompletableFuture.completedFuture(InitializeResult(capabilities, info))
    }

    override fun shutdown(): CompletableFuture<Any> {
        textDocumentService.close()
        return CompletableFuture.completedFuture(null)
    }

    override fun exit() { /* launcher loop terminates when streams close */ }

    override fun getTextDocumentService(): TextDocumentService = textDocumentService

    override fun getWorkspaceService(): WorkspaceService = workspaceService

    private fun serverVersion(): String = javaClass.`package`?.implementationVersion ?: "dev"
}
