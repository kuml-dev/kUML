package dev.kuml.lsp

import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.services.WorkspaceService

/** Wave 2: accept and ignore workspace notifications; wiring lands in later waves. */
class KumlWorkspaceService : WorkspaceService {
    override fun didChangeConfiguration(params: DidChangeConfigurationParams) { /* no-op */ }

    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) { /* no-op */ }
}
