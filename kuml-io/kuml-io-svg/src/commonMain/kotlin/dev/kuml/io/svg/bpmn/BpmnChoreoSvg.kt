package dev.kuml.io.svg.bpmn

import dev.kuml.bpmn.model.BpmnLoopType
import dev.kuml.bpmn.model.ChoreographyEvent
import dev.kuml.bpmn.model.ChoreographyGateway
import dev.kuml.bpmn.model.ChoreographySequenceFlow
import dev.kuml.bpmn.model.ChoreographyTask
import dev.kuml.bpmn.model.EventPosition
import dev.kuml.bpmn.model.GatewayType
import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.fmt2
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.NodeLayout
import dev.kuml.renderer.theme.core.KumlTheme
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

// ── Layout constants ─────────────────────────────────────────────────────────

/** Height of each participant band (top initiating, bottom receiving). */
internal const val CHOREO_BAND_H = 20f

/** Height of the middle task-name band. */
internal const val CHOREO_TASK_NAME_H = 40f

/** Inset for the inner double-border rect. */
internal const val CHOREO_INNER_INSET = 4f

/**
 * BPMN initiating-participant band fill (Aureolin yellow — BPMN convention:
 * the initiating participant is shown with a filled / coloured band).
 * Hard-coded to ensure correct contrast regardless of the active theme.
 */
private const val CHOREO_INITIATING_FILL = "#FFED00"

/**
 * Text colour on the initiating band (black for contrast on Aureolin yellow).
 * Must not follow `theme.colors.foreground` because dark-themes use white there.
 */
private const val CHOREO_INITIATING_TEXT = "#000000"

private const val BPMN_CHOREO_GATEWAY_SHAPE_H = 50f
private const val BPMN_CHOREO_EVENT_SHAPE_H = 36f

// ── ChoreographyTask ─────────────────────────────────────────────────────────

/**
 * Rendert einen [ChoreographyTask] als BPMN-Choreography-Task:
 *
 * - Äußere abgerundete Box
 * - Innerer Rahmen (4 px eingerückt, fill=none) für den Doppelrahmen-Effekt
 * - Oberes Band: initiierender Teilnehmer (Aureolin-Gelb, schwarzer Text)
 * - Unteres Band: empfangender Teilnehmer (weiß / Node-Fill, Vordergrundfarbe)
 * - Mittiges Textlabel (Task-Name)
 * - Optionaler Loop-Marker (STANDARD / MI_PARALLEL / MI_SEQ)
 * - Message-Envelope-Icons auf den Bändern (BPMN 2.0 §11.6.2): initiierende
 *   Nachricht (weißer/ungefüllter Umschlag) am oberen Band, Rückantwort
 *   (gefüllter/grauer Umschlag) am unteren Band.
 *
 * V3.2.2 — BPMN Choreography SVG-Renderer
 */
