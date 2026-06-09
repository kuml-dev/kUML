package dev.kuml.io.svg.uml

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.uml.MessageSort
import dev.kuml.uml.UmlCombinedFragment
import dev.kuml.uml.UmlInteraction
import dev.kuml.uml.UmlLifeline
import dev.kuml.uml.UmlMessage

private const val SEQ_HEAD_HEIGHT = 40f
private const val SEQ_ROW_HEIGHT = 32f
private const val FRAGMENT_PADDING = 8f
private const val FRAGMENT_TAG_W = 50f
private const val FRAGMENT_TAG_H = 18f
private const val SELF_CALL_W = 24f
private const val SELF_CALL_H = 16f

/** Render a UML lifeline head box + vertical dashed time axis. */
internal fun renderUmlLifelineHead(
    element: UmlLifeline,
    layout: NodeLayout,
    @Suppress("UNUSED_PARAMETER") theme: KumlTheme,
    builder: SvgBuilder,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height
    builder.tag("g", mapOf("id" to xmlEscapeAttr(element.id), "transform" to "translate(${fmt(x)},${fmt(y)})")) {
        tag("rect", mapOf("width" to fmt(w), "height" to fmt(SEQ_HEAD_HEIGHT), "class" to "kuml-class"))
        val stereotypeLabel = if (element.isActor) "«actor»" else "«lifeline»"
        tag("text", mapOf("class" to "kuml-stereotype", "x" to fmt(w / 2f), "y" to "14", "text-anchor" to "middle")) {
            text(stereotypeLabel)
        }
        tag("text", mapOf("class" to "kuml-title", "x" to fmt(w / 2f), "y" to "30", "text-anchor" to "middle")) {
            text(element.name)
        }
        tag(
            "line",
            mapOf(
                "x1" to fmt(w / 2f),
                "y1" to fmt(SEQ_HEAD_HEIGHT),
                "x2" to fmt(w / 2f),
                "y2" to fmt(h),
                "class" to "kuml-divider",
                "stroke-dasharray" to "4 4",
            ),
        )
    }
}

/**
 * Render all UML messages from an interaction. Called after the node loop.
 * Filters for messages where both endpoints are in visibleLifelineIds.
 * Messages are sorted by sequence number.
 */
internal fun renderUmlSeqMessages(
    messages: List<UmlMessage>,
    visibleLifelineIds: Set<String>,
    nodeLayouts: Map<NodeId, NodeLayout>,
    builder: SvgBuilder,
) {
    val visible =
        messages
            .filter { it.fromLifelineId in visibleLifelineIds && it.toLifelineId in visibleLifelineIds }
            .sortedWith(compareBy({ it.sequence }, { it.id }))
    for (msg in visible) {
        val srcLayout = nodeLayouts[NodeId(msg.fromLifelineId)] ?: continue
        val tgtLayout = nodeLayouts[NodeId(msg.toLifelineId)] ?: continue
        renderUmlMessage(msg, srcLayout, tgtLayout, builder)
    }
}

private fun renderUmlMessage(
    msg: UmlMessage,
    srcLayout: NodeLayout,
    tgtLayout: NodeLayout,
    builder: SvgBuilder,
) {
    val srcCx = srcLayout.bounds.origin.x + srcLayout.bounds.size.width / 2f
    val tgtCx = tgtLayout.bounds.origin.x + tgtLayout.bounds.size.width / 2f
    val srcHeadBottom = srcLayout.bounds.origin.y + SEQ_HEAD_HEIGHT
    // sequence is 1-based; y = headBottom + sequence * rowHeight
    val y = srcHeadBottom + msg.sequence * SEQ_ROW_HEIGHT

    if (msg.fromLifelineId == msg.toLifelineId) {
        renderUmlSelfCall(msg, srcCx, y, builder)
        return
    }

    val isReply = msg.sort == MessageSort.REPLY
    val strokeClass = if (isReply) "kuml-edge-dashed" else "kuml-edge"
    val arrowDx = if (tgtCx >= srcCx) -8f else 8f

    builder.tag("g", mapOf("id" to xmlEscapeAttr(msg.id))) {
        tag(
            "line",
            mapOf(
                "x1" to fmt(srcCx),
                "y1" to fmt(y),
                "x2" to fmt(tgtCx),
                "y2" to fmt(y),
                "class" to strokeClass,
            ),
        )
        when (msg.sort) {
            MessageSort.SYNC_CALL, MessageSort.CREATE ->
                renderFilledArrowheadUml(tgtCx, y, arrowDx, this)
            MessageSort.ASYNC_CALL, MessageSort.REPLY, MessageSort.DELETE ->
                renderOpenArrowheadUml(tgtCx, y, arrowDx, this)
        }
        val labelX = (srcCx + tgtCx) / 2f
        tag(
            "text",
            mapOf(
                "class" to "kuml-body",
                "x" to fmt(labelX),
                "y" to fmt(y - 4f),
                "text-anchor" to "middle",
            ),
        ) { text(msg.label) }
    }
}

