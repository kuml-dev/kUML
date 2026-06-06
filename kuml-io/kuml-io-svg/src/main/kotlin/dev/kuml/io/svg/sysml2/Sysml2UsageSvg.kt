package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.io.svg.xmlEscapeText
import dev.kuml.layout.NodeLayout
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.sysml2.ActorUsage
import dev.kuml.sysml2.AttributeUsage
import dev.kuml.sysml2.ConnectionUsage
import dev.kuml.sysml2.ExtendUsage
import dev.kuml.sysml2.IncludeUsage
import dev.kuml.sysml2.PartUsage
import dev.kuml.sysml2.PortUsage
import dev.kuml.sysml2.RequirementUsage
import dev.kuml.sysml2.Sysml2Usage
import dev.kuml.sysml2.UseCaseUsage

/**
 * Rendert SysML-2 Usages als IBD-Boxen (V2.0.6).
 *
 * Pro Usage-Kind:
 *  - [PartUsage] → `«part»` Stereotyp + Inhaltszeile `name : Type [mult]`. Das
 *    Brot-und-Butter-Element jeder IBD; das ist die einzige Usage-Form, die
 *    der IBD-Bridge im V2.0.6-MVP als Knoten ausgibt.
 *  - [PortUsage], [AttributeUsage], [ConnectionUsage] → werden im Renderer
 *    angeboten, aber im V2.0.6-MVP nicht von der Bridge erzeugt. Dispatcher
 *    nutzt diese Branches dennoch, damit ein direkter Aufruf von
 *    [renderSysml2Usage] (z. B. aus Tests) nicht in den Fallback rutscht.
 *
 * Layout: zweizeilig, da IBD-Boxen platzsparend sein sollen — Stereotyp
 * oben mittig, Inhaltszeile darunter mittig. Keine Compartment-Trennlinie,
 * keine Feature-Liste — die Detailtiefe einer Part-Usage gehört semantisch
 * in das BDD ihres Typs, nicht in das IBD ihres Owners.
 *
 * Theme-Anbindung: nutzt die existierenden `kuml-class`/`kuml-stereotype`/
 * `kuml-title`/`kuml-body`-CSS-Klassen, sodass IBD- und BDD-Boxen im selben
 * Stylesheet harmonieren.
 *
 * V2.x:
 *  - Boundary-Port-Marker auf der Box-Außenseite (braucht Port-Position-Hints).
 *  - Multiplicity-Glyphen nach SysML-2-Norm (statt der einfachen Bracket-Form).
 */
internal fun renderSysml2Usage(
    element: Sysml2Usage,
    layout: NodeLayout,
    @Suppress("UNUSED_PARAMETER") theme: KumlTheme,
    builder: SvgBuilder,
) {
    when (element) {
        is PartUsage -> renderUsageBox(element, layout, builder, stereotype = "part")
        is PortUsage -> renderUsageBox(element, layout, builder, stereotype = "port")
        is AttributeUsage -> renderUsageBox(element, layout, builder, stereotype = "attribute")
        is ConnectionUsage -> renderUsageBox(element, layout, builder, stereotype = "connection")
        // V2.0.7: UC-Usage-Kinds. Die Bridge zeigt im V2.0.7-MVP keine
        // UC-Usages auf der Node-Ebene (UC-Diagramme rendern Actor/UseCase
        // *Definitions* direkt), aber der Dispatcher braucht trotzdem einen
        // Branch, damit ein direkter Aufruf — z. B. aus einem Test — nicht
        // in den UML-Fallback rutscht. Wir nutzen die gleiche Box-Form mit
        // einem semantischen Stereotyp; V2.x-Polish bringt ggf. Stickfigur/
        // Ellipsen-Varianten für Usages.
        is ActorUsage -> renderUsageBox(element, layout, builder, stereotype = "actor")
        is UseCaseUsage -> renderUsageBox(element, layout, builder, stereotype = "use case")
        is IncludeUsage -> renderUsageBox(element, layout, builder, stereotype = "include")
        is ExtendUsage -> renderUsageBox(element, layout, builder, stereotype = "extend")
        // V2.0.8: REQ-Usage-Kind. Die Bridge zeigt im V2.0.8-MVP keine
        // RequirementUsages auf der Node-Ebene (REQ-Diagramme rendern
        // RequirementDefinitions direkt), aber der Dispatcher braucht einen
        // Branch, damit ein direkter Aufruf — z. B. aus einem Test — nicht
        // in den UML-Fallback rutscht.
        is RequirementUsage -> renderUsageBox(element, layout, builder, stereotype = "requirement")
    }
}

/**
 * Gemeinsamer Renderer für alle Usage-Kinds. Zweizeiliges Layout: Stereotyp +
 * Inhaltszeile mit `name : Type [multiplicity]`.
 */
private fun renderUsageBox(
    usage: Sysml2Usage,
    layout: NodeLayout,
    builder: SvgBuilder,
    stereotype: String,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height

    builder.tag(
        "g",
        mapOf("id" to xmlEscapeAttr(usage.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        tag("rect", mapOf("width" to fmt(w), "height" to fmt(h), "class" to "kuml-class"))

        val cx = w / 2f
        // Stereotype line — `«part»` etc., mid-upper.
        tag(
            "text",
            mapOf(
                "class" to "kuml-stereotype",
                "x" to fmt(cx),
                "y" to "18",
                "text-anchor" to "middle",
            ),
        ) { text("«$stereotype»") }

        // Content line — `name : Type [mult]`, vertically centred.
        tag(
            "text",
            mapOf(
                "class" to "kuml-title",
                "x" to fmt(cx),
                "y" to fmt(h / 2f + 10f),
                "text-anchor" to "middle",
            ),
        ) { text(xmlEscapeText(usage.formatIbd())) }
    }
}

/**
 * `name : Type [multiplicity]` — die IBD-kompakte Form für eine Usage-Zeile.
 * Multiplicity entfällt wenn `1`. Keine Default-Ausdrücke — die landen bei
 * Bedarf im BDD der zugrundeliegenden Definition.
 */
private fun Sysml2Usage.formatIbd(): String {
    val multSuffix = if (multiplicity.toSpecForm() == "1") "" else " [${multiplicity.toSpecForm()}]"
    return "$name : $definitionId$multSuffix"
}

private fun fmt(v: Float): String = if (v == v.toInt().toFloat()) v.toInt().toString() else String.format(java.util.Locale.US, "%.3f", v)