internal fun renderChoreographyTask(
    task: ChoreographyTask,
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

    builder.tag("g", mapOf("id" to xmlEscapeAttr(task.id))) {
        // 1. Äußere Box (weißer Hintergrund, einfacher Rahmen, abgerundete Ecken rx=8 gem. BPMN 2.0 §10.4.2)
        rawXml(
            """<rect x="${fmtF(x)}" y="${fmtF(y)}" width="${fmtF(w)}" height="${fmtF(h)}" """ +
                """rx="8" fill="$nodeFill" stroke="$borderColor" stroke-width="1.5"/>""",
        )

        // 2. Innerer Rahmen (4 px eingerückt, fill=none) → doppelter Rand-Effekt (rx=6 für sanften Versatz)
        rawXml(
            """<rect x="${fmtF(x + CHOREO_INNER_INSET)}" y="${fmtF(y + CHOREO_INNER_INSET)}" """ +
                """width="${fmtF(w - 2 * CHOREO_INNER_INSET)}" height="${fmtF(h - 2 * CHOREO_INNER_INSET)}" """ +
                """rx="6" fill="none" stroke="$borderColor" stroke-width="1"/>""",
        )

        // 3. Oberes Band: initiierender Teilnehmer (Aureolin-Gelb)
        // rx="8" muss mit der äußeren Box übereinstimmen, damit die abgerundeten
        // Ecken der äußeren Box nicht vom Band-Rect überdeckt werden (Painter's order:
        // Band-Rects liegen über der äußeren Box, Square-Corners würden die rx=8-Abrundung
        // der äußeren Box visuell überschreiben).
        rawXml(
            """<rect x="${fmtF(x)}" y="${fmtF(y)}" width="${fmtF(w)}" height="${fmtF(CHOREO_BAND_H)}" """ +
                """rx="8" fill="$CHOREO_INITIATING_FILL" stroke="$borderColor" stroke-width="1"/>""",
        )
        tag(
            "text",
            mapOf(
                "x" to fmtF(x + w / 2f),
                "y" to fmtF(y + CHOREO_BAND_H / 2f + 1f),
                "text-anchor" to "middle",
                "dominant-baseline" to "middle",
                "font-family" to fontFamily,
                "font-size" to "10",
                "fill" to CHOREO_INITIATING_TEXT,
            ),
        ) { text(task.initiatingParticipant) }

        // 4. Unteres Band: empfangender Teilnehmer (weiß / Node-Fill)
        // rx="8" analog zum oberen Band — verhindert Square-Corner-Bleed über die
        // abgerundeten unteren Ecken der äußeren Box.
        val receivingParticipant =
            task.participants.firstOrNull { it != task.initiatingParticipant }
                ?: task.participants.lastOrNull() ?: ""
        rawXml(
            """<rect x="${fmtF(x)}" y="${fmtF(y + h - CHOREO_BAND_H)}" width="${fmtF(w)}" height="${fmtF(CHOREO_BAND_H)}" """ +
                """rx="8" fill="$nodeFill" stroke="$borderColor" stroke-width="1"/>""",
        )
        tag(
            "text",
            mapOf(
                "x" to fmtF(x + w / 2f),
                "y" to fmtF(y + h - CHOREO_BAND_H / 2f + 1f),
                "text-anchor" to "middle",
                "dominant-baseline" to "middle",
                "font-family" to fontFamily,
                "font-size" to "10",
                "fill" to textColor,
            ),
        ) { text(receivingParticipant) }

        // 5. Task-Name: zentriert im Mittelband
        val taskName = task.name
        if (!taskName.isNullOrBlank()) {
            tag(
                "text",
                mapOf(
                    "x" to fmtF(x + w / 2f),
                    "y" to fmtF(y + CHOREO_BAND_H + CHOREO_TASK_NAME_H / 2f + 1f),
                    "text-anchor" to "middle",
                    "dominant-baseline" to "middle",
                    "font-family" to fontFamily,
                    "font-size" to "12",
                    "fill" to textColor,
                ),
            ) { text(taskName) }
        }

        // 6. Loop-Marker unten-mitte
        renderChoreoLoopMarker(task.loopType, x, y, w, h, borderColor, this)

        // 7. Message-Envelope-Icons an den Bändern (BPMN 2.0 §11.6.2)
        renderChoreoMessageEnvelopes(task.messageFlows, x, y, w, h, borderColor, this)
    }
}

/** Width/height of the small envelope glyph drawn above/below the participant bands. */
private const val CHOREO_ENVELOPE_W = 16f
private const val CHOREO_ENVELOPE_H = 10f

/** Fill for the non-initiating (return) message envelope — grey, per BPMN convention. */
private const val CHOREO_RETURN_ENVELOPE_FILL = "#DDDDDD"

/**
 * Rendert Message-Envelope-Icons für die [messageFlows] eines [ChoreographyTask]:
 * die initiierende Nachricht als weißer (fill=none) Umschlag oberhalb des oberen
 * Bands, die Rückantwort als gefüllter (grau) Umschlag unterhalb des unteren Bands.
 * Beide sind über eine kurze gestrichelte Stub-Linie mit ihrem Band verbunden.
 *
 * V3.2.2 — BPMN Choreography SVG-Renderer
 */
