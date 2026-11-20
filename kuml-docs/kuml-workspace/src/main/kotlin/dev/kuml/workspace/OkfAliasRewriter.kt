package dev.kuml.workspace

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption

/** Why [OkfAliasRewriter] left a document untouched. */
public enum class OkfAliasSkipReason {
    OUTSIDE_ROOT,
    SYMLINK,
    NOT_A_REGULAR_FILE,
    UNKNOWN_DOCUMENT,
    NOT_MARKDOWN,
    TOO_LARGE,
    NOT_UTF8,
    CHANGED_ON_DISK,
    IO_ERROR,
}

/** Per-document outcome of [OkfAliasRewriter.rewrite]. */
public sealed interface OkfAliasRewriteResult {
    public val relativePath: String

    /** The alias `type:` value was replaced by its canonical id on disk. */
    public data class Rewritten(
        override val relativePath: String,
        val alias: String,
        val canonical: String,
    ) : OkfAliasRewriteResult

    /** Dry run: the document would be rewritten; nothing was written. */
    public data class WouldRewrite(
        override val relativePath: String,
        val alias: String,
        val canonical: String,
    ) : OkfAliasRewriteResult

    /** The document no longer carries an alias (edited since the scan); nothing was written. */
    public data class Unchanged(
        override val relativePath: String,
    ) : OkfAliasRewriteResult

    /** The document was refused or could not be rewritten safely; the file is untouched. */
    public data class Skipped(
        override val relativePath: String,
        val reason: OkfAliasSkipReason,
    ) : OkfAliasRewriteResult
}

/**
 * Rewrites alias `type:` ids (OKF-I-001) to their canonical ids on disk, byte-preserving (Nacharbeit 122a).
 *
 * Design note: this deliberately does not use [OkfDocumentWriter.save]. `save` takes a `String` (input that is
 * not valid UTF-8 would lose byte identity) and runs [OkfSaveGate], which could block on unrelated problems
 * that already exist. The rewrite cannot introduce new findings because the alias and the canonical id resolve
 * to the same [OkfType].
 *
 * Per document, in order (every failure yields [OkfAliasRewriteResult.Skipped] and leaves the file untouched):
 * [WorkspaceWriteGuard] (containment, no leaf symlink, regular file, known document), `.md` only, size cap,
 * strict UTF-8 decode, [OkfAliasFix.canonicalizeType], a byte-diff sanity check, then (unless dry run) a
 * re-check of symlink state, parent real path and full file bytes immediately before an atomic replace.
 * The race between that last re-check and the rename is narrowed, not closed (no file locks).
 */
public object OkfAliasRewriter {
    public fun rewrite(
        root: File,
        targets: List<OkfDocument>,
        knownDocuments: Collection<File>,
        dryRun: Boolean,
    ): List<OkfAliasRewriteResult> =
        rewrite(root = root, targets = targets, knownDocuments = knownDocuments, dryRun = dryRun, beforeCommit = {
        })

