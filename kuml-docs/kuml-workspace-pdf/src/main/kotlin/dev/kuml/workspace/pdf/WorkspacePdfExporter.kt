package dev.kuml.workspace.pdf

import dev.kuml.workspace.OkfDocument
import dev.kuml.workspace.OkfFinding
import dev.kuml.workspace.OkfWorkspace
import dev.kuml.workspace.pdf.compose.DiagramKey
import dev.kuml.workspace.pdf.compose.DocumentComposer
import dev.kuml.workspace.pdf.compose.LocalImages
import dev.kuml.workspace.pdf.compose.RenderedDiagram
import dev.kuml.workspace.pdf.labels.PdfLabels
import dev.kuml.workspace.pdf.layout.PdfLayoutEngine
import dev.kuml.workspace.pdf.plan.ExportPlanner
import dev.kuml.workspace.pdf.render.PdfFontSet
import dev.kuml.workspace.pdf.security.ExportOutputGuard
import dev.kuml.workspace.pdf.writer.FinishInfo
import dev.kuml.workspace.pdf.writer.PdfDocumentFinisher
import org.apache.pdfbox.io.IOUtils
import org.apache.pdfbox.pdmodel.PDDocument
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal class PreparedPdf(
    val fileStem: String,
    val doc: PDDocument,
    val pageCount: Int,
)

/** A fully laid-out export held in memory; nothing has been written yet. Close it when discarding it. */
public class PreparedExport internal constructor(
    private val outputs: List<PreparedPdf>,
    private val knownDocuments: List<File>,
    private val options: PdfExportOptions,
    public val warnings: List<ScaleWarning>,
    public val failures: List<OkfFinding>,
    public val notes: List<String>,
    public val documentCount: Int,
    public val diagramCount: Int,
) : AutoCloseable {
    /** Total page count over all output files. */
    public val pageCount: Int get() = outputs.sumOf { it.pageCount }

    /** Number of PDF files that [WorkspacePdfExporter.write] will produce. */
    public val outputCount: Int get() = outputs.size

    /** File names (`<stem>.pdf`) that [WorkspacePdfExporter.write] creates when the target is a directory. */
    public val outputFileNames: List<String> get() = outputs.map { "${it.fileStem}.pdf" }

    internal fun outputs(): List<Pair<String, PDDocument>> = outputs.map { it.fileStem to it.doc }

    internal fun known(): List<File> = knownDocuments

    internal fun options(): PdfExportOptions = options

    override fun close() {
        outputs.forEach { runCatching { it.doc.close() } }
    }
}

/**
 * Exports a knowledge workspace to PDF: handbook, one PDF per document, or a diagram folio.
 *
 * Diagrams are obtained through the [DiagramSource] port (script execution stays in the caller, see the trust
 * model of `workspace render`). Rendering, layout and PDF assembly happen in [prepare], which writes nothing;
 * [write] saves atomically.
 */
public object WorkspacePdfExporter {
    private val logger = LoggerFactory.getLogger(WorkspacePdfExporter::class.java)

    /** Diagrams shrunk below this scale produce a [ScaleWarning]. */
    public const val MIN_SCALE_WARN: Double = 0.40

    /** Landscape is chosen automatically only when it gains at least this much scale. */
    public const val AUTO_LANDSCAPE_GAIN: Double = 0.15

