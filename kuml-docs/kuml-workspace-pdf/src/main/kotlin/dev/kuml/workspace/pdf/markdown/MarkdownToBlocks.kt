package dev.kuml.workspace.pdf.markdown

import dev.kuml.workspace.OkfDocument
import dev.kuml.workspace.pdf.ExportLimits
import dev.kuml.workspace.pdf.model.Block
import dev.kuml.workspace.pdf.model.Footnote
import dev.kuml.workspace.pdf.model.Inline
import dev.kuml.workspace.pdf.model.LinkTarget
import dev.kuml.workspace.pdf.security.TextSanitizer
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser

/** Everything MarkdownToBlocks needs besides the document: link resolution and local image loading. */
internal class MarkdownContext(
    val links: InternalLinkResolver,
    val imageLoader: (OkfDocument, String) -> Block.ImageBlock?,
    val limits: ExportLimits,
)

/**
 * Converts the body of an OKF document into the PDF [Block] tree using the JetBrains Markdown parser
 * (GFM flavour). Raw HTML is never interpreted (kept as plain text); ```` ```kuml ```` fences become
 * [Block.DiagramRef] placeholders in document order; images are loaded only from the workspace root.
 */
internal object MarkdownToBlocks {
    private const val MAX_LIST_DEPTH = 8
    private const val MAX_TABLE_COLUMNS = 12
    private const val MAX_CELL_CHARS = 2_000
    private const val MAX_CODE_LINES = 2_000
    private val WIKILINK = Regex("""\[\[([^\]|\n]+)(?:\|([^\]\n]+))?]]""")
    private val ATX =
        listOf(
            MarkdownElementTypes.ATX_1,
            MarkdownElementTypes.ATX_2,
            MarkdownElementTypes.ATX_3,
            MarkdownElementTypes.ATX_4,
            MarkdownElementTypes.ATX_5,
            MarkdownElementTypes.ATX_6,
        )

    /** Body text of [doc] (frontmatter removed, wikilinks rewritten to links outside code fences). */
    fun bodyOf(
        doc: OkfDocument,
        limits: ExportLimits,
    ): String {
        val raw = doc.file.readText(Charsets.UTF_8)
        val text = if (raw.length > limits.maxMarkdownBytesPerDocument) raw.take(limits.maxMarkdownBytesPerDocument) else raw
        val lines = text.split('\n')
        val bodyLines = lines.drop((doc.frontmatter.bodyStartLine - 1).coerceAtLeast(0))
        var inFence = false
        return bodyLines.joinToString("\n") { line ->
            if (line.trimStart().startsWith("```")) inFence = !inFence
            if (inFence) {
                line
            } else {
                line.replace(
                    WIKILINK,
                ) { m -> "[${m.groupValues[2].ifEmpty { m.groupValues[1] }}](wikilink:${m.groupValues[1]})" }
            }
        }
    }

    fun convert(
        doc: OkfDocument,
        ctx: MarkdownContext,
        docAnchor: String,
    ): List<Block> {
        val body = bodyOf(doc = doc, limits = ctx.limits)
        val root =
            MarkdownParser(
                GFMFlavourDescriptor(),
                false,
                CancellationToken.NonCancellable,
            ).buildMarkdownTreeFromString(body as CharSequence)
        val state = State(doc = doc, ctx = ctx, source = body, docAnchor = docAnchor)
        val out = mutableListOf<Block>()
        blocks(node = root, state = state, depth = 0, out = out)
        return out
    }

    private class State(
        val doc: OkfDocument,
        val ctx: MarkdownContext,
        val source: String,
        val docAnchor: String,
    ) {
        val slugs = HashMap<String, Int>()
        var footnotes = mutableListOf<Footnote>()
    }

    private fun text(
        node: ASTNode,
        s: State,
    ): String = s.source.substring(node.startOffset, node.endOffset)

