package dev.kuml.io.svg.bpmn

import dev.kuml.bpmn.model.CallConversation
import dev.kuml.bpmn.model.ConversationLink
import dev.kuml.bpmn.model.ConversationNodeElement
import dev.kuml.bpmn.model.SubConversation
import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.fmt2
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.NodeLayout
import dev.kuml.renderer.theme.core.KumlTheme

// ── Layout-Konstanten ─────────────────────────────────────────────────────────

/**
 * Tatsächliche Formhöhe eines Conversation-Hexagons (40 px Shape).
 * Die Bounds-Höhe ([dev.kuml.layout.bridge.bpmn.BpmnLayoutBridge.DEFAULT_CONVERSATION_NODE_SIZE].height = 44)
 * lässt 4 px Label-Reserve oben und unten.
 */
internal const val CONV_HEX_SHAPE_H = 40f

/**
 * Inset-Faktor für das elongierte BPMN-Conversation-Hexagon.
 *
 * Gibt den Anteil der Breite an, den die Schräge der oberen/unteren Eckpunkte
 * einnimmt. Wert 0.25 ergibt ein Standard-BPMN-Konversations-Hexagon mit
 * Spitzen links/rechts und flacher Ober-/Unterkante.
 */
private const val CONV_HEX_INSET_FACTOR = 0.25f

// ── ConversationNodeElement ───────────────────────────────────────────────────

/**
 * Rendert einen [ConversationNodeElement] als BPMN-Conversation-Hexagon.
 *
 * BPMN 2.0 §9.4 unterscheidet drei Hexagon-Varianten:
 * - [dev.kuml.bpmn.model.ConversationNode]: Normaler Rand (stroke-width=1.5).
 * - [CallConversation]: Dicker Rand (stroke-width=3) — Call-Aktivitäts-Konvention.
 * - [SubConversation]: Normaler Rand + +-Marker unten-mitte — zeigt Expandierbarkeit.
 *
 * Das Hexagon ist ein elongiertes „pointy-left/right"-Hexagon mit Spitzen
 * links und rechts (Mitte) und zwei Schrägen oben und unten. Nicht ein
 * „pointy-top/bottom"-Hexagon — das BPMN-Standard-Conversation-Symbol hat
 * horizontale Spitzen.
 *
 * V3.2.3 — BPMN Conversation Diagram: SVG-Renderer
 */
internal fun renderConversationNode(
    node: ConversationNodeElement,
    layout: NodeLayout,
    theme: KumlTheme,
    builder: SvgBuilder,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height

    val nodeFill = theme.colors.effectiveNodeFill.toHex()
    val borderColor = theme.colors.border.toHex()
    val textColor = theme.colors.foreground.toHex()
    val fontFamily = theme.typography.body.family

    val cx = x + w / 2f
    val cy = y + CONV_HEX_SHAPE_H / 2f

    val strokeWidth =
        when (node) {
            is CallConversation -> "3" // Dicker Rand für Call-Conversations
            else -> "1.5"
        }

    builder.tag("g", mapOf("id" to xmlEscapeAttr(node.id))) {
        // 1. Hexagon-Shape
        rawXml(
            """<polygon points="${hexagonPoints(cx, cy, w, CONV_HEX_SHAPE_H)}" """ +
                """fill="$nodeFill" stroke="$borderColor" stroke-width="$strokeWidth"/>""",
        )

        // 2. Für SubConversation: +-Marker unten-mitte im Hexagon
        if (node is SubConversation) {
            val markerCx = cx
            val markerCy = cy + CONV_HEX_SHAPE_H / 2f - 8f
            val arm = 4f
            rawXml(
                """<line x1="${fmtF(markerCx)}" y1="${fmtF(markerCy - arm)}" """ +
                    """x2="${fmtF(markerCx)}" y2="${fmtF(markerCy + arm)}" """ +
                    """stroke="$borderColor" stroke-width="1.5"/>""",
            )
            rawXml(
                """<line x1="${fmtF(markerCx - arm)}" y1="${fmtF(markerCy)}" """ +
                    """x2="${fmtF(markerCx + arm)}" y2="${fmtF(markerCy)}" """ +
                    """stroke="$borderColor" stroke-width="1.5"/>""",
            )
        }

        // 3+4. Label-Rendering:
        //   - Kurze Labels (≤ 8 Zeichen) passen ins Hexagon → intern zentriert.
        //   - Lange Labels (> 8 Zeichen) haben im kleinen Hexagon keinen Platz →
        //     nur als Untertitel unterhalb des Hexagons (kein internes Label, kein Doppel-Rendering).
        val label = node.name
        if (!label.isNullOrBlank() && label.length <= 8) {
            // Kurzes Label: intern in der Hexagon-Mitte
            tag(
                "text",
                mapOf(
                    "x" to fmtF(cx),
                    "y" to fmtF(cy + 1f),
                    "text-anchor" to "middle",
                    "dominant-baseline" to "middle",
                    "font-family" to fontFamily,
                    "font-size" to "10",
                    "fill" to textColor,
                ),
            ) { text(label) }
        } else if (!label.isNullOrBlank()) {
            // Langes Label (> 8 Zeichen): nur als Untertitel unterhalb des Hexagons
            tag(
                "text",
                mapOf(
                    "x" to fmtF(cx),
                    "y" to fmtF(y + h + 11f),
                    "text-anchor" to "middle",
                    "font-family" to fontFamily,
                    "font-size" to "10",
                    "fill" to textColor,
                ),
            ) { text(label) }
        }
    }
}

