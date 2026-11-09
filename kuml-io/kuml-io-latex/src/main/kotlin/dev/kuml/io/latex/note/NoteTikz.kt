package dev.kuml.io.latex.note

import dev.kuml.core.model.note.NoteKind
import dev.kuml.core.model.note.NoteSpan
import dev.kuml.io.latex.LatexRenderOptions
import dev.kuml.io.latex.escapeNoteText
import dev.kuml.io.latex.fmtCm
import dev.kuml.io.latex.fmtCoord
import dev.kuml.io.latex.tikzId
import dev.kuml.io.latex.uml.UmlEdgeLatexRenderer
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.NodeId
import dev.kuml.layout.Rect
import dev.kuml.layout.bridge.NoteFrameStyle
import dev.kuml.layout.bridge.NoteLayout
import dev.kuml.layout.bridge.StyledLine
import dev.kuml.layout.bridge.note.NoteSymbols
import dev.kuml.layout.bridge.note.NoteTone

/** What to draw for one note: its [id] (the layout node id), the [body] text, its [kind] and the placed [bounds]. */
internal data class NoteTikzSpec(
    val id: String,
    val body: String,
    val kind: NoteKind,
    val bounds: Rect,
    val frame: NoteFrameStyle = NoteFrameStyle.DOG_EAR,
)

/**
 * Line style of a note anchor: [DASHED] with a dot at the anchored end (UML, SysML 2, C4, Service Blueprint),
 * [DOTTED] without a dot (BPMN association).
 */
internal enum class NoteTikzAnchor { DASHED, DOTTED, }

/**
 * TikZ output of note symbols (dog-ear frame, kind symbol, formatted text, anchor lines) for the LaTeX renderer.
 *
 * Geometry comes from [NoteLayout] and the symbol data from [NoteSymbols], the same sources the SVG renderer uses,
 * so a note has the same size, symbol and tone in both outputs (positions differ where the SVG pipeline adds
 * diagram-specific planning). Everything drawn is built from numbers and constants except the note text, and
 * every piece of note text goes through [escapeNoteText] exactly once. Hyperlinks are drawn as underlined text;
 * no URL and no `hyperref` macro ever reaches the output.
 */
internal object NoteTikz {
    private const val WRAP_EPS = 0.5f
    private const val ACCENT_WIDTH = 3f
    private const val ANCHOR_DOT_RADIUS = 2f

    /** Colour name of [tone] (letters only, derived from the enum, never from user text). */
    internal fun colorName(tone: NoteTone): String = "kumlNoteTone" + tone.key.replaceFirstChar { it.uppercaseChar() }

    /**
     * Writes the note styles and the colours of the kinds in use. Called only when at least one note is drawn, so a
     * picture without notes keeps its exact output.
     */
    fun appendNoteStyles(
        sb: StringBuilder,
        indent: String,
        kinds: Collection<NoteKind>,
        association: Boolean = false,
    ) {
        sb.appendLine("$indent% kUML note styles")
        sb.appendLine("$indent\\tikzset{")
        sb.appendLine("$indent  kuml-note/.style={draw=black, fill=white, line width=0.6pt},")
        sb.appendLine("$indent  kuml-note-fold/.style={draw=black, line width=0.6pt},")
        sb.appendLine("$indent  kuml-note-anchor/.style={draw=black, line width=0.6pt, dash pattern=on 4pt off 3pt},")
        if (association) {
            sb.appendLine("$indent  kuml-note-assoc/.style={draw=black, line width=0.6pt, dash pattern=on 1pt off 2pt},")
        }
        sb.appendLine(
            "$indent  kuml-note-text/.style={font=\\sffamily\\fontsize{10pt}{15pt}\\selectfont, anchor=base west, inner sep=0pt, text=black},",
        )
        sb.appendLine("$indent}")
        val tones = kinds.filter { it.hasIcon }.map { NoteSymbols.tone(it) }.toSet()
        for (tone in NoteTone.entries) {
            if (tone !in tones) continue
            val rgb = NoteSymbols.LIGHT_RGB[tone] ?: 0x000000
            sb.appendLine("$indent\\definecolor{${colorName(tone)}}{HTML}{${hex6(rgb)}}")
        }
    }

