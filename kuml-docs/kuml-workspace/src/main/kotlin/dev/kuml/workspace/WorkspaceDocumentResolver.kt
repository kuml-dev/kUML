package dev.kuml.workspace

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Resolves a user-supplied, workspace-relative document path (`kuml workspace validate/render
 * --document <path>`) to the exact [File] the [WorkspaceScanner] would have discovered for it —
 * or rejects it.
 *
 * Path confinement is the security-relevant part and is enforced by independent checks, each of
 * which alone is sufficient to stop its class of attack:
 * 1. empty / NUL-containing paths ([Rejection.EMPTY]);
 * 2. absolute paths — POSIX, Windows drive and UNC ([Rejection.ABSOLUTE_PATH]);
 * 3. lexical containment after `..` normalisation, compared segment-wise via [Path.startsWith],
 *    never as a string prefix ([Rejection.OUTSIDE_ROOT]);
 * 4. no path segment may be a symbolic link, and the real path must stay inside the real root
 *    ([Rejection.SYMLINK_ESCAPE]) — consistent with the scanner, which never enters symlinked
 *    directories;
 * 5. regular file with `.md` extension ([Rejection.NOT_A_REGULAR_FILE], [Rejection.NOT_MARKDOWN]);
 * 6. the document must be visible to the scanner, i.e. not below a hidden or excluded
 *    directory ([Rejection.EXCLUDED]).
 *
 * Error messages echo only the requested path (control characters replaced), never file content.
 */
public object WorkspaceDocumentResolver {
    public enum class Rejection {
        EMPTY,
        ABSOLUTE_PATH,
        OUTSIDE_ROOT,
        SYMLINK_ESCAPE,
        NOT_FOUND,
        NOT_MARKDOWN,
        NOT_A_REGULAR_FILE,
        EXCLUDED,
        IO_ERROR,

        /** A path segment is `.` or `..` ([resolveForCreate] only). */
        PARENT_SEGMENT,

        /** A segment contains forbidden characters, is a reserved name or too long ([resolveForCreate] only). */
        INVALID_NAME,

        /** The create target already exists ([resolveForCreate] only). */
        ALREADY_EXISTS,

        /** The create path exceeds the depth or length limit ([resolveForCreate] only). */
        TOO_DEEP,
    }

    public class DocumentResolutionException(
        public val rejection: Rejection,
        public val requested: String,
        public val suggestion: String?,
        rootPath: String? = null,
    ) : Exception(buildMessage(rejection = rejection, requested = requested, suggestion = suggestion, rootPath = rootPath))

    private val WINDOWS_DRIVE = Regex("^[A-Za-z]:")

    private val RESERVED_WINDOWS_NAMES: Set<String> =
        setOf("CON", "PRN", "AUX", "NUL") + (1..9).map { "COM$it" } + (1..9).map { "LPT$it" }

    private const val FORBIDDEN_NAME_CHARS = "<>:\"|?*"
    private const val MAX_CREATE_SEGMENTS = 16
    private const val MAX_CREATE_PATH_CHARS = 1024
    private const val MAX_SEGMENT_CHARS = 255

    /**
     * Like [resolve], but for a document that must NOT exist yet (create). Returns the
     * scanner-equivalent absolute (non-real) target file. Stricter than [resolve] on purpose:
     * any `.` / `..` segment is refused (not merely normalised), segment names must be valid on
     * every supported OS (no `<>:"|?*`, no control characters, no reserved Windows device names,
     * no trailing space or dot), hidden and excluded directories are refused, and no existing
     * prefix segment may be a symbolic link or leave the real root.
     *
     * Intermediate directories are NOT created here; this only validates. The caller must
     * re-check every directory it creates (TOCTOU) and create the leaf exclusively.
     */
    public fun resolveForCreate(
        root: File,
        requested: String,
        excludeDirs: Set<File> = emptySet(),
    ): Result<File> =
        try {
            Result.success(resolveForCreateOrThrow(root = root, requested = requested, excludeDirs = excludeDirs))
        } catch (e: DocumentResolutionException) {
            Result.failure(e)
        }

