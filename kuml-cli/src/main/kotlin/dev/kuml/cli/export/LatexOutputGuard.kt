package dev.kuml.cli.export

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Writes the files of a folder export under one output directory. Same rules as the PDF export guard: the
 * directory is canonicalised, no path component may be a symbolic link, every target must stay below the
 * directory, and an existing file is replaced only with an explicit flag. All targets are checked before the
 * first byte is written, so a refusal leaves the directory untouched.
 */
internal object LatexOutputGuard {
    /** Text files, written as UTF-8. */
    fun writeAll(
        outDir: File,
        files: Map<String, String>,
        overwrite: Boolean,
    ): List<File> =
        writeAllBytes(
            outDir = outDir,
            files = files.mapValues { it.value.toByteArray(Charsets.UTF_8) },
            overwrite = overwrite,
        )

    /**
     * @param files relative path (with `/`) to content; written in iteration order.
     * @return the written files.
     */
    fun writeAllBytes(
        outDir: File,
        files: Map<String, ByteArray>,
        overwrite: Boolean,
    ): List<File> {
        val out = outDir.absoluteFile.normalize().toPath()
        if (Files.exists(out, LinkOption.NOFOLLOW_LINKS) &&
            (Files.isSymbolicLink(out) || !Files.isDirectory(out, LinkOption.NOFOLLOW_LINKS))
        ) {
            throw LatexFolderExportException(
                code = LatexFolderExportException.Code.OUTPUT_INVALID,
                message = "Output path is not a plain directory: ${display(out)}",
            )
        }
        val existing = mutableListOf<String>()
        for (rel in files.keys) {
            val target = checkedTarget(out = out, rel = rel)
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw LatexFolderExportException(
                        code = LatexFolderExportException.Code.OUTPUT_INVALID,
                        message = "Target exists and is not a regular file: $rel",
                    )
                }
                existing += rel
            }
        }
        if (existing.isNotEmpty() && !overwrite) {
            throw LatexFolderExportException(
                code = LatexFolderExportException.Code.OUTPUT_EXISTS,
                message = "Output already exists (${existing.size} file(s), first: ${display(existing.first())})",
            )
        }
        try {
            Files.createDirectories(out)
            val outReal = out.toRealPath()
            return files.map { (rel, content) ->
                writeOne(out = out, outReal = outReal, rel = rel, content = content, overwrite = overwrite)
            }
        } catch (e: IOException) {
            throw LatexFolderExportException(
                code = LatexFolderExportException.Code.IO,
                message = "I/O error while writing the export: ${e.javaClass.simpleName}",
            )
        }
    }

    private fun checkedTarget(
        out: Path,
        rel: String,
    ): Path {
        val target = out.resolve(rel).normalize()
        if (!target.startsWith(out) || target == out) outside(rel)
        var cursor = out
        for (segment in out.relativize(target.parent)) {
            cursor = cursor.resolve(segment)
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS) &&
                (Files.isSymbolicLink(cursor) || !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS))
            ) {
                outside(rel)
            }
        }
        return target
    }

    private fun writeOne(
        out: Path,
        outReal: Path,
        rel: String,
        content: ByteArray,
        overwrite: Boolean,
    ): File {
        val target = checkedTarget(out = out, rel = rel)
        val parent = target.parent
        Files.createDirectories(parent)
        if (!parent.toRealPath().startsWith(outReal)) outside(rel)
        val tmp = Files.createTempFile(parent, ".kuml-", ".tmp")
        try {
            Files.write(tmp, content)
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                if (overwrite) Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING) else Files.move(tmp, target)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
        return target.toFile()
    }

    private fun outside(rel: String): Nothing =
        throw LatexFolderExportException(
            code = LatexFolderExportException.Code.PATH_OUTSIDE_OUTPUT,
            message = "Refusing to write outside the output directory: ${display(rel)}",
        )

    private fun display(raw: Any): String = raw.toString().map { if (Character.isISOControl(it)) ' ' else it }.joinToString("")
}
