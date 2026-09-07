package dev.kuml.workspace

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The outcome of [OkfDocumentWriter.save]. */
public sealed interface OkfWriteResult {
    /** [file] was written; [warnings] are non-blocking [OkfFinding]s to surface in the UI. */
    public data class Written(
        public val file: File,
        public val warnings: List<OkfFinding>,
    ) : OkfWriteResult

    /** The save was refused by [OkfSaveGate]; the file was left untouched. */
    public data class Blocked(
        public val blocking: List<OkfFinding>,
        public val warnings: List<OkfFinding>,
    ) : OkfWriteResult

    /** The save was refused by [WorkspaceWriteGuard]; the file was left untouched. */
    public data class Rejected(
        public val rejection: WorkspaceWriteGuard.Rejection,
    ) : OkfWriteResult

    /** [content] exceeds [OkfDocumentParser.MAX_MD_FILE_SIZE_BYTES] (measured in UTF-8 bytes). */
    public data class TooLarge(
        public val bytes: Long,
        public val limit: Long,
    ) : OkfWriteResult

    /** An unexpected I/O failure occurred. The file may or may not have been left untouched — see [cause]. */
    public data class Failed(
        public val cause: Throwable,
    ) : OkfWriteResult
}

/**
 * Atomically writes an OKF Markdown document to disk, gated by [WorkspaceWriteGuard]
 * (is this write even allowed to happen) and [OkfValidator]/[OkfSaveGate] (does the
 * resulting content still conform to OKF).
 *
 * This welle never creates or deletes documents — [target] must already be a member of
 * [knownDocuments] (see [WorkspaceWriteGuard]'s `UNKNOWN_DOCUMENT` rejection). Every
 * step below runs, in order, before a single byte is written to [target] itself; a
 * rejection or block always leaves the file byte-identical to before the call.
 */
public object OkfDocumentWriter {
    public fun save(
        root: File,
        target: File,
        content: String,
        knownDocuments: Collection<File>,
    ): OkfWriteResult {
        val resolved =
            WorkspaceWriteGuard
                .resolveWritable(root = root, target = target, knownDocuments = knownDocuments)
                .getOrElse { e ->
                    val rejection = (e as? WorkspaceWriteGuard.WriteGuardException)?.rejection
                    return if (rejection != null) {
                        OkfWriteResult.Rejected(rejection = rejection)
                    } else {
                        OkfWriteResult.Failed(cause = e)
                    }
                }

        val byteSize = content.toByteArray(StandardCharsets.UTF_8).size.toLong()
        if (byteSize > OkfDocumentParser.MAX_MD_FILE_SIZE_BYTES) {
            return OkfWriteResult.TooLarge(bytes = byteSize, limit = OkfDocumentParser.MAX_MD_FILE_SIZE_BYTES)
        }

        val candidate = OkfDocumentParser.parse(root = root, file = resolved, text = content)
        val findings = OkfValidator.validateDocument(root = root, doc = candidate, strictVocabulary = true)
        when (val decision = OkfSaveGate.decide(findings)) {
            is OkfSaveDecision.Block -> return OkfWriteResult.Blocked(blocking = decision.blocking, warnings = decision.warnings)
            is OkfSaveDecision.Allow -> {
                return try {
                    writeAtomically(target = resolved, content = content)
                    OkfWriteResult.Written(file = resolved, warnings = decision.warnings)
                } catch (e: Exception) {
                    OkfWriteResult.Failed(cause = e)
                }
            }
        }
    }

    private fun writeAtomically(
        target: File,
        content: String,
    ) {
        val dir = target.parentFile ?: throw IllegalStateException("Target has no parent directory: $target")
        val tmp = Files.createTempFile(dir.toPath(), ".${target.name}.", ".tmp")
        try {
            copyPosixPermissionsIfSupported(source = target, tmp = tmp.toFile())
            Files.writeString(tmp, content, StandardCharsets.UTF_8)
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
     * `Files.createTempFile` always creates a POSIX-`600` file; a pre-existing `644`
     * document would otherwise silently lose its group/other read permission on every
     * save. Skipped entirely on a filesystem without POSIX attribute support (e.g.
     * Windows) — `Files.setPosixFilePermissions` would throw `UnsupportedOperationException`
     * there.
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
