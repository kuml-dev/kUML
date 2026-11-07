package dev.kuml.workspace.pdf.compose

import dev.kuml.workspace.OkfDocument
import dev.kuml.workspace.pdf.model.Block
import dev.kuml.workspace.pdf.source.WorkspaceImageFiles
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.File
import java.io.IOException

/**
 * Loads images from inside the workspace only. The path rules and the PNG/JPEG, size and pixel-dimension checks
 * live in [WorkspaceImageFiles] (shared with the LaTeX export); this class only hands an accepted file to PDFBox.
 */
internal class LocalImages(
    private val doc: PDDocument,
    private val root: File,
) {
    private fun load(
        file: File,
        maxBytes: Long,
        maxPixels: Int,
    ): Triple<PDImageXObject, Int, Int>? {
        // PDFBox picks the decoder from the file extension, so the extension must match a supported type too.
        val ext = file.extension.lowercase()
        if (ext != "png" && ext != "jpg" && ext != "jpeg") return null
        val probed = WorkspaceImageFiles.probe(file = file, maxBytes = maxBytes, maxPixels = maxPixels) ?: return null
        return try {
            Triple(PDImageXObject.createFromFile(file.path, doc), probed.widthPx, probed.heightPx)
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    /** Image referenced from Markdown [from]; `null` when it cannot be embedded (rendered as alt text). */
    fun forMarkdown(
        from: OkfDocument,
        rel: String,
    ): Block.ImageBlock? {
        val file = WorkspaceImageFiles.resolveFrom(root = root, base = from.file.parentFile, rel = rel) ?: return null
        val (img, w, h) = load(file = file, maxBytes = 10L * 1024 * 1024, maxPixels = 8000) ?: return null
        return Block.ImageBlock(image = img, widthPx = w, heightPx = h, alt = "")
    }

    /** Cover logo referenced from `index.md` frontmatter. */
    fun logo(rel: String): PDImageXObject? {
        val file = WorkspaceImageFiles.resolveFrom(root = root, base = null, rel = rel) ?: return null
        return load(file = file, maxBytes = 2L * 1024 * 1024, maxPixels = 4000)?.first
    }
}
