package dev.kuml.io.latex.uml

import dev.kuml.io.latex.LatexRenderOptions
import dev.kuml.io.latex.escapeLatex
import dev.kuml.io.latex.fmtCoord
import dev.kuml.io.latex.tikzId
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlClassifier
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.Visibility

/**
 * Emits the TikZ source for a single UML classifier — the three-compartment
 * class box that everyone recognises from textbooks: stereotype + name on
 * top, attributes in the middle, operations at the bottom.
 *
 * Layout-faithful: the outer rectangle lands at exactly the bounds the
 * layout engine produced (scaled). The internal compartment heights are
 * derived from the content — three equal thirds for full classes, halves
 * for interfaces with no attributes, etc. The MVP doesn't measure text,
 * so very long member names may overflow the box; that's a known
 * limitation, fixed in V2.x with a TeX-side `minimum width=…` hint.
 *
 * The layout's TOP-LEFT origin is the anchor point — we emit
 * `\node[anchor=north west]` so TikZ places the box at the same pixel
 * coords the SVG renderer uses.
 */
internal object UmlClassLatexRenderer {
    fun render(
        classifier: UmlClassifier,
        nodeId: NodeId,
        layout: NodeLayout,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        when (classifier) {
            is UmlClass -> renderClass(classifier, nodeId, layout, options, out)
            is UmlInterface -> renderInterface(classifier, nodeId, layout, options, out)
            is UmlEnumeration -> renderEnum(classifier, nodeId, layout, options, out)
            else -> renderFallback(nodeId, layout, options, out, label = classifier.name)
        }
    }

    /** Empty rectangle with a single centred label — used for unsupported types. */
    fun renderFallback(
        nodeId: NodeId,
        layout: NodeLayout,
        options: LatexRenderOptions,
        out: StringBuilder,
        label: String,
    ) {
        val (x, y) = topLeft(layout)
        val w = layout.bounds.size.width
        val h = layout.bounds.size.height
        with(out) {
            appendLine(
                "${options.indent}\\node[kuml-class, anchor=north west, " +
                    "minimum width=${fmtCoord(w)}pt, minimum height=${fmtCoord(h)}pt] " +
                    "(${tikzId(nodeId)}) at (${fmtCoord(x)}pt, ${fmtCoord(-y)}pt) {};",
            )
            appendLine(
                "${options.indent}\\node[kuml-classname] at (${tikzId(nodeId)}.center) {${escapeLatex(label)}};",
            )
        }
    }

    private fun renderClass(
        cls: UmlClass,
        nodeId: NodeId,
        layout: NodeLayout,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        val attrs = cls.attributes
        val ops = cls.operations
        val hasAttrs = attrs.isNotEmpty()
        val hasOps = ops.isNotEmpty()
        renderCompartmented(
            nodeId = nodeId,
            layout = layout,
            options = options,
            out = out,
            stereotypes = cls.stereotypes,
            headerName = cls.name,
            headerIsAbstract = cls.isAbstract,
            attributes = attrs.map(::formatAttribute),
            operations = ops.map(::formatOperation),
            showAttributesCompartment = hasAttrs,
            showOperationsCompartment = hasOps,
        )
    }

    private fun renderInterface(
        iface: UmlInterface,
        nodeId: NodeId,
        layout: NodeLayout,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        val ops = iface.operations
        renderCompartmented(
            nodeId = nodeId,
            layout = layout,
            options = options,
            out = out,
            stereotypes = listOf("interface") + iface.stereotypes,
            headerName = iface.name,
            headerIsAbstract = false,
            attributes = iface.attributes.map(::formatAttribute),
            operations = ops.map(::formatOperation),
            showAttributesCompartment = iface.attributes.isNotEmpty(),
            showOperationsCompartment = ops.isNotEmpty(),
        )
    }

    private fun renderEnum(
        enum: UmlEnumeration,
        nodeId: NodeId,
        layout: NodeLayout,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        renderCompartmented(
            nodeId = nodeId,
            layout = layout,
            options = options,
            out = out,
            stereotypes = listOf("enumeration") + enum.stereotypes,
            headerName = enum.name,
            headerIsAbstract = false,
            attributes = enum.literals.map { it.name },
            operations = emptyList(),
            showAttributesCompartment = enum.literals.isNotEmpty(),
            showOperationsCompartment = false,
        )
    }