    public fun prepare(
        workspace: OkfWorkspace,
        options: PdfExportOptions,
        diagrams: DiagramSource,
        aiNotes: AiNotesProvider? = null,
        progress: ExportProgressListener = ExportProgressListener { _, _, _ -> },
        cancelled: () -> Boolean = { false },
    ): PreparedExport {
        val started = System.nanoTime()
        val limits = options.limits
        val plan = ExportPlanner.plan(ws = workspace, scope = options.scope)
        val docs = plan.all
        if (docs.size > limits.maxDocuments) {
            throw PdfExportException(
                code = PdfExportException.Code.LIMIT_DOCUMENTS,
                message = "Export has ${docs.size} documents; the limit is ${limits.maxDocuments}",
            )
        }
        val total = docs.sumOf { it.kumlBlocks.size }
        if (total > limits.maxDiagrams) {
            throw PdfExportException(
                code = PdfExportException.Code.LIMIT_DIAGRAMS,
                message = "Export has $total diagrams; the limit is ${limits.maxDiagrams}",
            )
        }
        if (options.kind == PdfExportKind.DIAGRAMS && total == 0) {
            throw PdfExportException(code = PdfExportException.Code.EMPTY_SCOPE, message = "The selected scope contains no kuml diagrams")
        }
        val lang =
            options.language
                ?: plan.indexDoc?.frontmatter?.fields?.get("lang")?.let {
                    if (it.startsWith("de", ignoreCase = true)) {
                        PdfLanguage.DE
                    } else if (it.startsWith("en", ignoreCase = true)) {
                        PdfLanguage.EN
                    } else {
                        null
                    }
                }
                ?: if (Locale.getDefault().language == "de") PdfLanguage.DE else PdfLanguage.EN
        val labels = PdfLabels(lang)

        // 1. render diagrams (cached; timeouts per diagram and overall)
        val rendered = HashMap<DiagramKey, RenderedDiagram>()
        var done = 0
        val executor = DiagramExecutor()
        try {
            for (d in docs) {
                for ((i, block) in d.kumlBlocks.withIndex()) {
                    if (cancelled()) throw PdfExportException(code = PdfExportException.Code.CANCELLED, message = "Export cancelled")
                    if (Duration.ofNanos(System.nanoTime() - started) > limits.totalTimeout) {
                        throw PdfExportException(code = PdfExportException.Code.TIMEOUT, message = "Export exceeded the total time limit")
                    }
                    rendered[d.relativePath to i] =
                        RenderedDiagram(
                            renderOne(runner = executor, source = diagrams, doc = d, index = i, block = block, limits = limits),
                        )
                    progress.onProgress(done = ++done, total = total, phase = ExportPhase.RENDERING)
                }
            }
        } finally {
            executor.shutdown()
        }

        // 2. compose + layout per output file
        val failures = mutableListOf<OkfFinding>()
        val notes = plan.warnings.toMutableList()
        val composer =
            DocumentComposer(
                ws = workspace,
                plan = plan,
                options = options,
                labels = labels,
                rendered = rendered,
                aiNotes = aiNotes,
                failures = failures,
                warnings = notes,
            )
        val stems = composer.outputs()
        val outputs = mutableListOf<PreparedPdf>()
        val scaleWarnings = mutableListOf<ScaleWarning>()
        try {
            for ((idx, entry) in stems.withIndex()) {
                val stem = entry.stem
                val only = entry.only
                progress.onProgress(done = idx, total = stems.size, phase = ExportPhase.LAYOUT)
                val pdf = PDDocument(IOUtils.createTempFileOnlyStreamCache())
                outputs += PreparedPdf(fileStem = stem, doc = pdf, pageCount = 0)
                val fonts = PdfFontSet.load(pdf)
                val images = LocalImages(doc = pdf, root = workspace.root)
                val splitIndex = entry.splitIndex
                val composed = composer.compose(stem = stem, only = only, splitIndex = splitIndex, doc = pdf, images = images)
                val engine = PdfLayoutEngine(doc = pdf, fonts = fonts, labels = labels, options = options, cancelled = cancelled)
                val layout = engine.layout(composed)
                scaleWarnings += layout.warnings
                val fm =
                    plan.indexDoc
                        ?.frontmatter
                        ?.fields
                        .orEmpty()
                val meta = options.metadata
                PdfDocumentFinisher.finish(
                    doc = pdf,
                    fonts = fonts,
                    labels = labels,
                    options = options,
                    layout = layout,
                    info =
                        FinishInfo(
                            title = composed.title,
                            headerTitle = composed.title,
                            author = meta.author ?: fm["author"],
                            subject = meta.subtitle ?: fm["subtitle"],
                            keywords =
                                docs
                                    .mapNotNull { it.frontmatter.type }
                                    .distinct()
                                    .take(20)
                                    .joinToString(", ")
                                    .ifEmpty { null },
                            version = meta.version ?: fm["version"],
                            date = meta.date ?: fm["date"]?.let { runCatching { java.time.LocalDate.parse(it.take(10)) }.getOrNull() },
                            hasCover = composed.cover != null,
                            languageTag = if (lang == PdfLanguage.DE) "de-DE" else "en-US",
                        ),
                )
                outputs[outputs.lastIndex] = PreparedPdf(fileStem = stem, doc = pdf, pageCount = pdf.numberOfPages)
            }
        } catch (e: Throwable) {
            outputs.forEach { runCatching { it.doc.close() } }
            throw e
        }
        progress.onProgress(done = stems.size, total = stems.size, phase = ExportPhase.LAYOUT)
        val diagramFailures = failures.distinctBy { it.file to it.line }
        logger.info(
            "PDF export prepared: {} file(s), {} document(s), {} diagram(s), {} failure(s)",
            outputs.size,
            docs.size,
            total,
            diagramFailures.size,
        )
        return PreparedExport(
            outputs = outputs,
            knownDocuments = workspace.documents.map { it.file },
            options = options,
            warnings = scaleWarnings,
            failures = diagramFailures,
            notes = notes,
            documentCount = docs.size,
            diagramCount = total,
        )
    }

