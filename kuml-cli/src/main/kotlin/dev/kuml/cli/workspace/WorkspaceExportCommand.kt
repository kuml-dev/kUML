package dev.kuml.cli.workspace

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import dev.kuml.cli.ExitCodes
import dev.kuml.workspace.WorkspaceScanner
import dev.kuml.workspace.pdf.ExportMetadata
import dev.kuml.workspace.pdf.ExportScope
import dev.kuml.workspace.pdf.PageFormat
import dev.kuml.workspace.pdf.PageOrientation
import dev.kuml.workspace.pdf.PdfExportException
import dev.kuml.workspace.pdf.PdfExportKind
import dev.kuml.workspace.pdf.PdfExportOptions
import dev.kuml.workspace.pdf.PdfLanguage
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * `kuml workspace export --format pdf` — handbook, per-document PDFs or a diagram folio of a knowledge
 * workspace. Executes the workspace's ```kuml blocks (same trust model as `workspace render`).
 */
internal class WorkspaceExportCommand : CliktCommand(name = "export") {
    private val dir by argument(help = "Path to the OKF workspace root").file(mustExist = true, canBeFile = false)

    @Suppress("unused")
    private val format by option("--format", help = "Export format (currently only: pdf)").choice("pdf").required()

    private val kind by option("--kind", help = "handbook (one book), per-document (one PDF per document) or diagrams (folio)")
        .choice("handbook", "per-document", "diagrams")
        .default("handbook")

    private val scopes by option("--scope", help = "all | folder:<path> | <document path>. Repeatable.").multiple()
    private val page by option("--page", help = "Paper format").choice("a4", "a3").default("a4")
    private val orientation by option("--orientation", help = "Page orientation").choice("portrait", "landscape", "auto").default("auto")
    private val cover by option("--cover", help = "Add a cover page (default: handbook yes, per-document no)").flag()
    private val noCover by option("--no-cover", help = "Do not add a cover page").flag()
    private val noToc by option("--no-toc", help = "Omit the table of contents").flag()
    private val noHeaderFooter by option("--no-header-footer", help = "Omit running header and footer").flag()
    private val draft by option("--draft", help = "Add a DRAFT watermark").flag()
    private val author by option("--author", help = "Author (overrides index.md frontmatter)")
    private val version by option("--version", help = "Document version (overrides index.md frontmatter)")
    private val date by option("--date", help = "Date, ISO-8601 (overrides index.md frontmatter)")
    private val lang by option("--lang", help = "Label language").choice("de", "en")
    private val theme by option("--theme", help = "Diagram theme")
    private val output by option("-o", "--output", help = "Output file (handbook, folio) or directory (per-document, --split)").file()
    private val force by option("--force", help = "Overwrite existing output files").flag()
    private val strict by option("--strict", help = "Exit 3 without writing on any warning (diagram too small) or failed diagram").flag()
    private val split by option("--split", help = "diagrams only: one PDF per diagram").flag()
    private val aiNotes by option("--ai-notes", help = "Reserved: AI explanations are not available from the CLI in this version").flag()

    override fun help(context: Context): String = "Export a knowledge workspace to PDF (handbook, per-document or diagram folio)."

    private fun usage(msg: String): Nothing {
        echo("Error: $msg", err = true)
        throw ProgramResult(ExitCodes.USAGE)
    }

    private fun parseScope(): ExportScope {
        val values = scopes.filter { it.isNotBlank() }
        if (values.isEmpty() || values.any { it == "all" }) return ExportScope.All
        val folders = values.filter { it.startsWith("folder:") }.map { it.removePrefix("folder:") }
        val docs = values.filterNot { it.startsWith("folder:") }
        if (folders.isNotEmpty() && docs.isNotEmpty()) usage("--scope cannot mix folder: and document entries")
        if (folders.size > 1) usage("only one --scope folder:<path> is supported")
        return if (folders.isNotEmpty()) {
            ExportScope.Folder(
                relativePath = folders.single(),
            )
        } else {
            ExportScope.Documents(relativePaths = docs)
        }
    }