private fun renderChoreoMessageEnvelopes(
    messageFlows: List<dev.kuml.bpmn.model.ChoreographyMessageFlow>,
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    borderColor: String,
    builder: SvgBuilder,
) {
    val cx = x + w / 2f

    fun envelope(
        centerX: Float,
        centerY: Float,
        fill: String,
    ) {
        val ex = centerX - CHOREO_ENVELOPE_W / 2f
        val ey = centerY - CHOREO_ENVELOPE_H / 2f
        builder.rawXml(
            """<rect x="${fmtF(ex)}" y="${fmtF(ey)}" width="${fmtF(CHOREO_ENVELOPE_W)}" """ +
                """height="${fmtF(CHOREO_ENVELOPE_H)}" fill="$fill" stroke="$borderColor" stroke-width="1"/>""",
        )
        builder.rawXml(
            """<polyline points="${fmtF(ex)},${fmtF(ey)} ${fmtF(centerX)},${fmtF(ey + CHOREO_ENVELOPE_H / 2f)} """ +
                """${fmtF(ex + CHOREO_ENVELOPE_W)},${fmtF(ey)}" fill="none" stroke="$borderColor" stroke-width="1"/>""",
        )
    }

    val initiating = messageFlows.firstOrNull { it.isInitiating }
    if (initiating != null) {
        val envY = y - CHOREO_ENVELOPE_H / 2f - 4f
        builder.rawXml(
            """<line x1="${fmtF(cx)}" y1="${fmtF(y)}" x2="${fmtF(cx)}" y2="${fmtF(envY + CHOREO_ENVELOPE_H / 2f)}" """ +
                """stroke="$borderColor" stroke-width="1" stroke-dasharray="2,2"/>""",
        )
        envelope(cx, envY, "none")
    }

    val returning = messageFlows.firstOrNull { !it.isInitiating }
    if (returning != null) {
        val envY = y + h + CHOREO_ENVELOPE_H / 2f + 4f
        builder.rawXml(
            """<line x1="${fmtF(cx)}" y1="${fmtF(y + h)}" x2="${fmtF(cx)}" y2="${fmtF(envY - CHOREO_ENVELOPE_H / 2f)}" """ +
                """stroke="$borderColor" stroke-width="1" stroke-dasharray="2,2"/>""",
        )
        envelope(cx, envY, CHOREO_RETURN_ENVELOPE_FILL)
    }
}

// ── ChoreographyGateway ──────────────────────────────────────────────────────

/**
 * Rendert einen [ChoreographyGateway] als BPMN-Raute mit Typ-Symbol.
 *
 * Reused geometry from [renderBpmnGateway] (adapted for [ChoreographyGateway.type]).
 *
 * V3.2.2 — BPMN Choreography SVG-Renderer
 */
