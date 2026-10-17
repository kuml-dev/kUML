package dev.kuml.codegen.reverse

import java.nio.file.Files
import java.nio.file.Path

/**
 * Include/exclude glob filter for source files, shared by reverse engines.
 *
 * Semantics:
 * - A glob matches a file if it matches either the **file name** (last path segment) or the
 *   **path relative to the source root** (always compared with `/` separators).
 * - `*` matches within one segment, `**` across segments, `?` one character, `[abc]` a character
 *   class and `{a,b}` alternatives. A `**` followed by `/` also matches zero segments, so
 *   `**&#47;test&#47;**` matches `test/A.kt` directly below the root.
 * - A file is accepted if ([includeGlobs] is empty or at least one include matches) and no
 *   exclude matches. Excludes always win.
 * - Matching is case-sensitive. A backslash in a glob or path is treated as a path separator
 *   (not as an escape character), so Windows paths and globs behave the same as on Unix.
 */
public class SourceFileFilter(
    includeGlobs: List<String>,
    excludeGlobs: List<String>,
) {
    private val includes: List<Regex> = includeGlobs.map { globToRegex(it) }
    private val excludes: List<Regex> = excludeGlobs.map { globToRegex(it) }

    /** [relativePath] is relative to the source root; `/` and `\` are both accepted as separators. */
    public fun accepts(relativePath: String): Boolean {
        val normalized = relativePath.replace('\\', '/').trimStart('/')
        val name = normalized.substringAfterLast('/')

        fun matches(regexes: List<Regex>): Boolean = regexes.any { it.matches(normalized) || it.matches(name) }
        return (includes.isEmpty() || matches(includes)) && !matches(excludes)
    }

    /** Convenience: relativizes [file] against [root]. If [file] is the root itself only its name is used. */
    public fun accepts(
        root: Path,
        file: Path,
    ): Boolean {
        val rel = if (root == file) file.fileName.toString() else root.relativize(file).toString()
        return accepts(rel)
    }

    /**
     * Recursively collects regular files below all [roots] (symbolic links are not followed)
     * whose extension is in [extensions] and which pass this filter. A root that is itself a file
     * is considered directly. Non-existing roots are skipped. The result is sorted and free of
     * duplicates (overlapping roots), so it is deterministic.
     */
    public fun select(
        roots: List<Path>,
        extensions: Set<String>,
    ): List<Path> {
        val result = sortedMapOf<String, Path>()
        for (root in roots) {
            if (!Files.exists(root)) continue
            val absRoot = root.toAbsolutePath().normalize()
            val candidates: List<Path> =
                if (Files.isRegularFile(absRoot)) {
                    listOf(absRoot)
                } else {
                    Files.walk(absRoot).use { stream -> stream.filter { Files.isRegularFile(it) }.toList() }
                }
            for (file in candidates) {
                if (file.fileName.toString().substringAfterLast('.', "") !in extensions) continue
                if (!accepts(root = absRoot, file = file)) continue
                val key = runCatching { file.toRealPath().toString() }.getOrDefault(file.toString())
                result.putIfAbsent(key, file)
            }
        }
        return result.values.toList()
    }

    public companion object {
        private const val MAX_GLOB_LENGTH = 1024

        /** Translates [glob] into a [Regex] for `/`-normalized paths. Throws [IllegalArgumentException] if invalid. */
        public fun globToRegex(glob: String): Regex {
            require(glob.isNotBlank()) { "Glob must not be empty" }
            require(glob.length <= MAX_GLOB_LENGTH) { "Glob too long (max $MAX_GLOB_LENGTH characters): ${glob.take(40)}..." }
            val g = glob.replace('\\', '/')
            val sb = StringBuilder()
            var braceDepth = 0
            var i = 0
            while (i < g.length) {
                val c = g[i]
                when (c) {
                    '*' -> {
                        var j = i
                        while (j < g.length && g[j] == '*') j++
                        if (j - i >= 2) {
                            if (j < g.length && g[j] == '/') {
                                sb.append("(?:.*/)?")
                                j++
                            } else {
                                sb.append(".*")
                            }
                        } else {
                            sb.append("[^/]*")
                        }
                        i = j
                        continue
                    }
                    '?' -> sb.append("[^/]")
                    '[' -> {
                        val end = g.indexOf(']', i + 2)
                        require(end > 0) { "Invalid glob '$glob': unclosed '['" }
                        var body = g.substring(i + 1, end)
                        var negate = false
                        if (body.startsWith("!") || body.startsWith("^")) {
                            negate = true
                            body = body.substring(1)
                        }
                        val escaped =
                            body
                                .replace("\\", "\\\\")
                                .replace("[", "\\[")
                                .replace("&", "\\&")
                                .replace("^", "\\^")
                        sb.append(if (negate) "[^$escaped/]" else "[$escaped]")
                        i = end
                    }
                    '{' -> {
                        braceDepth++
                        sb.append("(?:")
                    }
                    '}' -> {
                        require(braceDepth > 0) { "Invalid glob '$glob': unmatched '}'" }
                        braceDepth--
                        sb.append(')')
                    }
                    ',' -> if (braceDepth > 0) sb.append('|') else sb.append(Regex.escape(","))
                    else -> sb.append(Regex.escape(c.toString()))
                }
                i++
            }
            require(braceDepth == 0) { "Invalid glob '$glob': unclosed '{'" }
            return Regex(sb.toString())
        }
    }
}
