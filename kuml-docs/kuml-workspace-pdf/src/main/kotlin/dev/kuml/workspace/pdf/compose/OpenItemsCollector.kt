package dev.kuml.workspace.pdf.compose

import dev.kuml.workspace.OkfDocument
import dev.kuml.workspace.pdf.DiagramFacts
import dev.kuml.workspace.pdf.ExportLimits
import dev.kuml.workspace.pdf.markdown.MarkdownToBlocks

internal data class OpenItem(
    val text: String,
    val documentPath: String,
)

/**
 * Finds open items with fixed, documented rules: unchecked tasks, `TODO:`/`FIXME:`/`OFFEN:`/`OPEN:` markers
 * outside code, `[!todo]`/`[!question]` callouts, documents with status open/draft, and open model notes.
 */
internal object OpenItemsCollector {
    private val TASK = Regex("""^\s*[-*+]\s+\[ ]\s+(.*)$""")
    private val MARKER = Regex("""\b(TODO|FIXME|OFFEN|OPEN):\s*(.*)$""")
    private val CALLOUT = Regex("""^\s*>\s*\[!(todo|question)][+-]?\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val OPEN_STATUS = setOf("offen", "open", "draft", "entwurf")
    private const val MAX_TEXT = 300

    fun collect(
        doc: OkfDocument,
        facts: List<DiagramFacts>,
        limits: ExportLimits,
    ): List<OpenItem> {
        val out = mutableListOf<OpenItem>()

        fun add(t: String) {
            val clean = t.trim().take(MAX_TEXT)
            out +=
                OpenItem(text = clean.ifEmpty { doc.frontmatter.title ?: doc.file.nameWithoutExtension }, documentPath = doc.relativePath)
        }
        doc.frontmatter.fields["status"]?.trim()?.lowercase()?.takeIf { it in OPEN_STATUS }?.let {
            add("Status: $it")
        }
        var inFence = false
        for (line in MarkdownToBlocks.bodyOf(doc = doc, limits = limits).lines()) {
            if (line.trimStart().startsWith("```")) {
                inFence = !inFence
                continue
            }
            if (inFence) continue
            val task = TASK.find(line)
            val callout = CALLOUT.find(line)
            val marker = MARKER.find(line)
            when {
                task != null -> add(task.groupValues[1])
                callout != null -> add(callout.groupValues[2].ifEmpty { callout.groupValues[1] })
                marker != null -> add("${marker.groupValues[1]}: ${marker.groupValues[2]}")
            }
        }
        facts.flatMap { it.notes }.filter { it.open }.forEach { add(it.text) }
        return out
    }
}
