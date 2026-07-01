package dev.kuml.io.svg.uml

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.fmt2
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.layout.NodeLayout
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.uml.UmlStateMachine

/**
 * Rendert einen beschrifteten Frame für ein [UmlStateMachine].
 *
 * V1.1.3 Ticket 2.5 — bisher war `UmlStateMachine` nicht im Dispatcher,
 * landete also als generische Fallback-Grau-Box. Ab jetzt: abgerundetes
 * Rechteck mit Label `stateMachine` oben links, optionalem Stereotyp-
 * Header zentriert und Name-Text mittig.
 *
 * Hierarchische Nesting der State-Vertices ist nicht Teil dieses Renderers
 * (V2-Thema). State-Vertices werden weiterhin separat über `renderUmlState`
 * layoutet und gerendert.
 *
 * Visuelle Struktur (von oben nach unten):
 *  - Abgerundetes Rechteck als Rahmen
 *  - Kleines Label `stateMachine` oben links
 *  - Optionaler Stereotyp-Header zentriert (z.B. `«BehaviorSpec»`)
 *  - State-Machine-Name zentriert
 */
internal fun renderUmlStateMachine(
    element: UmlStateMachine,
    layout: NodeLayout,
    theme: KumlTheme,
    builder: SvgBuilder,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height
    val cx = w / 2f

    builder.tag(
        "g",
        mapOf(
            "id" to xmlEscapeAttr(element.id),
            "transform" to "translate(${fmt(x)},${fmt(y)})",
        ),
    ) {
        // 1. Frame: rounded rectangle
        tag(
            "rect",
            mapOf(
                "width" to fmt(w),
                "height" to fmt(h),
                "rx" to "8",
                "ry" to "8",
                "class" to "kuml-frame",
            ),
        )

        // 2. Label "stateMachine" top-left (small)
        tag(
            "text",
            mapOf(
                "class" to "kuml-small",
                "x" to "8",
                "y" to "16",
            ),
        ) { text("stateMachine") }

        // 3. Optional stereotype header centered (via StereotypeHelper)
        var cy = 32f
        val stereoAdv = StereotypeHelper.renderHeader(element, theme, this, cx, cy)
        cy += stereoAdv

        // 4. Name centered below the stereotype header (or near top if none)
        tag(
            "text",
            mapOf(
                "class" to "kuml-title",
                "x" to fmt(cx),
                "y" to fmt(cy + 14f),
                "text-anchor" to "middle",
            ),
        ) { text(element.name) }
    }
}

private fun fmt(v: Float): String = fmt2(v)
