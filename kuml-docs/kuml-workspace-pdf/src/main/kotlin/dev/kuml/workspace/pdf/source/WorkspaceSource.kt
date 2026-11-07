package dev.kuml.workspace.pdf.source

import dev.kuml.workspace.OkfDocument
import dev.kuml.workspace.OkfWorkspace
import dev.kuml.workspace.pdf.ExportLimits
import dev.kuml.workspace.pdf.ExportMetadata
import dev.kuml.workspace.pdf.ExportScope
import dev.kuml.workspace.pdf.PdfExportException
import dev.kuml.workspace.pdf.PdfLanguage
import dev.kuml.workspace.pdf.labels.PdfLabels
import dev.kuml.workspace.pdf.markdown.InternalLinkResolver
import dev.kuml.workspace.pdf.markdown.MarkdownContext
import dev.kuml.workspace.pdf.markdown.MarkdownToBlocks
import dev.kuml.workspace.pdf.plan.ExportPlan
import dev.kuml.workspace.pdf.plan.ExportPlanner
import dev.kuml.workspace.pdf.security.TextSanitizer
import java.time.LocalDate
import java.util.Locale

/** One chapter of the reading order; [depth] is the nesting below `index.md` (0 = top level). */
public data class SourceChapter(
    public val document: OkfDocument,
    public val depth: Int,
)

/** Which documents an export contains and in which order. The same plan drives the PDF and the LaTeX export. */
public data class SourcePlan(
    public val indexDocument: OkfDocument?,
    public val chapters: List<SourceChapter>,
    public val glossary: List<OkfDocument>,
    public val appendix: List<OkfDocument>,
    public val warnings: List<String>,
) {
    /** Every document in reading order. */
    public val all: List<OkfDocument> get() = chapters.map { it.document } + glossary + appendix
}

/** Cover data resolved from the options and the `index.md` frontmatter. */
public data class SourceMetadata(
    public val title: String,
    public val subtitle: String?,
    public val author: String?,
    public val organization: String?,
    public val version: String?,
    public val date: LocalDate?,
    public val status: String?,
)

/**
 * Script-free, PDFBox-free access to what both document exports need: the plan, the metadata, the label
 * language and the converted documents. Nothing here executes ```` ```kuml ```` blocks.
 */
public object WorkspaceSource {
    /** @throws PdfExportException when the scope is invalid or empty. */
    public fun plan(
        workspace: OkfWorkspace,
        scope: ExportScope,
    ): SourcePlan = ExportPlanner.plan(ws = workspace, scope = scope).toSourcePlan()

    internal fun ExportPlan.toSourcePlan(): SourcePlan =
        SourcePlan(
            indexDocument = indexDoc,
            chapters = chapters.map { SourceChapter(document = it.doc, depth = it.depth) },
            glossary = glossary,
            appendix = appendix,
            warnings = warnings,
        )

    /** [override], else the `lang` field of `index.md`, else the JVM locale. */
    public fun language(
        plan: SourcePlan,
        override: PdfLanguage?,
    ): PdfLanguage = language(indexDoc = plan.indexDocument, override = override)

    internal fun language(
        indexDoc: OkfDocument?,
        override: PdfLanguage?,
    ): PdfLanguage =
        override
            ?: indexDoc?.frontmatter?.fields?.get("lang")?.let {
                if (it.startsWith("de", ignoreCase = true)) {
                    PdfLanguage.DE
                } else if (it.startsWith("en", ignoreCase = true)) {
                    PdfLanguage.EN
                } else {
                    null
                }
            }
            ?: if (Locale.getDefault().language == "de") PdfLanguage.DE else PdfLanguage.EN

    /** Overrides win over the `index.md` frontmatter; an unparsable date is dropped, never invented. */
    public fun metadata(
        workspace: OkfWorkspace,
        plan: SourcePlan,
        override: ExportMetadata,
    ): SourceMetadata {
        val fm =
            plan.indexDocument
                ?.frontmatter
                ?.fields
                .orEmpty()
        return SourceMetadata(
            title = override.title ?: plan.indexDocument?.frontmatter?.title ?: workspace.root.name,
            subtitle = override.subtitle ?: fm["subtitle"],
            author = override.author ?: fm["author"],
            organization =
                (fm["organization"] ?: fm["organisation"])
                    ?.let { TextSanitizer.clean(text = it, maxLength = 200).replace('\n', ' ').trim() }
                    ?.takeIf { it.isNotEmpty() },
            version = override.version ?: fm["version"],
            date = override.date ?: fm["date"]?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() },
            status = override.status ?: fm["status"],
        )
    }

    /** Title of [document]: frontmatter `title`, else the file name. */
    public fun documentTitle(document: OkfDocument): String =
        document.frontmatter.title?.takeIf { it.isNotBlank() } ?: document.file.nameWithoutExtension

    /** Caption of the ```` ```kuml ```` block [index] of [document]. */
    public fun blockTitle(
        document: OkfDocument,
        index: Int,
    ): String {
        val block = document.kumlBlocks.getOrNull(index)
        return block?.name ?: if (document.kumlBlocks.size > 1) "${documentTitle(document)} (${index + 1})" else documentTitle(document)
    }

    /** Converts documents of one export; links to documents outside [plan] stay plain text. */
    public fun reader(
        workspace: OkfWorkspace,
        plan: SourcePlan,
        language: PdfLanguage,
        limits: ExportLimits = ExportLimits.DEFAULT,
    ): SourceReader =
        SourceReader(
            workspace = workspace,
            inExport = plan.all.map { it.relativePath }.toSet(),
            language = language,
            limits = limits,
        )
}

/** Converts the documents of one export to the neutral model. Create it with [WorkspaceSource.reader]. */
public class SourceReader internal constructor(
    workspace: OkfWorkspace,
    inExport: Set<String>,
    language: PdfLanguage,
    private val limits: ExportLimits,
) {
    private val links =
        InternalLinkResolver(
            allDocuments = workspace.documents,
            inExport = inExport,
            notInExportLabel = PdfLabels(language).notInExport,
        )

    /**
     * Converts [document]. A leading level-1 heading becomes the title and is dropped from the blocks, so a
     * consumer that prints the title as its own heading does not show it twice.
     */
    public fun document(
        document: OkfDocument,
        level: Int,
    ): SourceDocument {
        val anchor = InternalLinkResolver.docAnchor(document.relativePath)
        val converted =
            MarkdownToBlocks.convert(
                doc = document,
                ctx = MarkdownContext(links = links, limits = limits),
                docAnchor = anchor,
            )
        var title = WorkspaceSource.documentTitle(document)
        var blocks = converted.blocks
        val first = blocks.firstOrNull()
        if (first is SourceBlock.Heading && first.level == 1) {
            title = first.text.ifBlank { title }
            blocks = blocks.drop(1)
        }
        return SourceDocument(
            relativePath = document.relativePath,
            title = title,
            level = level,
            anchorId = anchor,
            blocks = blocks,
            diagnostics = converted.diagnostics,
        )
    }
}
