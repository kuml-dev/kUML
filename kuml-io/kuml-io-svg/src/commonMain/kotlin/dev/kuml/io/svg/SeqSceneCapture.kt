package dev.kuml.io.svg

import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.note.SeqNoteScene
import dev.kuml.layout.bridge.note.SeqStem
import kotlin.math.max
import kotlin.math.min

/**
 * What a sequence-diagram draw function put on the canvas, read back from the markup it produced (Nacharbeit 39).
 *
 * The note placement must avoid exactly what is drawn. Instead of re-deriving the geometry of fragment frames, tags,
 * guards, activation bars, `ref` frames and message paths a second time (which would drift away from the draw
 * functions), the draw functions themselves are run into a scratch [SvgBuilder] and their output is parsed here. Only
 * the width of a text is estimated ([TEXT_CHAR_PX] per character).
 */
internal sealed interface SeqPrim {
    /** The id of the innermost enclosing `<g id=...>`, if any. */
    val groupId: String?

    data class Box(
        val rect: Rect,
        val dashed: Boolean,
        override val groupId: String?,
    ) : SeqPrim

    data class Path(
        val pts: List<Point>,
        val closed: Boolean,
        val dashed: Boolean,
        override val groupId: String?,
    ) : SeqPrim

    data class Text(
        val rect: Rect,
        override val groupId: String?,
    ) : SeqPrim
}

internal object SeqSceneCapture {
    /** Estimated width of one character of a label (the sequence label font is about 12 px). */
    const val TEXT_CHAR_PX: Float = 7.2f

    /** Estimated text height around the baseline. */
    private const val TEXT_ASCENT: Float = 12f
    private const val TEXT_DESCENT: Float = 4f

    private val tagRe = Regex("<(/?)([a-zA-Z]+)([^>]*?)(/?)>")
    private val attrRe = Regex("([\\w:-]+)=\"([^\"]*)\"")
    private val numRe = Regex("-?\\d+(?:\\.\\d+)?(?:[eE]-?\\d+)?")
    private val translateRe = Regex("translate\\(\\s*(-?[\\d.]+)[ ,]+(-?[\\d.]+)\\s*\\)")

    /** Runs [draw] into a scratch builder and returns the primitives it drew, in layout coordinates. */
    fun capture(draw: (SvgBuilder) -> Unit): List<SeqPrim> {
        val b = SvgBuilder(pretty = false)
        draw(b)
        return parse(b.toString())
    }