private fun renderUmlSelfCall(
    msg: UmlMessage,
    cx: Float,
    y: Float,
    builder: SvgBuilder,
) {
    val isReply = msg.sort == MessageSort.REPLY
    val strokeClass = if (isReply) "kuml-edge-dashed" else "kuml-edge"
    builder.tag("g", mapOf("id" to xmlEscapeAttr(msg.id))) {
        tag(
            "path",
            mapOf(
                "d" to
                    "M ${fmt(
                        cx,
                    )} ${fmt(y)} L ${fmt(cx + SELF_CALL_W)} ${fmt(y)} L ${fmt(cx + SELF_CALL_W)} ${fmt(y + SELF_CALL_H)} L ${fmt(cx)} ${fmt(
                        y + SELF_CALL_H,
                    )}",
                "class" to strokeClass,
                "fill" to "none",
            ),
        )
        renderOpenArrowheadUml(cx, y + SELF_CALL_H, +8f, this)
        tag("text", mapOf("class" to "kuml-body", "x" to fmt(cx + SELF_CALL_W + 4f), "y" to fmt(y - 2f))) {
            text(msg.label)
        }
    }
}

/**
 * Render all combined fragments (ALT, OPT, etc.) for an interaction.
 * Called before messages so frames appear behind arrows.
 */
internal fun renderUmlCombinedFragments(
    fragments: List<UmlCombinedFragment>,
    interaction: UmlInteraction,
    visibleLifelineLayouts: List<NodeLayout>,
    builder: SvgBuilder,
) {
    if (visibleLifelineLayouts.isEmpty()) return
    val msgById: Map<String, UmlMessage> = interaction.messages.associateBy { it.id }
    for (fragment in fragments) {
        renderUmlFragment(fragment, msgById, visibleLifelineLayouts, builder)
    }
}

