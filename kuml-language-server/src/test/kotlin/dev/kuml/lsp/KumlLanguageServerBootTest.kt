package dev.kuml.lsp

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient
import java.io.Closeable
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * End-to-end stdio boot test: wires an in-memory [LanguageClient] to a real
 * [KumlLanguageServer] over piped streams (mirrors how [Main] wires stdin/stdout
 * in production) and drives the `initialize` handshake through the JSON-RPC
 * wire, not by calling [KumlLanguageServer.initialize] directly.
 */
class KumlLanguageServerBootTest :
    StringSpec({

        "initialize advertises full sync + completion with '.'/' ' triggers and serverInfo name" {
            // clientOut -> serverIn ; serverOut -> clientIn
            val serverIn = PipedInputStream()
            val clientOut = PipedOutputStream(serverIn)
            val clientIn = PipedInputStream()
            val serverOut = PipedOutputStream(clientIn)

            val server = KumlLanguageServer()
            val serverLauncher = LSPLauncher.createServerLauncher(server, serverIn, serverOut)
            server.connect(serverLauncher.remoteProxy)

            val client = RecordingLanguageClient()
            val clientLauncher = LSPLauncher.createClientLauncher(client, clientIn, clientOut)
            val remote = clientLauncher.remoteProxy

            val sL = serverLauncher.startListening()
            val cL = clientLauncher.startListening()
            try {
                val result = remote.initialize(InitializeParams()).get(10, TimeUnit.SECONDS)
                val caps = result.capabilities

                // textDocumentSync: setTextDocumentSync(kind) stores it as the Left of the Either.
                caps.textDocumentSync.left shouldBe TextDocumentSyncKind.Full
                caps.completionProvider.shouldNotBeNull()
                caps.completionProvider.triggerCharacters shouldContainExactly listOf(".", " ")
                result.serverInfo?.name shouldBe "kuml-lsp"

                remote.initialized(InitializedParams())
                remote.shutdown().get(5, TimeUnit.SECONDS)
                remote.exit()
            } finally {
                listOf<Closeable>(clientOut, serverOut, clientIn, serverIn).forEach { runCatching { it.close() } }
                sL.cancel(true)
                cL.cancel(true)
            }
        }
    })

/** Minimal no-op [LanguageClient] used only to complete the JSON-RPC handshake. */
private class RecordingLanguageClient : LanguageClient {
    val diagnostics: MutableList<PublishDiagnosticsParams> = CopyOnWriteArrayList()

    override fun telemetryEvent(any: Any?) { /* no-op */ }

    override fun publishDiagnostics(params: PublishDiagnosticsParams) {
        diagnostics += params
    }

    override fun showMessage(params: MessageParams?) { /* no-op */ }

    override fun showMessageRequest(params: ShowMessageRequestParams?): CompletableFuture<MessageActionItem> =
        CompletableFuture.completedFuture(null)

    override fun logMessage(params: MessageParams?) { /* no-op */ }
}
