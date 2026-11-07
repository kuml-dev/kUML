package dev.kuml.desktop.workspace

import dev.kuml.cli.workspace.WorkspaceLatexRun
import dev.kuml.workspace.WorkspaceScanner
import java.io.File

/** What is wrong with the output folder the user typed; the dialog explains it and disables the export. */
internal enum class LatexTargetProblem { EMPTY_OR_INVALID, IS_WORKSPACE_ROOT }

/** Form of the "Export Workspace > As LaTeX…" dialog. */
internal data class WorkspaceLatexFormState(
    val targetPath: String,
    val strict: Boolean = false,
    val themeName: String,
) {
    /** `null` when the target can be used. Pure string/path logic; no file is touched except for canonicalising. */
    fun problem(root: File): LatexTargetProblem? =
        when {
            targetPath.isBlank() || targetPath.any { Character.isISOControl(it) } -> LatexTargetProblem.EMPTY_OR_INVALID
            // Excluding the root from the scan would leave nothing to export.
            resolveLatexTarget(
                path = targetPath,
                root = root,
            ) == WorkspaceScanner.canonicalRoot(root) -> LatexTargetProblem.IS_WORKSPACE_ROOT
            else -> null
        }

    fun canSubmit(root: File): Boolean = problem(root) == null

    companion object {
        fun initial(
            root: File,
            themeName: String,
        ): WorkspaceLatexFormState = WorkspaceLatexFormState(targetPath = WorkspaceLatexRun.defaultTarget(root).path, themeName = themeName)
    }
}

/**
 * The output directory exactly as typed: absolute and normalised, a relative path taken relative to [root]. This is
 * what is written to; the export's guard then refuses a symbolic link in it. Nothing is resolved or created.
 */
internal fun typedLatexTarget(
    path: String,
    root: File,
): File = File(path).let { if (it.isAbsolute) it else File(root, path) }.absoluteFile.normalize()

/**
 * The canonical location of [path], used only to tell whether the target lies inside the workspace (so an export
 * never reads its own previous output): a relative path is taken relative to the workspace [root], and the
 * deepest existing ancestor is canonicalised, so that a target below the workspace is recognised as such even when the
 * path was typed through a symbolic link (on macOS `/var` is `/private/var`). Nothing is created.
 */
internal fun resolveLatexTarget(
    path: String,
    root: File,
): File {
    val typed = typedLatexTarget(path = path, root = root)
    var existing: File? = typed
    val rest = ArrayDeque<String>()
    while (existing != null && !existing.exists()) {
        rest.addFirst(existing.name)
        existing = existing.parentFile
    }
    val base = existing?.let { runCatching { it.canonicalFile }.getOrDefault(it) } ?: return typed
    return rest.fold(base) { acc, name -> File(acc, name) }
}
