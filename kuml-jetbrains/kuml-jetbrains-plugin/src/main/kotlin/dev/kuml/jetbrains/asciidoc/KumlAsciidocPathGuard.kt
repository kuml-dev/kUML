package dev.kuml.jetbrains.asciidoc

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Validates and resolves relative paths referenced by `kuml::` block macros.
 *
 * This class computes and validates paths — it never reads file *contents*. It does,
 * however, consult the filesystem (`Files.exists` / `Path.toRealPath`) to defeat
 * symlink-escape attacks: a purely lexical `normalize()` + `startsWith()` check never
 * resolves symlinks, so a symlink placed inside the allowed tree but pointing outside it
 * would otherwise sail through undetected, only to be transparently followed by the
 * caller's later `Files.readString`. See [resolve] for details.
 *
 * Callers must run a successful [resolve] before any `readText()` / `Files.exists`.
 */
internal object KumlAsciidocPathGuard {
    sealed class Result {
        data class Ok(
            val resolvedPath: Path,
        ) : Result()

        data class Rejected(
            val reason: String,
        ) : Result()
    }

    /**
     * Resolves [targetPath] relative to [adocParentDir], ensuring the result stays
     * inside the project content root when [projectBaseDir] is known.
     */
    fun resolve(
        targetPath: String,
        adocParentDir: Path,
        projectBaseDir: Path? = null,
    ): Result {
        val trimmed = targetPath.trim()
        if (trimmed.isEmpty()) {
            return Result.Rejected("Pfad ist leer")
        }

        val lower = trimmed.lowercase()
        if (lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            lower.startsWith("file:") ||
            lower.startsWith("ftp://")
        ) {
            return Result.Rejected("Ungültiger oder nicht erlaubter Pfad: $trimmed")
        }

        val adocParent = adocParentDir.toAbsolutePath().normalize()
        val candidate =
            try {
                val raw = Path.of(trimmed)
                if (raw.isAbsolute) {
                    raw.normalize()
                } else {
                    adocParent.resolve(raw).normalize()
                }
            } catch (e: Exception) {
                return Result.Rejected("Ungültiger oder nicht erlaubter Pfad: $trimmed (${e.message})")
            }

        // Always reject paths that escape the filesystem root via excessive `..`.
        // (normalize() already collapses `..`, but absolute-outside checks remain.)
        val containmentBase: Path
        if (projectBaseDir != null) {
            containmentBase = projectBaseDir.toAbsolutePath().normalize()
            if (!candidate.startsWith(containmentBase)) {
                return Result.Rejected("Ungültiger oder nicht erlaubter Pfad: $trimmed")
            }
        } else {
            // No project base: still reject absolute paths outside the adoc directory tree,
            // and relative paths that normalised outside the adoc parent.
            containmentBase = adocParent
            if (!candidate.startsWith(containmentBase)) {
                return Result.Rejected("Ungültiger oder nicht erlaubter Pfad: $trimmed")
            }
        }

        // Symlink-escape check: the lexical checks above never resolve symlinks, so a
        // symlink living inside the allowed tree but pointing elsewhere on disk would
        // otherwise pass containment and then be transparently followed by the caller's
        // `Files.readString`/`isRegularFile`. Resolve the REAL path (following all
        // symlinks) whenever something actually exists at `candidate` and re-check
        // containment against the REAL base. A candidate that does not exist yet cannot
        // leak any content, so it is left to the lexical result — the caller's own
        // existence check will report "file not found" as before.
        if (Files.exists(candidate)) {
            val realCandidate =
                try {
                    candidate.toRealPath()
                } catch (e: IOException) {
                    return Result.Rejected("Pfad konnte nicht aufgelöst werden: $trimmed (${e.message})")
                }
            val realBase =
                try {
                    containmentBase.toRealPath()
                } catch (e: IOException) {
                    containmentBase
                }
            if (!realCandidate.startsWith(realBase)) {
                return Result.Rejected("Ungültiger oder nicht erlaubter Pfad (Symlink außerhalb des Projekts): $trimmed")
            }
        }

        return Result.Ok(candidate)
    }
}
