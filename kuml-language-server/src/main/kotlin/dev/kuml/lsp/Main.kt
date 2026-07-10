package dev.kuml.lsp

import org.eclipse.lsp4j.launch.LSPLauncher

/**
 * stdio entry point. STDOUT is reserved for the LSP JSON-RPC channel — all
 * diagnostics/logging MUST go to STDERR, never println.
 */
fun main() {
    val server = KumlLanguageServer()
    val launcher = LSPLauncher.createServerLauncher(server, System.`in`, System.out)
    server.connect(launcher.remoteProxy)
    launcher.startListening().get()
}
