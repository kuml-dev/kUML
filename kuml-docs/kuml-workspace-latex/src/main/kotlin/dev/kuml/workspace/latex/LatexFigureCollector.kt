package dev.kuml.workspace.latex

import dev.kuml.io.latex.LatexDocumentAssembler
import dev.kuml.io.latex.LatexSourceCheck
import dev.kuml.workspace.OkfDocument
import dev.kuml.workspace.pdf.source.WorkspaceSource
import java.util.Locale

/** Renders the diagrams through the port and writes the `\kumlfigure` calls (or visible placeholders). */
internal class LatexFigureCollector(
    private val ctx: WriterContext,
) {
    fun include(
        doc: OkfDocument,
        blockIndex: Int,
        out: StringBuilder,
    ) {
        val block = doc.kumlBlocks.getOrNull(blockIndex) ?: return
        val source = "${doc.relativePath}:${block.startLine}"
        val title = WorkspaceSource.blockTitle(document = doc, index = blockIndex)
        val caption = ctx.text(title)
        ctx.figureNo++
        when (val r = ctx.diagrams.render(document = doc, blockIndex = blockIndex, block = block)) {
            is LatexDiagramOutcome.Rendered -> rendered(r = r, source = source, title = title, caption = caption, out = out)
            is LatexDiagramOutcome.Skipped -> {
                ctx.issue(level = LatexIssueLevel.SKIP, source = source, message = r.reason)
                missing(caption = caption, reason = ctx.labels.excluded, out = out)
            }
            is LatexDiagramOutcome.Failed -> {
                ctx.issue(level = LatexIssueLevel.ERROR, source = source, message = r.reason)
                missing(caption = caption, reason = ctx.labels.notRendered, out = out)
            }
        }
    }

    private fun rendered(
        r: LatexDiagramOutcome.Rendered,
        source: String,
        title: String,
        caption: String,
        out: StringBuilder,
    ) {
        val target = String.format(Locale.ROOT, "figures/f%03d-%s", ctx.figureNo, slugOf(raw = title, fallback = "diagram"))
        check(LatexDocumentAssembler.isSafeInput(target)) { "Unsafe figure path" }
        ctx.files += LatexOutputFile.Text(path = "$target.tex", content = r.tex)
        ctx.families += r.family
        if (r.diagramCount > 1) {
            ctx.issue(
                level = LatexIssueLevel.NOTE,
                source = source,
                message = "${r.diagramCount} diagrams in the block, exported the first",
            )
        }
        val dangling = LatexSourceCheck.undefinedNodeReferences(r.tex)
        if (dangling.isEmpty()) {
            ctx.figureCount++
            out
                .append("\\kumlfigure{")
                .append(target)
                .append("}{")
                .append(caption)
                .append("}\n\n")
        } else {
            ctx.excludedFigureCount++
            val names = dangling.sorted().take(MAX_LISTED_NAMES).joinToString(", ")
            ctx.issue(
                level = LatexIssueLevel.WARN,
                source = source,
                message = "figure excluded from main.tex - undefined TikZ node reference(s): ${LatexEscaper.commentSafe(names)}",
            )
            out.append("% excluded: ").append(target).append(".tex - undefined TikZ node reference(s): ")
            out.append(LatexEscaper.commentSafe(names)).append('\n')
            missing(caption = caption, reason = ctx.labels.excluded, out = out)
        }
    }

    private fun missing(
        caption: String,
        reason: String,
        out: StringBuilder,
    ) {
        out
            .append("\\kumlmissing{")
            .append(caption)
            .append("}{")
            .append(ctx.text(reason))
            .append("}\n\n")
    }

    private companion object {
        const val MAX_LISTED_NAMES = 5
    }
}
