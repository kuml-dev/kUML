package dev.kuml.io.svg.uml

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.fmt3
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.uml.InteractionOperator
import dev.kuml.uml.MessageSort
import dev.kuml.uml.UmlCombinedFragment
import dev.kuml.uml.UmlInteraction
import dev.kuml.uml.UmlLifeline
import dev.kuml.uml.UmlMessage
import kotlin.math.abs

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

/** Horizontal inset applied on each side of a nested (inner) fragment frame.
 *  Makes nested frames visibly narrower than their enclosing outer frame, providing
 *  a clear visual hierarchy — e.g. a BREAK inside a LOOP is not the same width. */
private const val NESTED_FRAGMENT_INSET = 10f

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

/**
 * Zusätzlicher horizontaler Abstand zwischen dem linken Nachbarn eines Guard-Labels
 * — dem Operator-Tag-Pentagon bei `index == 0`, dem Frame-Rand bei `index > 0` — und
 * dem sichtbaren linken Rand des Guard-Hintergrund-Rechtecks. Guards werden in einem
 * eigenen, LETZTEN Render-Pass gezeichnet (siehe [renderUmlGuardLabels]), sodass ihr
 * Hintergrund-Rechteck alles darunter überzeichnet — ohne genug Abstand malt es sich
 * über die Pentagon-Kontur bzw. den Frame-Rahmen. Behebt eine ~2px-Überlappung
 * ("Box-Titel" wird vom Guard-Text teilweise verdeckt", Bug-Report 2026-07-14).
 */
private const val GUARD_TAG_GAP = 6f
private const val SELF_CALL_W = 24f
private const val SELF_CALL_H = 16f

/**
 * **Header-Band pro Fragment-Operand.**
 *
 * Zusätzlicher vertikaler Freiraum (px), der DIREKT ÜBER der ersten Nachricht
 * jedes Combined-Fragment-Operanden reserviert wird. Ohne dieses Band sitzt der
 * Operand-Guard (bzw. das Fragment-Pentagon beim ersten Operanden) nur
 * [FRAGMENT_TOP_OUTSET] über dem ersten Pfeil — dessen Label (Baseline
 * `arrow - 4`, Oberkante `arrow - 15`) überschneidet sich dann mit dem
 * Guard-Band. Besonders sichtbar, wenn die erste Nachricht ein Self-Call auf
 * der linkesten Lifeline ist (z. B. `warteBackoff()` im LOOP von
 * SEQ-M-02): das Self-Call-Label landet exakt dort, wo der Guard steht, und
 * verdeckt ihn.
 *
 * Das Band verschiebt die erste Nachricht (und alle folgenden) um
 * [FRAGMENT_HEADER_BAND] nach unten, sodass zwischen Guard-Zeile und erster
 * Nachricht ein sauberer Korridor entsteht. Damit gestapelte Fragment-Rahmen
 * dabei NICHT kollidieren, wächst die Lifeline-Höhe im `UmlLayoutBridge` um
 * dieselbe Menge (`operandenAnzahl * FRAGMENT_HEADER_BAND`). Dieser Wert MUSS
 * mit der gleichnamigen Konstante im `UmlLayoutBridge` (kuml-layout-bridge)
 * synchron bleiben.
 */
internal const val FRAGMENT_HEADER_BAND = 24f

/**
 * Aufsteigend sortierte Liste der kleinsten Nachrichten-Sequenznummer jedes
 * NICHT-leeren Operanden über alle Combined-Fragments der Interaktion hinweg.
 * Jeder Eintrag bedeutet: „genau ein [FRAGMENT_HEADER_BAND] wird oberhalb dieser
 * Sequenz-Zeile eingefügt". [umlSeqRowOffset] macht daraus einen kumulativen
 * Pixel-Offset pro Sequenznummer.
 */
internal fun umlOperandFirstSeqs(
    fragments: List<UmlCombinedFragment>,
    msgById: Map<String, UmlMessage>,
): List<Int> =
    fragments
        .flatMap { frag -> frag.operands }
        .mapNotNull { op -> op.messageIds.mapNotNull { msgById[it]?.sequence }.minOrNull() }
        .sorted()

