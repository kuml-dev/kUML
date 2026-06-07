package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.io.svg.xmlEscapeText
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.renderer.theme.core.KumlTheme
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
// V2.x:
//  - Combined Fragments (`alt` / `opt` / `loop` / `par` / `strict`) als
//    gerahmte Bereiche mit Header-Label.
//  - Execution Specifications — Aktivierungsrechtecke entlang der
//    Lifeline-Achse während ein Token aktiv ist.
//  - `Create` / `Destroy` Lifecycle-Nachrichten (Stereotyp `«create»`, X-Marker
//    am Ende der Lifeline).
//  - Self-Call mit vollwertigem Activation-Marker (V2.0.11 MVP ist ein
//    kleiner U-förmiger Pfeil — funktional, aber nicht hübsch).
//  - Found / Lost Messages — Pfeile von / nach außerhalb des Diagrammrahmens.
//  - Zeitachsen-Annotationen (Duration Constraints, Time Constraints).
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
        }

        // Label rechts neben dem Selfcall.
        tag(
            "text",
            mapOf(
                "class" to "kuml-body",
                "x" to fmt(cx + w + 4f),
                "y" to fmt(y - 2f),
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

private fun fmt(v: Float): String =
    if (v == v.toInt().toFloat()) {
        v.toInt().toString()
    } else {
        String.format(java.util.Locale.US, "%.3f", v)
    }
