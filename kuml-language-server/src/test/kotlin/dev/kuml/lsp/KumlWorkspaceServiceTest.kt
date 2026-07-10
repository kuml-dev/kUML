package dev.kuml.lsp

import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.services.LanguageClient
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class KumlWorkspaceServiceTest :
    FunSpec({

        test("valid settings update cliPath/enable/debounceMs and re-validate open docs") {
            val stub = FakeCli.write(listOf("ERROR\t1\t1\t1\t1\tfrom new cli")) ?: return@test
            val server = KumlLanguageServer()
            val client = WorkspaceRecordingClient()
            server.connect(client)
            val docService = server.getTextDocumentService() as KumlTextDocumentService
            val workspaceService = server.getWorkspaceService() as KumlWorkspaceService
            try {
                // Open a document first, with diagnostics disabled so didOpen itself
                // never resolves/runs a CLI.
                server.config.diagnosticsEnabled = false
                val uri = "file:///wsconfig.kuml.kts"
                docService.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "kuml", 1, "class Foo")))

                val settingsJson =
                    """
                    {"kuml":{"cliPath":"${stub.absolutePath.replace("\\", "\\\\")}","diagnostics":{"enable":true,"debounceMs":50}}}
                    """.trimIndent()
                val settings = JsonParser.parseString(settingsJson)

                client.latchFor(uri)
                workspaceService.didChangeConfiguration(DidChangeConfigurationParams(settings))

                server.config.cliPath shouldBe stub.absolutePath
                server.config.diagnosticsEnabled shouldBe true
                server.config.debounceMs shouldBe 50L

                // onConfigChanged() re-validates open docs -> a publish should follow.
                client.awaitFor(uri, 10, TimeUnit.SECONDS) shouldBe true
                client.diagnostics
                    .last { it.uri == uri }
                    .diagnostics.size shouldBe 1
            } finally {
                docService.close()
                stub.parentFile.deleteRecursively()
            }
        }

        test("malformed settings (enable as a string) do not throw and retain prior config") {
            val server = KumlLanguageServer()
            val client = WorkspaceRecordingClient()
            server.connect(client)
            val workspaceService = server.getWorkspaceService() as KumlWorkspaceService
            val docService = server.getTextDocumentService() as KumlTextDocumentService
            try {
                server.config.diagnosticsEnabled = true
                server.config.debounceMs = 300L

                val settings =
                    JsonParser.parseString(
                        """{"kuml":{"diagnostics":{"enable":"not-a-boolean"}}}""",
                    )

                workspaceService.didChangeConfiguration(DidChangeConfigurationParams(settings))

                // Malformed "enable" is ignored; prior value retained.
                server.config.diagnosticsEnabled shouldBe true
                server.config.debounceMs shouldBe 300L
            } finally {
                docService.close()
            }
        }

        test("completely malformed settings object never throws") {
            val server = KumlLanguageServer()
            val client = WorkspaceRecordingClient()
            server.connect(client)
            val workspaceService = server.getWorkspaceService() as KumlWorkspaceService
            val docService = server.getTextDocumentService() as KumlTextDocumentService
            try {
                val settings = JsonParser.parseString("""[1, 2, 3]""")
                workspaceService.didChangeConfiguration(DidChangeConfigurationParams(settings))
                server.config.cliPath.shouldBeNull()
            } finally {
                docService.close()
            }
        }
    })

private class WorkspaceRecordingClient : LanguageClient {
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