    private fun resolveForCreateOrThrow(
        root: File,
        requested: String,
        excludeDirs: Set<File>,
    ): File {
        val scanRoot = WorkspaceScanner.canonicalRoot(root)
        val excludeCanonical = WorkspaceScanner.canonicalExcludes(excludeDirs)

        fun fail(rejection: Rejection): Nothing =
            throw DocumentResolutionException(
                rejection = rejection,
                requested = requested,
                suggestion = null,
                rootPath = null,
            )

        if (requested.isBlank() || requested.any { it.isISOControl() }) fail(Rejection.EMPTY)
        if (requested.length > MAX_CREATE_PATH_CHARS) fail(Rejection.TOO_DEEP)
        val req = requested.replace('\\', '/')
        if (req.startsWith("/") || WINDOWS_DRIVE.containsMatchIn(req)) fail(Rejection.ABSOLUTE_PATH)

        val segments = req.split('/')
        if (segments.any { it == ".." || it == "." }) fail(Rejection.PARENT_SEGMENT)
        if (segments.any { it.isEmpty() }) fail(Rejection.INVALID_NAME)
        if (segments.size > MAX_CREATE_SEGMENTS) fail(Rejection.TOO_DEEP)
        for (seg in segments) {
            if (seg.length > MAX_SEGMENT_CHARS) fail(Rejection.INVALID_NAME)
            if (seg.any { it.isISOControl() || it in FORBIDDEN_NAME_CHARS }) fail(Rejection.INVALID_NAME)
            if (seg.endsWith(" ") || seg.endsWith(".")) fail(Rejection.INVALID_NAME)
            if (seg.substringBefore('.').uppercase() in RESERVED_WINDOWS_NAMES) fail(Rejection.INVALID_NAME)
        }
        val dirs = segments.dropLast(1)
        if (dirs.any { it.startsWith(".") } || segments.last().startsWith(".")) fail(Rejection.EXCLUDED)
        if (!segments.last().endsWith(".md") || segments.last().length <= ".md".length) fail(Rejection.NOT_MARKDOWN)
        dirs.indices.forEach { i ->
            if (File(scanRoot, segments.subList(0, i + 1).joinToString("/")).absoluteFile.normalize() in excludeCanonical) {
                fail(Rejection.EXCLUDED)
            }
        }

        val realRoot =
            try {
                scanRoot.toPath().toRealPath()
            } catch (_: IOException) {
                fail(Rejection.IO_ERROR)
            }
        var cur: Path = realRoot
        for (seg in dirs) {
            cur =
                try {
                    cur.resolve(seg)
                } catch (_: InvalidPathException) {
                    fail(Rejection.INVALID_NAME)
                }
            if (Files.isSymbolicLink(cur)) fail(Rejection.SYMLINK_ESCAPE)
            if (Files.exists(cur, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(cur, java.nio.file.LinkOption.NOFOLLOW_LINKS)) fail(Rejection.INVALID_NAME)
                val real =
                    try {
                        cur.toRealPath()
                    } catch (_: IOException) {
                        fail(Rejection.IO_ERROR)
                    }
                if (!real.startsWith(realRoot)) fail(Rejection.SYMLINK_ESCAPE)
            }
        }
        val leaf =
            try {
                cur.resolve(segments.last())
            } catch (_: InvalidPathException) {
                fail(Rejection.INVALID_NAME)
            }
        if (Files.isSymbolicLink(leaf)) fail(Rejection.SYMLINK_ESCAPE)
        if (Files.exists(leaf, java.nio.file.LinkOption.NOFOLLOW_LINKS)) fail(Rejection.ALREADY_EXISTS)

        return File(scanRoot, segments.joinToString("/"))
    }

    /** Returns the scanner-equivalent absolute (non-real) file for [requested], or a failure. */
    public fun resolve(
        root: File,
        requested: String,
        excludeDirs: Set<File> = emptySet(),
    ): Result<File> =
        try {
            Result.success(resolveOrThrow(root = root, requested = requested, excludeDirs = excludeDirs))
        } catch (e: DocumentResolutionException) {
            Result.failure(e)
        }

