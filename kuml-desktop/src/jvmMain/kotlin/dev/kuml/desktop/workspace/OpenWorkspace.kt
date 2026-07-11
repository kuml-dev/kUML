package dev.kuml.desktop.workspace

import java.io.File

/**
 * The currently open workspace in [dev.kuml.desktop.AppState.openWorkspace] (V3.6.4).
 * `null` (absence of this type) means the app is in its normal single-file mode.
 */
sealed class OpenWorkspace {
    /** A Knowledge-mode workspace, rendered by [KnowledgeWorkspaceScreen]. */
    data class Knowledge(
        val state: WorkspaceState,
    ) : OpenWorkspace()

    /**
     * An Engineering-mode workspace, rendered by [EngineeringWorkspaceScreen].
     * Reuses the existing single-file editor/preview via [dev.kuml.desktop.AppState] —
     * only the file tree is workspace-specific.
     */
    data class Engineering(
        val root: File,
        val scriptFiles: List<File>,
    ) : OpenWorkspace()
}