    /**
     * Generic compartmented classifier emitter — drives all three classifier
     * variants. The compartment count adapts: 1 (header only), 2 (header +
     * one body), or 3 (full UML class box).
     */
    @Suppress("LongParameterList")
    private fun renderCompartmented(
        nodeId: NodeId,
        layout: NodeLayout,
        options: LatexRenderOptions,
        out: StringBuilder,
        stereotypes: List<String>,
        headerName: String,
        headerIsAbstract: Boolean,
        attributes: List<String>,
        operations: List<String>,
        showAttributesCompartment: Boolean,
        showOperationsCompartment: Boolean,
    ) {
        val (x, y) = topLeft(layout)
        val w = layout.bounds.size.width
        val h = layout.bounds.size.height
        val nodeName = tikzId(nodeId)

        // Outer frame. Layout Y points DOWN; TikZ Y points UP. We negate Y
        // on emission so the box anchors at the same visual top-left.
        with(out) {
            appendLine(
                "${options.indent}\\node[kuml-class, anchor=north west, " +
                    "minimum width=${fmtCoord(w)}pt, minimum height=${fmtCoord(h)}pt] " +
                    "($nodeName) at (${fmtCoord(x)}pt, ${fmtCoord(-y)}pt) {};",
            )
        }

        // Compartment heights — proportional, sums to h.
        val compartments = mutableListOf<Float>()
        compartments += HEADER_RATIO
        if (showAttributesCompartment) compartments += ATTR_RATIO
        if (showOperationsCompartment) compartments += OP_RATIO
        val total = compartments.sum()
        val heights = compartments.map { h * (it / total) }

        // Track running top-y in TikZ coords (y points DOWN in layout, so we
        // subtract going down the box).
        var cursorY = y
        val nameLine = formatClassHeader(stereotypes, headerName)

        // Header compartment.
        val headerH = heights[0]
        appendCompartmentLabel(
            out,
            options,
            cx = x + w / 2f,
            cyTop = cursorY,
            height = headerH,
            text = nameLine,
            style = if (headerIsAbstract) "kuml-classname-abstract" else "kuml-classname",
        )
        cursorY += headerH

        // Divider after header (if there's a body).
        var heightIdx = 1
        if (showAttributesCompartment) {
            appendDivider(out, options, x, cursorY, w)
            val ch = heights[heightIdx++]
            appendCompartmentText(out, options, x, cursorY, w, ch, attributes)
            cursorY += ch
        }
        if (showOperationsCompartment) {
            appendDivider(out, options, x, cursorY, w)
            val ch = heights[heightIdx]
            appendCompartmentText(out, options, x, cursorY, w, ch, operations)
        }
    }

    // ─── Compartment helpers ─────────────────────────────────────────────────

    private fun appendCompartmentLabel(
        out: StringBuilder,
        options: LatexRenderOptions,
        cx: Float,
        cyTop: Float,
        height: Float,
        text: String,
        style: String,
    ) {
        // Centre the label inside the compartment band.
        val cy = cyTop + height / 2f
        out.appendLine(
            "${options.indent}\\node[$style, anchor=center] at " +
                "(${fmtCoord(cx)}pt, ${fmtCoord(-cy)}pt) {$text};",
        )
    }

    private fun appendCompartmentText(
        out: StringBuilder,
        options: LatexRenderOptions,
        x: Float,
        yTop: Float,
        w: Float,
        h: Float,
        lines: List<String>,
    ) {
        if (lines.isEmpty()) return
        // Stack lines top-to-bottom with vertical centring inside the band.
        val lineHeight = h / (lines.size + 1).coerceAtLeast(2)
        for ((i, line) in lines.withIndex()) {
            val ly = yTop + lineHeight * (i + 1)
            out.appendLine(
                "${options.indent}\\node[kuml-feature, anchor=west] at " +
                    "(${fmtCoord(x + INNER_PAD)}pt, ${fmtCoord(-ly)}pt) {${escapeLatex(line)}};",
            )
        }
        // (Width `w` is currently used only by the frame; kept on the
        // signature so the geometry stays explicit and refactors are local.)
        @Suppress("UNUSED_PARAMETER")
        val ignored = w
    }

    private fun appendDivider(
        out: StringBuilder,
        options: LatexRenderOptions,
        x: Float,
        y: Float,
        w: Float,
    ) {
        out.appendLine(
            "${options.indent}\\draw[line width=0.4pt] " +
                "(${fmtCoord(x)}pt, ${fmtCoord(-y)}pt) -- " +
                "(${fmtCoord(x + w)}pt, ${fmtCoord(-y)}pt);",
        )
    }

    // ─── Member formatting ───────────────────────────────────────────────────

    private fun formatClassHeader(
        stereotypes: List<String>,
        name: String,
    ): String {
        // Stereotypes go on a single line above the name, both inside one
        // `\node` so vertical centring is one anchor instead of two.
        val name0 = escapeLatex(name)
        if (stereotypes.isEmpty()) return name0
        val stereo =
            stereotypes.joinToString(" ") {
                "\\guillemotleft{}${escapeLatex(it)}\\guillemotright{}"
            }
        return "\\begin{tabular}{c}\\textit{\\small $stereo}\\\\\\textbf{$name0}\\end{tabular}"
    }

    private fun formatAttribute(p: UmlProperty): String {
        val visibility = visibilityGlyph(p.visibility)
        val type = p.type.name
        return "$visibility ${p.name} : $type"
    }

    private fun formatOperation(op: UmlOperation): String {
        val visibility = visibilityGlyph(op.visibility)
        val params = op.parameters.joinToString(", ") { "${it.name}: ${it.type.name}" }
        val ret = op.returnType?.let { " : ${it.name}" } ?: ""
        return "$visibility ${op.name}($params)$ret"
    }

    private fun visibilityGlyph(v: Visibility): String =
        when (v) {
            Visibility.PUBLIC -> "+"
            Visibility.PRIVATE -> "-"
            Visibility.PROTECTED -> "#"
            Visibility.PACKAGE -> "~"
        }

    // ─── Coordinate helpers ──────────────────────────────────────────────────

    private fun topLeft(layout: NodeLayout): Pair<Float, Float> {
        // Layout origin is top-left of the bounds box. TikZ Y points up, so
        // we render at (x, -y) when emitting — but we return the layout-space
        // origin here so the rest of the code can do arithmetic in layout coords.
        return layout.bounds.origin.x to layout.bounds.origin.y
    }

    private const val INNER_PAD: Float = 6f
    private const val HEADER_RATIO: Float = 1.0f
    private const val ATTR_RATIO: Float = 1.0f
    private const val OP_RATIO: Float = 1.0f
}
