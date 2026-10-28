package dev.kuml.workspace.pdf.compose

import dev.kuml.workspace.OkfDocument
import dev.kuml.workspace.OkfFinding
import dev.kuml.workspace.OkfSeverity
import dev.kuml.workspace.OkfWorkspace
import dev.kuml.workspace.pdf.AiNotesProvider
import dev.kuml.workspace.pdf.DiagramArtifact
import dev.kuml.workspace.pdf.PdfExportKind
import dev.kuml.workspace.pdf.PdfExportOptions
import dev.kuml.workspace.pdf.TraceKind
import dev.kuml.workspace.pdf.labels.PdfLabels
import dev.kuml.workspace.pdf.markdown.InternalLinkResolver
import dev.kuml.workspace.pdf.markdown.MarkdownContext
import dev.kuml.workspace.pdf.markdown.MarkdownToBlocks
import dev.kuml.workspace.pdf.model.Block
import dev.kuml.workspace.pdf.model.ComposedDocument
import dev.kuml.workspace.pdf.model.CoverData
import dev.kuml.workspace.pdf.model.Inline
import dev.kuml.workspace.pdf.model.LinkTarget
import dev.kuml.workspace.pdf.model.Section
import dev.kuml.workspace.pdf.plan.ExportPlan
import dev.kuml.workspace.pdf.render.DrawnSvg
import dev.kuml.workspace.pdf.render.SvgGraphicsRenderer
import dev.kuml.workspace.pdf.render.SvgRenderException
import org.apache.pdfbox.pdmodel.PDDocument
import java.time.LocalDate

/** A rendered diagram, cached across output files. */
internal class RenderedDiagram(
    val artifact: DiagramArtifact,
)

internal typealias DiagramKey = Pair<String, Int>

/**
 * Turns an [ExportPlan] plus rendered diagrams into [ComposedDocument]s, one per output PDF. Each call of
 * [composeFor] targets one [PDDocument] because form XObjects and images belong to exactly one document.
 */
