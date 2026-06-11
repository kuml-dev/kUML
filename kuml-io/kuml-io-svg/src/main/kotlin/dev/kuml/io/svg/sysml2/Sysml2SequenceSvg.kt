package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.io.svg.xmlEscapeText
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.sysml2.CombinedFragmentUsage
import dev.kuml.sysml2.ExecutionSpecificationUsage
import dev.kuml.sysml2.LifelineDefinition
import dev.kuml.sysml2.MessageKind
import dev.kuml.sysml2.MessageUsage

// ─────────────────────────────────────────────────────────────────────────────
// Renderer für SysML-2 Sequence-Diagramme (V2.0.11).
//
// **Architektur-Divergenz** gegenüber den anderen sechs SysML-2-Diagrammen:
// SEQ verarbeitet Nachrichten *direkt*, nicht über den
// `EdgeRendererDispatcher`. Die Bridge gibt nur Lifelines als LayoutNodes aus
// — keine Edges — weil ELKs hierarchisches Layout für Sequence-Diagramme
// ungeeignet ist (siehe `Sysml2LayoutBridge.toLayoutGraph(SeqDiagram)` KDoc).
//
// Konsequenz für diesen Renderer:
//  1. Der Standard-Knoten-Loop (via `NodeRendererDispatcher`) rendert die
//     Lifeline-Köpfe + die vertikale gestrichelte Zeit-Achse unter jedem Kopf
//     — Dispatch über `renderLifelineHead` in `renderSysml2Definition`.
//  2. *Nach* dem Standard-Knoten-Loop iteriert der SEQ-Renderer-Hook
//     (`renderSysml2SeqMessages`) `model.usages.filterIsInstance<MessageUsage>()`,
//     filtert auf sichtbare Endpunkte und zeichnet jede Nachricht als
//     horizontalen Pfeil. Die Y-Position errechnet sich aus
//     `LIFELINE_HEAD_HEIGHT + (seqNo + 1) * MESSAGE_ROW_HEIGHT`; die
//     X-Positionen aus den Lifeline-Mittelpunkten in der `LayoutResult`.
//
// **Warum diese Divergenz?** Die anderen SysML-2-Diagramme sind Graphen, die
// ein generisches Edge-Routing-System sinnvoll bedient. SEQ-Nachrichten sind
// keine Edges — sie sind Tabellen-Zellen-Inhalte (Spalte = Lifeline-Lane,
// Zeile = Sequenznummer). Generisches Edge-Routing würde sie als beliebige
// Linien zwischen Knoten interpretieren und das Layout zerstören. Die
// Renderer-direkte Implementierung respektiert die strukturelle
// Andersartigkeit von SEQ.
//
// V2.0.15:
//  - Combined Fragments (8 commonly-used operators — Alt / Opt / Loop / Par /
//    Strict / Seq / Break / Critical) als gerahmte Bereiche mit
//    Operator-Tag-Pentagon im oberen linken Frame-Eck. Renderer-direkt
//    (siehe renderCombinedFragment).
//  - Execution Specifications — thin vertical activation bars on a lifeline
//    while it is actively processing a message. Renderer-direkt (siehe
//    renderExecutionSpec).
//  - `Create` / `Destroy` Lifecycle-Nachrichten — Create-Pfeil endet am
//    Lifeline-Head-Box mit `«create»` Stereotyp; Destroy-Pfeil mit
//    `«destroy»` Stereotyp + X-Marker auf der Target-Lifeline.
//
// V2.x:
//  - Nested Combined Fragments (CF inside CF) — braucht Baum-Repräsentation
//    und rekursiven Layout-Pass.
//  - Nested Execution Specifications (overlapping activations auf derselben
//    Lifeline).
//  - Die restlichen 4 CF-Operatoren (assert, neg, consider, ignore).
//  - Self-Call mit vollwertigem Activation-Marker (V2.0.11 MVP ist ein
//    kleiner U-förmiger Pfeil — funktional, aber nicht hübsch).
//  - Found / Lost Messages — Pfeile von / nach außerhalb des Diagrammrahmens.
//  - Co-region / general-ordering constraints, Zeitachsen-Annotationen
//    (Duration Constraints, Time Constraints).
//  - LaTeX-Rendering für Fragments / ExecSpecs / Create / Destroy (V2.0.15
//    ist SVG-only — siehe KumlLatexRenderer.toLatex(SeqDiagram)).
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Rendert den Lifeline-Kopf plus die vertikale gestrichelte Zeit-Achse als
 * Teil des Standard-Knoten-Loops. Wird über die `when`-Klausel in
 * [renderSysml2Definition] aufgerufen.
 *
 * Layout (top → bottom):
 *  1. **Lifeline-Kopf** — Rechteck der Breite `bounds.width` und Höhe
 *     [SEQ_RENDERER_HEAD_HEIGHT]. Trägt das `«lifeline»`-Stereotyp oben und
 *     den Namen darunter, beides horizontal zentriert.
 *  2. **Vertikale gestrichelte Linie** — vom unteren Rand des Kopfes bis
 *     zum unteren Rand der Bounds, horizontal in der Mitte. SVG:
 *     `stroke-dasharray="4 4"`.
 *
 * Theme-Anbindung: nutzt die existierenden CSS-Klassen (`kuml-class`,
 * `kuml-stereotype`, `kuml-title`), damit Lifelines visuell mit BDD-Boxen
 * im selben Stylesheet harmonieren.
 */
