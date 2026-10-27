package dev.kuml.io.svg.uml

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.renderNote
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.bridge.NoteFrameStyle
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
    renderNote(
        id = element.id,
        body = element.body,
        layout = layout,
        frame = NoteFrameStyle.DOG_EAR,
        frameClasses = "kuml-comment",
        builder = builder,
    )
}