    private fun blocks(
        node: ASTNode,
        state: State,
        depth: Int,
        out: MutableList<Block>,
    ) {
        for (child in node.children) {
            val type = child.type
            when {
                type in ATX -> heading(node = child, level = ATX.indexOf(type) + 1, state = state, out = out)
                type == MarkdownElementTypes.SETEXT_1 -> heading(node = child, level = 1, state = state, out = out)
                type == MarkdownElementTypes.SETEXT_2 -> heading(node = child, level = 2, state = state, out = out)
                type == MarkdownElementTypes.PARAGRAPH -> paragraph(node = child, state = state, out = out)
                type == MarkdownElementTypes.UNORDERED_LIST || type == MarkdownElementTypes.ORDERED_LIST ->
                    list(node = child, ordered = type == MarkdownElementTypes.ORDERED_LIST, state = state, depth = depth, out = out)
                type == MarkdownElementTypes.CODE_FENCE -> fence(node = child, state = state, out = out)
                type == MarkdownElementTypes.CODE_BLOCK ->
                    out +=
                        Block.CodeBlock(
                            text = clipCode(text(node = child, s = state).lines().joinToString("\n") { it.removePrefix("    ") }),
                        )
                type == MarkdownElementTypes.BLOCK_QUOTE || type == GFMElementTypes.ALERT ->
                    quote(
                        node = child,
                        state = state,
                        depth = depth,
                        out = out,
                    )
                type == GFMElementTypes.TABLE -> table(node = child, state = state, out = out)
                type == MarkdownElementTypes.HTML_BLOCK ->
                    TextSanitizer.clean(text = text(node = child, s = state)).trim().takeIf { it.isNotEmpty() }?.let {
                        out += Block.Paragraph(inlines = listOf(Inline.Text(text = it)))
                    }
                type == MarkdownElementTypes.MARKDOWN_FILE || type == MarkdownElementTypes.LIST_ITEM ->
                    blocks(node = child, state = state, depth = depth, out = out)
                else -> Unit
            }
        }
    }

    private fun slug(
        text: String,
        s: State,
    ): String {
        val base =
            text
                .lowercase()
                .replace(Regex("[^\\p{L}\\p{N}]+"), "-")
                .trim('-')
                .ifEmpty { "section" }
        val n = s.slugs.merge(base, 1, Int::plus)!!
        return if (n == 1) base else "$base-$n"
    }

    private fun heading(
        node: ASTNode,
        level: Int,
        state: State,
        out: MutableList<Block>,
    ) {
        val content =
            node.children.firstOrNull {
                it.type == MarkdownTokenTypes.ATX_CONTENT || it.type == MarkdownTokenTypes.SETEXT_CONTENT
            }
        val plain =
            content
                ?.let {
                    inlines(node = it, state = state).joinToString("") { i -> (i as? Inline.Text)?.text.orEmpty() }
                }.orEmpty()
                .trim()
        out += Block.Heading(level = level, text = plain, anchorId = "${state.docAnchor}#${slug(text = plain, s = state)}")
    }

    private fun paragraph(
        node: ASTNode,
        state: State,
        out: MutableList<Block>,
    ) {
        // A paragraph that is just one image becomes an image block.
        val only = node.children.singleOrNull { it.type != MarkdownTokenTypes.EOL && it.type != MarkdownTokenTypes.WHITE_SPACE }
        if (only?.type == MarkdownElementTypes.IMAGE) {
            val (alt, dest) = imageParts(node = only, state = state)
            val img = state.ctx.imageLoader(state.doc, dest)
            if (img != null) {
                out += img.copy(alt = alt)
                return
            }
        }
        state.footnotes = mutableListOf()
        val inl = inlines(node = node, state = state)
        if (inl.isNotEmpty()) out += Block.Paragraph(inlines = trim(inl), footnotes = state.footnotes.toList())
        state.footnotes = mutableListOf()
    }

    private fun trim(list: List<Inline>): List<Inline> {
        val l = list.toMutableList()
        (l.firstOrNull() as? Inline.Text)?.let { l[0] = it.copy(text = it.text.trimStart()) }
        (l.lastOrNull() as? Inline.Text)?.let { l[l.lastIndex] = it.copy(text = it.text.trimEnd()) }
        return l
    }