private fun renderUmlFragment(
    fragment: UmlCombinedFragment,
    msgById: Map<String, UmlMessage>,
    visibleLifelineLayouts: List<NodeLayout>,
    builder: SvgBuilder,
) {
    if (fragment.operands.isEmpty()) return
    // Determine min/max seqNo covered by this fragment
    val allMsgSeqs =
        fragment.operands.flatMap { op ->
            op.messageIds.mapNotNull { id -> msgById[id]?.sequence }
        }
    if (allMsgSeqs.isEmpty()) return
    val minSeq = allMsgSeqs.min()
    val maxSeq = allMsgSeqs.max()

    val anyLayout = visibleLifelineLayouts.first()
    val headBottom = anyLayout.bounds.origin.y + SEQ_HEAD_HEIGHT
    val minLifelineX = visibleLifelineLayouts.minOf { it.bounds.origin.x }
    val maxLifelineX = visibleLifelineLayouts.maxOf { it.bounds.origin.x + it.bounds.size.width }

    val frameX = minLifelineX - FRAGMENT_PADDING
    val frameW = (maxLifelineX - minLifelineX) + 2f * FRAGMENT_PADDING
    val frameY = headBottom + (minSeq - 0.5f) * SEQ_ROW_HEIGHT - FRAGMENT_PADDING
    val frameBottom = headBottom + (maxSeq + 0.5f) * SEQ_ROW_HEIGHT + FRAGMENT_PADDING
    val frameH = frameBottom - frameY

    builder.tag("g", mapOf("id" to xmlEscapeAttr(fragment.id))) {
        tag(
            "rect",
            mapOf(
                "x" to fmt(frameX),
                "y" to fmt(frameY),
                "width" to fmt(frameW),
                "height" to fmt(frameH),
                "class" to "kuml-class",
                "fill" to "none",
                "stroke-dasharray" to "6 4",
            ),
        )
        val tagX = frameX
        val tagY = frameY
        val notch = 6f
        val pts =
            "${fmt(tagX)},${fmt(tagY)} ${fmt(tagX + FRAGMENT_TAG_W)},${fmt(tagY)} " +
                "${fmt(tagX + FRAGMENT_TAG_W)},${fmt(tagY + FRAGMENT_TAG_H - notch)} " +
                "${fmt(tagX + FRAGMENT_TAG_W - notch)},${fmt(tagY + FRAGMENT_TAG_H)} " +
                "${fmt(tagX)},${fmt(tagY + FRAGMENT_TAG_H)}"
        tag("polygon", mapOf("points" to pts, "class" to "kuml-class", "fill" to "white"))
        tag(
            "text",
            mapOf(
                "class" to "kuml-stereotype",
                "x" to fmt(tagX + FRAGMENT_TAG_W / 2f),
                "y" to fmt(tagY + FRAGMENT_TAG_H / 2f + 4f),
                "text-anchor" to "middle",
            ),
        ) { text(fragment.operator.name) }

        // Guards per operand
        for ((index, operand) in fragment.operands.withIndex()) {
            val opMsgSeqs = operand.messageIds.mapNotNull { msgById[it]?.sequence }
            val opMinSeq = opMsgSeqs.minOrNull() ?: continue
            val guardY =
                if (index == 0) {
                    tagY + FRAGMENT_TAG_H + 4f
                } else {
                    val sepY = headBottom + (opMinSeq - 0.5f) * SEQ_ROW_HEIGHT
                    // Draw separator line
                    tag(
                        "line",
                        mapOf(
                            "x1" to fmt(frameX),
                            "y1" to fmt(sepY),
                            "x2" to fmt(frameX + frameW),
                            "y2" to fmt(sepY),
                            "class" to "kuml-divider",
                            "stroke-dasharray" to "6 4",
                        ),
                    )
                    sepY + 12f
                }
            val guard = operand.guard
            if (guard != null) {
                tag(
                    "text",
                    mapOf(
                        "class" to "kuml-body",
                        "x" to fmt(tagX + FRAGMENT_TAG_W + 6f),
                        "y" to fmt(guardY),
                    ),
                ) { text("[" + guard + "]") }
            }
        }
    }
}

private fun renderFilledArrowheadUml(
    tipX: Float,
    y: Float,
    baseDx: Float,
    builder: SvgBuilder,
) {
    val baseX = tipX + baseDx
    builder.tag(
        "polygon",
        mapOf(
            "points" to "${fmt(tipX)},${fmt(y)} ${fmt(baseX)},${fmt(y - 4f)} ${fmt(baseX)},${fmt(y + 4f)}",
            "class" to "kuml-edge",
            "fill" to "currentColor",
        ),
    )
}

private fun renderOpenArrowheadUml(
    tipX: Float,
    y: Float,
    baseDx: Float,
    builder: SvgBuilder,
) {
    val baseX = tipX + baseDx
    builder.tag(
        "path",
        mapOf(
            "d" to "M ${fmt(baseX)} ${fmt(y - 4f)} L ${fmt(tipX)} ${fmt(y)} L ${fmt(baseX)} ${fmt(y + 4f)}",
            "class" to "kuml-edge",
            "fill" to "none",
        ),
    )
}

private fun fmt(v: Float): String =
    if (v == v.toInt().toFloat()) {
        v.toInt().toString()
    } else {
        String.format(java.util.Locale.US, "%.3f", v)
    }