/**
 * Kumulativer vertikaler Offset (px) für die gegebene Sequenz-Zeile: ein
 * [FRAGMENT_HEADER_BAND] pro Operand, dessen erste Nachricht bei oder vor
 * [sequence] liegt.
 */
internal fun umlSeqRowOffset(
    sequence: Int,
    operandFirstSeqs: List<Int>,
): Float = FRAGMENT_HEADER_BAND * operandFirstSeqs.count { it <= sequence }

/**
 * Heuristische Pixel-Breite pro Zeichen für `kuml-body`-Text. Wird genutzt, um
 * die weißen Hintergrund-Rechtecke hinter Beschriftungen zu dimensionieren —
 * SVG kennt zur Render-Zeit keine echten Textmaße, deshalb diese pragmatische
 * Abschätzung. Etwas großzügig gewählt, damit auch breite Zeichen (W, M) noch
 * vollständig vom weißen Hintergrund abgedeckt werden.
 */
private const val BODY_CHAR_WIDTH = 6.5f

/**
 * Horizontaler Polster links und rechts des Text-Hintergrund-Rechtecks.
 *
 * War vormals 3f — das reichte nicht: bei Nachrichten, die exakt von einer
 * Lifeline-Mittellinie zur nächsten reichen (z. B. Reply-Pfeile über die volle
 * Diagrammbreite), landeten die Rechteck-Kanten fast exakt auf den gestrichelten
 * Lifeline-Achsen, sodass Antialiasing/Dash-Phase den ersten bzw. letzten
 * Buchstaben sichtbar anschnitt (siehe "Diagnosen (Reparatur-Prompt)"-Bug,
 * 2026-07-14). 6f schafft spürbaren Abstand zur Lifeline, ohne benachbarte
 * Labels zu kollidieren.
 */
private const val LABEL_BG_HPAD = 6f

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
 *
 * @param labelBackdropFill Uniform background fill used for every message label's background
 *   rect, regardless of whether the message sits inside a combined fragment or not — see
 *   [renderUmlCombinedFragments] doc comment for why a single colour (the theme's effective
 *   node fill) is correct everywhere instead of per-fragment matching. Defaults to `"white"`
 *   for backward compatibility.
 */
internal fun renderUmlSeqMessages(
    messages: List<UmlMessage>,
    visibleLifelineIds: Set<String>,
    nodeLayouts: Map<NodeId, NodeLayout>,
    builder: SvgBuilder,
    operandFirstSeqs: List<Int> = emptyList(),
    labelBackdropFill: String = "white",
) {
    val visible =
        messages
            .filter { it.fromLifelineId in visibleLifelineIds && it.toLifelineId in visibleLifelineIds }
            .sortedWith(compareBy({ it.sequence }, { it.id }))
    for (msg in visible) {
        val srcLayout = nodeLayouts[NodeId(msg.fromLifelineId)] ?: continue
        val tgtLayout = nodeLayouts[NodeId(msg.toLifelineId)] ?: continue
        val rowOffset = umlSeqRowOffset(msg.sequence, operandFirstSeqs)
        renderUmlMessage(msg, srcLayout, tgtLayout, builder, rowOffset, labelBackdropFill)
    }
}

