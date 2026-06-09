package dev.kuml.web

/**
 * Standalone entry point for the kUML Web server.
 *
 * Starts the server on port 8080 (default). For custom host/port,
 * use the `kuml serve` CLI subcommand.
 */
fun main() {
    KumlWebServer.start()
}
