package dev.kuml.desktop.preview

import dev.kuml.desktop.i18n.Strings
import dev.kuml.desktop.io.FileMenu
import dev.kuml.io.png.KumlPngRenderer
import dev.kuml.io.png.PngRenderOptions
import java.awt.Window
import java.io.File
import kotlin.math.roundToInt

/** Image formats offered by the preview's "Save as" buttons and the File menu. */
enum class ImageFormat(
    val ext: String,
    val description: String,
) {
    SVG("svg", "SVG image (*.svg)"),
    PNG("png", "PNG image (*.png)"),
}

/** What is saved: the [svg] currently shown, in [format], proposed under [suggestedFileName]. */
data class ExportTarget(
    val format: ImageFormat,
    val suggestedFileName: String,
    val svg: String,
)

/** Outcome of [saveShownDiagram]; a failure is always reported to the caller, never swallowed. */
sealed interface ExportSaveResult {
    data class Saved(
        val file: File,
    ) : ExportSaveResult

    /** The user closed the file chooser; nothing was written. */
    data object Cancelled : ExportSaveResult

    data class Failed(
        val message: String,
    ) : ExportSaveResult
}

/** Single-file mode: while a simulation runs the preview shows its frozen base SVG, so that is what is exported. */
fun singleFileSvg(
    simulationBaseSvg: String?,
    lastSvg: String,
    /** The preview fell back to the plain render (the base SVG is unrenderable), so that is what is shown and exported. */
    fallbackStatic: Boolean = false,
): String = if (fallbackStatic) lastSvg else simulationBaseSvg ?: lastSvg

/** Why the preview shows the plain diagram although a simulation session is active. */
internal enum class FallbackReason { BASE_UNRENDERABLE, }

/** What the preview canvas shows; [Empty] means there is nothing usable to show. */
internal sealed interface PreviewChoice {
    /** The session's frozen base SVG, with live highlight patching. */
    data class Simulation(
        val svg: String,
    ) : PreviewChoice

    /** A session is active but its base SVG cannot be shown: the plain render, without state markers. */
    data class FallbackStatic(
        val svg: String,
        val reason: FallbackReason,
    ) : PreviewChoice

    /** No session: the latest render. */
    data class Normal(
        val svg: String,
    ) : PreviewChoice

    data object Empty : PreviewChoice
}

/** The SVG this choice shows, or `null` for [PreviewChoice.Empty]. */
internal fun PreviewChoice.svgOrNull(): String? =
    when (this) {
        is PreviewChoice.Simulation -> svg
        is PreviewChoice.FallbackStatic -> svg
        is PreviewChoice.Normal -> svg
        PreviewChoice.Empty -> null
    }

/**
 * Pure decision of which document the preview shows. "Parsed" means the SVG could be turned into a document
 * AND (for [baseParsed]) has not since failed to build or render in Batik. A blank or `null` SVG never counts.
 */
internal fun previewDocumentChoice(
    sessionActive: Boolean,
    baseSvg: String?,
    baseParsed: Boolean,
    lastSvg: String,
    lastParsed: Boolean,
): PreviewChoice {
    val baseUsable = !baseSvg.isNullOrBlank() && baseParsed
    val lastUsable = lastSvg.isNotBlank() && lastParsed
    return when {
        sessionActive && baseUsable -> PreviewChoice.Simulation(baseSvg.orEmpty())
        sessionActive && lastUsable -> PreviewChoice.FallbackStatic(svg = lastSvg, reason = FallbackReason.BASE_UNRENDERABLE)
        sessionActive -> PreviewChoice.Empty
        lastUsable -> PreviewChoice.Normal(lastSvg)
        else -> PreviewChoice.Empty
    }
}

/**
 * The single source of "the diagram that is shown", for toolbar AND File menu. A blank string counts as none.
 * In workspace mode only [docSvg] counts: a document without a diagram never falls back to the stale
 * single-file [lastSvg] (that would silently export the wrong picture).
 */
