package dev.kuml.workspace.pdf.source

import dev.kuml.workspace.OkfDocument
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import javax.imageio.ImageIO

/** Raster formats that every export target can embed. */
public enum class ImageFormat(
    public val extension: String,
) {
    PNG("png"),
    JPEG("jpg"),
}

/** An image that passed [WorkspaceImageFiles.probe]. */
public data class WorkspaceImage(
    public val file: File,
    public val format: ImageFormat,
    public val widthPx: Int,
    public val heightPx: Int,
)

/**
 * The one place that decides which local image files an export may read. Shared by the PDF and the LaTeX
 * export: remote URLs, `data:` URIs, absolute paths, drive letters, `..` leaving the root, symbolic links
 * (as the file or as any path component) and non-regular files are refused.
 */
public object WorkspaceImageFiles {
    /**
     * Resolves [destination] (a Markdown image target) relative to [from] ([root] when `null`). `null` means
     * refused.
     */
    public fun resolve(
        root: File,
        from: OkfDocument?,
        destination: String,
    ): File? = resolveFrom(root = root, base = from?.file?.parentFile, rel = destination)

    internal fun resolveFrom(
        root: File,
        base: File?,
        rel: String,
    ): File? {
        if (rel.isBlank() || rel.contains("://") || rel.startsWith("data:") || rel.startsWith("/") || rel.contains(':')) return null
        if (rel.contains('\\') || rel.any { Character.isISOControl(it) }) return null
        val realRoot =
            try {
                root.toPath().toRealPath()
            } catch (_: IOException) {
                return null
            }
        val rootPath: Path = root.absoluteFile.toPath().normalize()
        val candidate: Path = (base?.toPath() ?: root.toPath()).resolve(rel.substringBefore('#').substringBefore('?')).normalize()
        if (!candidate.startsWith(rootPath)) return null
        var cur: Path = rootPath
        for (i in rootPath.nameCount until candidate.nameCount) {
            cur = cur.resolve(candidate.getName(i))
            if (Files.isSymbolicLink(cur)) return null
        }
        if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) return null
        return try {
            if (candidate.toRealPath().startsWith(realRoot)) candidate.toFile() else null
        } catch (_: IOException) {
            null
        }
    }

    /**
     * Reads the header of [file] only. The format comes from the image reader (magic bytes), never from the
     * extension; size and pixel limits are checked before anything is decoded. `null` = not an acceptable image.
     */
    public fun probe(
        file: File,
        maxBytes: Long,
        maxPixels: Int,
    ): WorkspaceImage? {
        if (file.length() > maxBytes) return null
        try {
            ImageIO.createImageInputStream(file).use { iis ->
                if (iis == null) return null
                val readers = ImageIO.getImageReaders(iis)
                if (!readers.hasNext()) return null
                val reader = readers.next()
                try {
                    val format =
                        when (reader.formatName.lowercase()) {
                            "png" -> ImageFormat.PNG
                            "jpeg", "jpg" -> ImageFormat.JPEG
                            else -> return null
                        }
                    reader.input = iis
                    val w = reader.getWidth(0)
                    val h = reader.getHeight(0)
                    if (w <= 0 || h <= 0 || w > maxPixels || h > maxPixels) return null
                    return WorkspaceImage(file = file, format = format, widthPx = w, heightPx = h)
                } finally {
                    reader.dispose()
                }
            }
        } catch (_: IOException) {
            return null
        } catch (_: RuntimeException) {
            return null
        }
    }
}
