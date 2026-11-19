package dev.kuml.desktop.workspace

import dev.kuml.desktop.AppState
import dev.kuml.workspace.OkfWorkspace
import dev.kuml.workspace.WorkspaceMode
import java.io.File

/**
 * The single decision/materialisation path for opening a workspace, shared by the File menu,
 * "New Workspace" and the MCP tool `kuml_desktop_workspace_open`.
 *
 * Invariant: a workspace that is open in the UI is trusted. [decide] returns [Decision.NeedsTrust]
 * for every untrusted root, regardless of who asked; trust is only ever granted by
 * [grantTrustFromUi], which is called exclusively from the trust dialog's "Trust" button.
 */
internal object WorkspaceOpener {
    sealed interface Decision {
        data class Open(
            val workspace: OkfWorkspace,
            val mode: WorkspaceMode,
        ) : Decision

        data class NeedsTrust(
            val workspace: OkfWorkspace,
            val forcedMode: WorkspaceMode?,
        ) : Decision

        data class NeedsMode(
            val workspace: OkfWorkspace,
        ) : Decision
    }

    /** Trust first, then mode: the same order as the menu (trust dialog, then mode chooser). */
    fun decide(
        workspace: OkfWorkspace,
        trusted: Boolean,
        forcedMode: WorkspaceMode? = null,
    ): Decision {
        require(forcedMode != WorkspaceMode.UNKNOWN) { "forcedMode must be a concrete mode" }
        if (!trusted) return Decision.NeedsTrust(workspace = workspace, forcedMode = forcedMode)
        val mode = if (workspace.mode == WorkspaceMode.UNKNOWN) forcedMode else workspace.mode
        return if (mode == null || mode == WorkspaceMode.UNKNOWN) {
            Decision.NeedsMode(workspace)
        } else {
            Decision.Open(workspace = workspace, mode = mode)
        }
    }

    /** Builds the [OpenWorkspace]; [engineeringFiles] must be pre-scanned off the UI thread for ENGINEERING. */
    fun materialize(
        workspace: OkfWorkspace,
        mode: WorkspaceMode,
        engineeringFiles: List<File> = emptyList(),
    ): OpenWorkspace {
        require(mode != WorkspaceMode.UNKNOWN) { "a concrete mode is required" }
        return when (mode) {
            WorkspaceMode.ENGINEERING -> OpenWorkspace.Engineering(root = workspace.root, scriptFiles = engineeringFiles)
            else -> OpenWorkspace.Knowledge(WorkspaceState(initialWorkspace = workspace))
        }
    }

    /** The ONLY place trust is granted; called exclusively from the trust dialog (UI), never from the mcp package. */
    fun grantTrustFromUi(
        state: AppState,
        root: File,
    ) {
        val canonical = WorkspaceTrust.canonicalPath(root)
        if (canonical !in state.trustedWorkspaces) state.trustedWorkspaces.add(canonical)
    }
}