    fun parse(svg: String): List<SeqPrim> {
        val out = ArrayList<SeqPrim>()

        class Frame(
            val tag: String,
            val dx: Float,
            val dy: Float,
            val id: String?,
        )

        val stack = ArrayList<Frame>()
        var pos = 0
        while (true) {
            val m = tagRe.find(svg, pos) ?: break
            pos = m.range.last + 1
            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2]
            val attrs = attrRe.findAll(m.groupValues[3]).associate { it.groupValues[1] to it.groupValues[2] }
            val selfClosing = m.groupValues[4] == "/"
            if (closing) {
                if (stack.isNotEmpty() && stack.last().tag == name) stack.removeAt(stack.size - 1)
                continue
            }
            val dx = stack.sumOf { it.dx.toDouble() }.toFloat()
            val dy = stack.sumOf { it.dy.toDouble() }.toFloat()
            val gid = stack.lastOrNull { it.id != null }?.id
            val dashed = attrs["stroke-dasharray"] != null

            fun f(key: String): Float = attrs[key]?.toFloatOrNull() ?: 0f
            when (name) {
                "g" -> {
                    val t = attrs["transform"]?.let { translateRe.find(it) }
                    val frame =
                        Frame(
                            tag = "g",
                            dx = t?.groupValues?.get(1)?.toFloatOrNull() ?: 0f,
                            dy = t?.groupValues?.get(2)?.toFloatOrNull() ?: 0f,
                            id = attrs["id"],
                        )
                    if (!selfClosing) stack += frame
                }
                "rect" ->
                    out +=
                        SeqPrim.Box(
                            rect =
                                Rect(
                                    origin = Point(x = f("x") + dx, y = f("y") + dy),
                                    size = Size(width = f("width"), height = f("height")),
                                ),
                            dashed = dashed,
                            groupId = gid,
                        )
                "line" ->
                    out +=
                        SeqPrim.Path(
                            pts = listOf(Point(x = f("x1") + dx, y = f("y1") + dy), Point(x = f("x2") + dx, y = f("y2") + dy)),
                            closed = false,
                            dashed = dashed,
                            groupId = gid,
                        )
                "polygon", "polyline" -> {
                    val nums = numRe.findAll(attrs["points"] ?: "").map { it.value.toFloat() }.toList()
                    val pts = nums.chunked(2).filter { it.size == 2 }.map { Point(x = it[0] + dx, y = it[1] + dy) }
                    if (pts.size >= 2) out += SeqPrim.Path(pts = pts, closed = name == "polygon", dashed = dashed, groupId = gid)
                }
                "path" -> {
                    val pts = pathPoints(d = attrs["d"] ?: "").map { Point(x = it.x + dx, y = it.y + dy) }
                    if (pts.size >= 2) out += SeqPrim.Path(pts = pts, closed = false, dashed = dashed, groupId = gid)
                }
                "text" -> {
                    val close = svg.indexOf("</text>", pos)
                    if (close > 0 && !selfClosing) {
                        val content =
                            svg
                                .substring(pos, close)
                                .replace(Regex("<[^>]*>"), "")
                                .replace(Regex("\\s+"), " ")
                                .trim()
                        pos = close + "</text>".length
                        val w = content.length * TEXT_CHAR_PX
                        val x = f("x") + dx
                        val y = f("y") + dy
                        val left =
                            when (attrs["text-anchor"]) {
                                "middle" -> x - w / 2f
                                "end" -> x - w
                                else -> x
                            }
                        out +=
                            SeqPrim.Text(
                                rect =
                                    Rect(
                                        origin = Point(x = left, y = y - TEXT_ASCENT),
                                        size =
                                            Size(
                                                width = w,
                                                height =
                                                    TEXT_ASCENT + TEXT_DESCENT,
                                            ),
                                    ),
                                groupId = gid,
                            )
                    }
                }
            }
        }
        return out
    }

    /** The end points of the commands of a path (M, L, H, V, Q, C; relative commands are not drawn by the sequence code). */
    private fun pathPoints(d: String): List<Point> {
        val pts = ArrayList<Point>()
        val tokens = Regex("[MLHVQCZmlhvqcz]|-?\\d+(?:\\.\\d+)?").findAll(d).map { it.value }.toList()
        var cmd = 'M'
        var i = 0
        var cx = 0f
        var cy = 0f
        val nums = ArrayList<Float>()

        fun flush() {
            when (cmd) {
                'M', 'L' ->
                    nums.chunked(2).filter { it.size == 2 }.forEach {
                        cx = it[0]
                        cy = it[1]
                        pts += Point(x = cx, y = cy)
                    }
                'H' ->
                    nums.forEach {
                        cx = it
                        pts += Point(x = cx, y = cy)
                    }
                'V' ->
                    nums.forEach {
                        cy = it
                        pts += Point(x = cx, y = cy)
                    }
                'Q' ->
                    nums.chunked(4).filter { it.size == 4 }.forEach {
                        pts += Point(x = it[0], y = it[1])
                        cx = it[2]
                        cy = it[3]
                        pts +=
                            Point(x = cx, y = cy)
                    }
                'C' ->
                    nums.chunked(6).filter { it.size == 6 }.forEach {
                        pts += Point(x = it[0], y = it[1])
                        pts += Point(x = it[2], y = it[3])
                        cx = it[4]
                        cy = it[5]
                        pts += Point(x = cx, y = cy)
                    }
            }
            nums.clear()
        }
        while (i < tokens.size) {
            val t = tokens[i]
            if (t.length == 1 && t[0].isLetter()) {
                flush()
                cmd = t[0].uppercaseChar()
            } else {
                nums += t.toFloat()
            }
            i++
        }
        flush()
        return pts
    }

    fun SeqPrim.bounds(): Rect =
        when (this) {
            is SeqPrim.Box -> rect
            is SeqPrim.Text -> rect
            is SeqPrim.Path -> {
                val l = pts.minOf { it.x }
                val t = pts.minOf { it.y }
                Rect(
                    origin = Point(x = l, y = t),
                    size =
                        Size(
                            width = max(0f, pts.maxOf { it.x } - l),
                            height =
                                max(
                                    0f,
                                    pts.maxOf { it.y } - t,
                                ),
                        ),
                )
            }
        }

    /** Closed outline of a rectangle as a polyline. */
    fun outline(r: Rect): List<Point> {
        val x2 = r.origin.x + r.size.width
        val y2 = r.origin.y + r.size.height
        return listOf(r.origin, Point(x = x2, y = r.origin.y), Point(x = x2, y = y2), Point(x = r.origin.x, y = y2), r.origin)
    }

    /** Head boxes and stems of the lifelines drawn into [prims] (a head is its first rectangle, a stem its dashed vertical line). */
    fun headsAndStems(prims: List<SeqPrim>): Pair<Map<String, Rect>, Map<String, SeqStem>> {
        val heads = LinkedHashMap<String, Rect>()
        val stems = LinkedHashMap<String, SeqStem>()
        for (p in prims) {
            val id = p.groupId ?: continue
            when {
                p is SeqPrim.Box && id !in heads -> heads[id] = p.rect
                p is SeqPrim.Path && p.pts.size == 2 && p.dashed && id !in stems && kotlin.math.abs(p.pts[0].x - p.pts[1].x) < 0.01f ->
                    stems[id] = SeqStem(x = p.pts[0].x, top = min(p.pts[0].y, p.pts[1].y), bottom = max(p.pts[0].y, p.pts[1].y))
            }
        }
        return heads to stems
    }

    /** Collects scene parts. */
    class Builder {
        val heads = LinkedHashMap<String, Rect>()
        val stems = LinkedHashMap<String, SeqStem>()
        val blockers = ArrayList<Rect>()
        val frameLines = ArrayList<List<Point>>()
        val operandLines = ArrayList<List<Point>>()
        val messageLines = ArrayList<List<Point>>()
        val messages = LinkedHashMap<String, List<Point>>()

        /** Everything in [prims] is a solid block (activation bars, `ref` frames, marks and their texts). */
        fun solid(prims: List<SeqPrim>) {
            for (p in prims) blockers += with(SeqSceneCapture) { p.bounds() }
        }

        /** One combined fragment: the first rectangle is the frame, lines are operand separators, the rest is solid. */
        fun fragment(prims: List<SeqPrim>) {
            var frameTaken = false
            for (p in prims) {
                when {
                    p is SeqPrim.Box && !frameTaken -> {
                        frameTaken = true
                        frameLines += outline(p.rect)
                    }
                    p is SeqPrim.Path && !p.closed && p.pts.size == 2 -> operandLines += p.pts
                    else -> blockers += with(SeqSceneCapture) { p.bounds() }
                }
            }
        }

        /** The messages: paths are message lines; arrow heads, label backgrounds and label texts are solid. */
        fun messagesDrawn(prims: List<SeqPrim>) {
            for (p in prims) {
                when {
                    p is SeqPrim.Path && !p.closed && p.pts.size >= 2 && !isArrowHeadStroke(p) -> messageLines += p.pts
                    else -> blockers += with(SeqSceneCapture) { p.bounds() }
                }
            }
        }

        private fun isArrowHeadStroke(p: SeqPrim.Path): Boolean = p.pts.size == 3 && p.pts.maxOf { it.x } - p.pts.minOf { it.x } <= 12f

        fun build(rowPitch: Float): SeqNoteScene =
            SeqNoteScene(
                stems = stems,
                heads = heads,
                blockers = blockers,
                frameLines = frameLines,
                operandLines = operandLines,
                messageLines = messageLines,
                messages = messages,
                rowPitch = rowPitch,
            )
    }
}