    override fun run() {
        if (aiNotes) usage("--ai-notes is not available from the CLI in this version (no AI provider is wired into the export).")
        if (cover && noCover) usage("--cover and --no-cover are mutually exclusive")
        val parsedDate =
            try {
                date?.let { LocalDate.parse(it) }
            } catch (_: DateTimeParseException) {
                usage("--date must be an ISO-8601 date such as 2026-10-28")
            }
        val exportKind =
            when (kind) {
                "per-document" -> PdfExportKind.PER_DOCUMENT
                "diagrams" -> PdfExportKind.DIAGRAMS
                else -> PdfExportKind.HANDBOOK
            }
        if (split && exportKind != PdfExportKind.DIAGRAMS) usage("--split only applies to --kind diagrams")
        val root = WorkspaceScanner.canonicalRoot(dir)
        val options =
            PdfExportOptions(
                kind = exportKind,
                scope = parseScope(),
                page = if (page == "a3") PageFormat.A3 else PageFormat.A4,
                orientation = PageOrientation.valueOf(orientation.uppercase()),
                cover = if (noCover) false else cover || exportKind != PdfExportKind.PER_DOCUMENT,
                toc = !noToc,
                headerFooter = !noHeaderFooter,
                draft = draft,
                split = split,
                language = lang?.let { if (it == "de") PdfLanguage.DE else PdfLanguage.EN },
                metadata = ExportMetadata(author = author, version = version, date = parsedDate),
                producerVersion = WorkspacePdfExport.producer,
            )
        val slug =
            root.name
                .replace(Regex("[^A-Za-z0-9._-]+"), "_")
                .trim('_')
                .ifEmpty { "workspace" }
        val defaultBase = File(root, ".kuml-out/export")
        val asDir = exportKind == PdfExportKind.PER_DOCUMENT || split
        val target =
            output
                ?: if (asDir) {
                    File(
                        defaultBase,
                        if (split) "diagrams" else "pdf",
                    )
                } else {
                    File(defaultBase, "$slug${if (exportKind == PdfExportKind.DIAGRAMS) "-diagrams" else ""}.pdf")
                }

        try {
            val ws = WorkspaceScanner.scan(root = root, excludeDirs = setOf(defaultBase.absoluteFile.normalize()))
            WorkspacePdfExport.prepare(workspace = ws, options = options, themeName = theme).use { prepared ->
                prepared.notes.forEach { echo("NOTE $it", err = true) }
                prepared.warnings.forEach {
                    echo(
                        "WARN scale ${(it.scale * 100).toInt()}% ${it.documentPath}#${it.blockIndex + 1} \"${it.title}\" — suggestion: ${it.suggestion}",
                        err = true,
                    )
                }
                prepared.failures.forEach { echo("ERROR [${it.code}] ${it.file}:${it.line}: ${it.message}", err = true) }
                if (strict && (prepared.warnings.isNotEmpty() || prepared.failures.isNotEmpty())) {
                    echo("Error: --strict: nothing written.", err = true)
                    throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                }
                val result = WorkspacePdfExport.write(prepared = prepared, target = target, overwrite = force)
                val firstFile = result.files.firstOrNull()
                val shown = (if (asDir) firstFile?.parentFile?.path else null) ?: firstFile?.path ?: target.path
                echo("Wrote ${result.files.size} file(s), ${result.pageCount} pages: $shown")
            }
        } catch (e: PdfExportException) {
            echo("Error: ${e.message}", err = true)
            throw ProgramResult(
                when (e.code) {
                    PdfExportException.Code.OUTPUT_EXISTS -> {
                        echo("Hint: pass --force to overwrite.", err = true)
                        ExitCodes.USAGE
                    }
                    PdfExportException.Code.TIMEOUT -> ExitCodes.SCRIPT_ERROR
                    PdfExportException.Code.EMPTY_SCOPE -> ExitCodes.USAGE
                    PdfExportException.Code.IO, PdfExportException.Code.LIMIT_OUTPUT_SIZE -> ExitCodes.IO_ERROR
                    else -> ExitCodes.USAGE
                },
            )
        } catch (e: java.io.IOException) {
            echo("Error: I/O error: ${e.javaClass.simpleName}", err = true)
            throw ProgramResult(ExitCodes.IO_ERROR)
        }
    }
}
