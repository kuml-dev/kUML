package dev.kuml.io.svg.bpmn

import dev.kuml.bpmn.model.BpmnCallActivity
import dev.kuml.bpmn.model.BpmnSubProcess
import dev.kuml.bpmn.model.BpmnTask
import dev.kuml.bpmn.model.TaskType
import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.fmt2
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.layout.NodeLayout
import dev.kuml.renderer.theme.core.KumlTheme

/**
 * Rendert BPMN-Activity-Elemente als abgerundete Rechteck-Boxen.
 *
 * - [BpmnTask]: normale Task-Box mit optionalem Typ-Icon und Loop-Markern
 * - [BpmnSubProcess]: Sub-Process-Box mit + (collapsed) oder doppeltem Rahmen (transactional)
 * - [BpmnCallActivity]: Task-Box mit dickem Rand (stroke-width=3)
 *
 * V3.1.3 — BPMN Process SVG-Renderer
 */
internal fun renderBpmnTask(
    task: BpmnTask,
    layout: NodeLayout,
    theme: KumlTheme,
    builder: SvgBuilder,
) {
    builder.tag(name = "g", attrs = mapOf("id" to xmlEscapeAttr(task.id))) {
        // boxId gives the inner <rect> its own id so SMIL fill/stroke-width animations
        // can target the shape directly. Animating fill on the parent <g> does NOT
        // propagate to a child <rect> that carries its own explicit fill="white".
        renderActivityBox(layout = layout, builder = this, strokeWidth = 1.5f, label = task.name, boxId = "${task.id}-box", theme = theme)
        renderBpmnTaskMarkers(activity = task, layout = layout, builder = this, theme = theme)
        renderTaskTypeIcon(type = task.taskType, layout = layout, builder = this, theme = theme)
    }
}

internal fun renderBpmnSubProcess(
    sp: BpmnSubProcess,
    layout: NodeLayout,
    theme: KumlTheme,
    builder: SvgBuilder,
) {
    builder.tag(name = "g", attrs = mapOf("id" to xmlEscapeAttr(sp.id))) {
        val x = layout.bounds.origin.x
        val y = layout.bounds.origin.y
        val w = layout.bounds.size.width
        val h = layout.bounds.size.height

        val nodeFill = theme.colors.effectiveNodeFill.toHex()
        val borderColor = theme.colors.border.toHex()
        val textColor = theme.colors.foreground.toHex()
        val fontFamily = theme.typography.body.family

        if (sp.expanded) {
            // Expanded SubProcess: the frame contains its child flow-nodes, so
            // the name must NOT be centred (it would collide with the children).
            // BPMN convention places the name at the top of the frame.
            renderActivityBox(layout = layout, builder = this, strokeWidth = 1.5f, label = null, rx = 8f, theme = theme)
            if (!sp.name.isNullOrBlank()) {
                tag(
                    name = "text",
                    attrs =
                        mapOf(
                            "x" to fmtF(x + w / 2f),
                            "y" to fmtF(y + 16f),
                            "text-anchor" to "middle",
                            "dominant-baseline" to "middle",
                            "font-family" to fontFamily,
                            "font-size" to "12",
                            "fill" to textColor,
                        ),
                ) { text(sp.name!!) }
            }
        } else {
            renderActivityBox(layout = layout, builder = this, strokeWidth = 1.5f, label = sp.name, rx = 8f, theme = theme)
        }
        renderBpmnTaskMarkers(activity = sp, layout = layout, builder = this, theme = theme)

        if (!sp.expanded) {
            // Collapsed: + Symbol in der Mitte unten
            val cx = x + w / 2f
            val cy = y + h - 12f
            rawXml(
                """<rect x="${fmtF(cx - 7f)}" y="${fmtF(cy - 7f)}" width="14" height="14" """ +
                    """rx="2" fill="$nodeFill" stroke="$borderColor" stroke-width="1"/>""",
            )
            rawXml(
                """<line x1="${fmtF(cx)}" y1="${fmtF(cy - 4f)}" """ +
                    """x2="${fmtF(cx)}" y2="${fmtF(cy + 4f)}" stroke="$borderColor" stroke-width="1.5"/>""",
            )
            rawXml(
                """<line x1="${fmtF(cx - 4f)}" y1="${fmtF(cy)}" """ +
                    """x2="${fmtF(cx + 4f)}" y2="${fmtF(cy)}" stroke="$borderColor" stroke-width="1.5"/>""",
            )
        }

        if (sp.transactional) {
            // Doppelter Rahmen: inneres Rechteck 4px eingerückt
            rawXml(
                """<rect x="${fmtF(x + 4f)}" y="${fmtF(y + 4f)}" """ +
                    """width="${fmtF(w - 8f)}" height="${fmtF(h - 8f)}" """ +
                    """rx="6" fill="none" stroke="$borderColor" stroke-width="1"/>""",
            )
        }
    }
}

internal fun renderBpmnCallActivity(
    ca: BpmnCallActivity,
    layout: NodeLayout,
    theme: KumlTheme,
    builder: SvgBuilder,
) {
    builder.tag(name = "g", attrs = mapOf("id" to xmlEscapeAttr(ca.id))) {
        renderActivityBox(layout = layout, builder = this, strokeWidth = 3f, label = ca.name, theme = theme)
    }
}

// ── Private helpers ───────────────────────────────────────────────────────────

