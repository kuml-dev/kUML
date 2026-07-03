package dev.kuml.io.svg.uml

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.fmt2
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.bridge.UmlCommentLayout
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.uml.UmlComment

/**
 * Rendert eine [UmlComment] (UML-Notiz) als Rechteck mit gefalteter oberer
 * rechter Ecke ("dog-ear") — das klassische UML-Note-Symbol.
 *
 * Freitext wird gemäß [UmlCommentLayout.wrapBody] auf mehrere Zeilen
 * umgebrochen; explizite Zeilenumbrüche im Quelltext werden als harte
 * Absatzgrenzen respektiert. Die Body-Zeilen müssen mit denselben Parametern
 * umgebrochen werden wie in [dev.kuml.layout.bridge.UmlContentSizeProvider]
 * (via [UmlCommentLayout.sizeOf]), sonst läuft der Text aus der Box oder die
 * Box hat unnötigen Leerraum.
 *
 * Die gestrichelte Verbindungslinie zu Anker-Elementen wird NICHT hier
 * gezeichnet — sie ist eine separate [dev.kuml.uml.UmlCommentLink]-Relationship
 * und läuft durch den normalen Edge-Rendering-Pfad
 * ([dev.kuml.io.svg.uml.renderUmlCommentLink]).
 */
internal fun renderUmlComment(
    element: UmlComment,
    layout: NodeLayout,
    theme: KumlTheme,
    builder: SvgBuilder,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height
    val ear = UmlCommentLayout.DOG_EAR_SIZE

    builder.tag(
        "g",
        mapOf("id" to xmlEscapeAttr(element.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        // Note body: rectangle with the top-right corner cut off, outline only
        // (the folded triangle below draws the "flap" so the cut looks folded,
        // not just clipped).
        val path =
            listOf(
                "M 0 0",
                "L ${fmt(w - ear)} 0",
                "L $w ${fmt(ear)}",
                "L $w $h",
                "L 0 $h",
                "Z",
            ).joinToString(" ")
        tag("path", mapOf("d" to path, "class" to "kuml-comment"))

        // Folded corner ("dog-ear") — small triangle drawn with a distinct
        // stroke so the fold reads visually as paper folded over, not just a
        // notch cut out of the rectangle.
        val earPath =
            listOf(
                "M ${fmt(w - ear)} 0",
                "L ${fmt(w - ear)} ${fmt(ear)}",
                "L $w ${fmt(ear)}",
            ).joinToString(" ")
        tag("path", mapOf("d" to earPath, "class" to "kuml-comment-fold"))

        val lines = UmlCommentLayout.wrapBody(element.body)
        val startY = UmlCommentLayout.V_PADDING / 2f + UmlCommentLayout.LINE_H * 0.7f
        tag(
            "text",
            mapOf(
                "class" to "kuml-body",
                "x" to fmt(UmlCommentLayout.H_PADDING / 2f),
                "y" to fmt(startY),
            ),
        ) {
            lines.forEachIndexed { idx, line ->
                tag(
                    "tspan",
                    mapOf(
                        "x" to fmt(UmlCommentLayout.H_PADDING / 2f),
                        "dy" to if (idx == 0) "0" else fmt(UmlCommentLayout.LINE_H),
                    ),
                ) { text(line) }
            }
        }
    }
}

private fun fmt(v: Float): String = fmt2(v)
