package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.layout.NodeLayout
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.sysml2.ActorDefinition

/**
 * Rendert eine SysML-2-[ActorDefinition] als Strichmännchen mit Name darunter
 * (V2.0.7).
 *
 * Visuell identisch zur UML-Actor-Form: Kopf (Kreis), Körper (vertikale Linie),
 * Arme (horizontale Linie), Beine (zwei diagonale Linien), Name unter den
 * Füßen. Die SysML-2-Konvention spricht ohnehin von "actor" — Stickfigur ist
 * die etablierte visuelle Sprache und braucht keinen SysML-2-spezifischen
 * Stereotyp-Header.
 *
 * Layout-Annahmen: die Bridge gibt für Actors `UC_ACTOR_WIDTH × UC_ACTOR_HEIGHT`
 * (= 60 × 100) als intrinsische Größe an. Innerhalb der Bounds wird die Figur
 * horizontal zentriert; die Höhen-Aufteilung ist ein Drittel Kopf + Körper,
 * ein Drittel Beine, ein Drittel Name darunter.
 *
 * Theme-Anbindung: nutzt die `kuml-actor`-CSS-Klasse (gleich wie [UmlActor]),
 * damit SysML-2-Actors und UML-Actors visuell harmonieren.
 */
internal fun renderSysml2Actor(
    element: ActorDefinition,
    layout: NodeLayout,
    @Suppress("UNUSED_PARAMETER") theme: KumlTheme,
    builder: SvgBuilder,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height
    val cx = w / 2f

    // Geometrie: vertikales Drei-Drittel-Layout — Figur in den oberen ~80 % der
    // Bounds, Name in den unteren ~20 %. Skaliert automatisch mit der Bounds-
    // Größe, ohne Hardcoded-Magic-Numbers auf den Default-Maßen hängen zu bleiben.
    val headRadius = 8f
    val headCy = headRadius + 4f
    val bodyTop = headCy + headRadius
    val bodyBottom = h * 0.55f
    val armsY = bodyTop + (bodyBottom - bodyTop) * 0.35f
    val legSpread = 10f
    val armSpread = 12f
    val legsBottom = h * 0.78f
    val nameY = h * 0.92f

    builder.tag(
        "g",
        mapOf("id" to xmlEscapeAttr(element.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        // Head
        tag(
            "circle",
            mapOf(
                "cx" to fmt(cx),
                "cy" to fmt(headCy),
                "r" to fmt(headRadius),
                "class" to "kuml-actor",
            ),
        )
        // Body + arms + legs in a single path — exakt wie der UML-Actor-Renderer.
        tag(
            "path",
            mapOf(
                "d" to "M ${fmt(cx)} ${fmt(bodyTop)} L ${fmt(cx)} ${fmt(bodyBottom)} " +
                    "M ${fmt(cx - armSpread)} ${fmt(armsY)} L ${fmt(cx + armSpread)} ${fmt(armsY)} " +
                    "M ${fmt(cx)} ${fmt(bodyBottom)} L ${fmt(cx - legSpread)} ${fmt(legsBottom)} " +
                    "M ${fmt(cx)} ${fmt(bodyBottom)} L ${fmt(cx + legSpread)} ${fmt(legsBottom)}",
                "class" to "kuml-actor",
            ),
        )
        // Name unterhalb der Figur — Standard `kuml-body`-Klasse für lesbare Sans-Serif.
        tag(
            "text",
            mapOf(
                "class" to "kuml-body",
                "x" to fmt(cx),
                "y" to fmt(nameY),
                "text-anchor" to "middle",
            ),
        ) { text(element.name) }
    }
}

private fun fmt(v: Float): String = if (v == v.toInt().toFloat()) v.toInt().toString() else String.format(java.util.Locale.US, "%.3f", v)
