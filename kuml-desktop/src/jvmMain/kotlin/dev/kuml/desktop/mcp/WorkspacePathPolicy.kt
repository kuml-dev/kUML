package dev.kuml.desktop.mcp

/**
 * Pre-check for every document path an MCP client sends to the workspace tools. This is the first
 * of several independent layers: it rejects the path SHAPES that must never reach the file system
 * (absolute, UNC, drive letters, `..`, `~`), after which `WorkspaceDocumentResolver` /
 * `OkfDocumentWriter` re-check containment and symlinks against the real file system.
 *
 * Unicode look-alikes (e.g. a fullwidth full stop) are intentionally NOT treated as `..`: they are
 * ordinary characters on every file system, so they cannot traverse; the resolver still confines
 * them lexically.
 */
internal object WorkspacePathPolicy {
    sealed interface Checked {
        /** [posixPath] uses `/` separators and has no empty, `.` or `..` segment. */
        data class Ok(
            val posixPath: String,
        ) : Checked

        /** [reason] is one of the stable codes `EMPTY`, `ABSOLUTE_PATH`, `UNC_PATH`, `DRIVE_LETTER`, `PARENT_SEGMENT`, `INVALID_NAME`, `TOO_LONG`. */
        data class Rejected(
            val reason: String,
        ) : Checked
    }

    const val MAX_PATH_CHARS = 1024

    private val DRIVE = Regex("^[A-Za-z]:")

    fun check(raw: String): Checked {
        if (raw.isBlank()) return Checked.Rejected("EMPTY")
        if (raw.length > MAX_PATH_CHARS) return Checked.Rejected("TOO_LONG")
        if (raw.any { it.isISOControl() }) return Checked.Rejected("INVALID_NAME")
        val norm = raw.replace('\\', '/')
        if (norm.startsWith("//")) return Checked.Rejected("UNC_PATH")
        if (norm.startsWith("/")) return Checked.Rejected("ABSOLUTE_PATH")
        if (DRIVE.containsMatchIn(norm)) return Checked.Rejected("DRIVE_LETTER")
        val segments = norm.split('/')
        if (segments.first() == "~") return Checked.Rejected("ABSOLUTE_PATH")
        if (segments.any { it == ".." || it == "." }) return Checked.Rejected("PARENT_SEGMENT")
        if (segments.any { it.isEmpty() }) return Checked.Rejected("INVALID_NAME")
        return Checked.Ok(posixPath = norm)
    }

    /** Printable, bounded form of a client-supplied path for error messages and DEBUG logs. */
    fun sanitize(raw: String): String = raw.take(120).map { if (it.isISOControl()) '?' else it }.joinToString("")
}
