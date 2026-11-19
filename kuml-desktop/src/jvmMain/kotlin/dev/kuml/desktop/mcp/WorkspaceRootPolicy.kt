package dev.kuml.desktop.mcp

import dev.kuml.desktop.workspace.hasUnsafeDisplayChars
import java.io.File
import java.io.IOException

/**
 * Pre-check for the one ABSOLUTE path an MCP client may send: the workspace root of
 * `kuml_desktop_workspace_open`. The counterpart of [WorkspacePathPolicy] (which only accepts
 * root-relative document paths).
 *
 * The accepted root is canonicalised (symlinks resolved) with exactly the same function the trust
 * decision uses (`File.canonicalFile`, see `WorkspaceTrust.canonicalPath`), so a symlink can never
 * borrow the trust of its link path. A [Checked.Rejected] carries only a stable reason code, never
 * the client-supplied path.
 */
internal object WorkspaceRootPolicy {
    sealed interface Checked {
        data class Ok(
            val root: File,
        ) : Checked

        /**
         * [reason]: `EMPTY`, `TOO_LONG`, `INVALID_NAME`, `DEVICE_PATH`, `UNC_PATH`, `RELATIVE_PATH`, `IO_ERROR`,
         * `NOT_A_DIRECTORY` (missing and non-directory are deliberately indistinguishable, so a client cannot
         * probe the file system), `FILESYSTEM_ROOT`, `HOME_DIRECTORY`.
         */
        data class Rejected(
            val reason: String,
        ) : Checked
    }

    const val MAX_PATH_CHARS = 1024

    private val WINDOWS_DEVICE = Regex("^(CON|PRN|AUX|NUL|COM\\d|LPT\\d)(\\..*)?$", RegexOption.IGNORE_CASE)

    fun check(
        raw: String,
        home: File = File(System.getProperty("user.home").orEmpty()),
        fsRoots: List<File> = File.listRoots().orEmpty().toList(),
        windows: Boolean = File.separatorChar == '\\',
    ): Checked {
        if (raw.isBlank()) return Checked.Rejected("EMPTY")
        if (raw.length > MAX_PATH_CHARS) return Checked.Rejected("TOO_LONG")
        if (hasUnsafeChars(raw)) return Checked.Rejected("INVALID_NAME")
        val norm = raw.replace('\\', '/')
        if (norm.startsWith("//?/") || norm.startsWith("//./")) return Checked.Rejected("DEVICE_PATH")
        if (windows && norm.split('/').any { WINDOWS_DEVICE.matches(it) }) return Checked.Rejected("DEVICE_PATH")
        if (norm.startsWith("//")) return Checked.Rejected("UNC_PATH")
        if (norm.startsWith("~")) return Checked.Rejected("RELATIVE_PATH")
        val file = File(raw)
        if (!file.isAbsolute) return Checked.Rejected("RELATIVE_PATH")
        val canonical =
            try {
                file.canonicalFile
            } catch (_: IOException) {
                return Checked.Rejected("IO_ERROR")
            } catch (_: SecurityException) {
                return Checked.Rejected("IO_ERROR")
            }
        // The canonical path (symlinks resolved) is what the trust dialog shows: a directory name with
        // control / bidi characters behind a clean symlink must not reach it (UI spoofing).
        if (hasUnsafeChars(canonical.path)) return Checked.Rejected("INVALID_NAME")
        try {
            if (!canonical.exists() || !canonical.isDirectory) return Checked.Rejected("NOT_A_DIRECTORY")
            if (canonical.parentFile == null || fsRoots.any { sameFile(a = it, b = canonical) }) return Checked.Rejected("FILESYSTEM_ROOT")
            if (sameFile(a = home, b = canonical)) return Checked.Rejected("HOME_DIRECTORY")
        } catch (_: SecurityException) {
            return Checked.Rejected("IO_ERROR")
        }
        return Checked.Ok(canonical)
    }

    /** Control, format (bidi, tag characters) and line/paragraph-separator code points; see isUnsafeDisplayCodePoint. */
    internal fun hasUnsafeChars(text: String): Boolean = hasUnsafeDisplayChars(text)

    private fun sameFile(
        a: File,
        b: File,
    ): Boolean =
        try {
            a.canonicalFile == b.canonicalFile
        } catch (_: IOException) {
            false
        }
}