    private fun hex6(rgb: Int): String = (rgb and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')

    /** Draws one note (named invisible node, frame, accent stripe, symbol, text). */
    fun renderNote(
        spec: NoteTikzSpec,
        options: LatexRenderOptions,
        out: StringBuilder,
        maxWidth: Float = NoteLayout.MAX_W,
    ) {
        val ind = options.indent
        val x = spec.bounds.origin.x
        val y = spec.bounds.origin.y
        val w = spec.bounds.size.width
        val h = spec.bounds.size.height
        val kind = spec.kind
        val frame = spec.frame

        // Invisible node with the box size: anchor lines without a route attach to its border.
        out.appendLine(
            "$ind\\node[inner sep=0pt, outer sep=0pt, draw=none, anchor=north west, " +
                "minimum width=${fmtCoord(w)}pt, minimum height=${fmtCoord(h)}pt] " +
                "(${tikzId(NodeId(spec.id))}) at (${pt(x = x, y = y)}) {};",
        )
        if (frame == NoteFrameStyle.BRACKET) {
            // BPMN text annotation: white area (hides lines behind it) and an open bracket; a kind puts its tone on the bracket.
            val arm = NoteLayout.BRACKET_ARM
            out.appendLine("$ind\\fill[white] (${pt(x = x, y = y)}) rectangle (${pt(x = x + w, y = y + h)});")
            val toneOption = if (kind.hasIcon) ", draw=${colorName(NoteSymbols.tone(kind))}" else ""
            out.appendLine(
                "$ind\\draw[kuml-note-fold$toneOption] (${pt(x = x + arm, y = y)}) -- (${pt(x = x, y = y)}) -- " +
                    "(${pt(x = x, y = y + h)}) -- (${pt(x = x + arm, y = y + h)});",
            )
        } else {
            appendDogEar(x = x, y = y, w = w, h = h, ind = ind, out = out)
        }

        if (kind.hasIcon) {
            val color = colorName(NoteSymbols.tone(kind))
            if (frame == NoteFrameStyle.DOG_EAR) {
                out.appendLine("$ind\\fill[$color] (${pt(x = x, y = y)}) rectangle (${pt(x = x + ACCENT_WIDTH, y = y + h)});")
            }
            appendSymbol(
                kind = kind,
                originX = x + NoteLayout.iconX(frame = frame, kind = kind),
                originY = y + NoteLayout.ICON_Y,
                color = color,
                options = options,
                out = out,
            )
        }

        val inset = NoteLayout.textInset(frame = frame, kind = kind)
        val padding = NoteLayout.horizontalPadding(frame)
        val inner = minOf(w - padding + WRAP_EPS, maxWidth - padding)
        val text = NoteLayout.displayText(body = spec.body, maxInnerWidth = inner, kind = kind)
        val baseline = y + NoteLayout.V_PADDING / 2f + NoteLayout.LINE_H * 0.7f
        val styled = text.styledLines
        if (styled == null) {
            text.lines.forEachIndexed { i, line ->
                if (line.isEmpty()) return@forEachIndexed
                appendTextNode(
                    x = x + inset,
                    y = baseline + NoteLayout.LINE_H * i,
                    content = escapeNoteText(line),
                    options = options,
                    out = out,
                )
            }
        } else {
            styled.forEachIndexed {
                i,
                line,
                ->
                appendStyledLine(line = line, i = i, x = x, inset = inset, baseline = baseline, options = options, out = out)
            }
        }
    }

    private fun appendDogEar(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        ind: String,
        out: StringBuilder,
    ) {
        val ear = NoteLayout.DOG_EAR_SIZE
        out.appendLine(
            "$ind\\draw[kuml-note] (${pt(x = x, y = y)}) -- (${pt(x = x + w - ear, y = y)}) -- (${pt(x = x + w, y = y + ear)}) -- " +
                "(${pt(x = x + w, y = y + h)}) -- (${pt(x = x, y = y + h)}) -- cycle;",
        )
        out.appendLine(
            "$ind\\draw[kuml-note-fold] (${pt(x = x + w - ear, y = y)}) -- (${pt(x = x + w - ear, y = y + ear)}) -- (${pt(
                x =
                    x + w,
                y = y + ear,
            )});",
        )
    }

    private fun appendStyledLine(
        line: StyledLine,
        i: Int,
        x: Float,
        inset: Float,
        baseline: Float,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        if (line.spans.isEmpty() && line.marker == null) return
        val y = baseline + NoteLayout.LINE_H * i
        val marker = line.marker
        if (marker != null) {
            appendTextNode(x = x + inset, y = y, content = escapeNoteText(marker), options = options, out = out)
        }
        if (line.spans.isNotEmpty()) {
            appendTextNode(
                x = x + inset + line.indentPx,
                y = y,
                content = line.spans.joinToString("") { spanTex(it) },
                options = options,
                out = out,
            )
        }
    }

    /** One span as TeX: escaped text wrapped in the style macros of the span; a link is underlined, never a hyperlink. */
    private fun spanTex(span: NoteSpan): String {
        var tex = escapeNoteText(span.text)
        if (span.code) tex = "\\texttt{$tex}"
        if (span.italic) tex = "\\textit{$tex}"
        if (span.bold) tex = "\\textbf{$tex}"
        if (span.href != null) tex = "\\underline{$tex}"
        return tex
    }

    private fun appendTextNode(
        x: Float,
        y: Float,
        content: String,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        out.appendLine("${options.indent}\\node[kuml-note-text] at (${pt(x = x, y = y)}) {$content};")
    }

    /** The kind symbol: its 24-grid polygons scaled to [NoteLayout.ICON_SIZE], filled with the even-odd rule. */
    private fun appendSymbol(
        kind: NoteKind,
        originX: Float,
        originY: Float,
        color: String,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        val scale = NoteLayout.ICON_SIZE / NoteSymbols.GRID
        val tokens =
            NoteSymbols
                .path(kind)
                .trim()
                .split(' ')
                .filter { it.isNotEmpty() }
        val path = StringBuilder()
        var i = 0
        var open = false
        while (i < tokens.size) {
            when (val t = tokens[i]) {
                "M", "L" -> {
                    val px = tokens[i + 1].toFloat()
                    val py = tokens[i + 2].toFloat()
                    val point = "(${pt(x = originX + px * scale, y = originY + py * scale)})"
                    if (t == "M") {
                        if (path.isNotEmpty()) path.append(' ')
                        path.append(point)
                    } else {
                        path.append(" -- ").append(point)
                    }
                    open = true
                    i += 3
                }
                "Z" -> {
                    path.append(" -- cycle")
                    open = false
                    i += 1
                }
                else -> error("Unexpected token '$t' in the symbol path of $kind")
            }
        }
        check(!open) { "Unclosed symbol path of $kind" }
        out.appendLine("${options.indent}\\fill[$color, even odd rule] $path;")
    }

    /** Draws an anchor line along [route]; with [NoteTikzAnchor.DASHED] a dot marks the anchored end. */
    fun renderAnchor(
        route: EdgeRoute,
        style: NoteTikzAnchor,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        UmlEdgeLatexRenderer.render(route = route, style = UmlEdgeLatexRenderer.Style.NOTE_ANCHOR, options = options, out = out)
        if (style == NoteTikzAnchor.DASHED) {
            out.appendLine(
                "${options.indent}\\fill (${pt(x = route.target.x, y = route.target.y)}) circle (${fmtCoord(ANCHOR_DOT_RADIUS)}pt);",
            )
        }
    }

    /** Anchor line between two named nodes, for an anchor the layout carries no route for. */
    fun renderAnchorBetween(
        noteId: String,
        targetId: String,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        out.appendLine("${options.indent}\\draw[kuml-note-anchor] (${tikzId(NodeId(noteId))}) -- (${tikzId(NodeId(targetId))});")
    }

    private val NAME = Regex("[A-Za-z0-9_]+")

    /**
     * Association line between two TikZ names that are already valid (letters, digits, underscore): neither is
     * prefixed again, unlike [renderAnchorBetween]. BPMN flow nodes carry the bare sanitised id as name.
     */
    fun renderAnchorNamed(
        fromName: String,
        toName: String,
        style: NoteTikzAnchor,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        require(NAME.matches(fromName) && NAME.matches(toName)) { "Invalid TikZ node name" }
        val tikzStyle = if (style == NoteTikzAnchor.DOTTED) "kuml-note-assoc" else "kuml-note-anchor"
        out.appendLine("${options.indent}\\draw[$tikzStyle] ($fromName) -- ($toName);")
    }

    /** Dashed anchor line from the point ([fromX], [fromY]) in cm to the east anchor of the named node [toName]. */
    fun renderAnchorCmToEast(
        fromX: Double,
        fromY: Double,
        toName: String,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        require(NAME.matches(toName)) { "Invalid TikZ node name" }
        out.appendLine("${options.indent}\\draw[kuml-note-anchor] (${fmtCm(fromX)},${fmtCm(fromY)}) -- ($toName.east);")
    }

    /**
     * Dashed anchor line from the point ([fromX], [fromY]) in cm to the right edge of the named node [toName], routed
     * through the gap below the node's row: first horizontally out of the note column into the gap of width
     * [leaveCm] (so the vertical part never runs along the border of a stacked note), then vertically to the route
     * height [routeDy] cm below the node's south east, then horizontally under the boxes of the columns in between,
     * then up along a vertical [routeDx] cm right of the node's right edge, and finally horizontally onto the edge:
     * at the east anchor, or at [endLiftCm] above the south east corner when the node is partly covered by a box
     * stacked on top of it (so the end sits on its visible part). The line never crosses the label of another box.
     */
    fun renderAnchorCmRoutedToEast(
        fromX: Double,
        fromY: Double,
        toName: String,
        options: LatexRenderOptions,
        out: StringBuilder,
        leaveCm: Double = 0.2,
        routeDy: Double = 0.15,
        routeDx: Double = 0.0,
        endLiftCm: Double? = null,
    ) {
        require(NAME.matches(toName)) { "Invalid TikZ node name" }
        val dx = if (routeDx > 0.0) "xshift=${fmtCm(routeDx)}cm," else ""
        val end =
            if (endLiftCm == null) {
                "($toName.east)"
            } else {
                "([yshift=${fmtCm(endLiftCm)}cm]$toName.south east)"
            }
        val up =
            if (endLiftCm == null) {
                "([${dx}yshift=0cm]$toName.east)"
            } else {
                "([${dx}yshift=${fmtCm(endLiftCm)}cm]$toName.south east)"
            }
        out.appendLine(
            "${options.indent}\\draw[kuml-note-anchor] (${fmtCm(fromX)},${fmtCm(fromY)}) " +
                "-- (${fmtCm(fromX - leaveCm)},${fmtCm(fromY)}) " +
                "|- ([${dx}yshift=-${fmtCm(routeDy)}cm]$toName.south east) -- $up -- $end;",
        )
    }

    /** The dot that marks the anchored end at the east anchor of the named node [toName] (or [liftCm] above its south east). */
    fun renderAnchorDotEast(
        toName: String,
        options: LatexRenderOptions,
        out: StringBuilder,
        liftCm: Double? = null,
    ) {
        require(NAME.matches(toName)) { "Invalid TikZ node name" }
        val at = if (liftCm == null) "$toName.east" else "[yshift=${fmtCm(liftCm)}cm]$toName.south east"
        out.appendLine("${options.indent}\\fill ($at) circle (${fmtCoord(ANCHOR_DOT_RADIUS)}pt);")
    }

    private fun pt(
        x: Float,
        y: Float,
    ): String = "${fmtCoord(x)}pt, ${fmtCoord(-y)}pt"
}