internal fun renderChoreographyGateway(
    gw: ChoreographyGateway,
    layout: NodeLayout,
    theme: KumlTheme,
    builder: SvgBuilder,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height
    val cx = x + w / 2f
    val cy = y + BPMN_CHOREO_GATEWAY_SHAPE_H / 2f
    val hw = w / 2f
    val hh = BPMN_CHOREO_GATEWAY_SHAPE_H / 2f

    val nodeFill = theme.colors.effectiveNodeFill.toHex()
    val borderColor = theme.colors.border.toHex()
    val textColor = theme.colors.foreground.toHex()
    val fontFamily = theme.typography.body.family

    builder.tag("g", mapOf("id" to xmlEscapeAttr(gw.id))) {
        // Raute
        rawXml(
            """<polygon id="${xmlEscapeAttr(gw.id)}-diamond" """ +
                """points="${fmtF(cx)},${fmtF(cy - hh)} ${fmtF(cx + hw)},${fmtF(cy)} """ +
                """${fmtF(cx)},${fmtF(cy + hh)} ${fmtF(cx - hw)},${fmtF(cy)}" """ +
                """fill="$nodeFill" stroke="$borderColor" stroke-width="1.5"/>""",
        )

        // Typ-Symbol in der Mitte
        val symbolSize = minOf(hw, hh) * 0.6f

        when (gw.type) {
            GatewayType.EXCLUSIVE -> {
                val d = symbolSize * 0.6f
                rawXml(
                    """<line x1="${fmtF(cx - d)}" y1="${fmtF(cy - d)}" """ +
                        """x2="${fmtF(cx + d)}" y2="${fmtF(cy + d)}" stroke="$borderColor" stroke-width="2"/>""",
                )
                rawXml(
                    """<line x1="${fmtF(cx + d)}" y1="${fmtF(cy - d)}" """ +
                        """x2="${fmtF(cx - d)}" y2="${fmtF(cy + d)}" stroke="$borderColor" stroke-width="2"/>""",
                )
            }

            GatewayType.PARALLEL -> {
                val arm = symbolSize * 0.8f
                rawXml(
                    """<line x1="${fmtF(cx)}" y1="${fmtF(cy - arm)}" """ +
                        """x2="${fmtF(cx)}" y2="${fmtF(cy + arm)}" stroke="$borderColor" stroke-width="2"/>""",
                )
                rawXml(
                    """<line x1="${fmtF(cx - arm)}" y1="${fmtF(cy)}" """ +
                        """x2="${fmtF(cx + arm)}" y2="${fmtF(cy)}" stroke="$borderColor" stroke-width="2"/>""",
                )
            }

            GatewayType.INCLUSIVE -> {
                rawXml(
                    """<circle cx="${fmtF(cx)}" cy="${fmtF(cy)}" r="${fmtF(symbolSize * 0.7f)}" """ +
                        """fill="none" stroke="$borderColor" stroke-width="2"/>""",
                )
            }

            GatewayType.EVENT_BASED -> {
                val r1 = symbolSize * 0.8f
                val r2 = symbolSize * 0.6f
                rawXml(
                    """<circle cx="${fmtF(cx)}" cy="${fmtF(cy)}" r="${fmtF(r1)}" fill="none" stroke="$borderColor" stroke-width="1"/>""",
                )
                rawXml(
                    """<circle cx="${fmtF(cx)}" cy="${fmtF(cy)}" r="${fmtF(r2)}" fill="none" stroke="$borderColor" stroke-width="1"/>""",
                )
                val pr = symbolSize * 0.4f
                val pts =
                    (0 until 5).joinToString(" ") { i ->
                        val angle = PI * (-0.5 + 2.0 * i / 5)
                        val px = cx + pr * cos(angle).toFloat()
                        val py = cy + pr * sin(angle).toFloat()
                        "${fmtF(px)},${fmtF(py)}"
                    }
                rawXml("""<polygon points="$pts" fill="none" stroke="$borderColor" stroke-width="1"/>""")
            }

            GatewayType.COMPLEX -> {
                val armR = symbolSize * 0.7f
                listOf(0.0, 60.0, 120.0).forEach { deg ->
                    val rad = (deg * PI / 180.0)
                    val dx = (armR * cos(rad)).toFloat()
                    val dy = (armR * sin(rad)).toFloat()
                    rawXml(
                        """<line x1="${fmtF(cx - dx)}" y1="${fmtF(cy - dy)}" """ +
                            """x2="${fmtF(cx + dx)}" y2="${fmtF(cy + dy)}" stroke="$borderColor" stroke-width="2"/>""",
                    )
                }
            }
        }

        // Label unterhalb der Raute
        val label = gw.name
        if (!label.isNullOrBlank()) {
            tag(
                "text",
                mapOf(
                    "x" to fmtF(cx),
                    "y" to fmtF(y + BPMN_CHOREO_GATEWAY_SHAPE_H + 12f),
                    "text-anchor" to "middle",
                    "font-family" to fontFamily,
                    "font-size" to "11",
                    "fill" to textColor,
                ),
            ) { text(label) }
        }
    }
}

// ── ChoreographyEvent ────────────────────────────────────────────────────────

