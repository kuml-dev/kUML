package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.io.svg.xmlEscapeText
import dev.kuml.kerml.KermlFeature
import dev.kuml.layout.NodeLayout
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.sysml2.ActionDefinition
import dev.kuml.sysml2.ActivityPartitionDefinition
import dev.kuml.sysml2.ActorDefinition
import dev.kuml.sysml2.AttributeDefinition
import dev.kuml.sysml2.ConnectionDefinition
import dev.kuml.sysml2.ConstraintDefinition
import dev.kuml.sysml2.LifelineDefinition
import dev.kuml.sysml2.PartDefinition
import dev.kuml.sysml2.PortDefinition
import dev.kuml.sysml2.RequirementDefinition
import dev.kuml.sysml2.StateDefinition
import dev.kuml.sysml2.Sysml2Definition
import dev.kuml.sysml2.UseCaseDefinition

/**
 * Rendert SysML-2 Definitionen als BDD-Boxen.
 *
 * Layout pro Definition-Kind (V2.0.4 MVP):
 *  - **PartDefinition** — Stereotyp-Header `«part def»` + Name (fett) +
 *    Attribute-Compartment + Ports/Sub-Parts-Compartment.
 *  - **AttributeDefinition** — `«attribute def»` + Name.
 *  - **PortDefinition** — `«port def»` + Name.
 *  - **ConnectionDefinition** — `«connection def»` + Name.
 *
 * Vereinfachung im MVP: alle Features (KermlFeatures) landen in einer einzigen
 * Sektion. Die saubere Trennung zwischen Attribute/Port/Part/Connection-Usages
 * pro Feature-Kind kommt in V2.x, wenn der KerML-Feature den SysML-2-Usage-Kind
 * als Metadata-Slot trägt — heute ist die DSL der einzige Ort, an dem die
 * Trennung sichtbar ist.
 *
 * Theme-Anbindung: nutzt die existierenden CSS-Klassen (`kuml-class`,
 * `kuml-title`, `kuml-stereotype`, `kuml-body`, `kuml-divider`), damit die
 * SysML-2-Boxen visuell mit UML-Klassen im selben Diagramm harmonieren —
 * Tooling-Konsumenten brauchen keine Spezial-Stylesheets.
 */
internal fun renderSysml2Definition(
    element: Sysml2Definition,
    layout: NodeLayout,
    theme: KumlTheme,
    builder: SvgBuilder,
) {
    when (element) {
        is PartDefinition -> renderBox(element, layout, builder, stereotype = "part def")
        is AttributeDefinition -> renderBox(element, layout, builder, stereotype = "attribute def")
        is PortDefinition -> renderBox(element, layout, builder, stereotype = "port def")
        is ConnectionDefinition -> renderBox(element, layout, builder, stereotype = "connection def")
        // V2.0.7: UC-Diagramm-spezifische Formen.
        is ActorDefinition -> renderSysml2Actor(element, layout, theme, builder)
        is UseCaseDefinition -> renderSysml2UseCase(element, layout, theme, builder)
        // V2.0.8: REQ-Diagramm — dreikompartimentige Anforderungs-Box mit
        // `«requirement»`-Stereotyp, optional `R-NNN ::`-prefixiertem Namen,
        // und wort-gewrapptem Anforderungstext.
        is RequirementDefinition -> renderSysml2Requirement(element, layout, theme, builder)
        // V2.0.9: STM-Diagramm — abgerundeter Zustand (oder Initial/Final
        // Pseudo-State als Kreis/Donut).
        is StateDefinition -> renderStateDefinition(element, layout, theme, builder)
        // V2.0.10: ACT-Diagramm — Aktion, Pseudo-Knoten (Initial/Final/FlowFinal),
        // Decision/Merge-Raute oder Fork/Join-Bar — Dispatch über
        // ActionDefinition.kind.
        is ActionDefinition -> renderActionDefinition(element, layout, theme, builder)
        // V2.0.11: SEQ-Diagramm — Lifeline-Kopf (Box mit «lifeline»-Stereotyp)
        // plus vertikale gestrichelte Zeit-Achse unter dem Kopf bis zum
        // unteren Ende der Bounds. Nachrichten werden NICHT hier gezeichnet
        // — sie werden vom Sequence-Renderer direkt nach dem Standard-Knoten-
        // Loop emittiert (siehe Sysml2SequenceSvg.kt).
        is LifelineDefinition -> renderLifelineHead(element, layout, theme, builder)
        // V2.0.12: PAR-Diagramm — drei-Kompartiment-Box mit `«constraint»`-
        // Stereotyp, Name, Expression-Body (monospaced, ggf. ellipsis-trunkiert)
        // und Parameter-Liste mit `«in»`/`«out»`/`«inout»`-Stereotyp-Präfix.
        is ConstraintDefinition -> renderConstraintDefinition(element, layout, theme, builder)
        // V2.0.16: ACT-Diagramm — ActivityPartitions (Swimlanes) sind KEINE
        // gewöhnlichen Node-Boxes. Sie surface'n im Renderer als Gruppen-
        // Container über `layoutResult.groups` — die KumlSvgRenderer-ACT-
        // Overload zeichnet die gestrichelten Lanes + Header-Bars direkt
        // (siehe renderActivityPartitionGroup). Hier im Definitions-Dispatch
        // sollen sie deshalb NICHT als reguläre Box gerendert werden — wir
        // returnen explizit, damit der NodeRendererDispatcher keine
        // Doppel-Box-Visualisierung produziert, falls ein ActivityPartition-
        // Knoten je in `layoutResult.nodes` landet (z. B. aus einem Unit-
        // Test, der die Sysml2DefSvg-Funktion direkt aufruft). Der Lane-
        // Pfad ist die einzige korrekte Visualisierung.
        is ActivityPartitionDefinition -> Unit
    }
}