    private fun resolveOrThrow(
        root: File,
        requested: String,
        excludeDirs: Set<File>,
    ): File {
        val scanRoot = WorkspaceScanner.canonicalRoot(root)
        val excludeCanonical = WorkspaceScanner.canonicalExcludes(excludeDirs)

        fun fail(
            rejection: Rejection,
            suggest: Boolean = false,
        ): Nothing =
            throw DocumentResolutionException(
                rejection = rejection,
                requested = requested,
                suggestion =
                    if (suggest) {
                        suggestionFor(
                            scanRoot = scanRoot,
                            excludeCanonical = excludeCanonical,
                            requested = requested,
                        )
                    } else {
                        null
                    },
                rootPath = scanRoot.path,
            )

        // 1. empty / NUL
        if (requested.isBlank() || '\u0000' in requested) fail(Rejection.EMPTY)
        val req = requested.replace('\\', '/')

        // 2. absolute
        if (req.startsWith("/") || WINDOWS_DRIVE.containsMatchIn(req)) fail(Rejection.ABSOLUTE_PATH)

        val realRoot =
            try {
                scanRoot.toPath().toRealPath()
            } catch (_: IOException) {
                fail(Rejection.IO_ERROR)
            }

        // 3. lexical containment (segment-wise)
        val lexical =
            try {
                realRoot.resolve(req).normalize()
            } catch (_: InvalidPathException) {
                fail(Rejection.EMPTY)
            }
        if (!lexical.startsWith(realRoot)) fail(Rejection.OUTSIDE_ROOT)
        val rel = realRoot.relativize(lexical)
        val segments = (0 until rel.nameCount).map { rel.getName(it).toString() }.filter { it.isNotEmpty() }
        if (segments.isEmpty()) fail(Rejection.NOT_A_REGULAR_FILE)

        // 4. no symlink on any segment (also catches dangling links); real path stays inside root
        var cur: Path = realRoot
        for (seg in segments) {
            cur = cur.resolve(seg)
            if (Files.isSymbolicLink(cur)) fail(Rejection.SYMLINK_ESCAPE)
        }
        if (!Files.exists(lexical)) fail(Rejection.NOT_FOUND, suggest = true)
        val real =
            try {
                lexical.toRealPath()
            } catch (_: IOException) {
                fail(Rejection.IO_ERROR)
            }
        if (!real.startsWith(realRoot)) fail(Rejection.SYMLINK_ESCAPE)

        // 5. regular .md file
        if (!Files.isRegularFile(lexical)) fail(Rejection.NOT_A_REGULAR_FILE)
        if (!segments.last().endsWith(".md")) fail(Rejection.NOT_MARKDOWN, suggest = true)

        // 6. visible to the scanner: no hidden / excluded directory on the way
        val result = File(scanRoot, segments.joinToString("/"))
        val hiddenDir = segments.dropLast(1).any { it.startsWith(".") }
        // Mirrors WorkspaceScanner.isWalkableDir exactly: the scan root is always walkable, so only
        // directories strictly below it count, compared by equality.
        val excluded =
            segments.dropLast(1).indices.any { i ->
                File(scanRoot, segments.subList(0, i + 1).joinToString("/")).absoluteFile.normalize() in excludeCanonical
            }
        if (hiddenDir || excluded) fail(Rejection.EXCLUDED, suggest = true)

        return result
    }

    /** Closest `.md` path (relative, `/`-separated) among the files the scanner would see. */
    private fun suggestionFor(
        scanRoot: File,
        excludeCanonical: Set<File>,
        requested: String,
    ): String? =
        try {
            val candidates =
                scanRoot
                    .walkTopDown()
                    .onEnter { WorkspaceScanner.isWalkableDir(dir = it, scanRoot = scanRoot, excludeCanonical = excludeCanonical) }
                    .filter { it.isFile && it.extension == "md" }
                    .take(WorkspaceScanner.MAX_MD_FILE_COUNT)
                    .map { it.relativeTo(scanRoot).path.replace(File.separatorChar, '/') }
                    .toList()
            Levenshtein.closest(target = requested.replace('\\', '/'), candidates = candidates)
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }

    private fun printable(s: String): String = s.map { if (it.isISOControl()) '?' else it }.joinToString("")

    private fun buildMessage(
        rejection: Rejection,
        requested: String,
        suggestion: String?,
        rootPath: String?,
    ): String {
        val p = printable(requested)
        val hint = suggestion?.let { " Did you mean '${printable(it)}'?" } ?: ""
        val where = rootPath?.let { " in workspace $it" } ?: ""
        return when (rejection) {
            Rejection.EMPTY -> "Refusing document '$p': the path is empty or invalid."
            Rejection.ABSOLUTE_PATH ->
                "Refusing document '$p': absolute paths are not allowed; use a path relative to the workspace root."
            Rejection.OUTSIDE_ROOT -> "Refusing document '$p': resolves outside the workspace root."
            Rejection.SYMLINK_ESCAPE ->
                "Refusing document '$p': the path is or passes through a symbolic link, or resolves outside the workspace root."
            Rejection.NOT_FOUND -> "Unknown document '$p': not found$where.$hint"
            Rejection.EXCLUDED ->
                "Unknown document '$p': not part of the workspace (hidden or excluded directory)$where.$hint"
            Rejection.NOT_MARKDOWN -> "Unknown document '$p': not a Markdown (.md) file.$hint"
            Rejection.NOT_A_REGULAR_FILE -> "Unknown document '$p': not a regular file."
            Rejection.IO_ERROR -> "Cannot resolve document '$p': I/O error."
            Rejection.PARENT_SEGMENT -> "Refusing document '$p': '.' and '..' path segments are not allowed."
            Rejection.INVALID_NAME -> "Refusing document '$p': the path contains an invalid file or directory name."
            Rejection.ALREADY_EXISTS -> "Refusing document '$p': it already exists."
            Rejection.TOO_DEEP -> "Refusing document '$p': the path is too long or nested too deeply."
        }
    }
}
