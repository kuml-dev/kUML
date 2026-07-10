package dev.kuml.lsp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.services.LanguageClient
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Direct-call tests (no JSON-RPC wire) exercising [KumlTextDocumentService]'s
 * document bookkeeping and its push-diagnostics side effects.
 */
class KumlTextDocumentServiceTest :
    FunSpec({

        test("didOpen stores the document text, retrievable via documentText") {
            val server = KumlLanguageServer()
            val client = RecordingClient()
            server.connect(client)
            val service = server.getTextDocumentService() as KumlTextDocumentService
            try {
                val uri = "file:///open.kuml.kts"
                service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "kuml", 1, "class Foo")))
                service.documentText(uri) shouldBe "class Foo"
            } finally {
                service.close()
            }
        }

        test("didClose publishes an empty diagnostics set and forgets the document") {
            val server = KumlLanguageServer()
            val client = RecordingClient()
            server.connect(client)
            val service = server.getTextDocumentService() as KumlTextDocumentService
            try {
                val uri = "file:///close.kuml.kts"
                service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "kuml", 1, "class Foo")))
                service.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri)))

                service.documentText(uri).shouldBeNull()
                // didClose cancels the debounced didOpen validation and immediately
                // publishes an empty diagnostics set of its own for the closed uri.
                val publishedForUri = client.diagnostics.filter { it.uri == uri }
                publishedForUri.size shouldBe 1
                publishedForUri[0].diagnostics shouldBe emptyList()
            } finally {
                service.close()
            }
        }

        test("didOpen eventually publishes debounced (empty) diagnostics for the opened uri") {
            val server = KumlLanguageServer()
            val client = RecordingClient()
            server.connect(client)
            val service = server.getTextDocumentService() as KumlTextDocumentService
            try {
                val uri = "file:///validate.kuml.kts"
                client.latchFor(uri)
                service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "kuml", 1, "class Foo")))
                client.awaitFor(uri, 5, TimeUnit.SECONDS) shouldBe true
            } finally {
                service.close()
            }
        }
    })

private class RecordingClient : LanguageClient {
    val diagnostics: MutableList<PublishDiagnosticsParams> = CopyOnWriteArrayList()
    private val latches = mutableMapOf<String, CountDownLatch>()

    @Synchronized
    fun latchFor(uri: String) {
        latches[uri] = CountDownLatch(1)
    }

    fun awaitFor(
        uri: String,
        timeout: Long,
        unit: TimeUnit,
    ): Boolean = latches[uri]?.await(timeout, unit) ?: false

    override fun telemetryEvent(any: Any?) { /* no-op */ }

    @Synchronized
    override fun publishDiagnostics(params: PublishDiagnosticsParams) {
        diagnostics += params
        latches[params.uri]?.countDown()
    }

    override fun showMessage(params: MessageParams?) { /* no-op */ }

    override fun showMessageRequest(params: ShowMessageRequestParams?): CompletableFuture<MessageActionItem> =
        CompletableFuture.completedFuture(null)

    override fun logMessage(params: MessageParams?) { /* no-op */ }
}