/**
 * Rendert ein [ChoreographyEvent] als BPMN-Kreis-Symbol.
 *
 * - START: dünner Ring (stroke-width=1.5)
 * - INTERMEDIATE: Doppelring
 * - END: dicker Ring (stroke-width=3)
 *
 * Choreography-Events sind immer plain (kein EventDefinition-Symbol).
 *
 * V3.2.2 — BPMN Choreography SVG-Renderer
 */
internal fun renderChoreographyEvent(
    event: ChoreographyEvent,
    layout: NodeLayout,
    theme: KumlTheme,
    builder: SvgBuilder,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val cx = x + w / 2f
    val cy = y + BPMN_CHOREO_EVENT_SHAPE_H / 2f
    val r = BPMN_CHOREO_EVENT_SHAPE_H / 2f - 2f

    val nodeFill = theme.colors.effectiveNodeFill.toHex()
    val borderColor = theme.colors.border.toHex()
    val textColor = theme.colors.foreground.toHex()
    val fontFamily = theme.typography.body.family

    builder.tag("g", mapOf("id" to xmlEscapeAttr(event.id))) {
        when (event.position) {
            EventPosition.START -> {
                rawXml(
                    """<circle cx="${fmtF(cx)}" cy="${fmtF(cy)}" r="${fmtF(r)}" """ +
                        """fill="$nodeFill" stroke="$borderColor" stroke-width="1.5"/>""",
                )
            }

            EventPosition.INTERMEDIATE -> {
                rawXml(
                    """<circle cx="${fmtF(cx)}" cy="${fmtF(cy)}" r="${fmtF(r)}" """ +
                        """fill="$nodeFill" stroke="$borderColor" stroke-width="1.5"/>""",
                )
                rawXml(
                    """<circle cx="${fmtF(cx)}" cy="${fmtF(cy)}" r="${fmtF(r - 3f)}" """ +
                        """fill="none" stroke="$borderColor" stroke-width="1"/>""",
                )
            }

            EventPosition.END -> {
                rawXml(
                    """<circle cx="${fmtF(cx)}" cy="${fmtF(cy)}" r="${fmtF(r)}" """ +
                        """fill="$nodeFill" stroke="$borderColor" stroke-width="3"/>""",
                )
            }
        }

        // Label unterhalb des Events
        val label = event.name
        if (!label.isNullOrBlank()) {
            tag(
                "text",
                mapOf(
                    "x" to fmtF(cx),
                    "y" to fmtF(y + BPMN_CHOREO_EVENT_SHAPE_H + 12f),
                    "text-anchor" to "middle",
                    "font-family" to fontFamily,
                    "font-size" to "11",
                    "fill" to textColor,
                ),
            ) { text(label) }
        }
    }
}

// ── ChoreographySequenceFlow ─────────────────────────────────────────────────

/**
 * Rendert einen [ChoreographySequenceFlow] als BPMN-Pfeil-Kante.
 *
 * Analog zu [renderBpmnSequenceFlow] aus dem Process-Renderer, angepasst
 * für den Choreography-Typen (kein isDefault-Schrägstrich; optionales
 * Condition-Label).
 *
 * V3.2.2 — BPMN Choreography SVG-Renderer
 */
