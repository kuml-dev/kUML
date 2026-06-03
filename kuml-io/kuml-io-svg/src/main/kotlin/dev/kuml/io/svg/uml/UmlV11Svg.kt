package dev.kuml.io.svg.uml

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.io.svg.xmlEscapeText
import dev.kuml.layout.NodeLayout
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.uml.UmlActivityNode
import dev.kuml.uml.UmlActivityNodeKind
import dev.kuml.uml.UmlArtifact
import dev.kuml.uml.UmlInteractionFrameKind
import dev.kuml.uml.UmlInteractionOverviewFrame
import dev.kuml.uml.UmlNode
import dev.kuml.uml.UmlStereotype
import dev.kuml.uml.UmlTimingLifeline

/**
 * V1.1 element renderers — kept compact and aligned with the V1 visual style.
 * Each rendering aims for the canonical UML 2.x notation but stays within the
 * single-rect → label idiom used throughout the codebase, with kind-specific
 * decorations.
 */

internal fun renderUmlNode(
    element: UmlNode,
    layout: NodeLayout,
    @Suppress("UNUSED_PARAMETER") theme: KumlTheme,
    builder: SvgBuilder,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height
    val depth = 8f
    builder.tag(
        "g",
        mapOf("id" to xmlEscapeAttr(element.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        // 3D cube illusion: a slanted parallelogram on top + right + front rectangle.
        tag(
            "polygon",
            mapOf(
                "points" to "0,$depth $depth,0 ${fmt(w)},0 ${fmt(w - depth)},$depth",
                "class" to "kuml-node-top",
            ),
        )
        tag(
            "polygon",
            mapOf(
                "points" to "${fmt(w - depth)},$depth ${fmt(w)},0 ${fmt(w)},${fmt(h - depth)} ${fmt(w - depth)},${fmt(h)}",
                "class" to "kuml-node-side",
            ),
        )
        tag(
            "rect",
            mapOf(
                "x" to "0",
                "y" to fmt(depth),
                "width" to fmt(w - depth),
                "height" to fmt(h - depth),
                "class" to "kuml-node",
            ),
        )
        val stereotype =
            when (element.nodeKind) {
                "executionEnvironment" -> "«executionEnvironment»"
                "device" -> "«device»"
                else -> null
            }
        var cy = depth + 16f
        stereotype?.let {
            tag(
                "text",
                mapOf("class" to "kuml-stereotype", "x" to fmt((w - depth) / 2f), "y" to fmt(cy), "text-anchor" to "middle"),
            ) { text(it) }
            cy += 14f
        }
        tag(
            "text",
            mapOf("class" to "kuml-title", "x" to fmt((w - depth) / 2f), "y" to fmt(cy), "text-anchor" to "middle"),
        ) { text(xmlEscapeText(element.name)) }
    }
}

internal fun renderUmlArtifact(
    element: UmlArtifact,
    layout: NodeLayout,
    @Suppress("UNUSED_PARAMETER") theme: KumlTheme,
    builder: SvgBuilder,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height
    val ear = 10f
    builder.tag(
        "g",
        mapOf("id" to xmlEscapeAttr(element.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        // Rectangle with a dog-eared top-right corner.
        tag(
            "polygon",
            mapOf(
                "points" to "0,0 ${fmt(w - ear)},0 ${fmt(w)},$ear ${fmt(w)},${fmt(h)} 0,${fmt(h)}",
                "class" to "kuml-artifact",
            ),
        )
        tag(
            "polygon",
            mapOf("points" to "${fmt(w - ear)},0 ${fmt(w - ear)},$ear ${fmt(w)},$ear", "class" to "kuml-artifact-ear"),
        )
        tag(
            "text",
            mapOf("class" to "kuml-stereotype", "x" to fmt(w / 2f), "y" to "20", "text-anchor" to "middle"),
        ) { text("«artifact»") }
        tag(
            "text",
            mapOf("class" to "kuml-title", "x" to fmt(w / 2f), "y" to "36", "text-anchor" to "middle"),
        ) { text(xmlEscapeText(element.name)) }
    }
}

internal fun renderUmlStereotype(
    element: UmlStereotype,
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
        tag("rect", mapOf("width" to fmt(w), "height" to fmt(h), "class" to "kuml-stereotype-box"))
        tag(
            "text",
            mapOf("class" to "kuml-stereotype", "x" to fmt(w / 2f), "y" to "18", "text-anchor" to "middle"),
        ) { text("«stereotype»") }
        tag(
            "text",
            mapOf("class" to "kuml-title", "x" to fmt(w / 2f), "y" to "34", "text-anchor" to "middle"),
        ) { text(xmlEscapeText(element.name)) }
    }
}

internal fun renderUmlActivityNode(
    element: UmlActivityNode,
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
        when (element.kind) {
            UmlActivityNodeKind.ACTION -> {
                tag(
                    "rect",
                    mapOf("width" to fmt(w), "height" to fmt(h), "rx" to "12", "ry" to "12", "class" to "kuml-action"),
                )
                tag(
                    "text",
                    mapOf("class" to "kuml-title", "x" to fmt(w / 2f), "y" to fmt(h / 2f + 5f), "text-anchor" to "middle"),
                ) { text(xmlEscapeText(element.name)) }
            }
            UmlActivityNodeKind.INITIAL ->
                tag(
                    "circle",
                    mapOf("cx" to fmt(w / 2f), "cy" to fmt(h / 2f), "r" to "10", "class" to "kuml-pseudostate"),
                )
            UmlActivityNodeKind.ACTIVITY_FINAL -> {
                tag(
                    "circle",
                    mapOf("cx" to fmt(w / 2f), "cy" to fmt(h / 2f), "r" to "12", "class" to "kuml-final-outer"),
                )
                tag(
                    "circle",
                    mapOf("cx" to fmt(w / 2f), "cy" to fmt(h / 2f), "r" to "6", "class" to "kuml-pseudostate"),
                )
            }
            UmlActivityNodeKind.FLOW_FINAL -> {
                tag(
                    "circle",
                    mapOf("cx" to fmt(w / 2f), "cy" to fmt(h / 2f), "r" to "10", "class" to "kuml-final-outer"),
                )
                tag(
                    "line",
                    mapOf(
                        "x1" to fmt(w / 2f - 6f),
                        "y1" to fmt(h / 2f - 6f),
                        "x2" to fmt(w / 2f + 6f),
                        "y2" to fmt(h / 2f + 6f),
                        "class" to "kuml-edge",
                    ),
                )
                tag(
                    "line",
                    mapOf(
                        "x1" to fmt(w / 2f - 6f),
                        "y1" to fmt(h / 2f + 6f),
                        "x2" to fmt(w / 2f + 6f),
                        "y2" to fmt(h / 2f - 6f),
                        "class" to "kuml-edge",
                    ),
                )
            }
            UmlActivityNodeKind.DECISION, UmlActivityNodeKind.MERGE -> {
                tag(
                    "polygon",
                    mapOf(
                        "points" to "${fmt(w / 2f)},0 ${fmt(w)},${fmt(h / 2f)} ${fmt(w / 2f)},${fmt(h)} 0,${fmt(h / 2f)}",
                        "class" to "kuml-decision",
                    ),
                )
            }
            UmlActivityNodeKind.FORK, UmlActivityNodeKind.JOIN -> {
                tag(
                    "rect",
                    mapOf("x" to "0", "y" to fmt(h / 2f - 4f), "width" to fmt(w), "height" to "8", "class" to "kuml-fork-bar"),
                )
            }
            UmlActivityNodeKind.OBJECT -> {
                tag("rect", mapOf("width" to fmt(w), "height" to fmt(h), "class" to "kuml-class"))
                tag(
                    "text",
                    mapOf("class" to "kuml-title", "x" to fmt(w / 2f), "y" to fmt(h / 2f + 5f), "text-anchor" to "middle"),
                ) { text(xmlEscapeText(element.name)) }
            }
        }
    }
}

internal fun renderUmlTimingLifeline(
    element: UmlTimingLifeline,
    layout: NodeLayout,
    @Suppress("UNUSED_PARAMETER") theme: KumlTheme,
    builder: SvgBuilder,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height
    val labelW = 80f
    val plotX = labelW
    val plotW = w - labelW
    val stateCount = element.states.size.coerceAtLeast(1)
    val rowH = (h - 20f) / stateCount
    builder.tag(
        "g",
        mapOf("id" to xmlEscapeAttr(element.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        tag("rect", mapOf("width" to fmt(w), "height" to fmt(h), "class" to "kuml-timing-frame"))

        // Y-axis: state labels
        for ((i, state) in element.states.withIndex()) {
            val cy = 20f + i * rowH + rowH / 2f + 4f
            tag(
                "text",
                mapOf("class" to "kuml-body", "x" to fmt(labelW - 4f), "y" to fmt(cy), "text-anchor" to "end"),
            ) { text(xmlEscapeText(state)) }
        }

        // Title
        tag(
            "text",
            mapOf("class" to "kuml-title", "x" to fmt(labelW / 2f), "y" to "14", "text-anchor" to "middle"),
        ) { text(xmlEscapeText(element.name)) }

        // Step-line through ticks
        if (element.timeline.isNotEmpty()) {
            val stateIndex = element.states.withIndex().associate { (i, s) -> s to i }
            val maxT = (element.timeline.maxOf { it.t }).coerceAtLeast(1)
            val sx: (Int) -> Float = { t -> plotX + (t.toFloat() / maxT) * plotW }
            val sy: (String) -> Float = { state ->
                val idx = stateIndex[state] ?: 0
                20f + idx * rowH + rowH / 2f
            }
            val path = StringBuilder("M ")
            element.timeline.forEachIndexed { i, tick ->
                val px = sx(tick.t)
                val py = sy(tick.state)
                if (i == 0) {
                    path.append("${fmt(px)} ${fmt(py)}")
                } else {
                    val prev = element.timeline[i - 1]
                    path.append(" H ${fmt(px)} V ${fmt(py)}")
                    // suppress unused-variable false positive
                    @Suppress("UNUSED_VARIABLE")
                    val unused = prev
                }
            }
            tag("path", mapOf("d" to path.toString(), "class" to "kuml-timing-line"))
        }
    }
}

internal fun renderUmlInteractionOverviewFrame(
    element: UmlInteractionOverviewFrame,
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
        when (element.kind) {
            UmlInteractionFrameKind.INTERACTION_REF -> {
                tag(
                    "rect",
                    mapOf("width" to fmt(w), "height" to fmt(h), "rx" to "6", "ry" to "6", "class" to "kuml-frame"),
                )
                tag(
                    "text",
                    mapOf("class" to "kuml-small", "x" to "8", "y" to "16"),
                ) { text("ref") }
                tag(
                    "text",
                    mapOf("class" to "kuml-title", "x" to fmt(w / 2f), "y" to fmt(h / 2f + 5f), "text-anchor" to "middle"),
                ) { text(xmlEscapeText(element.name)) }
            }
            UmlInteractionFrameKind.INITIAL ->
                tag(
                    "circle",
                    mapOf("cx" to fmt(w / 2f), "cy" to fmt(h / 2f), "r" to "10", "class" to "kuml-pseudostate"),
                )
            UmlInteractionFrameKind.FINAL -> {
                tag(
                    "circle",
                    mapOf("cx" to fmt(w / 2f), "cy" to fmt(h / 2f), "r" to "12", "class" to "kuml-final-outer"),
                )
                tag(
                    "circle",
                    mapOf("cx" to fmt(w / 2f), "cy" to fmt(h / 2f), "r" to "6", "class" to "kuml-pseudostate"),
                )
            }
            UmlInteractionFrameKind.DECISION, UmlInteractionFrameKind.MERGE ->
                tag(
                    "polygon",
                    mapOf(
                        "points" to "${fmt(w / 2f)},0 ${fmt(w)},${fmt(h / 2f)} ${fmt(w / 2f)},${fmt(h)} 0,${fmt(h / 2f)}",
                        "class" to "kuml-decision",
                    ),
                )
        }
    }
}

private fun fmt(v: Float): String {
    val i = v.toInt()
    return if (v == i.toFloat()) "$i" else "%.2f".format(java.util.Locale.ROOT, v)
}