private fun renderUmlMessage(
    msg: UmlMessage,
    srcLayout: NodeLayout,
    tgtLayout: NodeLayout,
    builder: SvgBuilder,
    rowOffset: Float,
    backdropFill: String,
) {
    val srcCx = srcLayout.bounds.origin.x + srcLayout.bounds.size.width / 2f
    val tgtCx = tgtLayout.bounds.origin.x + tgtLayout.bounds.size.width / 2f
    val srcHeadBottom = srcLayout.bounds.origin.y + SEQ_HEAD_HEIGHT
    // sequence is 1-based; y = headBottom + sequence * rowHeight + fragment-header offset
    val y = srcHeadBottom + msg.sequence * SEQ_ROW_HEIGHT + rowOffset

    if (msg.fromLifelineId == msg.toLifelineId) {
        renderUmlSelfCall(msg, srcCx, y, builder, backdropFill)
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
        // Draw label BEFORE arrowhead so the arrowhead is painted on top of the
        // white background rect and is never obscured by it.
        val labelX = (srcCx + tgtCx) / 2f
        val labelY = y - 4f
        // 20f (not 8f): messages spanning the full distance between two lifeline
        // centerlines (e.g. a reply arrow back across several columns) need clear
        // margin so the label background rect's edges don't land almost exactly on
        // the lifelines' own dashed strokes — see LABEL_BG_HPAD doc comment.
        val availableWidth = (abs(tgtCx - srcCx) - 20f).coerceAtLeast(20f)
        drawLabelWithWhiteBackground(
            label = msg.label,
            x = labelX,
            y = labelY,
            anchor = "middle",
            builder = this,
            maxWidth = availableWidth,
            fill = backdropFill,
        )
        when (msg.sort) {
            MessageSort.SYNC_CALL, MessageSort.CREATE ->
                renderFilledArrowheadUml(tgtCx, y, arrowDx, this)
            MessageSort.ASYNC_CALL, MessageSort.REPLY, MessageSort.DELETE ->
                renderOpenArrowheadUml(tgtCx, y, arrowDx, this)
        }
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
 *
 * @param maxWidth Optional maximum rendered width in pixels. When the estimated text
 *   width exceeds this value, SVG `textLength` + `lengthAdjust="spacingAndGlyphs"` are
 *   added to the `<text>` element so the browser compresses the glyphs to fit. The
 *   white background rect is also capped to this width. Used by [renderUmlMessage] to
 *   prevent message labels from overflowing beyond the two lifeline columns.
 * @param hPad Horizontal padding added to each side of the text estimate when sizing the
 *   background rect. Defaults to [LABEL_BG_HPAD]. Pass `0f` for self-call labels
 *   so the background matches the text width and does not spill into adjacent lifelines.
 * @param fill Background rect fill colour. Defaults to `"white"`, but callers pass the ONE
 *   uniform label backdrop colour used across the whole sequence diagram (the theme's
 *   effective node fill) — see [renderUmlCombinedFragments] doc comment for why a single
 *   colour, not per-fragment matching, is correct here.
 */
private fun drawLabelWithWhiteBackground(
    label: String,
    x: Float,
    y: Float,
    anchor: String,
    builder: SvgBuilder,
    cssClass: String = "kuml-body",
    maxWidth: Float? = null,
    hPad: Float = LABEL_BG_HPAD,
    fill: String = "white",
) {
    val textW = label.length * BODY_CHAR_WIDTH
    val constrainedW = if (maxWidth != null && textW > maxWidth) maxWidth else textW
    val bgW = constrainedW + 2f * hPad
    val bgH = BODY_TEXT_ASCENT + BODY_TEXT_DESCENT
    val bgX =
        when (anchor) {
            "middle" -> x - bgW / 2f
            "end" -> x - bgW
            else -> x - hPad
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
            "fill" to fill,
            "stroke" to "none",
        ),
    )
    val baseAttrs =
        if (anchor == "start") {
            mapOf("class" to cssClass, "x" to fmt(x), "y" to fmt(y))
        } else {
            mapOf("class" to cssClass, "x" to fmt(x), "y" to fmt(y), "text-anchor" to anchor)
        }
    // When the text is wider than the available span, compress it via textLength so it
    // stays within the lifeline columns instead of overflowing the diagram frame.
    val attrs =
        if (maxWidth != null && textW > maxWidth) {
            baseAttrs + mapOf("textLength" to fmt(maxWidth), "lengthAdjust" to "spacingAndGlyphs")
        } else {
            baseAttrs
        }
    builder.tag("text", attrs) { text(label) }
}

private fun renderUmlSelfCall(
    msg: UmlMessage,
    cx: Float,
    y: Float,
    builder: SvgBuilder,
    backdropFill: String,
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
        // hPad = 0f: background exactly matches the text width estimate so it
        // does not spill into the next lifeline's dashed time-axis column.
        drawLabelWithWhiteBackground(
            label = msg.label,
            x = cx + SELF_CALL_W + 4f,
            y = y - 2f,
            anchor = "start",
            builder = this,
            hPad = 0f,
            fill = backdropFill,
        )
    }
}

