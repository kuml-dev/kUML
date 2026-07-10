package dev.kuml.lsp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.services.LanguageClient
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Direct-call tests (no JSON-RPC wire) exercising [KumlTextDocumentService]'s
 * document bookkeeping and its push-diagnostics side effects, using an
 * injected fake CLI stub ([FakeCli]) so the diagnostics pipeline is exercised
 * end-to-end without depending on a real kUML CLI being on `PATH`.
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
                // didClose cancels the debounced didOpen validation (default 300ms
                // delay, well after this synchronous call) before it can fire, then
                // immediately publishes an empty diagnostics set of its own.
                service.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri)))

                service.documentText(uri).shouldBeNull()
                val publishedForUri = client.diagnostics.filter { it.uri == uri }
                publishedForUri.size shouldBe 1
                publishedForUri[0].diagnostics shouldBe emptyList()
            } finally {
                service.close()
            }
        }

        test("unresolved-reference fixture: publishes one diagnostic at the mapped range") {
            val stub = FakeCli.write(listOf("ERROR\t3\t5\t3\t9\tUnresolved reference: bar")) ?: return@test
            val server = KumlLanguageServer()
            val client = RecordingClient()
            server.connect(client)
            server.config.cliPath = stub.absolutePath
            val service = server.getTextDocumentService() as KumlTextDocumentService
            try {
                val uri = "file:///unresolved.kuml.kts"
                val text = "aaaaaaaaaa\nbbbbbbbbbb\ncccccccccc\ndddddddddd"
                client.latchFor(uri)
                service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "kuml", 1, text)))
                client.awaitFor(uri, 10, TimeUnit.SECONDS) shouldBe true

                val published = client.diagnostics.last { it.uri == uri }
                published.diagnostics.size shouldBe 1
                val d = published.diagnostics[0]
                d.severity shouldBe DiagnosticSeverity.Error
                d.range.start.line shouldBe 2
                d.range.start.character shouldBe 4
            } finally {
                service.close()
                stub.parentFile.deleteRecursively()
            }
        }

        test("valid fixture: stub emits nothing, publishes an empty diagnostic list") {
            val stub = FakeCli.write(emptyList()) ?: return@test
            val server = KumlLanguageServer()
            val client = RecordingClient()
            server.connect(client)
            server.config.cliPath = stub.absolutePath
            val service = server.getTextDocumentService() as KumlTextDocumentService
            try {
                val uri = "file:///valid.kuml.kts"
                client.latchFor(uri)
                service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "kuml", 1, "class Foo")))
                client.awaitFor(uri, 10, TimeUnit.SECONDS) shouldBe true

                client.diagnostics
                    .last { it.uri == uri }
                    .diagnostics
                    .shouldBeEmpty()
            } finally {
                service.close()
                stub.parentFile.deleteRecursively()
            }
        }

        test("missing CLI: publishes empty diagnostics and warns exactly once") {
            val server = KumlLanguageServer()
            val client = RecordingClient()
            server.connect(client)
            server.config.cliPath = "/no/such/kuml"
            // Constructed directly (not via server.getTextDocumentService()) with a
            // resolver that always reports "not found": a bogus cliPath alone isn't
            // enough to prove this deterministically, since KumlCliLocator.resolve
            // falls back to PATH/common locations, and this repo's dev machines
            // have a real `kuml` installed via Homebrew (see CLAUDE.md).
            val service = KumlTextDocumentService(server, server.config, cliResolver = { _, _ -> null })
            try {
                val uri = "file:///missing-cli.kuml.kts"
                client.latchFor(uri)
                service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "kuml", 1, "class Foo")))
                client.awaitFor(uri, 10, TimeUnit.SECONDS) shouldBe true
                client.awaitWarning(10, TimeUnit.SECONDS) shouldBe true

                client.diagnostics
                    .last { it.uri == uri }
                    .diagnostics
                    .shouldBeEmpty()
                client.warnings.size shouldBe 1
                client.warnings[0].type shouldBe MessageType.Warning
            } finally {
                service.close()
            }
        }

        test("diagnostics disabled: publishes empty even though the stub would emit an error") {
            val stub = FakeCli.write(listOf("ERROR\t1\t1\t1\t1\tshould never surface")) ?: return@test
            val server = KumlLanguageServer()
            val client = RecordingClient()
            server.connect(client)
            server.config.cliPath = stub.absolutePath
            server.config.diagnosticsEnabled = false
            val service = server.getTextDocumentService() as KumlTextDocumentService
            try {
                val uri = "file:///disabled.kuml.kts"
                client.latchFor(uri)
                service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "kuml", 1, "class Foo")))
                client.awaitFor(uri, 10, TimeUnit.SECONDS) shouldBe true

                client.diagnostics
                    .last { it.uri == uri }
                    .diagnostics
                    .shouldBeEmpty()
            } finally {
                service.close()
                stub.parentFile.deleteRecursively()
            }
        }

        test("didSave triggers a fresh (debounced) revalidation") {
            val stub = FakeCli.write(listOf("ERROR\t1\t1\t1\t1\tsave triggered")) ?: return@test
            val server = KumlLanguageServer()
            val client = RecordingClient()
            server.connect(client)
            server.config.cliPath = stub.absolutePath
            val service = server.getTextDocumentService() as KumlTextDocumentService
            try {
                val uri = "file:///save.kuml.kts"
                client.latchFor(uri)
                service.didChange(
                    DidChangeTextDocumentParams(
                        VersionedTextDocumentIdentifier(uri, 2),
                        mutableListOf(TextDocumentContentChangeEvent("class Foo")),
                    ),
                )
                // didChange races didOpen-less bookkeeping; store text directly is
                // unnecessary since didChange already updates the store.
                client.awaitFor(uri, 10, TimeUnit.SECONDS) shouldBe true
                val countAfterChange = client.diagnostics.count { it.uri == uri }

                client.latchFor(uri)
                service.didSave(DidSaveTextDocumentParams(TextDocumentIdentifier(uri)))
                client.awaitFor(uri, 10, TimeUnit.SECONDS) shouldBe true
                val countAfterSave = client.diagnostics.count { it.uri == uri }

                (countAfterSave > countAfterChange) shouldBe true
            } finally {
                service.close()
                stub.parentFile.deleteRecursively()
            }
        }
    })

