package dev.kuml.lsp

/**
 * Thread-safe holder for client-pushed settings (`workspace/didChangeConfiguration`).
 *
 * [KumlWorkspaceService] writes it after parsing the client's settings payload;
 * [KumlTextDocumentService] reads it on every validation run. Plain `@Volatile`
 * fields are sufficient: each field is written as a whole (no compound updates),
 * and readers only ever need the latest value, never a consistent snapshot across
 * fields.
 */
class ServerConfig {
    /** `kuml.cliPath` — explicit override for the `kuml` CLI location. `null`/blank → auto-detect. */
    @Volatile
    var cliPath: String? = null

    /** `kuml.diagnostics.enable` — master switch for the diagnostics pipeline. */
    @Volatile
    var diagnosticsEnabled: Boolean = true

    /** `kuml.diagnostics.debounceMs` — validation debounce delay in milliseconds. */
    @Volatile
    var debounceMs: Long = 300L
}