/**
 * A guard label (e.g. `[k ≤ 3]`, `[kompiliert]`) queued for later rendering.
 *
 * Guard labels are collected instead of drawn immediately by [renderUmlFragment]
 * so the caller can paint them in a later pass — see [renderUmlCombinedFragments].
 *
 * @param backdropFill The ONE uniform label backdrop colour used across the whole sequence
 *   diagram (the theme's effective node fill) — see [renderUmlCombinedFragments] doc comment
 *   for why every guard label uses the same colour regardless of its enclosing fragment.
 */
internal data class SeqGuardLabel(
    val label: String,
    val x: Float,
    val y: Float,
    val anchor: String,
    val maxWidth: Float,
    val backdropFill: String,
)

/**
 * Result of [renderUmlCombinedFragments]: guard labels queued for a later render pass.
 */
internal data class SeqFragmentRenderResult(
    val guardLabels: List<SeqGuardLabel>,
)

/**
 * Render all combined fragments (ALT, OPT, etc.) for an interaction.
 * Called before messages so frames appear behind arrows.
 *
 * The frame border, operator-tag pentagon and operand separators are drawn
 * directly into [builder] here (kept behind the lifelines' dashed time axes —
 * see call site in `KumlSvgRenderer`). Guard labels (`[k ≤ 3]`, `[kompiliert]`
 * …) are NOT drawn here: they are returned as [SeqGuardLabel]s so the caller
 * can render them via [renderUmlGuardLabels] in a later pass, after the
 * lifelines are painted. Without this split, a lifeline's dashed vertical can
 * paint directly through a guard's own background rect + text (the guard sits
 * inside the fragment, not just on its border).
 *
 * **Label backdrop design (Bug fix 2026-07-14):** guard labels use ONE uniform backdrop
 * colour ([labelBackdropFill]) everywhere, instead of matching each fragment's own declared
 * fill (`#eef6ff` for BREAK, `"none"`/canvas white otherwise). Reason: the frame `<rect>`
 * carries BOTH a `fill` presentation attribute AND `class="kuml-class"`, and the generated
 * stylesheet rule `.kuml-class { fill: <effectiveNodeFill> }` ([SvgDocument]) has higher CSS
 * specificity, so it always wins — every fragment interior actually renders as the theme's
 * effective node fill, never the `fill` attribute's `"#eef6ff"` or canvas white. Matching
 * labels to the (never-rendered) `fill` attribute produced mismatched blue/white label chips.
 * Matching [labelBackdropFill] (== effectiveNodeFill) makes every chip blend with its true
 * backdrop. Do NOT reintroduce per-fragment colour matching without first fixing the CSS
 * specificity clash — otherwise the mismatch bug returns.
 *
 * @param labelBackdropFill The ONE uniform background colour for every guard label's rect —
 *   pass the theme's effective node fill. Defaults to `"white"` for backward compatibility.
 */
internal fun renderUmlCombinedFragments(
    fragments: List<UmlCombinedFragment>,
    interaction: UmlInteraction,
    visibleLifelineLayouts: List<NodeLayout>,
    builder: SvgBuilder,
    labelBackdropFill: String = "white",
): SeqFragmentRenderResult {
    if (visibleLifelineLayouts.isEmpty()) return SeqFragmentRenderResult(emptyList())
    val msgById: Map<String, UmlMessage> = interaction.messages.associateBy { it.id }
    val operandFirstSeqs = umlOperandFirstSeqs(fragments, msgById)
    // Nested fragments (those referenced by any operand's fragmentIds) must be
    // rendered AFTER their enclosing outer frames so they appear on top in SVG.
    // Without this sort, a break_ inside loop is added to the flat list BEFORE
    // the loop (during operand construction), causing the outer LOOP frame to
    // overwrite the inner BREAK frame visually.
    val nestedIds = fragments.flatMap { f -> f.operands.flatMap { o -> o.fragmentIds } }.toSet()
    val renderOrder = fragments.sortedBy { if (it.id in nestedIds) 1 else 0 }
    val guardLabels = mutableListOf<SeqGuardLabel>()
    for (fragment in renderOrder) {
        renderUmlFragment(
            fragment,
            msgById,
            visibleLifelineLayouts,
            builder,
            operandFirstSeqs,
            guardLabels,
            labelBackdropFill,
            isNested = fragment.id in nestedIds,
        )
    }
    return SeqFragmentRenderResult(guardLabels)
}

