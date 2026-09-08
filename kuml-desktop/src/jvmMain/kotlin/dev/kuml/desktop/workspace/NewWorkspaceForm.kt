package dev.kuml.desktop.workspace

import dev.kuml.workspace.scaffold.WorkspaceInitSpec
import java.io.File

/** V3.x (FT-Desktop-New-Workspace) — the two scaffold modes offered by the "New Workspace…" dialog. */
enum class NewWorkspaceMode { KNOWLEDGE, ENGINEERING }

/**
 * V3.x (FT-Desktop-New-Workspace) — validation states the "New Workspace…" dialog can be in.
 * Mirrors exactly the failure cases [WorkspaceInitSpec]/`WorkspaceScaffolder.scaffold` already
 * know (empty name, non-empty target directory), plus a runtime write failure surfaced after a
 * `create()` attempt — the dialog invents nothing the CLI equivalent (`kuml workspace init`)
 * doesn't already guard against.
 */
enum class NewWorkspaceError { NAME_EMPTY, TARGET_NOT_EMPTY, WRITE_FAILED }

/**
 * Pure, Compose-free form state for the "New Workspace…" dialog (V3.x,
 * FT-Desktop-New-Workspace — design-team decision 2026-09-08). Kept free of any Compose
 * import specifically so it is unit-testable without a Compose runtime, following the same
 * "pure-function-extraction" pattern already used in this module for [renderDelayFor]-style
 * helpers (see `MainWindow.kt`).
 *
 * [parentDir] is the folder the user picks (or the app's remembered `lastDir`) — [targetDir]
 * (parent + slug) is what actually gets created.
 */
data class NewWorkspaceFormState(
    val name: String = "",
    val nameTouched: Boolean = false,
    val mode: NewWorkspaceMode = NewWorkspaceMode.KNOWLEDGE,
    val parentDir: File,
    val runtimeError: String? = null,
) {
    val slug: String get() = WorkspaceInitSpec.slugify(name)
    val targetDir: File get() = File(parentDir, slug)

    val error: NewWorkspaceError?
        get() =
            when {
                name.isBlank() -> NewWorkspaceError.NAME_EMPTY
                targetDir.isDirectory && targetDir.list()?.isNotEmpty() == true -> NewWorkspaceError.TARGET_NOT_EMPTY
                runtimeError != null -> NewWorkspaceError.WRITE_FAILED
                else -> null
            }

    /**
     * Whether the "Erstellen" button should be enabled. Deliberately NOT `error == null`:
     * [NewWorkspaceError.WRITE_FAILED] is a transient, retryable condition (a passing I/O
     * glitch, a virus-scanner lock, a momentarily full disk) rather than a validation problem
     * with the form's current values — unlike [NewWorkspaceError.NAME_EMPTY] and
     * [NewWorkspaceError.TARGET_NOT_EMPTY], it must never leave the button permanently
     * disabled. Without this, a failed attempt was a dead end: the button stayed disabled and
     * neither the mode radio buttons nor another click on "Erstellen" itself could clear
     * [runtimeError] — only editing the name or picking a new target directory could, and both
     * of those also change what would be created. [dev.kuml.desktop.workspace.NewWorkspaceDialog.create]
     * clears [runtimeError] at the start of every attempt, so a WRITE_FAILED state never
     * survives a retry either way — this flag just lets the user actually trigger that retry.
     */
    val canCreate: Boolean get() = error == null || error == NewWorkspaceError.WRITE_FAILED

    fun toSpec(): WorkspaceInitSpec =
        WorkspaceInitSpec.from(name = name, mode = if (mode == NewWorkspaceMode.KNOWLEDGE) "knowledge" else "engineering")
}