    private fun renderOne(
        runner: DiagramExecutor,
        source: DiagramSource,
        doc: OkfDocument,
        index: Int,
        block: dev.kuml.markdown.KumlCodeBlock,
        limits: ExportLimits,
    ): DiagramArtifact {
        val future = runner.submit { source.render(document = doc, blockIndex = index, block = block) }
        return try {
            val art = future.get(limits.perDiagramTimeout.toMillis(), TimeUnit.MILLISECONDS)
            if (art is DiagramArtifact.Rendered && art.svg.length > limits.maxSvgBytes) DiagramArtifact.Failed("svg too large") else art
        } catch (_: TimeoutException) {
            future.cancel(true)
            // A script that ignores interrupts keeps its thread busy; abandon that thread so the next diagram
            // does not queue behind it and time out as well.
            runner.replaceWorker()
            DiagramArtifact.Failed("timeout")
        } catch (e: ExecutionException) {
            logger.warn("Diagram render failed in {}: {}", doc.relativePath, e.cause?.javaClass?.simpleName)
            DiagramArtifact.Failed("render error (${e.cause?.javaClass?.simpleName ?: "unknown"})")
        }
    }

    /**
     * Saves [prepared] atomically (temporary file plus move). [target] is a file for the handbook and the
     * diagram folio, a directory for per-document output and for a split folio. With [restrictToRoot] the
     * output must stay below that directory. The prepared export is closed afterwards.
     */
    public fun write(
        prepared: PreparedExport,
        target: File,
        overwrite: Boolean,
        restrictToRoot: File? = null,
    ): PdfExportResult {
        prepared.use {
            val o = prepared.options()
            val outs = prepared.outputs()
            val asDir = o.kind == PdfExportKind.PER_DOCUMENT || (o.kind == PdfExportKind.DIAGRAMS && o.split)
            val files: List<Pair<File, PDDocument>> =
                if (asDir) {
                    val dir = ExportOutputGuard.resolveDirectory(target = target, restrictToRoot = restrictToRoot)
                    // validate every target before anything is written (all or nothing)
                    outs.map { (stem, pdf) ->
                        ExportOutputGuard.resolveFile(
                            target = File(dir, "$stem.pdf"),
                            overwrite = overwrite,
                            restrictToRoot = restrictToRoot,
                            knownDocuments = prepared.known(),
                        ) to
                            pdf
                    }
                } else {
                    listOf(
                        ExportOutputGuard.resolveFile(
                            target = target,
                            overwrite = overwrite,
                            restrictToRoot = restrictToRoot,
                            knownDocuments = prepared.known(),
                        ) to
                            outs.single().second,
                    )
                }
            val seen = HashSet<String>()
            for ((file, _) in files) {
                // case-insensitive: macOS and Windows file systems would silently overwrite one with the other
                if (!seen.add(
                        file.absoluteFile
                            .normalize()
                            .path
                            .lowercase(),
                    )
                ) {
                    throw PdfExportException(
                        code = PdfExportException.Code.OUTPUT_INVALID,
                        message = "Two outputs would write the same file: ${file.name}",
                    )
                }
            }
            val written = mutableListOf<File>()
            for ((file, pdf) in files) {
                file.parentFile?.mkdirs()
                val tmp = Files.createTempFile(file.parentFile.toPath(), ".kuml-pdf-", ".tmp")
                try {
                    CountingStream(out = Files.newOutputStream(tmp), max = o.limits.maxOutputBytes).use { pdf.save(it) }
                    Files.move(tmp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (e: LimitExceeded) {
                    throw PdfExportException(
                        code = PdfExportException.Code.LIMIT_OUTPUT_SIZE,
                        message = "PDF exceeds the output size limit of ${o.limits.maxOutputBytes} bytes",
                    )
                } catch (e: IOException) {
                    throw PdfExportException(
                        code = PdfExportException.Code.IO,
                        message = "Could not write ${file.name}: ${e.javaClass.simpleName}",
                    )
                } finally {
                    Files.deleteIfExists(tmp)
                }
                written += file
            }
            return PdfExportResult(
                files = written,
                pageCount = prepared.pageCount,
                documentCount = prepared.documentCount,
                diagramCount = prepared.diagramCount,
                warnings = prepared.warnings,
                failures = prepared.failures,
            )
        }
    }

    /** Single-thread executor whose worker can be abandoned after a diagram that cannot be interrupted. */
    private class DiagramExecutor {
        private var delegate = create()

        private fun create() = Executors.newSingleThreadExecutor { r -> Thread(r, "kuml-pdf-diagram").also { it.isDaemon = true } }

        fun <T> submit(task: () -> T): java.util.concurrent.Future<T> = delegate.submit<T> { task() }

        fun replaceWorker() {
            delegate.shutdownNow()
            delegate = create()
        }

        fun shutdown() {
            delegate.shutdownNow()
        }
    }

    private class LimitExceeded : IOException("limit")

    private class CountingStream(
        private val out: OutputStream,
        private val max: Long,
    ) : OutputStream() {
        private var n = 0L

        override fun write(b: Int) {
            if (++n > max) throw LimitExceeded()
            out.write(b)
        }

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            n += len
            if (n > max) throw LimitExceeded()
            out.write(b, off, len)
        }

        override fun close() = out.close()
    }
}
