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

    /** [OkfDocumentWriter.create] refused the path ([WorkspaceDocumentResolver.resolveForCreate]); nothing was created. */
    public data class CreateRejected(
        public val rejection: WorkspaceDocumentResolver.Rejection,
    ) : OkfWriteResult

    /** An unexpected I/O failure occurred. The file may or may not have been left untouched — see [cause]. */
    public data class Failed(
        public val cause: Throwable,
    ) : OkfWriteResult
}

/** The outcome of [OkfDocumentWriter.delete]. */
public sealed interface OkfDeleteResult {
    public data class Deleted(
        public val file: File,
    ) : OkfDeleteResult

    public data class Rejected(
        public val rejection: WorkspaceWriteGuard.Rejection,
    ) : OkfDeleteResult

    public data class Failed(
        public val cause: Throwable,
    ) : OkfDeleteResult
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

        // `resolved` (canonicalised by WorkspaceWriteGuard, resolving any symlink in the
        // path — e.g. macOS's `/var` -> `/private/var`) is used ONLY for the security
        // check above and for the physical write below (writing through the fully-resolved
        // path is the right defence-in-depth choice: it can't be redirected by a symlink
        // swapped in between the check and the write). It must NOT leak into the parsed
        // `OkfDocument`/the returned result: `root` here (and everywhere else in this
        // object's caller, `WorkspaceState`) is the ORIGINAL, non-canonicalised workspace
        // root from `WorkspaceScanner.scan`, so computing `relativePath` against `resolved`
        // instead of the original `target` mixes two different path "coordinate systems" —
        // `file.relativeTo(root)` then can't find `resolved` under `root` by simple prefix
        // and falls back to a `..`-laden absolute-to-absolute relative path instead of the
        // plain `foo.md` every other document in `documents` has. `target` and `resolved`
        // name the identical file on disk (one via a symlink, one via its real path), so
        // using `target` here changes nothing about what gets read/written — only which
        // path string downstream identity/equality checks see (bugfix, found running
        // `WorkspaceStateEditingTest` on macOS).
        val candidate = OkfDocumentParser.parse(root = root, file = target, text = content)
        val findings = OkfValidator.validateDocument(root = root, doc = candidate, strictVocabulary = true)
        when (val decision = OkfSaveGate.decide(findings)) {
            is OkfSaveDecision.Block -> return OkfWriteResult.Blocked(blocking = decision.blocking, warnings = decision.warnings)
            is OkfSaveDecision.Allow -> {
                return try {
                    writeAtomically(target = resolved, content = content)
                    OkfWriteResult.Written(file = target, warnings = decision.warnings)
                } catch (e: Exception) {
                    OkfWriteResult.Failed(cause = e)
                }
            }
        }
    }

    /**
     * Creates a NEW document at [relativePath] (below [root]), including missing parent
     * directories. Order: path validation ([WorkspaceDocumentResolver.resolveForCreate]), size
     * limit, OKF validation + [OkfSaveGate] (a blocked document creates nothing, not even a
     * directory), then directories one by one (each re-checked for symlink / real-root escape),
     * the leaf exclusively via [Files.createFile], and finally the content via the atomic-write
     * path. Directories created before a later failure are left in place.
     */
    public fun create(
        root: File,
        relativePath: String,
        content: String,
        excludeDirs: Set<File> = emptySet(),
    ): OkfWriteResult {
        val scanRoot = WorkspaceScanner.canonicalRoot(root)
        val target =
            WorkspaceDocumentResolver
                .resolveForCreate(root = scanRoot, requested = relativePath, excludeDirs = excludeDirs)
                .getOrElse { e ->
                    val rejection = (e as? WorkspaceDocumentResolver.DocumentResolutionException)?.rejection
                    return if (rejection != null) OkfWriteResult.CreateRejected(rejection = rejection) else OkfWriteResult.Failed(cause = e)
                }

        val byteSize = content.toByteArray(StandardCharsets.UTF_8).size.toLong()
        if (byteSize > OkfDocumentParser.MAX_MD_FILE_SIZE_BYTES) {
            return OkfWriteResult.TooLarge(bytes = byteSize, limit = OkfDocumentParser.MAX_MD_FILE_SIZE_BYTES)
        }

        val candidate = OkfDocumentParser.parse(root = scanRoot, file = target, text = content)
        val findings = OkfValidator.validateDocument(root = scanRoot, doc = candidate, strictVocabulary = true)
        val warnings =
            when (val decision = OkfSaveGate.decide(findings)) {
                is OkfSaveDecision.Block -> return OkfWriteResult.Blocked(blocking = decision.blocking, warnings = decision.warnings)
                is OkfSaveDecision.Allow -> decision.warnings
            }

        return try {
            val realRoot = scanRoot.toPath().toRealPath()
            val segments = relativePath.replace('\\', '/').split('/')
            var dir: File = scanRoot
            for (seg in segments.dropLast(1)) {
                dir = File(dir, seg)
                if (!dir.exists()) Files.createDirectory(dir.toPath())
                // Re-check after every step (TOCTOU): never follow a symlink, never leave the root.
                if (Files.isSymbolicLink(dir.toPath()) || !Files.isDirectory(dir.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    return OkfWriteResult.CreateRejected(rejection = WorkspaceDocumentResolver.Rejection.SYMLINK_ESCAPE)
                }
                if (!dir.toPath().toRealPath().startsWith(realRoot)) {
                    return OkfWriteResult.CreateRejected(rejection = WorkspaceDocumentResolver.Rejection.SYMLINK_ESCAPE)
                }
            }
            val resolved =
                WorkspaceWriteGuard
                    .resolveCreatable(root = scanRoot, target = target)
                    .getOrElse { e ->
                        return OkfWriteResult.Failed(cause = e)
                    }
            try {
                Files.createFile(resolved.toPath())
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                return OkfWriteResult.CreateRejected(rejection = WorkspaceDocumentResolver.Rejection.ALREADY_EXISTS)
            }
            try {
                writeAtomically(target = resolved, content = content)
            } catch (e: Exception) {
                runCatching { Files.deleteIfExists(resolved.toPath()) }
                return OkfWriteResult.Failed(cause = e)
            }
            OkfWriteResult.Written(file = target, warnings = warnings)
        } catch (e: Exception) {
            OkfWriteResult.Failed(cause = e)
        }
    }

    /**
     * Deletes the existing document [target] (hard delete, no recursion, no directories). Only a
     * regular, non-symlink file that is a member of [knownDocuments] and lies inside [root] is
     * deleted -- the same [WorkspaceWriteGuard.resolveWritable] gate as [save].
     */
    public fun delete(
        root: File,
        target: File,
        knownDocuments: Collection<File>,
    ): OkfDeleteResult {
        val resolved =
            WorkspaceWriteGuard
                .resolveWritable(root = root, target = target, knownDocuments = knownDocuments)
                .getOrElse { e ->
                    val rejection = (e as? WorkspaceWriteGuard.WriteGuardException)?.rejection
                    return if (rejection != null) OkfDeleteResult.Rejected(rejection = rejection) else OkfDeleteResult.Failed(cause = e)
                }
        return try {
            Files.delete(resolved.toPath())
            OkfDeleteResult.Deleted(file = target)
        } catch (e: Exception) {
            OkfDeleteResult.Failed(cause = e)
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
