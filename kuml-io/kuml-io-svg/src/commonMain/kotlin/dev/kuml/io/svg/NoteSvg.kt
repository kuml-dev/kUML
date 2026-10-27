package dev.kuml.io.svg

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.bridge.NoteFrameStyle
import dev.kuml.layout.bridge.NoteLayout

/** Line style of a note anchor. */
internal enum class NoteAnchorStyle { DASHED, DOTTED }

/**
 * Renders a note symbol: a `<g id>` wrapper with the frame and the body text.
 *
 * Text goes through [SvgBuilder.text] only and attribute values through
 * [SvgBuilder.tag], both of which escape; nothing is escaped a second time
 * (no `&amp;amp;`).
 */
internal fun renderNote(
    id: String,
    body: String,
    layout: NodeLayout,
    frame: NoteFrameStyle,
    frameClasses: String,
    builder: SvgBuilder,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height
    builder.tag(
        name = "g",
        attrs = mapOf("id" to id, "transform" to "translate(${fmt2(x)},${fmt2(y)})"),
    ) {
        renderNoteFrame(w = w, h = h, frame = frame, frameClasses = frameClasses, builder = this)
        val inset = if (frame == NoteFrameStyle.BRACKET) NoteLayout.H_PADDING / 2f + 8f else NoteLayout.H_PADDING / 2f
        renderNoteText(lines = NoteLayout.displayText(body = body).lines, xInset = inset, builder = this)
    }
}

internal fun renderNoteFrame(
    w: Float,
    h: Float,
    frame: NoteFrameStyle,
    frameClasses: String,
    builder: SvgBuilder,
) {
    when (frame) {
        NoteFrameStyle.DOG_EAR -> {
            val ear = NoteLayout.DOG_EAR_SIZE
            val path =
                listOf(
                    "M 0 0",
                    "L ${fmt2(w - ear)} 0",
                    "L $w ${fmt2(ear)}",
                    "L $w $h",
                    "L 0 $h",
                    "Z",
                ).joinToString(" ")
            builder.tag(name = "path", attrs = mapOf("d" to path, "class" to frameClasses))
            val earPath =
                listOf(
                    "M ${fmt2(w - ear)} 0",
                    "L ${fmt2(w - ear)} ${fmt2(ear)}",
                    "L $w ${fmt2(ear)}",
                ).joinToString(" ")
            builder.tag(name = "path", attrs = mapOf("d" to earPath, "class" to "kuml-comment-fold"))
        }
        NoteFrameStyle.BRACKET -> {
            val arm = NoteLayout.BRACKET_ARM
            builder.tag(
                name = "rect",
                attrs = mapOf("width" to fmt2(w), "height" to fmt2(h), "class" to "kuml-bpmn-text-annotation-area"),
            )
            val path = "M ${fmt2(arm)} 0 L 0 0 L 0 ${fmt2(h)} L ${fmt2(arm)} ${fmt2(h)}"
            builder.tag(name = "path", attrs = mapOf("d" to path, "class" to frameClasses))
        }
    }
}

internal fun renderNoteText(
    lines: List<String>,
    xInset: Float,
    builder: SvgBuilder,
) {
    val startY = NoteLayout.V_PADDING / 2f + NoteLayout.LINE_H * 0.7f
    builder.tag(
        name = "text",
        attrs = mapOf("class" to "kuml-body", "x" to fmt2(xInset), "y" to fmt2(startY)),
    ) {
        lines.forEachIndexed { idx, line ->
            tag(
                name = "tspan",
                attrs = mapOf("x" to fmt2(xInset), "dy" to if (idx == 0) "0" else fmt2(NoteLayout.LINE_H)),
            ) { text(line) }
        }
    }
}

/** Anchor line from a note to its target; no arrowhead, no label. */
internal fun renderNoteAnchor(
    route: EdgeRoute,
    style: NoteAnchorStyle,
    builder: SvgBuilder,
) {
    val (tag, attrs) = EdgePathBuilder.build(route)
    val cls = if (style == NoteAnchorStyle.DASHED) "kuml-note-anchor" else "kuml-bpmn-association"
    builder.tag(name = tag, attrs = attrs + mapOf("class" to cls))
}