fun shownSvg(
    workspaceMode: Boolean,
    docSvg: String?,
    lastSvg: String?,
): String? = (if (workspaceMode) docSvg else lastSvg)?.takeIf { it.isNotBlank() }

private val FORBIDDEN_FILENAME_CHARS = Regex("""[/\\:*?"<>|\u0000-\u001F]""")

/** [baseName] is the file name without extension (see [FileMenu.exportBaseName]); `null` when there is no SVG. */
fun exportTarget(
    format: ImageFormat,
    svg: String?,
    baseName: String?,
): ExportTarget? {
    if (svg.isNullOrBlank()) return null
    val cleaned =
        (baseName ?: "")
            .removeSuffix(".kuml.kts")
            .removeSuffix(".md")
            .replace(FORBIDDEN_FILENAME_CHARS, "_")
            .trim()
            .trim('.')
    val base = cleaned.ifEmpty { "diagram" }
    return ExportTarget(format = format, suggestedFileName = "$base.${format.ext}", svg = svg)
}

private const val PNG_MIN_WIDTH = 800
private const val PNG_MAX_WIDTH = 4096
private const val PNG_FALLBACK_WIDTH = 1600
private const val SVG_HEAD_SCAN_LIMIT = 8192

private val SVG_START_TAG = Regex("""<svg\b[^>]*>""")
private val VIEW_BOX_ATTR = Regex("""(?<![\w:-])viewBox\s*=\s*["']([^"']*)["']""")
private val WIDTH_ATTR = Regex("""(?<![\w:-])width\s*=\s*["']\s*([0-9]+(?:\.[0-9]+)?)\s*(?:px)?\s*["']""")

/**
 * PNG width for [svg]: twice its natural width (viewBox width, else `width`), clamped to 800..4096 px,
 * 1600 when the width cannot be read. Only a regex over the `<svg` start tag is used: no DOM, no entity resolution.
 */
fun pngWidthFor(svg: String): Int {
    val tag = SVG_START_TAG.find(svg.take(SVG_HEAD_SCAN_LIMIT))?.value ?: return PNG_FALLBACK_WIDTH
    val fromViewBox =
        VIEW_BOX_ATTR
            .find(tag)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.split(Regex("""[\s,]+"""))
            ?.getOrNull(2)
            ?.toDoubleOrNull()
    val natural =
        fromViewBox?.takeIf { it.isFinite() && it > 0 } ?: WIDTH_ATTR
            .find(tag)
            ?.groupValues
            ?.get(1)
            ?.toDoubleOrNull()
    if (natural == null || !natural.isFinite() || natural <= 0) return PNG_FALLBACK_WIDTH
    return (natural * 2).coerceAtMost(PNG_MAX_WIDTH.toDouble()).roundToInt().coerceIn(PNG_MIN_WIDTH, PNG_MAX_WIDTH)
}

fun exportBytes(target: ExportTarget): ByteArray =
    when (target.format) {
        ImageFormat.SVG -> target.svg.toByteArray(Charsets.UTF_8)
        ImageFormat.PNG -> KumlPngRenderer.toPng(svg = target.svg, options = PngRenderOptions(widthPx = pngWidthFor(target.svg)))
    }

/**
 * Asks for a destination through the file chooser and writes [target] there. Used by the preview toolbars AND the
 * File menu, so there is exactly one save path. Cancelling writes nothing; any failure is returned as
 * [ExportSaveResult.Failed] for the caller to show.
 */
fun saveShownDiagram(
    parent: Window?,
    initialDir: File?,
    target: ExportTarget,
    strings: Strings,
): ExportSaveResult =
    try {
        val chosen =
            FileMenu.chooseExport(
                parent = parent,
                initialDir = initialDir,
                suggestedName = target.suggestedFileName,
                description = target.format.description,
                extension = target.format.ext,
                strings = strings,
            )
        if (chosen == null) {
            ExportSaveResult.Cancelled
        } else {
            FileMenu.writeBytes(file = chosen, bytes = exportBytes(target))
            ExportSaveResult.Saved(chosen)
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        ExportSaveResult.Failed(e.message ?: e.javaClass.simpleName)
    }
