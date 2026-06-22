package dev.kuml.io.svg.bpmn

import dev.kuml.bpmn.model.BpmnCallActivity
import dev.kuml.bpmn.model.BpmnSubProcess
import dev.kuml.bpmn.model.BpmnTask
import dev.kuml.bpmn.model.TaskType
import dev.kuml.io.svg.SvgBuilder
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
    @Suppress("UNUSED_PARAMETER") theme: KumlTheme,
    builder: SvgBuilder,
) {
    builder.tag("g", mapOf("id" to xmlEscapeAttr(task.id))) {
        renderActivityBox(layout, this, strokeWidth = 1.5f, label = task.name)
        renderBpmnTaskMarkers(task, layout, this)
        renderTaskTypeIcon(task.taskType, layout, this)
    }
}

internal fun renderBpmnSubProcess(
    sp: BpmnSubProcess,
    layout: NodeLayout,
    @Suppress("UNUSED_PARAMETER") theme: KumlTheme,
    builder: SvgBuilder,
) {
    builder.tag("g", mapOf("id" to xmlEscapeAttr(sp.id))) {
        renderActivityBox(layout, this, strokeWidth = 1.5f, label = sp.name, rx = 8f)
        renderBpmnTaskMarkers(sp, layout, this)

        val x = layout.bounds.origin.x
        val y = layout.bounds.origin.y
        val w = layout.bounds.size.width
        val h = layout.bounds.size.height

        if (!sp.expanded) {
            // Collapsed: + Symbol in der Mitte unten
            val cx = x + w / 2f
            val cy = y + h - 12f
            rawXml(
                """<rect x="${fmtF(cx - 7f)}" y="${fmtF(cy - 7f)}" width="14" height="14" """ +
                    """rx="2" fill="white" stroke="#333" stroke-width="1"/>""",
            )
            rawXml(
                """<line x1="${fmtF(cx)}" y1="${fmtF(cy - 4f)}" """ +
                    """x2="${fmtF(cx)}" y2="${fmtF(cy + 4f)}" stroke="#333" stroke-width="1.5"/>""",
            )
            rawXml(
                """<line x1="${fmtF(cx - 4f)}" y1="${fmtF(cy)}" """ +
                    """x2="${fmtF(cx + 4f)}" y2="${fmtF(cy)}" stroke="#333" stroke-width="1.5"/>""",
            )
        }

        if (sp.transactional) {
            // Doppelter Rahmen: inneres Rechteck 4px eingerückt
            rawXml(
                """<rect x="${fmtF(x + 4f)}" y="${fmtF(y + 4f)}" """ +
                    """width="${fmtF(w - 8f)}" height="${fmtF(h - 8f)}" """ +
                    """rx="6" fill="none" stroke="#333" stroke-width="1"/>""",
            )
        }
    }
}

internal fun renderBpmnCallActivity(
    ca: BpmnCallActivity,
    layout: NodeLayout,
    @Suppress("UNUSED_PARAMETER") theme: KumlTheme,
    builder: SvgBuilder,
) {
    builder.tag("g", mapOf("id" to xmlEscapeAttr(ca.id))) {
        renderActivityBox(layout, this, strokeWidth = 3f, label = ca.name)
    }
}

// ── Private helpers ───────────────────────────────────────────────────────────

private fun renderActivityBox(
    layout: NodeLayout,
    builder: SvgBuilder,
    strokeWidth: Float,
    label: String?,
    rx: Float = 6f,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height

    builder.rawXml(
        """<rect x="${fmtF(x)}" y="${fmtF(y)}" width="${fmtF(w)}" height="${fmtF(h)}" """ +
            """rx="${fmtF(rx)}" fill="white" stroke="#333" stroke-width="${fmtF(strokeWidth)}"/>""",
    )

    if (!label.isNullOrBlank()) {
        val cx = x + w / 2f
        val cy = y + h / 2f
        builder.tag(
            "text",
            mapOf(
                "x" to fmtF(cx),
                "y" to fmtF(cy + 4f),
                "text-anchor" to "middle",
                "dominant-baseline" to "middle",
                "font-family" to "sans-serif",
                "font-size" to "12",
                "fill" to "#333",
            ),
        ) { text(label) }
    }
}

private fun renderTaskTypeIcon(
    type: TaskType,
    layout: NodeLayout,
    builder: SvgBuilder,
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
            "text",
            mapOf(
                "x" to fmtF(ix + 6f),
                "y" to fmtF(iy + 10f),
                "text-anchor" to "middle",
                "font-size" to "10",
            ),
        ) { text(iconLabel) }
    }
}

private fun fmtF(v: Float): String {
    val i = v.toInt()
    return if (v == i.toFloat()) "$i" else "%.2f".format(java.util.Locale.ROOT, v)
}
