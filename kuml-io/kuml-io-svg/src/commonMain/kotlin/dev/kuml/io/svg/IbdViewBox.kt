@file:Suppress("RequireNamedArguments")

package dev.kuml.io.svg

import dev.kuml.layout.LayoutResult
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.Sysml2LayoutBridge

/**
 * View-box fitting for SysML 2 IBD SVGs (PAL wave 2C-b1).
 *
 * Port and connector labels may reach beyond the canvas the layout engine reported. Instead of clipping them, the
 * IBD view box grows to the union of the canvas, all nodes, groups and edge points and every `<text>` (measured with
 * [measure], the same width estimate the layout uses for port labels), plus a [MARGIN_PX] margin on each side that had
 * to grow. The origin may become negative; [SvgDocument] moves background, frame and watermark with it.
 */
internal object IbdViewBox {
    /** Margin (px) added on every side the view box had to grow. */
    const val MARGIN_PX = 16f

    private const val DEFAULT_FONT_PX = 12f
    private const val TOLERANCE_PX = 0.5f

    /** A measured `<text>` element in SVG user space. */
    data class TextBox(
        val rect: Rect,
        val content: String,
    )

    /** Width of [text] at [fontPx], the estimate also used by the layout for port labels. */
    fun measure(
        text: String,
        fontPx: Float,
    ): Float = Sysml2LayoutBridge.estimateLabelSize(text = text, fontPx = fontPx).width

    /**
     * Returns the view box that contains the canvas of [svg], [layout] (shifted by [padding] and [shiftX]) and every
     * text of [svg], or null if the canvas already contains all of it. The bottom [watermarkBand] px of the root view
     * box are watermark chrome, not free canvas: content must end above it, and when the view box has to grow the band
     * is kept below the grown content (the returned view box includes it).
     */
    fun fit(
        svg: String,
        layout: LayoutResult,
        padding: Float,
        shiftX: Float = 0f,
        watermarkBand: Float = 0f,
        measure: (String, Float) -> Float = ::measure,
    ): Rect? {
        val vb = rootViewBox(svg) ?: return null
        // The watermark itself lives in the band and is chrome, not content.
        val texts =
            textBoxes(svg = svg, measure = measure).let { all ->
                if (watermarkBand > 0f) all.filter { it.content != SvgDocument.WATERMARK_TEXT } else all
            }
        val bounds = contentBounds(layout = layout, padding = padding, texts = texts, shiftX = shiftX)
        val l = vb.origin.x
        val t = vb.origin.y
        val r = l + vb.size.width
        val b = t + vb.size.height - watermarkBand
        val nl = if (bounds.minX < l - TOLERANCE_PX) bounds.minX - MARGIN_PX else l
        val nt = if (bounds.minY < t - TOLERANCE_PX) bounds.minY - MARGIN_PX else t
        val nr = if (bounds.maxX > r + TOLERANCE_PX) bounds.maxX + MARGIN_PX else r
        val nb = if (bounds.maxY > b + TOLERANCE_PX) bounds.maxY + MARGIN_PX else b
        if (nl == l && nt == t && nr == r && nb == b) return null
        return Rect(origin = Point(x = nl, y = nt), size = Size(width = nr - nl, height = nb + watermarkBand - nt))
    }

    /** Extent of everything drawn, in SVG user space. */
    data class Bounds(
        val minX: Float,
        val minY: Float,
        val maxX: Float,
        val maxY: Float,
    )

    /** Union of node, group and edge points (shifted into SVG space) and all [texts]. */
    fun contentBounds(
        layout: LayoutResult,
        padding: Float,
        texts: List<TextBox>,
        shiftX: Float = 0f,
    ): Bounds {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE

        fun add(
            x: Float,
            y: Float,
        ) {
            minX = minOf(minX, x)
            minY = minOf(minY, y)
            maxX = maxOf(maxX, x)
            maxY = maxOf(maxY, y)
        }

        fun addRect(r: Rect) {
            add(r.origin.x + padding + shiftX, r.origin.y + padding)
            add(r.origin.x + r.size.width + padding + shiftX, r.origin.y + r.size.height + padding)
        }
        for (n in layout.nodes.values) addRect(n.bounds)
        for (g in layout.groups.values) addRect(g.bounds)
        for (e in layout.edges.values) {
            val pts =
                when (e) {
                    is dev.kuml.layout.EdgeRoute.Direct -> listOf(e.source, e.target)
                    is dev.kuml.layout.EdgeRoute.OrthogonalRounded -> listOf(e.source) + e.waypoints + listOf(e.target)
                    is dev.kuml.layout.EdgeRoute.TreeRounded -> listOf(e.source) + e.waypoints + listOf(e.target)
                    is dev.kuml.layout.EdgeRoute.Bezier -> listOf(e.source) + e.controlPoints + listOf(e.target)
                }
            for (p in pts) add(p.x + padding + shiftX, p.y + padding)
        }
        for (t in texts) {
            add(t.rect.origin.x, t.rect.origin.y)
            add(t.rect.origin.x + t.rect.size.width, t.rect.origin.y + t.rect.size.height)
        }
        if (minX > maxX) return Bounds(0f, 0f, 0f, 0f)
        return Bounds(minX, minY, maxX, maxY)
    }