internal fun renderLifelineHead(
    element: LifelineDefinition,
    layout: NodeLayout,
    @Suppress("UNUSED_PARAMETER") theme: KumlTheme,
    builder: SvgBuilder,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height

    builder.tag(
        "g",
        mapOf("id" to xmlEscapeAttr(element.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        // 1. Lifeline-Kopf-Box.
        tag(
            "rect",
            mapOf(
                "width" to fmt(w),
                "height" to fmt(SEQ_RENDERER_HEAD_HEIGHT),
                "class" to "kuml-class",
            ),
        )

        // 2. Stereotyp-Zeile.
        tag(
            "text",
            mapOf(
                "class" to "kuml-stereotype",
                "x" to fmt(w / 2f),
                "y" to fmt(14f),
                "text-anchor" to "middle",
            ),
        ) { text("«lifeline»") }

        // 3. Name-Zeile.
        val nameClass = if (element.isAbstract) "kuml-title kuml-title-abstract" else "kuml-title"
        tag(
            "text",
            mapOf(
                "class" to nameClass,
                "x" to fmt(w / 2f),
                "y" to fmt(30f),
                "text-anchor" to "middle",
            ),
        ) { text(xmlEscapeText(element.name)) }

        // 4. Vertikale gestrichelte Zeit-Achse vom unteren Kopfrand bis zum
        //    unteren Bounds-Rand.
        tag(
            "line",
            mapOf(
                "x1" to fmt(w / 2f),
                "y1" to fmt(SEQ_RENDERER_HEAD_HEIGHT),
                "x2" to fmt(w / 2f),
                "y2" to fmt(h),
                "class" to "kuml-divider",
                "stroke-dasharray" to "4 4",
            ),
        )
    }
}

/**
 * Iteriert alle [MessageUsage]s aus dem Modell, filtert auf Nachrichten,
 * deren Endpunkte beide unter den [visibleLifelineIds] sind, sortiert nach
 * [MessageUsage.seqNo] aufsteigend (mit stabiler Sekundärsortierung über
 * die Id für deterministische Ausgabe) und zeichnet jede Nachricht direkt
 * über [renderMessage].
 *
 * Wird vom [dev.kuml.io.svg.KumlSvgRenderer]-`toSvg(model, SeqDiagram, …)`-
 * Overload nach dem Standard-Knoten-Loop aufgerufen — siehe Architektur-
 * Divergenz oben.
 *
 * @param messages Alle MessageUsages aus `model.usages`.
 * @param visibleLifelineIds Set der ids der im SEQ-Diagramm sichtbaren
 *   Lifelines (aus `diagram.elementIds`).
 * @param nodeLayouts Lookup-Map `lifelineId → NodeLayout`, geliefert vom
 *   Layout-Algorithmus (LayoutResult.nodes, geshiftet um padding).
 * @param padding Renderer-Padding — wird in die Zeichnungs-X/Y-Koordinaten
 *   einbezogen (alle Layouts sind bereits geshiftet, der Padding wirkt nur
 *   auf die internen X/Y-Berechnungen relativ zum Kopf-Boden).
 * @param builder Der Edges-SvgBuilder (Nachrichten gehören semantisch zu
 *   den Edges, auch wenn sie hier direkt gezeichnet werden).
 */
internal fun renderSysml2SeqMessages(
    messages: List<MessageUsage>,
    visibleLifelineIds: Set<String>,
    nodeLayouts: Map<NodeId, NodeLayout>,
    builder: SvgBuilder,
) {
    val visible =
        messages
            .filter {
                it.sourceLifelineId in visibleLifelineIds &&
                    it.targetLifelineId in visibleLifelineIds
            }.sortedWith(compareBy({ it.seqNo }, { it.id }))

    for (msg in visible) {
        val srcLayout = nodeLayouts[NodeId(msg.sourceLifelineId)] ?: continue
        val tgtLayout = nodeLayouts[NodeId(msg.targetLifelineId)] ?: continue
        renderMessage(msg, srcLayout, tgtLayout, builder)
    }
}

/**
 * Zeichnet eine einzelne Nachricht als horizontalen Pfeil zwischen den
 * Lifeline-Achsen.
 *
 * X-Berechnung:
 *  - `sourceX = Mittelpunkt der Source-Lifeline = bounds.origin.x + bounds.width / 2`.
 *  - `targetX = Mittelpunkt der Target-Lifeline = bounds.origin.x + bounds.width / 2`.
 *
 * Y-Berechnung:
 *  - `Y = sourceLifelineHead.bottom + (seqNo + 1) * MESSAGE_ROW_HEIGHT`.
 *  - Der `+1`-Offset lässt Atemraum zwischen dem Kopf und der ersten
 *    Nachricht.
 *
 * Stil pro [MessageKind]:
 *  - **Sync** — durchgezogene Linie, gefüllte Pfeilspitze (`<polygon>`).
 *  - **Async** — durchgezogene Linie, offene Pfeilspitze (`<path>` mit
 *    "V"-Form).
 *  - **Reply** — gestrichelte Linie (`stroke-dasharray="6 4"`), offene
 *    Pfeilspitze.
 *
 * Label: über dem Pfeil, horizontal zentriert auf der Mitte zwischen
 * Source-X und Target-X, vertikal 4px über der Pfeillinie.
 *
 * **Self-Call** (Source-Lifeline == Target-Lifeline): zeichnet einen
 * kleinen U-förmigen Pfeil — rechts vom Lifeline-Mittelpunkt nach oben,
 * dann rechts, dann zurück nach unten auf die Lifeline. V2.0.11-MVP-
 * Vereinfachung; eine vollwertige Activation-Box-Variante ist V2.x.
 */
internal fun renderMessage(
    msg: MessageUsage,
    srcLayout: NodeLayout,
    tgtLayout: NodeLayout,
    builder: SvgBuilder,
) {
    val srcCx = srcLayout.bounds.origin.x + srcLayout.bounds.size.width / 2f
    val tgtCx = tgtLayout.bounds.origin.x + tgtLayout.bounds.size.width / 2f
    val srcHeadBottom = srcLayout.bounds.origin.y + SEQ_RENDERER_HEAD_HEIGHT
    val y = srcHeadBottom + (msg.seqNo + 1) * SEQ_RENDERER_MESSAGE_ROW_HEIGHT

    val isSelfCall = msg.sourceLifelineId == msg.targetLifelineId

    if (isSelfCall) {
        renderSelfCall(msg, srcCx, y, builder)
        return
    }

    // V2.0.15: lifecycle messages need their own visual treatment.
    when (msg.kind) {
        MessageKind.Create -> {
            renderCreateMessage(msg, srcCx, tgtCx, tgtLayout, y, builder)
            return
        }
        MessageKind.Destroy -> {
            renderDestroyMessage(msg, srcCx, tgtCx, tgtLayout, y, builder)
            return
        }
        else -> Unit
    }

    val isReply = msg.kind == MessageKind.Reply
    val strokeClass = if (isReply) "kuml-edge-dashed" else "kuml-edge"

    // Berechne die Richtung — die Pfeilspitze sitzt am Target-Ende.
    val arrowDx = if (tgtCx >= srcCx) -8f else 8f // Pfeilbasis liegt 8px vor target

    builder.tag(
        "g",
        mapOf("id" to xmlEscapeAttr(msg.id)),
    ) {
        // 1. Pfeil-Schaft.
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

        // 2. Pfeilspitze am Target-Ende.
        when (msg.kind) {
            MessageKind.Sync -> renderFilledArrowhead(tgtCx, y, arrowDx, this)
            MessageKind.Async,
            MessageKind.Reply,
            -> renderOpenArrowhead(tgtCx, y, arrowDx, this)
            // Already handled above — the early-return for Create/Destroy
            // means these branches are unreachable; the exhaustive `when`
            // keeps the compiler happy when MessageKind grows again.
            MessageKind.Create,
            MessageKind.Destroy,
            -> Unit
        }

        // 3. Label über dem Pfeil.
        val labelX = (srcCx + tgtCx) / 2f
        val labelY = y - 4f
        tag(
            "text",
            mapOf(
                "class" to "kuml-body",
                "x" to fmt(labelX),
                "y" to fmt(labelY),
                "text-anchor" to "middle",
            ),
        ) { text(xmlEscapeText(msg.messageLabel)) }
    }
}

/**
 * Render a `Create` message — V2.0.15.
 *
 * Visual semantics per SysML 2 / UML convention:
 *  - The arrow ends at the **mid-left (or mid-right) of the target lifeline's
 *    head box** rather than at the target lifeline's vertical line, because
 *    the `Create` semantically constructs the target object. The head box
 *    appears "at the arrow's tip" rather than at the original top.
 *  - The stereotype `«create»` appears above the arrow as the label prefix.
 *  - Open arrowhead at the target end (per UML — `Create` is conventionally
 *    rendered with an open arrowhead, regardless of sync/async).
 *
 * V2.0.15 MVP: the renderer does NOT actually shift the target lifeline's
 * head box vertically — that would require a second layout pass. We anchor
 * the arrow at the SAME Y as a regular message, with the «create» stereotype
 * making the lifecycle semantics explicit textually. The target's head-box
 * is rendered normally at the top by [renderLifelineHead]; the "head box at
 * arrow tip" V2.x polish needs a target-Y override that's out of scope here.
 */
private fun renderCreateMessage(
    msg: MessageUsage,
    srcCx: Float,
    tgtCx: Float,
    tgtLayout: NodeLayout,
    y: Float,
    builder: SvgBuilder,
) {
    // Arrow ends at the side of the target head-box (mid-left if arrow points
    // right, mid-right if arrow points left). This makes the `Create`
    // semantics visually distinct from regular arrows that end at the
    // lifeline's centre line.
    val tgtBoxLeft = tgtLayout.bounds.origin.x
    val tgtBoxRight = tgtLayout.bounds.origin.x + tgtLayout.bounds.size.width
    val arrowEndX = if (tgtCx >= srcCx) tgtBoxLeft else tgtBoxRight
    val arrowDx = if (tgtCx >= srcCx) -8f else 8f

    builder.tag(
        "g",
        mapOf("id" to xmlEscapeAttr(msg.id)),
    ) {
        // 1. Arrow shaft — dashed (UML convention: «create» arrows are dashed).
        tag(
            "line",
            mapOf(
                "x1" to fmt(srcCx),
                "y1" to fmt(y),
                "x2" to fmt(arrowEndX),
                "y2" to fmt(y),
                "class" to "kuml-edge-dashed",
            ),
        )

        // 2. Open arrowhead at the target box edge.
        renderOpenArrowhead(arrowEndX, y, arrowDx, this)

        // 3. Stereotype + label above the arrow.
        val labelX = (srcCx + arrowEndX) / 2f
        tag(
            "text",
            mapOf(
                "class" to "kuml-stereotype",
                "x" to fmt(labelX),
                "y" to fmt(y - 16f),
                "text-anchor" to "middle",
            ),
        ) { text("«create»") }
        tag(
            "text",
            mapOf(
                "class" to "kuml-body",
                "x" to fmt(labelX),
                "y" to fmt(y - 4f),
                "text-anchor" to "middle",
            ),
        ) { text(xmlEscapeText(msg.messageLabel)) }
    }
}

/**
 * Render a `Destroy` message — V2.0.15.
 *
 * Visual semantics:
 *  - Arrow runs to the target lifeline's centre line (like a regular sync
 *    message), but with a filled arrowhead.
 *  - Stereotype `«destroy»` appears above the arrow.
 *  - A small **X marker** (two crossing diagonals) is drawn at the target
 *    end of the arrow on the target lifeline — visually terminating the
 *    lifeline at this seqNo.
 *
 * V2.0.15 MVP: the X-marker is drawn at the arrow's Y position. A full
 * lifeline-shortening pass (the lifeline's vertical dashed line should
 * stop at the X) is V2.x — that requires a renderer-level override of the
 * lifeline-head's tail.
 */
private fun renderDestroyMessage(
    msg: MessageUsage,
    srcCx: Float,
    tgtCx: Float,
    @Suppress("UNUSED_PARAMETER") tgtLayout: NodeLayout,
    y: Float,
    builder: SvgBuilder,
) {
    val arrowDx = if (tgtCx >= srcCx) -8f else 8f
    builder.tag(
        "g",
        mapOf("id" to xmlEscapeAttr(msg.id)),
    ) {
        // 1. Arrow shaft.
        tag(
            "line",
            mapOf(
                "x1" to fmt(srcCx),
                "y1" to fmt(y),
                "x2" to fmt(tgtCx),
                "y2" to fmt(y),
                "class" to "kuml-edge",
            ),
        )

        // 2. Filled arrowhead.
        renderFilledArrowhead(tgtCx, y, arrowDx, this)

        // 3. X marker on the target lifeline — two crossing diagonals,
        //    centred on (tgtCx, y + DESTROY_X_OFFSET).
        val xCy = y + DESTROY_X_OFFSET
        val half = DESTROY_X_SIZE / 2f
        tag(
            "line",
            mapOf(
                "x1" to fmt(tgtCx - half),
                "y1" to fmt(xCy - half),
                "x2" to fmt(tgtCx + half),
                "y2" to fmt(xCy + half),
                "class" to "kuml-edge",
            ),
        )
        tag(
            "line",
            mapOf(
                "x1" to fmt(tgtCx + half),
                "y1" to fmt(xCy - half),
                "x2" to fmt(tgtCx - half),
                "y2" to fmt(xCy + half),
                "class" to "kuml-edge",
            ),
        )

        // 4. Stereotype + label above the arrow.
        val labelX = (srcCx + tgtCx) / 2f
        tag(
            "text",
            mapOf(
                "class" to "kuml-stereotype",
                "x" to fmt(labelX),
                "y" to fmt(y - 16f),
                "text-anchor" to "middle",
            ),
        ) { text("«destroy»") }
        tag(
            "text",
            mapOf(
                "class" to "kuml-body",
                "x" to fmt(labelX),
                "y" to fmt(y - 4f),
                "text-anchor" to "middle",
            ),
        ) { text(xmlEscapeText(msg.messageLabel)) }
    }
}

/**
 * Render an Execution Specification — V2.0.15.
 *
 * A thin vertical "activation bar" (small filled rect) on a lifeline,
 * spanning [ExecutionSpecificationUsage.startSeqNo] to [ExecutionSpecificationUsage.endSeqNo]
 * (inclusive). The bar is centred horizontally on the lifeline's vertical
 * line and offset half a row above the first message and half a row past
 * the last so it brackets the active range without overlapping the arrow
 * exactly.
 *
 * V2.0.15 MVP: flat — overlapping activations on the same lifeline are
 * V2.x. The bar has a fixed width [EXEC_SPEC_WIDTH] and white fill so
 * messages drawn after it stay visually on top.
 */
internal fun renderExecutionSpec(
    execSpec: ExecutionSpecificationUsage,
    lifelineLayout: NodeLayout,
    builder: SvgBuilder,
) {
    val cx = lifelineLayout.bounds.origin.x + lifelineLayout.bounds.size.width / 2f
    val headBottom = lifelineLayout.bounds.origin.y + SEQ_RENDERER_HEAD_HEIGHT
    val yStart = headBottom + (execSpec.startSeqNo + 0.5f) * SEQ_RENDERER_MESSAGE_ROW_HEIGHT
    val yEnd = headBottom + (execSpec.endSeqNo + 1.5f) * SEQ_RENDERER_MESSAGE_ROW_HEIGHT
    val w = EXEC_SPEC_WIDTH
    val h = (yEnd - yStart).coerceAtLeast(SEQ_RENDERER_MESSAGE_ROW_HEIGHT / 2f)

    builder.tag(
        "g",
        mapOf("id" to xmlEscapeAttr(execSpec.id)),
    ) {
        tag(
            "rect",
            mapOf(
                "x" to fmt(cx - w / 2f),
                "y" to fmt(yStart),
                "width" to fmt(w),
                "height" to fmt(h),
                "class" to "kuml-class",
                "fill" to "white",
            ),
        )
    }
}

/**
 * Render a Combined Fragment — V2.0.15.
 *
 * Drawn as a dashed rectangle enclosing the horizontal span of all visible
 * lifelines and the vertical span of all the fragment's operands.
 *
 * Layout details:
 *  - Frame X spans from the leftmost visible lifeline's left edge to the
 *    rightmost visible lifeline's right edge, plus [FRAGMENT_PADDING] on
 *    both sides for visual breathing room.
 *  - Frame Y spans from the first operand's startSeqNo (half a row above) to
 *    the last operand's endSeqNo (one and a half rows below the message
 *    line) — same offsets the exec-spec uses, so frames + activation bars
 *    line up visually.
 *  - An operator-tag pentagon sits in the top-left corner of the frame,
 *    containing the operator's name uppercased (`ALT`, `OPT`, ...).
 *  - Between operands, a dashed horizontal separator appears at the boundary
 *    seqNo, with the next operand's guard (`[guard]`) in its top-left corner.
 *
 * The frame is rendered without a fill so the lifeline lines + messages
 * drawn after it remain visible inside.
 */
internal fun renderCombinedFragment(
    fragment: CombinedFragmentUsage,
    visibleLifelineLayouts: List<NodeLayout>,
    builder: SvgBuilder,
) {
    if (visibleLifelineLayouts.isEmpty() || fragment.operands.isEmpty()) return
    val minLifelineX = visibleLifelineLayouts.minOf { it.bounds.origin.x }
    val maxLifelineX = visibleLifelineLayouts.maxOf { it.bounds.origin.x + it.bounds.size.width }
    val anyLayout = visibleLifelineLayouts.first()
    val headBottom = anyLayout.bounds.origin.y + SEQ_RENDERER_HEAD_HEIGHT

    val minStartSeqNo = fragment.operands.minOf { it.startSeqNo }
    val maxEndSeqNo = fragment.operands.maxOf { it.endSeqNo }

    val frameX = minLifelineX - FRAGMENT_PADDING
    val frameW = (maxLifelineX - minLifelineX) + 2f * FRAGMENT_PADDING
    val frameY = headBottom + (minStartSeqNo + 0.5f) * SEQ_RENDERER_MESSAGE_ROW_HEIGHT - FRAGMENT_PADDING
    val frameBottom = headBottom + (maxEndSeqNo + 1.5f) * SEQ_RENDERER_MESSAGE_ROW_HEIGHT + FRAGMENT_PADDING
    val frameH = frameBottom - frameY

    builder.tag(
        "g",
        mapOf("id" to xmlEscapeAttr(fragment.id)),
    ) {
        // 1. Dashed frame rectangle.
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

        // 2. Operator-tag pentagon in the top-left corner. Five points: a
        //    rectangle with a notched right edge so it looks like the UML
        //    interaction-frame tag.
        val tagX = frameX
        val tagY = frameY
        val tagW = FRAGMENT_OPERATOR_TAG_WIDTH
        val tagH = FRAGMENT_OPERATOR_TAG_HEIGHT
        val notch = 6f
        val pentagonPoints =
            "${fmt(tagX)},${fmt(tagY)} " +
                "${fmt(tagX + tagW)},${fmt(tagY)} " +
                "${fmt(tagX + tagW)},${fmt(tagY + tagH - notch)} " +
                "${fmt(tagX + tagW - notch)},${fmt(tagY + tagH)} " +
                "${fmt(tagX)},${fmt(tagY + tagH)}"
        tag(
            "polygon",
            mapOf(
                "points" to pentagonPoints,
                "class" to "kuml-class",
                "fill" to "white",
            ),
        )
        tag(
            "text",
            mapOf(
                "class" to "kuml-stereotype",
                "x" to fmt(tagX + tagW / 2f),
                "y" to fmt(tagY + tagH / 2f + 4f),
                "text-anchor" to "middle",
            ),
        ) { text(fragment.operator.name.uppercase()) }

        // 3. Operand guards — one per operand, in the top-left of each
        //    operand's vertical slice.
        //
        // V2.0.44: Track the previous operand's endSeqNo so that separator lines
        // for empty operands (those whose startSeqNo == previous endSeqNo, i.e. no
        // messages of their own) are pushed one full row below the last message of
        // the previous operand. Without this guard, the separator and guard label
        // land on top of the last message arrow, producing overlaps like
        // "[credentials invalid]welcomeScreen".
        var prevEndSeqNo = minStartSeqNo - 1
        for ((index, operand) in fragment.operands.withIndex()) {
            val operandY: Float

            if (index == 0) {
                operandY = tagY + tagH + 4f
            } else {
                // Separator Y: use max(operand.startSeqNo, prevEndSeqNo + 1) to
                // guarantee the separator falls AFTER all messages of the previous
                // operand, even when startSeqNo was reused by the DSL author.
                val naturalSepSeqNo = operand.startSeqNo.toFloat()
                val guardedSepSeqNo = maxOf(naturalSepSeqNo, prevEndSeqNo + 1f)
                val sepY = headBottom + (guardedSepSeqNo + 0.5f) * SEQ_RENDERER_MESSAGE_ROW_HEIGHT
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
                operandY = sepY + 12f
            }

            prevEndSeqNo = operand.endSeqNo

            val guard = operand.guard
            if (guard != null) {
                tag(
                    "text",
                    mapOf(
                        "class" to "kuml-body",
                        "x" to fmt(tagX + tagW + 6f),
                        "y" to fmt(operandY),
                    ),
                ) { text("[" + xmlEscapeText(guard) + "]") }
            }
        }
    }
}

/**
 * Self-Call — kleiner U-förmiger Pfeil rechts vom Lifeline-Mittelpunkt. Die
 * V2.0.11-MVP-Form: Linie von der Lifeline nach rechts ([SELF_CALL_WIDTH]),
 * Linie nach unten ([SELF_CALL_HEIGHT]), Linie zurück zur Lifeline (mit
 * Pfeilspitze am Ende). V2.x: vollwertige Activation-Box.
 */
private fun renderSelfCall(
    msg: MessageUsage,
    cx: Float,
    y: Float,
    builder: SvgBuilder,
) {
    val w = SELF_CALL_WIDTH
    val h = SELF_CALL_HEIGHT
    val isReply = msg.kind == MessageKind.Reply
    val strokeClass = if (isReply) "kuml-edge-dashed" else "kuml-edge"

    builder.tag(
        "g",
        mapOf("id" to xmlEscapeAttr(msg.id)),
    ) {
        // Drei Linien: rechts, runter, zurück.
        tag(
            "path",
            mapOf(
                "d" to "M ${fmt(cx)} ${fmt(y)} L ${fmt(cx + w)} ${fmt(y)} L ${fmt(cx + w)} ${fmt(y + h)} L ${fmt(cx)} ${fmt(y + h)}",
                "class" to strokeClass,
                "fill" to "none",
            ),
        )

        // Pfeilspitze am Ende (zeigt nach links auf die Lifeline).
        when (msg.kind) {
            MessageKind.Sync -> renderFilledArrowhead(cx, y + h, +8f, this)
            MessageKind.Async,
            MessageKind.Reply,
            -> renderOpenArrowhead(cx, y + h, +8f, this)
            // Self-calls of kind Create / Destroy are conceptually unusual
            // (a lifeline destroying itself); we fall back to an open
            // arrowhead so the U-shape still looks coherent.
            MessageKind.Create,
            MessageKind.Destroy,
            -> renderOpenArrowhead(cx, y + h, +8f, this)
        }

        // V2.0.44: Label positioned LEFT of the self-call brace (text-anchor="end")
        // instead of right-side. This keeps it away from the canvas right edge —
        // the self-call is already at the rightmost lifeline and "right of the U"
        // clips on narrow diagrams. Offset: 4px to the left of the start-corner.
        tag(
            "text",
            mapOf(
                "class" to "kuml-body",
                "x" to fmt(cx - 4f),
                "y" to fmt(y - 2f),
                "text-anchor" to "end",
            ),
        ) { text(xmlEscapeText(msg.messageLabel)) }
    }
}

/**
 * Gefüllte Pfeilspitze (synchroner Call) — Dreieck mit Spitze bei
 * `(tipX, y)` und Basis bei `(tipX + baseDx, y ± 4)`.
 */
private fun renderFilledArrowhead(
    tipX: Float,
    y: Float,
    baseDx: Float,
    builder: SvgBuilder,
) {
    val baseX = tipX + baseDx
    val points = "${fmt(tipX)},${fmt(y)} ${fmt(baseX)},${fmt(y - 4f)} ${fmt(baseX)},${fmt(y + 4f)}"
    builder.tag(
        "polygon",
        mapOf(
            "points" to points,
            "class" to "kuml-edge",
            "fill" to "currentColor",
        ),
    )
}

/**
 * Offene Pfeilspitze (asynchroner Send / Reply) — zwei Linien in V-Form an
 * der Spitze `(tipX, y)`.
 */
private fun renderOpenArrowhead(
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

/**
 * Helper für den Lookup eines Lifeline-NodeLayouts aus dem [LayoutResult]
 * über die Lifeline-Id.
 */
@Suppress("unused")
internal fun lookupLifeline(
    layoutResult: LayoutResult,
    lifelineId: String,
): NodeLayout? = layoutResult.nodes[NodeId(lifelineId)]

/**
 * Lifeline-Kopf-Höhe für den Renderer. Identisch mit der Bridge-Konstante
 * [dev.kuml.layout.bridge.Sysml2LayoutBridge.SEQ_LIFELINE_HEAD_HEIGHT] —
 * hier nochmal als lokale Konstante, damit der SVG-Modul keine direkte
 * Bridge-Abhängigkeit hat (sauberer Schichtenschnitt).
 */
private const val SEQ_RENDERER_HEAD_HEIGHT: Float = 40f

/**
 * Nachrichten-Zeilen-Höhe für den Renderer. Identisch mit der Bridge-Konstante
 * [dev.kuml.layout.bridge.Sysml2LayoutBridge.SEQ_MESSAGE_ROW_HEIGHT].
 */
private const val SEQ_RENDERER_MESSAGE_ROW_HEIGHT: Float = 32f

/** Self-Call U-Pfeil: Breite des Ausschwungs nach rechts. */
private const val SELF_CALL_WIDTH: Float = 24f

/** Self-Call U-Pfeil: Höhe des Ausschwungs nach unten. */
private const val SELF_CALL_HEIGHT: Float = 16f

/** Breite der Aktivierungs-Bar einer Execution Specification (V2.0.15). */
private const val EXEC_SPEC_WIDTH: Float = 10f

/** Breite der Operator-Tag-Pentagon-Form in der oberen linken Frame-Ecke (V2.0.15). */
private const val FRAGMENT_OPERATOR_TAG_WIDTH: Float = 50f

/** Höhe des Operator-Tag-Pentagons (V2.0.15). */
private const val FRAGMENT_OPERATOR_TAG_HEIGHT: Float = 18f

/** Horizontaler + vertikaler Atemraum zwischen Frame und Lifeline-Bounds (V2.0.15). */
private const val FRAGMENT_PADDING: Float = 8f

/** Vertikaler Abstand zwischen Arrow-Tip und X-Marker auf der Destroy-Lifeline (V2.0.15). */
private const val DESTROY_X_OFFSET: Float = 8f

/** Pixelgröße des X-Markers auf der Destroy-Lifeline (V2.0.15). */
private const val DESTROY_X_SIZE: Float = 10f

private fun fmt(v: Float): String =
    if (v == v.toInt().toFloat()) {
        v.toInt().toString()
    } else {
        String.format(java.util.Locale.US, "%.3f", v)
    }