// ── Conversation Participant ──────────────────────────────────────────────────

/**
 * Rendert einen Conversation-Participant als Rechteck.
 *
 * Participants im BPMN-Conversation-Diagramm sind sichtbare Rechtecke
 * (analog zu Pools in Collaboration-Diagrammen), NICHT Phantomknoten.
 * Fett-Text für den Namen (Participant = Pool-Äquivalent).
 *
 * V3.2.3 — BPMN Conversation Diagram: SVG-Renderer
 */
internal fun renderConversationParticipant(
    participantName: String,
    layout: NodeLayout,
    theme: KumlTheme,
    builder: SvgBuilder,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height

    val nodeFill = theme.colors.effectiveNodeFill.toHex()
    val borderColor = theme.colors.border.toHex()
    val textColor = theme.colors.foreground.toHex()
    val fontFamily = theme.typography.body.family

    val safeId = participantName.replace(Regex("[^a-zA-Z0-9]"), "_")
    builder.tag("g", mapOf("id" to "conv-participant-$safeId")) {
        // Participant-Rechteck (rx=3 für leichte Abrundung, analog Pool-Stil)
        rawXml(
            """<rect x="${fmtF(x)}" y="${fmtF(y)}" width="${fmtF(w)}" height="${fmtF(h)}" """ +
                """rx="3" fill="$nodeFill" stroke="$borderColor" stroke-width="1.5"/>""",
        )
        // Name zentriert, fett (Participants sind prominente Elemente)
        tag(
            "text",
            mapOf(
                "x" to fmtF(x + w / 2f),
                "y" to fmtF(y + h / 2f + 1f),
                "text-anchor" to "middle",
                "dominant-baseline" to "middle",
                "font-family" to fontFamily,
                "font-size" to "12",
                "font-weight" to "bold",
                "fill" to textColor,
            ),
        ) { text(participantName) }
    }
}

// ── Conversation Link ─────────────────────────────────────────────────────────

/**
 * Rendert einen [ConversationLink] als BPMN-Conversation-Link-Linie.
 *
 * BPMN 2.0 §9.5.3: Conversation Links haben **keinen Pfeilkopf** — sie
 * repräsentieren nur die Teilnahme, keine gerichtete Kommunikation.
 *
 * V3.2.3 — BPMN Conversation Diagram: SVG-Renderer
 */