    private val rootViewBoxRegex = Regex("""<svg\b[^>]*?viewBox="([^"]+)"""")
    private val numberRegex = Regex("""-?\d+(?:\.\d+)?""")

    /** The `viewBox` of the root `<svg>` element, or null. */
    fun rootViewBox(svg: String): Rect? {
        val m = rootViewBoxRegex.find(svg) ?: return null
        val n = numberRegex.findAll(m.groupValues[1]).map { it.value.toFloat() }.toList()
        if (n.size < 4) return null
        return Rect(origin = Point(x = n[0], y = n[1]), size = Size(width = n[2], height = n[3]))
    }

    private val tagRegex = Regex("""<(/?)([A-Za-z][A-Za-z0-9]*)([^>]*?)(/?)>""")
    private val cssRuleRegex = Regex("""\.([A-Za-z0-9_-]+)\s*\{[^}]*?font-size:\s*([0-9.]+)px""")
    private val translateRegex = Regex("""translate\(\s*(-?[0-9.]+)[\s,]+(-?[0-9.]+)\s*\)""")

    private fun attr(
        attrs: String,
        name: String,
    ): String? = Regex("""(?:^|\s)$name="([^"]*)"""").find(attrs)?.groupValues?.get(1)

    private fun unescape(s: String): String =
        s
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")

    /**
     * Boxes of all `<text>` elements of [svg] in SVG user space. `translate(...)` transforms of enclosing groups are
     * applied; the font size comes from the `font-size` attribute or the first CSS class with a font size. The box
     * runs from one font size above the baseline `y` to 0.2 font sizes below it (height 1.2 × font size) and is
     * placed according to `text-anchor`.
     */
    fun textBoxes(
        svg: String,
        measure: (String, Float) -> Float = ::measure,
    ): List<TextBox> {
        val classFont = HashMap<String, Float>()
        for (m in cssRuleRegex.findAll(svg)) classFont.getOrPut(m.groupValues[1]) { m.groupValues[2].toFloat() }
        val out = ArrayList<TextBox>()
        val stack = ArrayList<Pair<Float, Float>>()
        stack += 0f to 0f
        var pos = 0
        while (true) {
            val m = tagRegex.find(svg, pos) ?: break
            pos = m.range.last + 1
            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2]
            val attrs = m.groupValues[3]
            val selfClosing = m.groupValues[4] == "/"
            if (name == "g") {
                if (closing) {
                    if (stack.size > 1) stack.removeAt(stack.size - 1)
                } else if (!selfClosing) {
                    val tr = translateRegex.find(attr(attrs, "transform") ?: "")
                    val (px, py) = stack.last()
                    stack += if (tr != null) (px + tr.groupValues[1].toFloat()) to (py + tr.groupValues[2].toFloat()) else (px to py)
                }
            } else if (name == "text" && !closing && !selfClosing) {
                val end = svg.indexOf("</text>", pos)
                if (end < 0) break
                val content = unescape(svg.substring(pos, end).replace(Regex("<[^>]*>"), "")).trim()
                pos = end + "</text>".length
                if (content.isEmpty()) continue
                val x = (attr(attrs, "x")?.toFloatOrNull() ?: 0f) + stack.last().first
                val y = (attr(attrs, "y")?.toFloatOrNull() ?: 0f) + stack.last().second
                val font =
                    attr(attrs, "font-size")?.removeSuffix("px")?.toFloatOrNull()
                        ?: (attr(attrs, "class") ?: "").split(' ').firstNotNullOfOrNull { classFont[it] }
                        ?: DEFAULT_FONT_PX
                val w = measure(content, font)
                val left =
                    when (attr(attrs, "text-anchor")) {
                        "middle" -> x - w / 2f
                        "end" -> x - w
                        else -> x
                    }
                out +=
                    TextBox(
                        rect = Rect(origin = Point(x = left, y = y - font), size = Size(width = w, height = 1.2f * font)),
                        content = content,
                    )
            }
        }
        return out
    }
}