/**
 * Gemeinsamer Box-Renderer für alle Definition-Kinds. Erste Sektion immer:
 * Stereotyp-Zeile + Name. Wenn Features vorhanden, kommt eine Divider-Linie
 * und pro Feature eine Zeile mit `name : Type [multiplicity]`.
 */
private fun renderBox(
    element: Sysml2Definition,
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
        mapOf("id" to xmlEscapeAttr(element.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        tag("rect", mapOf("width" to fmt(w), "height" to fmt(h), "class" to "kuml-class"))

        var cy = 16f

        // Stereotype line — guillemets so the SysML-2 ‹‹kind››-Konvention bleibt erhalten.
        tag(
            "text",
            mapOf(
                "class" to "kuml-stereotype",
                "x" to fmt(w / 2f),
                "y" to fmt(cy),
                "text-anchor" to "middle",
            ),
        ) { text("«$stereotype»") }
        cy += 14f

        // Type name (bold, centered). Abstract definitions get the italics-via-style hint.
        val nameClass = if (element.isAbstract) "kuml-title kuml-title-abstract" else "kuml-title"
        tag(
            "text",
            mapOf(
                "class" to nameClass,
                "x" to fmt(w / 2f),
                "y" to fmt(cy),
                "text-anchor" to "middle",
            ),
        ) { text(xmlEscapeText(element.name)) }
        cy += 12f

        if (element.features.isNotEmpty()) {
            tag(
                "line",
                mapOf(
                    "x1" to "0",
                    "y1" to fmt(cy),
                    "x2" to fmt(w),
                    "y2" to fmt(cy),
                    "class" to "kuml-divider",
                ),
            )
            cy += 14f

            for (feature in element.features) {
                tag(
                    "text",
                    mapOf("class" to "kuml-body", "x" to "6", "y" to fmt(cy)),
                ) { text(xmlEscapeText(feature.formatBdd())) }
                cy += 13f
            }
        }
    }
}

/**
 * `name : Type [multiplicity]` — die SysML-2-BDD-Form für ein
 * Feature-Compartment-Eintrag. Multiplicity wird weggelassen wenn `1`
 * (Default). Default-Expression wird angehängt wenn vorhanden.
 */
private fun KermlFeature.formatBdd(): String {
    val type = typeId ?: "?"
    val multSuffix = if (multiplicity.toSpecForm() == "1") "" else " [${multiplicity.toSpecForm()}]"
    val defaultSuffix = defaultExpression?.let { " = $it" } ?: ""
    return "$name : $type$multSuffix$defaultSuffix"
}

private fun fmt(v: Float): String = if (v == v.toInt().toFloat()) v.toInt().toString() else String.format(java.util.Locale.US, "%.3f", v)
