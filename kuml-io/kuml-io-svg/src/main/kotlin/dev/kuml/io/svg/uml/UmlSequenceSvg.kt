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

/**
 * Horizontaler Atemraum links + rechts des Frames relativ zu den äußersten
 * Lifelines. V3.0.x: von 8f auf 24f vergrößert, damit der Guard-Text (`[valid]`,
 * `[invalid]` …) unter dem ALT-Pentagon links genug Platz hat, um den
 * gestrichelten Lifeline-Strich der ersten Lifeline NICHT zu kreuzen.
 *
 * Bleibt zugleich der Wert, um den der zentrale Canvas-Renderer das `paddingPx`
 * hochsetzt — daher als `internal const` exportiert. Siehe
 * `KumlSvgRenderer.renderUmlSequence`.
 */
internal const val UML_SEQ_FRAGMENT_PADDING = 24f
private const val FRAGMENT_PADDING_H = UML_SEQ_FRAGMENT_PADDING

/**
 * **Vertikale Outset-Werte des Frames — asymmetrisch.**
 *
 * Nachrichten-Beschriftungen sitzen 4 px ÜBER der Pfeillinie (Label-Baseline
 * `= arrow_y - 4`); mit Ascent ≈ 11 und Descent ≈ 3 ergibt das ein
 * Label-Hintergrund-Band von `arrow_y - 15` bis `arrow_y - 1`. Das bedeutet:
 * Der "freie Korridor" zwischen zwei aufeinanderfolgenden Nachrichten ist
 * **NICHT** mittig zwischen den Pfeilen. Er reicht vom Label-Bottom der
 * oberen Nachricht (`arrow_n - 1`) bis zum Label-Top der unteren
 * (`arrow_(n+1) - 15`), Breite 18 px, Mitte 8 px UNTER dem oberen Pfeil
 * (entspricht `arrow_n + 8`).
 *
 * Konsequenz für die beiden Frame-Kanten:
 * - **Oben**: 18-px-Korridor zwischen `arrow_(minSeq-1)` Label-Bottom
 *   (`arrow_minSeq - 33`) und `arrow_minSeq` Label-Top (`arrow_minSeq - 15`).
 *   Mitte: `arrow_minSeq - 24` → Frame extendiert **24 px** über `arrow_minSeq`.
 * - **Unten**: 18-px-Korridor zwischen `arrow_maxSeq` Label-Bottom
 *   (`arrow_maxSeq - 1`) und `arrow_(maxSeq+1)` Label-Top (`arrow_maxSeq + 17`).
 *   Mitte: `arrow_maxSeq + 8` → Frame extendiert **8 px** unter `arrow_maxSeq`.
 *
 * Die ursprüngliche symmetrische `±0.5-Row`-Formel mit `FRAGMENT_PADDING = 8`
 * landete die Unter-Kante bei `arrow_maxSeq + 24` — 7 px im Label-Bereich
 * der nächsten außerhalb-liegenden Nachricht. Im SysML-2-Test-Sample fiel
 * das nicht auf (keine Nachricht nach dem Fragment), im UML-Sample
 * "Place Order — API Submit" rutschte deshalb `confirmation` halb in die
 * ALT-Box. Asymmetrie korrigiert das.
 */
private const val FRAGMENT_TOP_OUTSET = 24f
private const val FRAGMENT_BOTTOM_OUTSET = 8f
private const val FRAGMENT_TAG_W = 50f
private const val FRAGMENT_TAG_H = 18f
private const val SELF_CALL_W = 24f
private const val SELF_CALL_H = 16f

/**
 * Heuristische Pixel-Breite pro Zeichen für `kuml-body`-Text. Wird genutzt, um
 * die weißen Hintergrund-Rechtecke hinter Beschriftungen zu dimensionieren —
 * SVG kennt zur Render-Zeit keine echten Textmaße, deshalb diese pragmatische
 * Abschätzung. Etwas großzügig gewählt, damit auch breite Zeichen (W, M) noch
 * vollständig vom weißen Hintergrund abgedeckt werden.
 */