internal class DocumentComposer(
    private val ws: OkfWorkspace,
    private val plan: ExportPlan,
    private val options: PdfExportOptions,
    private val labels: PdfLabels,
    private val rendered: Map<DiagramKey, RenderedDiagram>,
    private val aiNotes: AiNotesProvider?,
    private val failures: MutableList<OkfFinding>,
    private val warnings: MutableList<String>,
) {
    private val links =
        InternalLinkResolver(
            allDocuments = ws.documents,
            inExport = plan.all.map { it.relativePath }.toSet(),
            notInExportLabel = labels.notInExport,
        )

    private val reported = HashSet<DiagramKey>()

    private fun docTitle(d: OkfDocument) = d.frontmatter.title?.takeIf { it.isNotBlank() } ?: d.file.nameWithoutExtension

    fun workspaceTitle(): String =
        options.metadata.title
            ?: plan.indexDoc?.frontmatter?.title
            ?: ws.root.name

    // ---- one document -> section -------------------------------------------------------------------------

    private fun docSection(
        d: OkfDocument,
        level: Int,
        doc: PDDocument,
        images: LocalImages,
        drawCache: MutableMap<DiagramKey, DrawnSvg?>,
    ): Section {
        val ctx =
            MarkdownContext(links = links, imageLoader = {
                from,
                rel,
                ->
                images.forMarkdown(from = from, rel = rel)
            }, limits = options.limits)
        val raw = MarkdownToBlocks.convert(doc = d, ctx = ctx, docAnchor = InternalLinkResolver.docAnchor(d.relativePath))
        var title = docTitle(d)
        var blocks = raw
        val first = raw.firstOrNull()
        if (first is Block.Heading && first.level == 1) {
            title = first.text.ifBlank { title }
            blocks = raw.drop(1)
        }
        val resolved = resolveRefs(blocks = blocks, d = d, doc = doc, drawCache = drawCache)
        return Section(title = title, level = level, anchorId = InternalLinkResolver.docAnchor(d.relativePath), blocks = resolved)
    }

    /** Replaces [Block.DiagramRef] placeholders at any nesting depth (list items, quotes, callouts). */
    private fun resolveRefs(
        blocks: List<Block>,
        d: OkfDocument,
        doc: PDDocument,
        drawCache: MutableMap<DiagramKey, DrawnSvg?>,
    ): List<Block> =
        blocks.flatMap { b ->
            when (b) {
                is Block.DiagramRef -> diagramBlocks(d = d, index = b.blockIndex, doc = doc, drawCache = drawCache)
                is Block.ListBlock ->
                    listOf(b.copy(items = b.items.map { item -> resolveRefs(blocks = item, d = d, doc = doc, drawCache = drawCache) }))
                is Block.Quote -> listOf(b.copy(blocks = resolveRefs(blocks = b.blocks, d = d, doc = doc, drawCache = drawCache)))
                else -> listOf(b)
            }
        }

    private fun draw(
        key: DiagramKey,
        svg: String,
        doc: PDDocument,
        drawCache: MutableMap<DiagramKey, DrawnSvg?>,
    ): DrawnSvg? =
        drawCache.getOrPut(key) {
            try {
                SvgGraphicsRenderer.draw(doc = doc, svg = svg)
            } catch (e: SvgRenderException) {
                recordFailure(key = key, reason = e.message ?: "SVG could not be drawn")
                null
            }
        }

    private fun recordFailure(
        key: DiagramKey,
        reason: String,
    ) {
        if (!reported.add(key)) return
        val d = ws.documents.firstOrNull { it.relativePath == key.first }
        failures +=
            OkfFinding(
                code = "OKF-E-007",
                severity = OkfSeverity.ERROR,
                file = key.first,
                line = d?.kumlBlocks?.getOrNull(key.second)?.startLine ?: 0,
                message = "Failed to render kuml block #${key.second + 1}: $reason",
            )
    }

    private fun blockTitle(
        d: OkfDocument,
        index: Int,
    ): String {
        val block = d.kumlBlocks.getOrNull(index)
        return block?.name ?: if (d.kumlBlocks.size > 1) "${docTitle(d)} (${index + 1})" else docTitle(d)
    }

    private fun diagramBlocks(
        d: OkfDocument,
        index: Int,
        doc: PDDocument,
        drawCache: MutableMap<DiagramKey, DrawnSvg?>,
    ): List<Block> {
        val key = d.relativePath to index
        val title = blockTitle(d = d, index = index)
        val art =
            rendered[key]?.artifact ?: run {
                recordFailure(key = key, reason = "not rendered")
                return listOf(Block.DiagramFailed(title = title, reason = "not rendered"))
            }
        if (art is DiagramArtifact.Failed) {
            recordFailure(key = key, reason = art.reason)
            return listOf(Block.DiagramFailed(title = title, reason = art.reason))
        }
        art as DiagramArtifact.Rendered
        val drawn =
            draw(key = key, svg = art.svg, doc = doc, drawCache = drawCache)
                ?: return listOf(Block.DiagramFailed(title = title, reason = "svg"))
        val out =
            mutableListOf<Block>(
                Block.Diagram(
                    title = title,
                    documentPath = d.relativePath,
                    blockIndex = index,
                    line = d.kumlBlocks[index].startLine,
                    drawn = drawn,
                ),
            )
        val facts = art.facts
        if (facts != null && facts.elements.isNotEmpty()) {
            val rows = facts.elements.take(MAX_ELEMENT_ROWS)
            out +=
                Block.Table(
                    header = listOf(labels.kind, labels.name, labels.detail),
                    rows = rows.map { listOf(it.kind, it.name, it.detail.orEmpty()) },
                    title = labels.elements,
                )
            if (facts.elements.size > MAX_ELEMENT_ROWS) out += Block.Note(text = labels.truncatedNote)
        }
        if (options.aiNotes && aiNotes != null) {
            aiNotes.explain(document = d, facts = facts)?.takeIf { it.isNotBlank() }?.let { out += Block.AiDraftBox(text = it) }
        }
        return out
    }

    // ---- cover ----------------------------------------------------------------------------------------------

    private fun cover(
        images: LocalImages,
        openItems: Int?,
    ): CoverData {
        val fm =
            plan.indexDoc
                ?.frontmatter
                ?.fields
                .orEmpty()
        val m = options.metadata
        return CoverData(
            title = workspaceTitle(),
            subtitle = m.subtitle ?: fm["subtitle"],
            author = m.author ?: fm["author"],
            version = m.version ?: fm["version"],
            date = (m.date ?: fm["date"]?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() })?.toString(),
            status = m.status ?: fm["status"],
            logo = fm["logo"]?.takeIf { it.isNotBlank() }?.let { images.logo(it) },
            openItems = openItems?.takeIf { it > 0 },
            openItemsAnchor = if ((openItems ?: 0) > 0) OPEN_ITEMS_ANCHOR else null,
        )
    }

    // ---- outputs ------------------------------------------------------------------------------------------------

    /** One output file: its stem, the single document it covers (if any) and, for a split folio, the block index. */
    internal data class OutputEntry(
        val stem: String,
        val only: OkfDocument?,
        val splitIndex: Int? = null,
    )

    fun outputs(): List<OutputEntry> =
        when (options.kind) {
            PdfExportKind.HANDBOOK -> listOf(OutputEntry(stem = slug(workspaceTitle()), only = null))
            PdfExportKind.PER_DOCUMENT ->
                uniqueStems(
                    plan.all.map { slug(it.relativePath.removeSuffix(".md")) },
                ).zip(plan.all) { stem, d -> OutputEntry(stem = stem, only = d) }
            PdfExportKind.DIAGRAMS ->
                if (options.split) {
                    val entries = diagramEntries()
                    uniqueStems(entries.map { (d, i) -> "${slug(d.relativePath.removeSuffix(".md"))}-${i + 1}" })
                        .zip(entries) { stem, (d, i) -> OutputEntry(stem = stem, only = d, splitIndex = i) }
                } else {
                    listOf(OutputEntry(stem = slug(workspaceTitle()), only = null))
                }
        }

    /**
     * Makes [bases] unique, comparing case-insensitively (macOS/Windows file systems). A candidate that is already
     * taken gets the next free numeric suffix.
     */
    private fun uniqueStems(bases: List<String>): List<String> {
        val taken = HashSet<String>()
        val result = ArrayList<String>(bases.size)
        for (base in bases) {
            var candidate = base
            var n = 1
            while (!taken.add(candidate.lowercase())) {
                n++
                candidate = "$base-$n"
            }
            result += candidate
        }
        return result
    }

    private fun diagramEntries(): List<Pair<OkfDocument, Int>> = plan.all.flatMap { d -> d.kumlBlocks.indices.map { d to it } }

    fun compose(
        stem: String,
        only: OkfDocument?,
        splitIndex: Int?,
        doc: PDDocument,
        images: LocalImages,
    ): ComposedDocument {
        val cache = HashMap<DiagramKey, DrawnSvg?>()
        return when (options.kind) {
            PdfExportKind.HANDBOOK -> handbook(stem = stem, doc = doc, images = images, cache = cache)
            PdfExportKind.PER_DOCUMENT -> {
                val d = only!!
                val s = docSection(d = d, level = 1, doc = doc, images = images, drawCache = cache)
                val headings = s.blocks.count { it is Block.Heading }
                ComposedDocument(
                    fileStem = stem,
                    title = s.title,
                    cover =
                        if (options.cover) {
                            cover(
                                images = images,
                                openItems = null,
                            ).copy(title = s.title, subtitle = null, openItems = null, openItemsAnchor = null)
                        } else {
                            null
                        },
                    tocEnabled = options.toc && headings >= 2,
                    sections = listOf(s),
                )
            }
            PdfExportKind.DIAGRAMS -> folio(stem = stem, only = only, splitIndex = splitIndex, doc = doc, images = images, cache = cache)
        }
    }

    private fun folio(
        stem: String,
        only: OkfDocument?,
        splitIndex: Int?,
        doc: PDDocument,
        images: LocalImages,
        cache: MutableMap<DiagramKey, DrawnSvg?>,
    ): ComposedDocument {
        val entries = diagramEntries().let { all -> if (only != null && splitIndex != null) listOf(only to splitIndex) else all }
        val sections =
            entries.map { (d, i) ->
                val title = blockTitle(d = d, index = i)
                val key = d.relativePath to i
                val art = rendered[key]?.artifact
                val blocks: List<Block> =
                    when (art) {
                        is DiagramArtifact.Rendered ->
                            draw(key = key, svg = art.svg, doc = doc, drawCache = cache)?.let {
                                listOf(
                                    Block.Diagram(
                                        title = title,
                                        documentPath = d.relativePath,
                                        blockIndex = i,
                                        line = d.kumlBlocks[i].startLine,
                                        drawn = it,
                                    ),
                                )
                            } ?: listOf(Block.DiagramFailed(title = title, reason = "svg"))
                        is DiagramArtifact.Failed -> {
                            recordFailure(key = key, reason = art.reason)
                            listOf(Block.DiagramFailed(title = title, reason = art.reason))
                        }
                        null -> {
                            recordFailure(key = key, reason = "not rendered")
                            listOf(Block.DiagramFailed(title = title, reason = "not rendered"))
                        }
                    }
                Section(title = title, level = 1, anchorId = "diagram:${d.relativePath}#$i", blocks = blocks)
            }
        return ComposedDocument(
            fileStem = stem,
            title = workspaceTitle(),
            cover = null,
            tocEnabled = false,
            sections = sections,
            diagramsOnly = true,
        )
    }

    private fun handbook(
        stem: String,
        doc: PDDocument,
        images: LocalImages,
        cache: MutableMap<DiagramKey, DrawnSvg?>,
    ): ComposedDocument {
        val sections = mutableListOf<Section>()
        for (c in plan.chapters) {
            sections +=
                docSection(d = c.doc, level = (c.depth + 1).coerceAtMost(3), doc = doc, images = images, drawCache = cache)
        }

        // Traceability (only when the diagrams carry requirement links)
        val traces =
            plan.all.flatMap { d ->
                d.kumlBlocks.indices
                    .mapNotNull { i ->
                        (rendered[d.relativePath to i]?.artifact as? DiagramArtifact.Rendered)?.facts?.traces
                    }.flatten()
            }
        if (traces.isNotEmpty()) {
            val reqs = LinkedHashSet<String>()
            traces.forEach { reqs += it.targetName }
            val rows =
                reqs.map { r ->
                    listOf(
                        r,
                        traces.filter { it.kind == TraceKind.SATISFY && it.targetName == r }.map { it.sourceName }.distinct().joinToString(
                            ", ",
                        ),
                        traces.filter { it.kind == TraceKind.VERIFY && it.targetName == r }.map { it.sourceName }.distinct().joinToString(
                            ", ",
                        ),
                        traces.filter { it.kind == TraceKind.DERIVE && it.targetName == r }.map { it.sourceName }.distinct().joinToString(
                            ", ",
                        ),
                    )
                }
            sections +=
                Section(
                    title = labels.traceability,
                    level = 1,
                    anchorId = "generated:traceability",
                    blocks =
                        listOf(
                            Block.Table(
                                header = listOf(labels.requirement, labels.satisfiedBy, labels.verifiedBy, labels.derivedFrom),
                                rows = rows,
                            ),
                        ),
                )
        } else {
            warnings += "No requirement diagrams with satisfy/verify/derive links: traceability chapter omitted."
        }

        // Open items
        val open =
            plan.all.flatMap { d ->
                OpenItemsCollector.collect(
                    doc = d,
                    facts =
                        d.kumlBlocks.indices.mapNotNull {
                            (rendered[d.relativePath to it]?.artifact as? DiagramArtifact.Rendered)?.facts
                        },
                    limits = options.limits,
                )
            }
        val titles = ws.documents.associate { it.relativePath to docTitle(it) }
        sections +=
            Section(
                title = labels.openItems,
                level = 1,
                anchorId = OPEN_ITEMS_ANCHOR,
                blocks =
                    if (open.isEmpty()) {
                        listOf(Block.Note(text = "-"))
                    } else {
                        listOf(
                            Block.ListBlock(
                                ordered = false,
                                items =
                                    open.map {
                                        listOf<Block>(
                                            Block.Paragraph(
                                                inlines =
                                                    listOf(
                                                        Inline.Text(text = it.text + " ("),
                                                        Inline.Text(
                                                            text = titles[it.documentPath] ?: it.documentPath,
                                                            link =
                                                                LinkTarget.Internal(
                                                                    anchorId = InternalLinkResolver.docAnchor(it.documentPath),
                                                                ),
                                                        ),
                                                        Inline.Text(text = ")"),
                                                    ),
                                            ),
                                        )
                                    },
                            ),
                        )
                    },
            )

        // Glossary
        if (plan.glossary.isNotEmpty()) {
            if (plan.glossary.size == 1) {
                val g = docSection(d = plan.glossary.single(), level = 1, doc = doc, images = images, drawCache = cache)
                sections += g.copy(title = labels.glossary)
            } else {
                sections += Section(title = labels.glossary, level = 1, anchorId = "generated:glossary", blocks = emptyList())
                plan.glossary.forEach { sections += docSection(d = it, level = 2, doc = doc, images = images, drawCache = cache) }
            }
        }
        if (plan.appendix.isNotEmpty()) {
            sections += Section(title = labels.furtherDocuments, level = 1, anchorId = "generated:appendix", blocks = emptyList())
            plan.appendix.forEach { sections += docSection(d = it, level = 2, doc = doc, images = images, drawCache = cache) }
        }
        return ComposedDocument(
            fileStem = stem,
            title = workspaceTitle(),
            cover = if (options.cover) cover(images = images, openItems = open.size) else null,
            tocEnabled = options.toc,
            sections = sections,
        )
    }

    companion object {
        const val OPEN_ITEMS_ANCHOR = "generated:open-items"
        private const val MAX_ELEMENT_ROWS = 200

        fun slug(raw: String): String =
            raw
                .replace('/', '-')
                .replace(Regex("[^A-Za-z0-9._-]+"), "_")
                .trim('_', '-', '.')
                .ifEmpty { "export" }
                .take(100)
    }
}