private class RecordingClient : LanguageClient {
    val diagnostics: MutableList<PublishDiagnosticsParams> = CopyOnWriteArrayList()
    val warnings: MutableList<MessageParams> = CopyOnWriteArrayList()
    private val latches = mutableMapOf<String, CountDownLatch>()

    // publishDiagnostics() and showMessage() are both invoked from the same
    // runAndPublish() call on the debouncer's scheduler thread, but in that
    // order — awaiting only the diagnostics latch races the warning append on
    // the test thread. A dedicated latch lets tests that care about warnings
    // synchronize on the actual event instead of sleeping.
    private val warningLatch = CountDownLatch(1)

    @Synchronized
    fun latchFor(uri: String) {
        latches[uri] = CountDownLatch(1)
    }

    fun awaitFor(
        uri: String,
        timeout: Long,
        unit: TimeUnit,
    ): Boolean = latches[uri]?.await(timeout, unit) ?: false

    fun awaitWarning(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean = warningLatch.await(timeout, unit)

    override fun telemetryEvent(any: Any?) { /* no-op */ }

    @Synchronized
    override fun publishDiagnostics(params: PublishDiagnosticsParams) {
        diagnostics += params
        latches[params.uri]?.countDown()
    }

    override fun showMessage(params: MessageParams?) {
        params?.let { warnings += it }
        warningLatch.countDown()
    }

    override fun showMessageRequest(params: ShowMessageRequestParams?): CompletableFuture<MessageActionItem> =
        CompletableFuture.completedFuture(null)

    override fun logMessage(params: MessageParams?) { /* no-op */ }
}
