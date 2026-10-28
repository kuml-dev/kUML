package dev.kuml.workspace.pdf.security

import dev.kuml.workspace.pdf.PdfExportException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Validates where an export may write. Rules: the target is canonicalised, is never a symbolic link,
 * must stay under [restrictToRoot] when one is given (MCP), may never be a scanned source document, and an
 * existing file is only replaced with explicit `overwrite`.
 */
internal object ExportOutputGuard {
    private fun canonical(f: File): File =
        try {
            f.absoluteFile
                .toPath()
                .normalize()
                .toFile()
        } catch (e: IOException) {
            throw PdfExportException(code = PdfExportException.Code.OUTPUT_INVALID, message = "Invalid output path")
        }

    private fun realParent(f: File): File {
        var cur: File? = f.parentFile
        while (cur != null && !cur.exists()) cur = cur.parentFile
        return (cur ?: f).toPath().toRealPath().toFile()
    }

    private fun checkRoot(
        target: File,
        restrictToRoot: File?,
    ) {
        if (restrictToRoot == null) return
        val root = restrictToRoot.toPath().toRealPath()
        val ok =
            try {
                realParent(target).toPath().startsWith(root) &&
                    target.toPath().normalize().startsWith(restrictToRoot.absoluteFile.toPath().normalize())
            } catch (_: IOException) {
                false
            }
        if (!ok) {
            throw PdfExportException(
                code = PdfExportException.Code.PATH_OUTSIDE_ROOT,
                message = "Output path is outside the workspace root",
            )
        }
    }

    /** Resolves one output file. */
    fun resolveFile(
        target: File,
        overwrite: Boolean,
        restrictToRoot: File?,
        knownDocuments: Collection<File>,
    ): File {
        val t = canonical(target)
        if (!t.name.endsWith(".pdf", ignoreCase = true) || t.name.length <= 4) {
            throw PdfExportException(code = PdfExportException.Code.OUTPUT_INVALID, message = "Output file must end in .pdf")
        }
        checkRoot(target = t, restrictToRoot = restrictToRoot)
        if (Files.isSymbolicLink(t.toPath())) {
            throw PdfExportException(code = PdfExportException.Code.OUTPUT_INVALID, message = "Output path is a symbolic link")
        }
        if (knownDocuments.any { canonical(it) == t }) {
            throw PdfExportException(code = PdfExportException.Code.OUTPUT_INVALID, message = "Output path is a source document")
        }
        if (Files.exists(t.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(t.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw PdfExportException(
                    code = PdfExportException.Code.OUTPUT_EXISTS,
                    message = "Output path exists and is not a regular file",
                )
            }
            if (!overwrite) {
                throw PdfExportException(
                    code = PdfExportException.Code.OUTPUT_EXISTS,
                    message = "Output file already exists: ${t.name}",
                )
            }
        }
        return t
    }

    /** Resolves an output directory (created on demand, never a symbolic link). */
    fun resolveDirectory(
        target: File,
        restrictToRoot: File?,
    ): File {
        val t = canonical(target)
        checkRoot(target = t, restrictToRoot = restrictToRoot)
        if (Files.isSymbolicLink(t.toPath())) {
            throw PdfExportException(code = PdfExportException.Code.OUTPUT_INVALID, message = "Output directory is a symbolic link")
        }
        if (t.exists() && !t.isDirectory) {
            throw PdfExportException(code = PdfExportException.Code.OUTPUT_INVALID, message = "Output path is not a directory")
        }
        return t
    }
}
