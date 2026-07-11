package dev.kuml.desktop.workspace

import java.io.File

/**
 * Trust-gate helper for OKF workspace roots (V3.6.4, decision 2).
 *
 * Opening a workspace can execute kUML scripts embedded in its documents — the
 * "arbitrary code execution on file open" risk documented on
 * `DesktopRenderPipeline`/the CLI's `WorkspaceRenderer` KDoc. Before any
 * ` ```kuml ` block is evaluated for a workspace root, the user must have
 * explicitly trusted that root at least once.
 *
 * Deliberately stateless: the trusted-path set itself lives in
 * [dev.kuml.desktop.AppState.trustedWorkspaces] (mirrored into
 * [dev.kuml.desktop.io.AppSettings.trustedWorkspaces] for persistence). This
 * object only implements the pure canonicalization/lookup/add logic so it is
 * trivially unit-testable without Compose or file I/O beyond `File.canonicalFile`.
 */
object WorkspaceTrust {
    /** Canonical, OS-normalized absolute path used as the trust-set key. */
    fun canonicalPath(root: File): String = root.canonicalFile.path

    /** Whether [root]'s canonical path is already present in [trustedPaths]. */
    fun isTrusted(
        trustedPaths: List<String>,
        root: File,
    ): Boolean = canonicalPath(root) in trustedPaths

    /** Returns [trustedPaths] with [root]'s canonical path added (no duplicate entries). */
    fun withTrust(
        trustedPaths: List<String>,
        root: File,
    ): List<String> {
        val canonical = canonicalPath(root)
        return if (canonical in trustedPaths) trustedPaths else trustedPaths + canonical
    }
}
