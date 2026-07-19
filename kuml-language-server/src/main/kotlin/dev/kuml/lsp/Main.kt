package dev.kuml.lsp

import org.eclipse.lsp4j.launch.LSPLauncher
import kotlin.system.exitProcess

/**
 * stdio entry point. STDOUT is reserved for the LSP JSON-RPC channel — all
 * diagnostics/logging MUST go to STDERR, never println.
 */
fun main() {
    val server = KumlLanguageServer()
    val launcher = LSPLauncher.createServerLauncher(server, System.`in`, System.out)
    server.connect(launcher.remoteProxy)
    launcher.startListening().get()
    // lsp4j's RemoteEndpoint dispatch machinery starts non-daemon threads
    // that keep the JVM alive even after the input stream reaches EOF (the
    // signal a client sends when it disconnects, e.g. VS Code closing the
    // language client). Without an explicit exit here, `kuml-lsp` never
    // terminates on its own once the client hangs up — confirmed empirically
    // (2026-07-19): stdin EOF alone leaves the process running indefinitely,
    // unlike kuml-mcp's SDK, which returns from its own listening call
    // cleanly. Force a clean shutdown once listening has ended, so the
    // process can't outlive the editor that spawned it.
    exitProcess(0)
}