private const val BODY_CHAR_WIDTH = 6.5f

/** Horizontaler Polster links und rechts des Text-Hintergrund-Rechtecks. */
private const val LABEL_BG_HPAD = 3f

/**
 * Approximierte Pixel-Aufwärts-Höhe des `kuml-body`-Textes über der Baseline
 * (Cap-Höhe + 1px Polster). Nur für die Hintergrund-Rechtecke; nicht für
 * Layout-Berechnungen.
 */
private const val BODY_TEXT_ASCENT = 11f

/**
 * Approximierte Pixel-Abwärts-Höhe des `kuml-body`-Textes unter der Baseline
 * (Descender + 1px Polster).
 */
private const val BODY_TEXT_DESCENT = 3f

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
        val labelY = y - 4f
        drawLabelWithWhiteBackground(
            label = msg.label,
            x = labelX,
            y = labelY,
            anchor = "middle",
            builder = this,
        )
    }
}

/**
 * Zeichnet einen Body-Text mit einem weißen Hintergrund-Rechteck dahinter.
 *
 * Hintergrund: Sequenz-Diagramm-Labels (Nachrichten-Beschriftungen, Operand-
 * Guards) liegen geometrisch über mehreren gestrichelten Linien — dem
 * vertikalen Zeit-Achsen-Strich der Lifelines und den horizontalen Separator-
 * Strichen zwischen `alt`-Operanden bzw. der Frame-Umrandung. Ohne weißen
 * Hintergrund kreuzen diese Linien das Text-Glyphen-Innere, was die Lesbarkeit
 * massiv reduziert — siehe `[invalid]` über der ersten Lifeline und
 * `400 Bad Request` über der Operand-Trennlinie im PNG-Sample.
 *
 * Das Hintergrund-Rechteck wird mit einer überschlägigen Text-Breite
 * (`BODY_CHAR_WIDTH * label.length`) und [LABEL_BG_HPAD] horizontalem Polster
 * dimensioniert. Bei [anchor] == "middle" wird zentriert, bei "start" links-
 * bündig, bei "end" rechtsbündig gerechnet — analog zu SVG `text-anchor`.
 */
