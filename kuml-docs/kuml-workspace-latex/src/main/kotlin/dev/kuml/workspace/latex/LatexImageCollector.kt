package dev.kuml.workspace.latex

import dev.kuml.workspace.OkfDocument
import dev.kuml.workspace.pdf.source.WorkspaceImageFiles
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.Locale

/** Copies the local images a document references into `images/` and writes `\includegraphics`. */
internal class LatexImageCollector(
    private val ctx: WriterContext,
) {
    private val byRealPath = HashMap<String, String>()
    private var imageNo = 0
    private var totalBytes = 0L

    fun include(
        doc: OkfDocument,
        ref: dev.kuml.workspace.pdf.source.SourceBlock.ImageRef,
        out: StringBuilder,
    ) {
        val source = "${doc.relativePath}:${ref.line}"
        val alt = ctx.text(ref.alt)
        val reason = tryCopy(doc = doc, ref = ref)
        if (reason is Copied) {
            out.append("\\begin{center}\n\\includegraphics[width=").append(fraction(reason.width))
            out.append("\\linewidth,height=0.8\\textheight,keepaspectratio]{").append(reason.path).append("}\n")
            if (alt.isNotBlank()) out.append("\\par\\small\\emph{").append(alt).append("}\n")
            out.append("\\end{center}\n\n")
        } else {
            val why = (reason as Refused).why
            ctx.issue(level = LatexIssueLevel.WARN, source = source, message = "image not exported ($why)")
            out.append("\\kumlmissing{").append(alt.ifBlank { ctx.text(ctx.labels.imageMissing) }).append("}{")
            out.append(ctx.text(ctx.labels.imageMissing)).append("}\n\n")
        }
    }

    private sealed interface Outcome

    private class Copied(
        val path: String,
        val width: Double,
    ) : Outcome

    private class Refused(
        val why: String,
    ) : Outcome

    @Suppress("ReturnCount")
    private fun tryCopy(
        doc: OkfDocument,
        ref: dev.kuml.workspace.pdf.source.SourceBlock.ImageRef,
    ): Outcome {
        val limits = ctx.limits
        val file =
            WorkspaceImageFiles.resolve(root = ctx.workspace.root, from = doc, destination = ref.destination)
                ?: return Refused("not a local file inside the workspace")
        val real =
            try {
                file.toPath().toRealPath().toString()
            } catch (_: IOException) {
                return Refused("file not readable")
            }
        byRealPath[real]?.let { existing -> return Copied(path = existing, width = widthFor(existing)) }
        val image =
            WorkspaceImageFiles.probe(file = file, maxBytes = limits.maxImageBytes, maxPixels = limits.maxImagePixels)
                ?: return Refused("not a PNG/JPEG within the size limits")
        if (imageNo >= limits.maxImages) return Refused("more than ${limits.maxImages} images")
        if (totalBytes + file.length() > limits.maxTotalImageBytes) return Refused("total image size limit reached")
        val bytes = readBytes(file) ?: return Refused("file not readable")
        imageNo++
        totalBytes += bytes.size
        val target =
            String.format(
                Locale.ROOT,
                "images/i%03d-%s.%s",
                imageNo,
                slugOf(raw = file.nameWithoutExtension, fallback = "image"),
                image.format.extension,
            )
        ctx.files += LatexOutputFile.Binary(path = target, bytes = bytes)
        ctx.imageCount++
        widths[target] = (image.widthPx / REFERENCE_WIDTH_PX).coerceAtMost(1.0).coerceAtLeast(MIN_WIDTH)
        byRealPath[real] = target
        return Copied(path = target, width = widthFor(target))
    }

    private val widths = HashMap<String, Double>()

    private fun widthFor(path: String): Double = widths[path] ?: 1.0

    /** Reads without following a link that appeared after the path check. */
    private fun readBytes(file: java.io.File): ByteArray? =
        try {
            val p = file.toPath()
            if (Files.isSymbolicLink(p)) {
                null
            } else {
                Files
                    .newInputStream(p, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
                    .use {
                        it.readNBytes(
                            ctx.limits.maxImageBytes.toInt() + 1,
                        )
                    }.takeIf { it.size <= ctx.limits.maxImageBytes }
            }
        } catch (_: IOException) {
            null
        }

    private companion object {
        const val REFERENCE_WIDTH_PX = 600.0
        const val MIN_WIDTH = 0.1
    }
}