    internal fun rewrite(
        root: File,
        targets: List<OkfDocument>,
        knownDocuments: Collection<File>,
        dryRun: Boolean,
        beforeCommit: (File) -> Unit,
    ): List<OkfAliasRewriteResult> =
        targets.map { doc ->
            try {
                rewriteOne(root = root, doc = doc, knownDocuments = knownDocuments, dryRun = dryRun, beforeCommit = beforeCommit)
            } catch (_: Exception) {
                OkfAliasRewriteResult.Skipped(relativePath = doc.relativePath, reason = OkfAliasSkipReason.IO_ERROR)
            }
        }

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
    private fun rewriteOne(
        root: File,
        doc: OkfDocument,
        knownDocuments: Collection<File>,
        dryRun: Boolean,
        beforeCommit: (File) -> Unit,
    ): OkfAliasRewriteResult {
        val rel = doc.relativePath

        fun skip(reason: OkfAliasSkipReason) = OkfAliasRewriteResult.Skipped(relativePath = rel, reason = reason)

        // 1. Guard: containment, leaf symlink, regular file, known document.
        val resolved =
            WorkspaceWriteGuard
                .resolveWritable(root = root, target = doc.file, knownDocuments = knownDocuments)
                .getOrElse { e ->
                    val rejection = (e as? WorkspaceWriteGuard.WriteGuardException)?.rejection
                    return skip(
                        when (rejection) {
                            WorkspaceWriteGuard.Rejection.OUTSIDE_ROOT -> OkfAliasSkipReason.OUTSIDE_ROOT
                            WorkspaceWriteGuard.Rejection.SYMLINK -> OkfAliasSkipReason.SYMLINK
                            WorkspaceWriteGuard.Rejection.NOT_A_REGULAR_FILE -> OkfAliasSkipReason.NOT_A_REGULAR_FILE
                            WorkspaceWriteGuard.Rejection.UNKNOWN_DOCUMENT -> OkfAliasSkipReason.UNKNOWN_DOCUMENT
                            WorkspaceWriteGuard.Rejection.IO_ERROR, null -> OkfAliasSkipReason.IO_ERROR
                        },
                    )
                }
        val resolvedPath = resolved.toPath()
        val realRoot = root.toPath().toRealPath()
        val parentReal = resolvedPath.parent.toRealPath()

        // 2. Only Markdown files are ever written.
        if (!doc.file.name.endsWith(".md") || !resolved.name.endsWith(".md")) return skip(OkfAliasSkipReason.NOT_MARKDOWN)

        // 3. Size cap.
        if (Files.size(resolvedPath) > OkfDocumentParser.MAX_MD_FILE_SIZE_BYTES) return skip(OkfAliasSkipReason.TOO_LARGE)

        // 4. Strict UTF-8 decode (a lenient decode would replace bytes and lose data).
        val original = Files.readAllBytes(resolvedPath)
        val text =
            try {
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(original))
                    .toString()
            } catch (_: CharacterCodingException) {
                return skip(OkfAliasSkipReason.NOT_UTF8)
            }

        // 5. Compute the rewrite.
        val updated = OkfAliasFix.canonicalizeType(markdown = text) ?: return OkfAliasRewriteResult.Unchanged(relativePath = rel)
        val alias = FrontmatterParser.parse(text).type ?: return OkfAliasRewriteResult.Unchanged(relativePath = rel)
        val canonical = OkfType.canonicalIdFor(alias) ?: return OkfAliasRewriteResult.Unchanged(relativePath = rel)

        // 6. Defensive data-loss check: only the alias bytes may differ.
        val newBytes = updated.toByteArray(StandardCharsets.UTF_8)
        if (!onlyAliasChanged(
                original = original,
                updated = newBytes,
                aliasLen = alias.toByteArray(StandardCharsets.UTF_8).size,
                canonicalLen = canonical.toByteArray(StandardCharsets.UTF_8).size,
            )
        ) {
            return skip(OkfAliasSkipReason.IO_ERROR)
        }

        // 7. Dry run: same checks as a real run, no write.
        if (dryRun) return OkfAliasRewriteResult.WouldRewrite(relativePath = rel, alias = alias, canonical = canonical)

        // 8. Test hook.
        beforeCommit(resolved)

        // 9. Re-check right before writing (narrows the TOCTOU window).
        if (Files.isSymbolicLink(doc.file.toPath())) return skip(OkfAliasSkipReason.SYMLINK)
        if (!Files.isRegularFile(resolvedPath, LinkOption.NOFOLLOW_LINKS)) return skip(OkfAliasSkipReason.NOT_A_REGULAR_FILE)
        val parentNow = resolvedPath.parent.toRealPath()
        if (!parentNow.startsWith(realRoot)) return skip(OkfAliasSkipReason.OUTSIDE_ROOT)
        if (parentNow != parentReal) return skip(OkfAliasSkipReason.SYMLINK)
        if (!Files.readAllBytes(resolvedPath).contentEquals(original)) return skip(OkfAliasSkipReason.CHANGED_ON_DISK)

        // 10. Atomic replace at the canonical path (never writes through a link).
        AtomicFileWrite.replace(target = resolved) { Files.write(it, newBytes) }
        return OkfAliasRewriteResult.Rewritten(relativePath = rel, alias = alias, canonical = canonical)
    }

    private fun onlyAliasChanged(
        original: ByteArray,
        updated: ByteArray,
        aliasLen: Int,
        canonicalLen: Int,
    ): Boolean {
        if (updated.size - original.size != canonicalLen - aliasLen) return false
        val min = minOf(original.size, updated.size)
        var prefix = 0
        while (prefix < min && original[prefix] == updated[prefix]) prefix++
        var suffix = 0
        while (suffix < min - prefix && original[original.size - 1 - suffix] == updated[updated.size - 1 - suffix]) suffix++
        return original.size - prefix - suffix <= aliasLen && updated.size - prefix - suffix <= canonicalLen
    }
}
