package dev.kuml.workspace

import java.io.File
import java.nio.file.Files

/**
 * Security-critical gate in front of every write [OkfDocumentWriter] performs: decides
 * whether a target file may be written to at all, independent of [OkfSaveGate]'s
 * content-based decision.
 *
 * Five independent checks, all of which must pass:
 * 1. [root] and [target] are canonicalised ([File.getCanonicalFile] resolves symlinks
 *    anywhere in the path, not just at the leaf).
 * 2. The canonical target path must start with the canonical root path plus a path
 *    separator — a bare-prefix check alone would let a sibling directory that merely
 *    shares the root's name as a string prefix (e.g. root `/tmp/ws`, target
 *    `/tmp/ws-evil/x.md`) slip through.
 * 3. The target must not itself be a symbolic link — writing is never allowed to go
 *    *through* a symlink, even one that happens to resolve back inside the root.
 * 4. If the target already exists, it must be a regular file (never a directory, FIFO,
 *    or other special file).
 * 5. The canonical target path must be a member of [knownDocuments] (also
 *    canonicalised) — a second, independent line of defence: only a document the
 *    workspace scanner actually found may ever be overwritten. This welle never
 *    creates new documents (see [OkfDocumentWriter]'s KDoc), so "known" is exactly
 *    "already scanned", not "anywhere under the root".
 */
public object WorkspaceWriteGuard {
    /** Why [resolveWritable] refused to return a writable target. */
    public enum class Rejection {
        /** The canonical target does not fall under the canonical workspace root. */
        OUTSIDE_ROOT,

        /** The target itself is a symbolic link. */
        SYMLINK,

        /** The target exists but is not a regular file (e.g. a directory). */
        NOT_A_REGULAR_FILE,

        /** The target is not a member of the scanner's known-document set. */
        UNKNOWN_DOCUMENT,

        /**
         * [File.getCanonicalFile] failed (e.g. a symlink cycle under [root] or [target]) —
         * a defence-in-depth catch-all so a pathological filesystem state surfaces as a
         * clean rejection rather than an uncaught [java.io.IOException] escaping this gate.
         */
        IO_ERROR,
    }

    /** Thrown inside the [Result] returned by [resolveWritable] on failure — carries the [rejection] reason. */
    public class WriteGuardException(
        public val rejection: Rejection,
    ) : Exception("Write refused: $rejection")

    /**
     * Returns the canonicalised, writable target file, or a failed [Result] carrying a
     * [WriteGuardException] with the [Rejection] reason.
     */
    public fun resolveWritable(
        root: File,
        target: File,
        knownDocuments: Collection<File>,
    ): Result<File> {
        // File.getCanonicalFile can throw IOException on a pathological filesystem state
        // (e.g. a symlink cycle) — caught here so that surfaces as a clean Rejection
        // instead of an uncaught exception escaping this security gate.
        val canonicalRoot =
            runCatching { root.canonicalFile }
                .getOrElse { return Result.failure(WriteGuardException(rejection = Rejection.IO_ERROR)) }
        val canonicalTarget =
            runCatching { target.canonicalFile }
                .getOrElse { return Result.failure(WriteGuardException(rejection = Rejection.IO_ERROR)) }

        val rootPrefix = canonicalRoot.path + File.separator
        if (!canonicalTarget.path.startsWith(rootPrefix)) {
            return Result.failure(WriteGuardException(rejection = Rejection.OUTSIDE_ROOT))
        }

        if (Files.isSymbolicLink(target.toPath())) {
            return Result.failure(WriteGuardException(rejection = Rejection.SYMLINK))
        }

        if (canonicalTarget.exists() && !canonicalTarget.isFile) {
            return Result.failure(WriteGuardException(rejection = Rejection.NOT_A_REGULAR_FILE))
        }

        // Bugfix (review finding): canonicalizing the KNOWN-document set can throw the same
        // IOException as canonicalizing `root`/`target` above (e.g. a symlink cycle appearing
        // under one of them between the workspace scan and this save) — this call is now
        // guarded the same way, so a pathological filesystem state here also surfaces as a
        // clean Rejection.IO_ERROR instead of an uncaught exception escaping this gate.
        val knownCanonical =
            runCatching { knownDocuments.map { it.canonicalFile.path }.toSet() }
                .getOrElse { return Result.failure(WriteGuardException(rejection = Rejection.IO_ERROR)) }
        if (canonicalTarget.path !in knownCanonical) {
            return Result.failure(WriteGuardException(rejection = Rejection.UNKNOWN_DOCUMENT))
        }

        return Result.success(canonicalTarget)
    }
}