private fun renderActivityBox(
    layout: NodeLayout,
    builder: SvgBuilder,
    strokeWidth: Float,
    label: String?,
    rx: Float = 6f,
    boxId: String? = null,
    theme: KumlTheme,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height

    val nodeFill = theme.colors.effectiveNodeFill.toHex()
    val borderColor = theme.colors.border.toHex()
    val textColor = theme.colors.foreground.toHex()
    val fontFamily = theme.typography.body.family

    val idAttr = if (boxId != null) """id="${xmlEscapeAttr(boxId)}" """ else ""
    builder.rawXml(
        """<rect ${idAttr}x="${fmtF(x)}" y="${fmtF(y)}" width="${fmtF(w)}" height="${fmtF(h)}" """ +
            """rx="${fmtF(rx)}" fill="$nodeFill" stroke="$borderColor" stroke-width="${fmtF(strokeWidth)}"/>""",
    )

    // Overlay-Rect ohne eigenes stroke-width-Presentation-Attribut: SMIL <animate> auf
    // stroke-width ist auf einem <rect> mit inline stroke-width browser-inkonsistent
    // (Chrome/Safari ignorieren die Animation). Das transparente Overlay (fill="none",
    // stroke-width="0") ist das alleinige Ziel der Pulse-Animation. pointer-events="none"
    // hält Klick-/Hover-Verhalten beim Haupt-Rect.
    if (boxId != null) {
        builder.rawXml(
            """<rect id="${xmlEscapeAttr(boxId)}-pulse" x="${fmtF(x)}" y="${fmtF(y)}" """ +
                """width="${fmtF(w)}" height="${fmtF(h)}" rx="${fmtF(rx)}" """ +
                """fill="none" stroke="$borderColor" stroke-width="0" pointer-events="none"/>""",
        )
    }

    if (!label.isNullOrBlank()) {
        val cx = x + w / 2f
        val cy = y + h / 2f
        val estimatedTextW = label.length * BPMN_TASK_LABEL_CHAR_PX
        val maxTextW = (w - BPMN_TASK_LABEL_H_PADDING).coerceAtLeast(0f)
        val baseAttrs =
            mapOf(
                "x" to fmtF(cx),
                "y" to fmtF(cy + 4f),
                "text-anchor" to "middle",
                "dominant-baseline" to "middle",
                "font-family" to fontFamily,
                "font-size" to "12",
                "fill" to textColor,
            )
        // BpmnContentSizeProvider.taskBoxSize (kuml-layout-bridge) caps box width at
        // MAX_TASK_WIDTH as a DoS guard against pathological label input, but that only
        // bounds the *box* — this renderer previously drew the label as unclamped
        // single-line text regardless, so any name whose estimate exceeded the cap still
        // rendered wider than the box. textLength + lengthAdjust compresses the glyphs to
        // fit, same pattern as UmlSequenceSvg.drawLabelWithWhiteBackground.
        val attrs =
            if (estimatedTextW > maxTextW) {
                baseAttrs + mapOf("textLength" to fmtF(maxTextW), "lengthAdjust" to "spacingAndGlyphs")
            } else {
                baseAttrs
            }
        builder.tag(name = "text", attrs = attrs) { text(label) }
    }
}

/**
 * Estimated pixel width per character for a task/sub-process/call-activity
 * label at `font-size 12`. Duplicated from
 * `BpmnContentSizeProvider.TASK_CHAR_PX` (kuml-layout-bridge) — per the house
 * convention (`kuml-io-svg` can't depend on `kuml-layout-bridge`) — solely so
 * this renderer can tell when its own estimate would overflow the box the
 * bridge actually sized; MUST stay numerically identical to that constant.
 */
private const val BPMN_TASK_LABEL_CHAR_PX: Float = 7.0f

/**
 * Horizontal padding reserved inside the box before the label starts
 * overflowing. Duplicated from `BpmnContentSizeProvider.BOX_H_PADDING`.
 */
private const val BPMN_TASK_LABEL_H_PADDING: Float = 24f

private fun renderTaskTypeIcon(
    type: TaskType,
    layout: NodeLayout,
    builder: SvgBuilder,
    theme: KumlTheme,
) {
    if (type == TaskType.NONE) return
    val ix = layout.bounds.origin.x + 6f
    val iy = layout.bounds.origin.y + 6f

    // Einfache ASCII-Platzhalter als Task-Typ-Kennzeichen (SVG-kompatibel)
    val iconLabel =
        when (type) {
            TaskType.USER -> "U"
            TaskType.SERVICE -> "⚙"
            TaskType.SEND -> "S"
            TaskType.RECEIVE -> "R"
            TaskType.MANUAL -> "M"
            TaskType.SCRIPT -> "#"
            TaskType.BUSINESS_RULE -> "B"
            TaskType.NONE -> ""
        }

    if (iconLabel.isNotEmpty()) {
        builder.tag(
            name = "text",
            attrs =
                mapOf(
                    "x" to fmtF(ix + 6f),
                    "y" to fmtF(iy + 10f),
                    "text-anchor" to "middle",
                    "font-size" to "10",
                    // Explicit fill prevents inheritance from parent <g> when SMIL
                    // animates the group fill (e.g. task highlight animation).
                    "fill" to theme.colors.foreground.toHex(),
                ),
        ) { text(iconLabel) }
    }
}

private fun fmtF(v: Float): String = fmt2(v)
