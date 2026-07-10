package dev.kuml.lsp

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.services.WorkspaceService

/**
 * Applies client-pushed settings (`workspace/didChangeConfiguration`) to the
 * shared [ServerConfig] and re-triggers validation of open documents so an
 * enable/disable toggle or a `cliPath` fix takes effect immediately, without
 * waiting for the next edit.
 *
 * `didChangeWatchedFiles` remains a no-op — this wave has no file-watch use case.
 */
class KumlWorkspaceService(
    private val config: ServerConfig,
    private val docService: KumlTextDocumentService,
) : WorkspaceService {
    override fun didChangeConfiguration(params: DidChangeConfigurationParams) {
        try {
            val root = params.settings as? JsonElement ?: return
            // Clients push either the `kuml` sub-object directly or a root object
            // that contains a `kuml` key — accept both shapes defensively.
            val kuml =
                (root as? JsonObject)
                    ?.let { if (it.has("kuml")) it.getAsJsonObject("kuml") else it }
                    ?: return

            kuml.get("cliPath")?.asStringOrNull()?.let { config.cliPath = it.ifBlank { null } }

            val diagnostics = kuml.get("diagnostics") as? JsonObject
            diagnostics?.get("enable")?.asBooleanOrNull()?.let { config.diagnosticsEnabled = it }
            diagnostics
                ?.get("debounceMs")
                ?.asLongOrNull()
                ?.takeIf { it >= 0 }
                ?.let { config.debounceMs = it }

            docService.onConfigChanged()
        } catch (t: Throwable) {
            // STDOUT is the JSON-RPC channel — diagnostics about malformed
            // settings must never land there. Keep the prior config.
            System.err.println("kuml-lsp: ignoring malformed didChangeConfiguration payload: ${t.message}")
        }
    }

    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) { /* no-op */ }

    private fun JsonElement.asStringOrNull(): String? = if (isJsonPrimitive && asJsonPrimitive.isString) asString else null

    private fun JsonElement.asBooleanOrNull(): Boolean? = if (isJsonPrimitive && asJsonPrimitive.isBoolean) asBoolean else null

    private fun JsonElement.asLongOrNull(): Long? = if (isJsonPrimitive && asJsonPrimitive.isNumber) asLong else null
}
