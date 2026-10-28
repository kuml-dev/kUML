package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.fmt3
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.layout.GroupLayout
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.sysml2.PortDirection
import dev.kuml.sysml2.PortUsage

private const val FRAME_TAB_H = 22f

/** Draws the IBD owner frame (`kuml-ibd-frame`) with the title tab `ibd [part] Owner`. */
internal fun renderIbdFrame(
    builder: SvgBuilder,
    group: GroupLayout?,
    ownerId: String,
    ownerName: String,
    padding: Float,
) {
    if (group == null) return
    val x = group.bounds.origin.x + padding
    val y = group.bounds.origin.y + padding
    val w = group.bounds.size.width
    val h = group.bounds.size.height
    val title = "ibd [part] $ownerName".let { if (it.length <= PORT_LABEL_MAX_CHARS) it else it.take(PORT_LABEL_MAX_CHARS - 1) + "…" }
    val tabW = title.length * 6.4f + 16f
    builder.tag(name = "g", attrs = mapOf("id" to xmlEscapeAttr("frame:$ownerId"))) {
        tag(
            name = "rect",
            attrs = mapOf("x" to fmt3(x), "y" to fmt3(y), "width" to fmt3(w), "height" to fmt3(h), "class" to "kuml-ibd-frame"),
        )
        tag(
            name = "rect",
            attrs =
                mapOf(
                    "x" to fmt3(x),
                    "y" to fmt3(y - FRAME_TAB_H),
                    "width" to fmt3(tabW),
                    "height" to fmt3(FRAME_TAB_H),
                    "class" to "kuml-ibd-frame-tab",
                ),
        )
        tag(
            name = "text",
            attrs = mapOf("class" to "kuml-body", "x" to fmt3(x + 8f), "y" to fmt3(y - 7f), "text-anchor" to "start"),
        ) { text(title) }
    }
}

/** Draws the owner's boundary ports on the frame: square, direction triangle, `name : Type` label. */
internal fun renderIbdOwnerPorts(
    builder: SvgBuilder,
    layout: LayoutResult,
    ports: List<PortUsage>,
    context: Sysml2NodeContext,
    ownerId: String,
    padding: Float,
) {
    val frame =
        layout.groups[
            dev.kuml.layout.bridge.Sysml2LayoutBridge
                .ibdFrameGroupId(ownerId),
        ] ?: return
    val fx0 = frame.bounds.origin.x
    val fy0 = frame.bounds.origin.y
    val fx1 = fx0 + frame.bounds.size.width
    val fy1 = fy0 + frame.bounds.size.height
    val half = IBD_PORT_SIZE / 2f
    for (port in ports) {
        val b = layout.nodes[NodeId(port.id)]?.bounds ?: continue
        val cx = b.origin.x + b.size.width / 2f
        val cy = b.origin.y + b.size.height / 2f
        val dTop = kotlin.math.abs(cy - fy0)
        val dBottom = kotlin.math.abs(cy - fy1)
        val dLeft = kotlin.math.abs(cx - fx0)
        val dRight = kotlin.math.abs(cx - fx1)
        val m = minOf(dTop, dBottom, dLeft, dRight)
        val (ix, iy) =
            when (m) {
                dTop -> 0f to 1f
                dBottom -> 0f to -1f
                dLeft -> 1f to 0f
                else -> -1f to 0f
            }
        val label = portLabel(name = port.name, port = port, context = context)
        builder.tag(name = "g", attrs = mapOf("id" to xmlEscapeAttr(port.id))) {
            tag(
                name = "rect",
                attrs =
                    mapOf(
                        "x" to fmt3(cx - half + padding),
                        "y" to fmt3(cy - half + padding),
                        "width" to fmt3(IBD_PORT_SIZE),
                        "height" to fmt3(IBD_PORT_SIZE),
                        "class" to "kuml-port kuml-sysml2-port",
                    ),
            )
            if (port.direction != null && port.direction != PortDirection.InOut) {
                val (dx, dy) = if (port.direction == PortDirection.In) ix to iy else -ix to -iy
                appendDirectionTriangle(cx = cx + padding, cy = cy + padding, dx = dx, dy = dy)
            }
            val onRight = ix < 0f
            val lx = if (onRight) cx - half - 4f else cx + half + 4f
            val ly =
                if (iy > 0f) {
                    cy + half + 12f
                } else if (iy < 0f) {
                    cy - half - 4f
                } else {
                    cy - half - 3f
                }
            tag(
                name = "text",
                attrs =
                    mapOf(
                        "class" to "kuml-port-label-halo",
                        "x" to fmt3(lx + padding),
                        "y" to fmt3(ly + padding),
                        "text-anchor" to (if (onRight) "end" else "start"),
                    ),
            ) { text(label) }
            tag(
                name = "text",
                attrs =
                    mapOf(
                        "class" to "kuml-port-label",
                        "x" to fmt3(lx + padding),
                        "y" to fmt3(ly + padding),
                        "text-anchor" to (if (onRight) "end" else "start"),
                    ),
            ) { text(label) }
        }
    }
}