private fun drawLabelWithWhiteBackground(
    label: String,
    x: Float,
    y: Float,
    anchor: String,
    builder: SvgBuilder,
    cssClass: String = "kuml-body",
) {
    val textW = label.length * BODY_CHAR_WIDTH
    val bgW = textW + 2f * LABEL_BG_HPAD
    val bgH = BODY_TEXT_ASCENT + BODY_TEXT_DESCENT
    val bgX =
        when (anchor) {
            "middle" -> x - bgW / 2f
            "end" -> x - bgW
            else -> x - LABEL_BG_HPAD
        }
    // y im `<text>` ist die Baseline. Das Rechteck beginnt eine Cap-Höhe
    // darüber und endet knapp unterhalb der Baseline — knapp genug, um
    // Nachrichten-Pfeile (zeichnen 4 px unterhalb der Baseline) NICHT zu
    // überdecken.
    val bgY = y - BODY_TEXT_ASCENT
    builder.tag(
        "rect",
        mapOf(
            "x" to fmt(bgX),
            "y" to fmt(bgY),
            "width" to fmt(bgW),
            "height" to fmt(bgH),
            "fill" to "white",
            "stroke" to "none",
        ),
    )
    val attrs =
        if (anchor == "start") {
            mapOf("class" to cssClass, "x" to fmt(x), "y" to fmt(y))
        } else {
            mapOf("class" to cssClass, "x" to fmt(x), "y" to fmt(y), "text-anchor" to anchor)
        }
    builder.tag("text", attrs) { text(label) }
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
        drawLabelWithWhiteBackground(
            label = msg.label,
            x = cx + SELF_CALL_W + 4f,
            y = y - 2f,
            anchor = "start",
            builder = this,
        )
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

    val frameX = minLifelineX - FRAGMENT_PADDING_H
    val frameW = (maxLifelineX - minLifelineX) + 2f * FRAGMENT_PADDING_H
    // UML-Pfeil-Y für Sequenz n: `headBottom + n * SEQ_ROW_HEIGHT`. Frame-Top
    // 24 px ÜBER dem ersten enthaltenen Pfeil, Frame-Bottom 8 px UNTER dem
    // letzten enthaltenen Pfeil — asymmetrisch wegen Label-Position-Über-Pfeil
    // (siehe KDoc von FRAGMENT_TOP_OUTSET / FRAGMENT_BOTTOM_OUTSET).
    val frameY = headBottom + minSeq * SEQ_ROW_HEIGHT - FRAGMENT_TOP_OUTSET
    val frameBottom = headBottom + maxSeq * SEQ_ROW_HEIGHT + FRAGMENT_BOTTOM_OUTSET
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
        // Track the Y-bottom of the previous operand's last message row so that
        // separator lines and guards for empty operands (those with no messages of
        // their own, only inheriting a seqNo from a sibling operand) are pushed
        // below the last rendered message arrow instead of overlapping it.
        var prevOperandBottom = frameY + FRAGMENT_TAG_H + SEQ_ROW_HEIGHT / 2f
        for ((index, operand) in fragment.operands.withIndex()) {
            val opMsgSeqs = operand.messageIds.mapNotNull { msgById[it]?.sequence }
            val opMinSeq = opMsgSeqs.minOrNull()
            val guardY: Float
            if (index == 0) {
                // V3.0.x: Guard sits BELOW the ALT pentagon (not next to it) and
                // an extra +10 below the pentagon's bottom so the body-text
                // ascenders clear the pentagon outline. Together with the wider
                // FRAGMENT_PADDING (24) this keeps `[valid]` clear of the
                // leftmost lifeline's dashed time axis.
                guardY = tagY + FRAGMENT_TAG_H + 14f
                val opMaxSeq = opMsgSeqs.maxOrNull()
                if (opMaxSeq != null) {
                    prevOperandBottom = headBottom + (opMaxSeq + 0.5f) * SEQ_ROW_HEIGHT
                }
            } else {
                // V2.0.44: use max(computed sep, prevOperandBottom + gap) so guards
                // of empty operands don't overlap with messages of the previous one.
                val computedSepY =
                    if (opMinSeq != null) {
                        headBottom + (opMinSeq - 0.5f) * SEQ_ROW_HEIGHT
                    } else {
                        prevOperandBottom
                    }
                val sepY = maxOf(computedSepY, prevOperandBottom + 4f)
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
                guardY = sepY + 12f
                val opMaxSeq = opMsgSeqs.maxOrNull()
                prevOperandBottom =
                    if (opMaxSeq != null) {
                        headBottom + (opMaxSeq + 0.5f) * SEQ_ROW_HEIGHT
                    } else {
                        guardY + 4f
                    }
            }
            val guard = operand.guard
            if (guard != null) {
                // V3.0.11: idempotent bracket-wrapping. Authors may write
                // `guard = "valid"` (DSL adds brackets) or `guard = "[valid]"`
                // (DSL passes through). Both must render as `[valid]`, not
                // `[[valid]]`. Trim first so `" [valid] "` is also recognised.
                val trimmed = guard.trim()
                val displayGuard =
                    if (trimmed.startsWith("[") && trimmed.endsWith("]")) trimmed else "[$trimmed]"
                // V3.0.x: Guard sits at the LEFT edge of the frame instead of
                // hanging off the right side of the ALT pentagon. With the
                // wider FRAGMENT_PADDING the guard text now lives in the
                // breathing room left of the leftmost lifeline — its dashed
                // time-axis no longer crosses through the brackets.
                // A white background covers any dashed line that still passes
                // under the text (e.g. the operand-separator).
                drawLabelWithWhiteBackground(
                    label = displayGuard,
                    x = frameX + 4f,
                    y = guardY,
                    anchor = "start",
                    builder = this,
                )
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