internal fun renderChoreoSequenceFlow(
    flow: ChoreographySequenceFlow,
    route: EdgeRoute,
    builder: SvgBuilder,
    theme: KumlTheme,
) {
    val src = route.source
    val tgt = route.target

    val edgeColor = theme.colors.edge.toHex()
    val labelColor = theme.colors.muted.toHex()
    val fontFamily = theme.typography.body.family

    // Pfad-Daten
    val pathD =
        when (route) {
            is EdgeRoute.Direct ->
                "M ${fmtF(src.x)} ${fmtF(src.y)} L ${fmtF(tgt.x)} ${fmtF(tgt.y)}"

            is EdgeRoute.OrthogonalRounded -> {
                val allPts = listOf(src) + route.waypoints + listOf(tgt)
                buildChoreoPolyline(allPts)
            }

            is EdgeRoute.TreeRounded -> {
                val allPts = listOf(src) + route.waypoints + listOf(tgt)
                buildChoreoPolyline(allPts)
            }

            is EdgeRoute.Bezier ->
                "M ${fmtF(src.x)} ${fmtF(src.y)} L ${fmtF(tgt.x)} ${fmtF(tgt.y)}"
        }

    // Eindeutige Marker-ID per Flow
    val safeId = flow.id.replace(Regex("[^a-zA-Z0-9]"), "_")
    val markerId = "choreo-seq-arrow-$safeId"

    // Pfeilkopf-Definition
    builder.rawXml(
        """<defs><marker id="$markerId" markerWidth="8" markerHeight="6" """ +
            """refX="7" refY="3" orient="auto">""" +
            """<polygon points="0,0 8,3 0,6" fill="$edgeColor"/></marker></defs>""",
    )

    // Sequence-Flow-Linie
    builder.rawXml(
        """<path d="$pathD" fill="none" stroke="$edgeColor" stroke-width="1.5" """ +
            """marker-end="url(#$markerId)"/>""",
    )

    // Condition-/Name-Label an der Routenmitte
    val label = flow.condition ?: flow.name
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

// ── Private helpers ───────────────────────────────────────────────────────────

private fun renderChoreoLoopMarker(
    loopType: BpmnLoopType?,
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    markerColor: String,
    builder: SvgBuilder,
) {
    val markerY = y + h - 16f
    val markerCx = x + w / 2f

    when (loopType) {
        BpmnLoopType.STANDARD -> {
            // Pfeil-Kreis ↻
            builder.rawXml(
                """<path d="M ${fmtF(markerCx - 6f)},${fmtF(markerY + 8f)} """ +
                    """A 6,6 0 1,1 ${fmtF(markerCx + 5f)},${fmtF(markerY + 3f)}" """ +
                    """fill="none" stroke="$markerColor" stroke-width="1.5"/>""",
            )
            builder.rawXml(
                """<polyline points="${fmtF(markerCx + 5f)},${fmtF(markerY)} """ +
                    """${fmtF(markerCx + 5f)},${fmtF(markerY + 3f)} """ +
                    """${fmtF(markerCx + 8f)},${fmtF(markerY + 3f)}" """ +
                    """fill="none" stroke="$markerColor" stroke-width="1.5"/>""",
            )
        }

        BpmnLoopType.MULTI_INSTANCE_SEQUENTIAL -> {
            // Drei horizontale Striche ≡
            listOf(-4f, 0f, 4f).forEach { dy ->
                builder.rawXml(
                    """<line x1="${fmtF(markerCx - 5f)}" y1="${fmtF(markerY + 8f + dy)}" """ +
                        """x2="${fmtF(markerCx + 5f)}" y2="${fmtF(markerY + 8f + dy)}" """ +
                        """stroke="$markerColor" stroke-width="1.5"/>""",
                )
            }
        }

        BpmnLoopType.MULTI_INSTANCE_PARALLEL -> {
            // Drei vertikale Striche ‖
            listOf(-4f, 0f, 4f).forEach { dx ->
                builder.rawXml(
                    """<line x1="${fmtF(markerCx + dx)}" y1="${fmtF(markerY + 2f)}" """ +
                        """x2="${fmtF(markerCx + dx)}" y2="${fmtF(markerY + 14f)}" """ +
                        """stroke="$markerColor" stroke-width="1.5"/>""",
                )
            }
        }

        BpmnLoopType.NONE, null -> {
            // Kein Marker
        }
    }
}

private fun buildChoreoPolyline(points: List<dev.kuml.layout.Point>): String {
    if (points.isEmpty()) return ""
    val sb = StringBuilder()
    sb.append("M ${fmtF(points.first().x)} ${fmtF(points.first().y)}")
    points.drop(1).forEach { sb.append(" L ${fmtF(it.x)} ${fmtF(it.y)}") }
    return sb.toString()
}

private fun fmtF(v: Float): String = fmt2(v)