/** Draws guard labels queued by [renderUmlCombinedFragments], on top of whatever is already in [builder]. */
internal fun renderUmlGuardLabels(
    guards: List<SeqGuardLabel>,
    builder: SvgBuilder,
) {
    for (guard in guards) {
        drawLabelWithWhiteBackground(
            label = guard.label,
            x = guard.x,
            y = guard.y,
            anchor = guard.anchor,
            builder = builder,
            maxWidth = guard.maxWidth,
            fill = guard.backdropFill,
        )
    }
}

private fun renderUmlFragment(
    fragment: UmlCombinedFragment,
    msgById: Map<String, UmlMessage>,
    visibleLifelineLayouts: List<NodeLayout>,
    builder: SvgBuilder,
    operandFirstSeqs: List<Int>,
    guardLabels: MutableList<SeqGuardLabel>,
    labelBackdropFill: String,
    isNested: Boolean = false,
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

    // Nested fragments get an inset on both sides so they appear visually narrower
    // than their enclosing outer frame, making the nesting hierarchy immediately clear.
    val nestingInset = if (isNested) NESTED_FRAGMENT_INSET else 0f
    val frameX = minLifelineX - FRAGMENT_PADDING_H + nestingInset
    val frameW = (maxLifelineX - minLifelineX) + 2f * FRAGMENT_PADDING_H - 2f * nestingInset
    // Rendered Y of a message = headBottom + seq * ROW + fragment-header offset.
    // The first message of this fragment (operand 0) is pushed down by exactly
    // one FRAGMENT_HEADER_BAND (its own operand's band), which creates the clear
    // corridor between the pentagon/guard row and that first arrow.
    val firstMsgY = headBottom + minSeq * SEQ_ROW_HEIGHT + umlSeqRowOffset(minSeq, operandFirstSeqs)
    val lastMsgY = headBottom + maxSeq * SEQ_ROW_HEIGHT + umlSeqRowOffset(maxSeq, operandFirstSeqs)
    // Pentagon top sits FRAGMENT_HEADER_BAND + 13 above the first arrow so the
    // guard (drawn at frameY + FRAGMENT_TAG_H/2 + 4) clears the first message
    // label. Frame bottom keeps the small asymmetric outset below the last arrow.
    val frameY = firstMsgY - FRAGMENT_HEADER_BAND - 13f
    val frameBottom = lastMsgY + FRAGMENT_BOTTOM_OUTSET
    val frameH = frameBottom - frameY

    // BREAK frames: solid border + subtle fill so they visually stand out inside
    // an enclosing loop/alt frame that uses a dashed border.  All other operators
    // keep the standard dashed-border / transparent style. NOTE: the "#eef6ff"
    // fill attribute below is overridden by the `.kuml-class` CSS rule (higher
    // specificity — see [renderUmlCombinedFragments] doc comment) and never
    // actually paints; it is kept only so a style-based override could restore
    // it later. Guard labels use the uniform [labelBackdropFill], NOT this value.
    val isBreak = fragment.operator == InteractionOperator.BREAK
    builder.tag("g", mapOf("id" to xmlEscapeAttr(fragment.id))) {
        val rectAttrs =
            buildMap {
                put("x", fmt(frameX))
                put("y", fmt(frameY))
                put("width", fmt(frameW))
                put("height", fmt(frameH))
                put("class", "kuml-class")
                put("fill", if (isBreak) "#eef6ff" else "none")
                if (!isBreak) put("stroke-dasharray", "6 4")
            }
        tag("rect", rectAttrs)
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
            val opMaxSeq = opMsgSeqs.maxOrNull()
            // Rendered Y of the operand's last message row (incl. header offset).
            val opBottomY =
                if (opMaxSeq != null) {
                    headBottom + opMaxSeq * SEQ_ROW_HEIGHT +
                        umlSeqRowOffset(opMaxSeq, operandFirstSeqs) + FRAGMENT_BOTTOM_OUTSET
                } else {
                    null
                }
            val guardY: Float
            if (index == 0) {
                // Guard sits inline with the pentagon keyword (frameY + 13). With
                // frameY = firstMsgY − FRAGMENT_HEADER_BAND − 13, this puts the
                // guard exactly one header band above the first arrow — a clear
                // corridor, so neither a normal arrow nor a self-call arm covers it.
                guardY = tagY + FRAGMENT_TAG_H / 2f + 4f
                if (opBottomY != null) prevOperandBottom = opBottomY
            } else {
                // Each non-first operand's first message is pushed down by its own
                // header band, opening a (row + band) gap above it. Place the
                // separator one band + 12 px above that first arrow, and the guard
                // 12 px below the separator — mirroring the operand-0 corridor.
                val computedSepY =
                    if (opMinSeq != null) {
                        headBottom + opMinSeq * SEQ_ROW_HEIGHT +
                            umlSeqRowOffset(opMinSeq, operandFirstSeqs) - FRAGMENT_HEADER_BAND - 12f
                    } else {
                        prevOperandBottom
                    }
                val sepY = maxOf(computedSepY, prevOperandBottom + 8f)
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
                prevOperandBottom = opBottomY ?: (guardY + 4f)
            }
            val guard = operand.guard
            if (guard != null) {
                // V3.0.11: idempotent bracket-wrapping.
                val trimmed = guard.trim()
                val displayGuard =
                    if (trimmed.startsWith("[") && trimmed.endsWith("]")) trimmed else "[$trimmed]"
                // Index-0 guard lives to the RIGHT of the pentagon keyword label
                // (same Y as the keyword centre).  This keeps it above the first
                // message arrow and gives it the full remaining frame width.
                // Subsequent-operand guards sit at the left of the frame, just
                // below their separator line.
                //
                // Both branches keep [GUARD_TAG_GAP] clearance so the guard's
                // background rect (whose left edge sits at guardX − LABEL_BG_HPAD,
                // see drawLabelWithWhiteBackground) never overlaps its left-hand
                // neighbour: the operator-tag pentagon (index 0, flat right edge at
                // frameX + FRAGMENT_TAG_W) or the frame's own left border (index > 0).
                // Previously guardX = frameX + FRAGMENT_TAG_W + 4f put the rect's
                // left edge 2px INSIDE the pentagon (4 - LABEL_BG_HPAD(6) = -2), and
                // guardX = frameX + 4f put non-first guards' rects 2px OUTSIDE the
                // frame border — both painted over their neighbour's stroke because
                // guards render in a later pass, on top of the frame/pentagon.
                val guardX =
                    if (index == 0) {
                        frameX + FRAGMENT_TAG_W + LABEL_BG_HPAD + GUARD_TAG_GAP
                    } else {
                        frameX + LABEL_BG_HPAD + 4f
                    }
                val guardMaxWidth = (frameX + frameW - guardX - 8f).coerceAtLeast(20f)
                guardLabels.add(
                    SeqGuardLabel(
                        label = displayGuard,
                        x = guardX,
                        y = guardY,
                        anchor = "start",
                        maxWidth = guardMaxWidth,
                        backdropFill = labelBackdropFill,
                    ),
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
    // Use kuml-seq-arrow-filled instead of kuml-edge: `.kuml-edge { fill: none }` has
    // higher CSS specificity than the `fill="currentColor"` presentation attribute and
    // would override it, causing the arrowhead to appear hollow.
    builder.tag(
        "polygon",
        mapOf(
            "points" to "${fmt(tipX)},${fmt(y)} ${fmt(baseX)},${fmt(y - 4f)} ${fmt(baseX)},${fmt(y + 4f)}",
            "class" to "kuml-seq-arrow-filled",
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

private fun fmt(v: Float): String = fmt3(v)
