package dev.kuml.workspace.pdf.compose

import dev.kuml.workspace.OkfDocument
import dev.kuml.workspace.pdf.model.Block
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Loads images from inside the workspace only. PNG/JPEG, size and pixel-dimension limits are checked
 * before the image is decoded; remote URLs, symbolic links and paths leaving the root are refused.
 */
internal class LocalImages(
    private val doc: PDDocument,
    private val root: File,
) {
    private fun safeFile(
        base: File?,
        rel: String,
    ): File? {
        if (rel.contains("://") || rel.startsWith("data:") || rel.startsWith("/") || rel.contains(':')) return null
        val realRoot =
            try {
                root.toPath().toRealPath()
            } catch (_: IOException) {
                return null
            }
        val candidate: Path = (base?.toPath() ?: root.toPath()).resolve(rel.substringBefore('#').substringBefore('?')).normalize()
        if (!candidate.startsWith(root.absoluteFile.toPath().normalize())) return null
        var cur: Path = root.absoluteFile.toPath().normalize()
        for (
        i in 0 until candidate.nameCount -
            root.absoluteFile
                .toPath()
                .normalize()
                .nameCount
        ) {
            cur =
                cur.resolve(
                    candidate.getName(
                        root.absoluteFile
                            .toPath()
                            .normalize()
                            .nameCount + i,
                    ),
                )
            if (Files.isSymbolicLink(cur)) return null
        }
        if (!Files.isRegularFile(candidate)) return null
        return try {
            if (candidate.toRealPath().startsWith(realRoot)) candidate.toFile() else null
        } catch (_: IOException) {
            null
        }
    }

    private fun load(
        file: File,
        maxBytes: Long,
        maxPixels: Int,
    ): Triple<PDImageXObject, Int, Int>? {
        val ext = file.extension.lowercase()
        if (ext != "png" && ext != "jpg" && ext != "jpeg") return null
        if (file.length() > maxBytes) return null
        try {
            ImageIO.createImageInputStream(file).use { iis ->
                val readers = ImageIO.getImageReaders(iis)
                if (!readers.hasNext()) return null
                val reader = readers.next()
                try {
                    reader.input = iis
                    val w = reader.getWidth(0)
                    val h = reader.getHeight(0)
                    if (w <= 0 || h <= 0 || w > maxPixels || h > maxPixels) return null
                    return Triple(PDImageXObject.createFromFile(file.path, doc), w, h)
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

    /** Image referenced from Markdown [from]; `null` when it cannot be embedded (rendered as alt text). */
    fun forMarkdown(
        from: OkfDocument,
        rel: String,
    ): Block.ImageBlock? {
        val file = safeFile(base = from.file.parentFile, rel = rel) ?: return null
        val (img, w, h) = load(file = file, maxBytes = 10L * 1024 * 1024, maxPixels = 8000) ?: return null
        return Block.ImageBlock(image = img, widthPx = w, heightPx = h, alt = "")
    }

    /** Cover logo referenced from `index.md` frontmatter. */
    fun logo(rel: String): PDImageXObject? {
        val file = safeFile(base = null, rel = rel) ?: return null
        return load(file = file, maxBytes = 2L * 1024 * 1024, maxPixels = 4000)?.first
    }
}
