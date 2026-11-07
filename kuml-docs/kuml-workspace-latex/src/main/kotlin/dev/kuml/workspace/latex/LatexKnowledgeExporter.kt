package dev.kuml.workspace.latex

import dev.kuml.io.latex.KumlLatexRenderer
import dev.kuml.io.latex.LatexDocumentAssembler
import dev.kuml.workspace.OkfDocument
import dev.kuml.workspace.OkfWorkspace
import dev.kuml.workspace.pdf.ExportLimits
import dev.kuml.workspace.pdf.PdfExportException
import dev.kuml.workspace.pdf.source.SourcePlan
import dev.kuml.workspace.pdf.source.WorkspaceSource
import java.time.Clock
import java.time.LocalDate

/**
 * Exports a Knowledge workspace as a LaTeX project: `main.tex`, `kuml-preamble.tex`, one TikZ file per diagram
 * under `figures/` and the local images under `images/`.
 *
 * It never runs scripts: diagrams come from the [LatexDiagramSource] port. The reading order, titles, metadata
 * and language come from [WorkspaceSource], the same functions that drive the PDF export, so both agree on
 * documents and order.
 */
public object LatexKnowledgeExporter {
    public fun prepare(
        workspace: OkfWorkspace,
        options: LatexKnowledgeOptions,
        diagrams: LatexDiagramSource,
        clock: Clock = Clock.systemDefaultZone(),
    ): PreparedLatexKnowledge {
        val plan = plan(workspace = workspace, options = options)
        val language = WorkspaceSource.language(plan = plan, override = options.language)
        val ctx =
            WriterContext(
                workspace = workspace,
                language = language,
                labels = LatexLabels(language),
                limits = options.limits,
                diagrams = diagrams,
            )
        val reader =
            WorkspaceSource.reader(
                workspace = workspace,
                plan = plan,
                language = language,
                limits = ExportLimits(maxMarkdownBytesPerDocument = options.limits.maxMarkdownBytesPerDocument),
            )
        val writer = LatexBlockWriter(ctx)
        val body = StringBuilder()
        val order = plan.all
        try {
            writeChapters(plan = plan, reader = reader, writer = writer, body = body)
        } catch (e: java.io.IOException) {
            throw LatexKnowledgeExportException(
                code = LatexKnowledgeExportException.Code.IO,
                message = "I/O error while reading the workspace: ${e.javaClass.simpleName}",
            )
        }
        val metadata = WorkspaceSource.metadata(workspace = workspace, plan = plan, override = options.metadata)
        val main =
            LatexKnowledgeDocument.build(
                generator = options.generator,
                labels = ctx.labels,
                metadata = metadata,
                date = metadata.date ?: LocalDate.now(clock),
                toc = options.toc,
                body = body.toString(),
            )
        val files =
            ctx.files +
                LatexOutputFile.Text(
                    path = KumlLatexRenderer.SHARED_PREAMBLE_FILE,
                    content = KumlLatexRenderer.sharedPreamble(ctx.families),
                ) +
                LatexOutputFile.Text(path = "main.tex", content = main)
        files.forEach { check(LatexDocumentAssembler.isSafeInput(it.path)) { "Unsafe output path" } }
        return PreparedLatexKnowledge(
            files = files,
            issues = ctx.issues,
            documentCount = order.size,
            figureCount = ctx.figureCount,
            excludedFigureCount = ctx.excludedFigureCount,
            imageCount = ctx.imageCount,
            warnings = plan.warnings,
        )
    }

    private fun plan(
        workspace: OkfWorkspace,
        options: LatexKnowledgeOptions,
    ): SourcePlan {
        val plan =
            try {
                WorkspaceSource.plan(workspace = workspace, scope = options.scope)
            } catch (e: PdfExportException) {
                throw LatexKnowledgeExportException(
                    code =
                        when (e.code) {
                            PdfExportException.Code.EMPTY_SCOPE -> LatexKnowledgeExportException.Code.EMPTY_SCOPE
                            else -> LatexKnowledgeExportException.Code.PATH_OUTSIDE_ROOT
                        },
                    message = e.message.orEmpty(),
                )
            }
        val docs = plan.all
        if (docs.size > options.limits.maxDocuments) {
            throw LatexKnowledgeExportException(
                code = LatexKnowledgeExportException.Code.LIMIT_DOCUMENTS,
                message = "Export has ${docs.size} documents; the limit is ${options.limits.maxDocuments}",
            )
        }
        val diagrams = docs.sumOf { it.kumlBlocks.size }
        if (diagrams > options.limits.maxDiagrams) {
            throw LatexKnowledgeExportException(
                code = LatexKnowledgeExportException.Code.LIMIT_DIAGRAMS,
                message = "Export has $diagrams diagrams; the limit is ${options.limits.maxDiagrams}",
            )
        }
        return plan
    }

    private fun writeChapters(
        plan: SourcePlan,
        reader: dev.kuml.workspace.pdf.source.SourceReader,
        writer: LatexBlockWriter,
        body: StringBuilder,
    ) {
        fun write(
            doc: OkfDocument,
            level: Int,
        ) = writer.document(document = doc, source = reader.document(document = doc, level = level), out = body)

        for (c in plan.chapters) write(doc = c.document, level = (c.depth + 1).coerceAtMost(MAX_CHAPTER_LEVEL))
        if (plan.glossary.isNotEmpty()) {
            writer.heading(index = 1, title = writer.labels.glossary, anchorId = null, out = body)
            plan.glossary.forEach { write(doc = it, level = 2) }
        }
        if (plan.appendix.isNotEmpty()) {
            writer.heading(index = 1, title = writer.labels.furtherDocuments, anchorId = null, out = body)
            plan.appendix.forEach { write(doc = it, level = 2) }
        }
    }

    private const val MAX_CHAPTER_LEVEL = 3
}
