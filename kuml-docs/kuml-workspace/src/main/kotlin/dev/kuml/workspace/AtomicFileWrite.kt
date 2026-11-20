package dev.kuml.workspace

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Shared atomic "write a temp file, then move it over the target" used by [OkfDocumentWriter] and [OkfAliasRewriter]. */
internal object AtomicFileWrite {
    /**
     * Creates a temp file (`.<name>.*.tmp`) in [target]'s directory, copies the POSIX permissions of [target]
     * onto it, fills it via [fill], then moves it over [target] (`ATOMIC_MOVE` + `REPLACE_EXISTING`, with a
     * non-atomic fallback only on [AtomicMoveNotSupportedException]). The temp file is always deleted in `finally`.
     * The temp name ends in `.tmp`, so the workspace scanner (which only reads `.md`) never picks it up.
     */
    fun replace(
        target: File,
        fill: (Path) -> Unit,
    ) {
        val dir = target.parentFile ?: throw IllegalStateException("Target has no parent directory: $target")
        val tmp = Files.createTempFile(dir.toPath(), ".${target.name}.", ".tmp")
        try {
            copyPosixPermissionsIfSupported(source = target, tmp = tmp.toFile())
            fill(tmp)
            try {
                Files.move(tmp, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /**
     * `Files.createTempFile` always creates a POSIX-`600` file; a pre-existing `644` document would otherwise
     * silently lose its group/other read permission. Skipped on filesystems without POSIX attribute support.
     */
    private fun copyPosixPermissionsIfSupported(
        source: File,
        tmp: File,
    ) {
        if (!source.exists()) return
        val supportsPosix =
            tmp
                .toPath()
                .fileSystem
                .supportedFileAttributeViews()
                .contains("posix")
        if (!supportsPosix) return
        runCatching {
            val perms = Files.getPosixFilePermissions(source.toPath())
            Files.setPosixFilePermissions(tmp.toPath(), perms)
        }
    }
}