internal fun renderConversationLink(
    link: ConversationLink,
    route: EdgeRoute,
    builder: SvgBuilder,
    theme: KumlTheme,
) {
    val src = route.source
    val tgt = route.target

    val edgeColor = theme.colors.edge.toHex()
    val labelColor = theme.colors.muted.toHex()
    val fontFamily = theme.typography.body.family

    // Pfad-Daten (analog BpmnChoreoSvg.buildChoreoPolyline)
    val pathD =
        when (route) {
            is EdgeRoute.Direct ->
                "M ${fmtF(src.x)} ${fmtF(src.y)} L ${fmtF(tgt.x)} ${fmtF(tgt.y)}"

            is EdgeRoute.OrthogonalRounded -> {
                val allPts = listOf(src) + route.waypoints + listOf(tgt)
                buildConvPolyline(allPts)
            }

            is EdgeRoute.TreeRounded -> {
                val allPts = listOf(src) + route.waypoints + listOf(tgt)
                buildConvPolyline(allPts)
            }

            is EdgeRoute.Bezier ->
                "M ${fmtF(src.x)} ${fmtF(src.y)} L ${fmtF(tgt.x)} ${fmtF(tgt.y)}"
        }

    // KEIN marker-end — BPMN-2.0-Spec §9.5.3: Conversation Links sind ungerichtet
    builder.rawXml(
        """<path d="$pathD" fill="none" stroke="$edgeColor" stroke-width="1.5"/>""",
    )

    // Optionales Name-Label an der Routenmitte
    val label = link.name
    if (!label.isNullOrBlank()) {
        val midX: Float
        val midY: Float
        when (route) {
            is EdgeRoute.OrthogonalRounded -> {
                val allPts = listOf(src) + route.waypoints + listOf(tgt)
                val mid = allPts[allPts.size / 2]
                midX = mid.x
                midY = mid.y
            }

            is EdgeRoute.TreeRounded -> {
                val allPts = listOf(src) + route.waypoints + listOf(tgt)
                val mid = allPts[allPts.size / 2]
                midX = mid.x
                midY = mid.y
            }

            else -> {
                midX = (src.x + tgt.x) / 2f
                midY = (src.y + tgt.y) / 2f
            }
        }
        builder.tag(
            "text",
            mapOf(
                "x" to fmtF(midX + 4f),
                "y" to fmtF(midY - 4f),
                "font-family" to fontFamily,
                "font-size" to "10",
                "fill" to labelColor,
            ),
        ) { text(label) }
    }
}

// ── Private Helpers ───────────────────────────────────────────────────────────

/**
 * Berechnet die 6 Punkte eines elongierten BPMN-Conversation-Hexagons.
 *
 * Das Hexagon hat Spitzen **links und rechts** (Mitte), flache Ober-/Unterkante,
 * und zwei Schrägen auf jeder Seite. [k] = Inset-Faktor (Anteil der Breite,
 * den die Schräge einnimmt), Standard 0.25.
 *
 * Reihenfolge im Uhrzeigersinn, Start linke Spitze:
 * 1. Linke Spitze (cx - hw, cy)
 * 2. Oben-links  (cx - hw + inset, cy - hh)
 * 3. Oben-rechts (cx + hw - inset, cy - hh)
 * 4. Rechte Spitze (cx + hw, cy)
 * 5. Unten-rechts (cx + hw - inset, cy + hh)
 * 6. Unten-links  (cx - hw + inset, cy + hh)
 */
private fun hexagonPoints(
    cx: Float,
    cy: Float,
    w: Float,
    h: Float,
    k: Float = CONV_HEX_INSET_FACTOR,
): String {
    val hw = w / 2f
    val hh = h / 2f
    val inset = w * k
    val pts =
        listOf(
            cx - hw to cy, // 1: linke Spitze
            cx - hw + inset to cy - hh, // 2: oben-links
            cx + hw - inset to cy - hh, // 3: oben-rechts
            cx + hw to cy, // 4: rechte Spitze
            cx + hw - inset to cy + hh, // 5: unten-rechts
            cx - hw + inset to cy + hh, // 6: unten-links
        )
    return pts.joinToString(" ") { (px, py) -> "${fmtF(px)},${fmtF(py)}" }
}

private fun buildConvPolyline(points: List<dev.kuml.layout.Point>): String {
    if (points.isEmpty()) return ""
    val sb = StringBuilder()
    sb.append("M ${fmtF(points.first().x)} ${fmtF(points.first().y)}")
    points.drop(1).forEach { sb.append(" L ${fmtF(it.x)} ${fmtF(it.y)}") }
    return sb.toString()
}

private fun fmtF(v: Float): String = fmt2(v)