    private fun list(
        node: ASTNode,
        ordered: Boolean,
        state: State,
        depth: Int,
        out: MutableList<Block>,
    ) {
        if (depth >= MAX_LIST_DEPTH) return
        val items = mutableListOf<List<Block>>()
        for (item in node.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }) {
            val itemBlocks = mutableListOf<Block>()
            val checkbox = item.children.firstOrNull { it.type == GFMTokenTypes.CHECK_BOX }?.let { text(node = it, s = state).trim() }
            blocks(node = item, state = state, depth = depth + 1, out = itemBlocks)
            if (checkbox != null) {
                val first = itemBlocks.firstOrNull() as? Block.Paragraph
                val mark = if (checkbox.contains("x", ignoreCase = true)) "[x] " else "[ ] "
                if (first != null) {
                    itemBlocks[0] = first.copy(inlines = listOf<Inline>(Inline.Text(text = mark)) + first.inlines)
                } else {
                    itemBlocks.add(0, Block.Paragraph(inlines = listOf(Inline.Text(text = mark.trim()))))
                }
            }
            items +=
                itemBlocks
                    .ifEmpty { listOf(Block.Paragraph(inlines = listOf(Inline.Text(text = "")))) }
        }
        out += Block.ListBlock(ordered = ordered, items = items, depth = depth)
    }

    private fun clipCode(code: String): String {
        val lines = code.lines()
        return TextSanitizer.clean(text = if (lines.size > MAX_CODE_LINES) lines.take(MAX_CODE_LINES).joinToString("\n") + "\n…" else code)
    }

    private fun fence(
        node: ASTNode,
        state: State,
        out: MutableList<Block>,
    ) {
        val lang =
            node.children
                .firstOrNull { it.type == MarkdownTokenTypes.FENCE_LANG }
                ?.let { text(node = it, s = state).trim() }
                .orEmpty()
        if (lang == "kuml" || lang.startsWith("kuml ") || lang.startsWith("kuml{")) {
            // Match by source line, not by counting: the line-based CodeBlockExtractor and this parser disagree on
            // fences inside blockquotes, longer outer fences and "kuml{...}" info strings.
            val line = fileLineOf(offset = node.startOffset, state = state)
            val index = state.doc.kumlBlocks.indexOfFirst { it.startLine == line }
            if (index >= 0) {
                out += Block.DiagramRef(blockIndex = index)
                return
            }
        }
        val content =
            node.children
                .filter { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT || it.type == MarkdownTokenTypes.EOL }
                .joinToString("") { text(node = it, s = state) }
                .trim('\n')
        out += Block.CodeBlock(text = clipCode(content))
    }

    /** 1-based line of [offset] in the original file (the body starts at the frontmatter's `bodyStartLine`). */
    private fun fileLineOf(
        offset: Int,
        state: State,
    ): Int {
        var newlines = 0
        for (i in 0 until offset.coerceAtMost(state.source.length)) if (state.source[i] == '\n') newlines++
        return (state.doc.frontmatter.bodyStartLine - 1).coerceAtLeast(0) + 1 + newlines
    }

    private fun quote(
        node: ASTNode,
        state: State,
        depth: Int,
        out: MutableList<Block>,
    ) {
        val inner = mutableListOf<Block>()
        blocks(node = node, state = state, depth = depth, out = inner)
        var label: String? = null
        val first = inner.firstOrNull() as? Block.Paragraph
        val firstText = (first?.inlines?.firstOrNull() as? Inline.Text)?.text.orEmpty()
        val callout = Regex("""^\[!([A-Za-z]+)][+-]?\s*""").find(firstText)
        if (callout != null && first != null) {
            label = callout.groupValues[1].lowercase()
            val rest = first.inlines.toMutableList()
            rest[0] = (rest[0] as Inline.Text).copy(text = firstText.removeRange(callout.range))
            inner[0] = first.copy(inlines = rest)
        }
        out += Block.Quote(blocks = inner, label = label)
    }

    private fun table(
        node: ASTNode,
        state: State,
        out: MutableList<Block>,
    ) {
        fun cells(row: ASTNode): List<String> =
            row.children
                .filter { it.type == GFMTokenTypes.CELL }
                .take(MAX_TABLE_COLUMNS)
                .map { c ->
                    TextSanitizer
                        .clean(text = inlines(node = c, state = state).joinToString("") { (it as? Inline.Text)?.text.orEmpty() })
                        .trim()
                        .take(MAX_CELL_CHARS)
                }
        val header =
            node.children
                .firstOrNull { it.type == GFMElementTypes.HEADER }
                ?.let { cells(it) }
                .orEmpty()
        val rows = node.children.filter { it.type == GFMElementTypes.ROW }.map { cells(it) }
        if (header.isNotEmpty()) out += Block.Table(header = header, rows = rows)
    }

    private fun imageParts(
        node: ASTNode,
        state: State,
    ): Pair<String, String> {
        val link = node.children.firstOrNull { it.type == MarkdownElementTypes.INLINE_LINK } ?: return "" to ""
        val alt =
            link.children
                .firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
                ?.let { t ->
                    t.children.filter { it.type != MarkdownTokenTypes.LBRACKET && it.type != MarkdownTokenTypes.RBRACKET }.joinToString(
                        "",
                    ) { text(node = it, s = state) }
                }.orEmpty()
        val dest =
            link.children
                .firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }
                ?.let { text(node = it, s = state) }
                .orEmpty()
        return alt to dest
    }

    // ---- inlines ----------------------------------------------------------------------------------

    private data class Style(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false,
        val link: LinkTarget? = null,
    )

    private fun inlines(
        node: ASTNode,
        state: State,
    ): List<Inline> {
        val out = mutableListOf<Inline>()
        walk(node = node, style = Style(), state = state, out = out)
        return out
    }

    private fun emit(
        out: MutableList<Inline>,
        str: String,
        style: Style,
    ) {
        if (str.isEmpty()) return
        out +=
            Inline.Text(
                text = TextSanitizer.clean(text = str),
                bold = style.bold,
                italic = style.italic,
                code = style.code,
                link = style.link,
            )
    }

    private fun walk(
        node: ASTNode,
        style: Style,
        state: State,
        out: MutableList<Inline>,
    ) {
        for (child in node.children) {
            val t: IElementType = child.type
            when {
                t == MarkdownTokenTypes.EOL -> emit(out = out, str = " ", style = style)
                t == MarkdownTokenTypes.EMPH || t == MarkdownTokenTypes.BACKTICK || t == GFMTokenTypes.TILDE -> Unit
                t == MarkdownElementTypes.STRONG -> walk(node = child, style = style.copy(bold = true), state = state, out = out)
                t == MarkdownElementTypes.EMPH -> walk(node = child, style = style.copy(italic = true), state = state, out = out)
                t == GFMElementTypes.STRIKETHROUGH -> walk(node = child, style = style, state = state, out = out)
                t == MarkdownElementTypes.CODE_SPAN -> walk(node = child, style = style.copy(code = true), state = state, out = out)
                t == MarkdownElementTypes.INLINE_LINK -> link(node = child, style = style, state = state, out = out)
                t == MarkdownElementTypes.IMAGE -> {
                    val (alt, _) = imageParts(node = child, state = state)
                    emit(out = out, str = alt, style = style)
                }
                t == MarkdownElementTypes.AUTOLINK || t == GFMTokenTypes.GFM_AUTOLINK -> {
                    val url = text(node = child, s = state).trim('<', '>')
                    val resolved = state.ctx.links.resolve(from = state.doc, rawTarget = url)
                    val target = (resolved as? ResolvedTarget.Link)?.target
                    emit(out = out, str = url, style = style.copy(link = target))
                }
                t == MarkdownElementTypes.HTML_BLOCK || t == MarkdownTokenTypes.HTML_TAG ->
                    emit(
                        out = out,
                        str = text(node = child, s = state),
                        style = style,
                    )
                t == MarkdownElementTypes.FULL_REFERENCE_LINK ||
                    t == MarkdownElementTypes.SHORT_REFERENCE_LINK ||
                    t == MarkdownElementTypes.LINK_DEFINITION -> emit(out = out, str = text(node = child, s = state), style = style)
                child.children.isEmpty() -> emit(out = out, str = text(node = child, s = state), style = style)
                else -> walk(node = child, style = style, state = state, out = out)
            }
        }
    }

    private fun link(
        node: ASTNode,
        style: Style,
        state: State,
        out: MutableList<Inline>,
    ) {
        val textNode = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
        val dest =
            node.children
                .firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }
                ?.let {
                    text(
                        node = it,
                        s = state,
                    ).trim('<', '>')
                }.orEmpty()
        val resolved = state.ctx.links.resolve(from = state.doc, rawTarget = dest)
        val label = mutableListOf<Inline>()
        if (textNode != null) {
            val sub = mutableListOf<Inline>()
            for (c in textNode.children) {
                if (c.type == MarkdownTokenTypes.LBRACKET || c.type == MarkdownTokenTypes.RBRACKET) continue
                val tmp =
                    org.intellij.markdown.ast
                        .CompositeASTNode(MarkdownElementTypes.PARAGRAPH, listOf(c))
                walk(
                    node = tmp,
                    style = style.copy(link = (resolved as? ResolvedTarget.Link)?.target),
                    state = state,
                    out = sub,
                )
            }
            label += sub
        }
        out += label
        when (resolved) {
            is ResolvedTarget.PlainText -> resolved.suffix?.let { emit(out = out, str = " $it", style = style) }
            is ResolvedTarget.Link -> {
                val tgt = resolved.target
                if (tgt is LinkTarget.External && !tgt.uri.startsWith("mailto:")) {
                    val n = state.footnotes.size + 1
                    state.footnotes += Footnote(number = n, text = TextSanitizer.clean(text = tgt.uri, maxLength = 500))
                    out += Inline.FootnoteRef(number = n)
                }
            }
        }
    }
}
