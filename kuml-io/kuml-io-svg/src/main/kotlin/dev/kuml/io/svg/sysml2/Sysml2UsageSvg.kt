package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.PortId
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.sysml2.ActionUsage
import dev.kuml.sysml2.ActivityPartitionUsage
import dev.kuml.sysml2.ActorUsage
import dev.kuml.sysml2.AttributeUsage
import dev.kuml.sysml2.BindingConnectorUsage
import dev.kuml.sysml2.CombinedFragmentUsage
import dev.kuml.sysml2.ConnectionUsage
import dev.kuml.sysml2.ConstraintUsage
import dev.kuml.sysml2.ControlFlowUsage
import dev.kuml.sysml2.ExecutionSpecificationUsage
import dev.kuml.sysml2.ExtendUsage
import dev.kuml.sysml2.IncludeUsage
import dev.kuml.sysml2.LifelineUsage
import dev.kuml.sysml2.MessageUsage
import dev.kuml.sysml2.ObjectFlowUsage
import dev.kuml.sysml2.PartUsage
import dev.kuml.sysml2.PortUsage
import dev.kuml.sysml2.RequirementUsage
import dev.kuml.sysml2.StateUsage
import dev.kuml.sysml2.Sysml2Usage
import dev.kuml.sysml2.TransitionUsage
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
        is PartUsage -> renderUsageBox(element, layout, builder, stereotype = "part", ibdPorts = layout.ports)
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
        // V2.0.9: STM-Usage-Kinds. Die Bridge zeigt im V2.0.9-MVP keine
        // StateUsages/TransitionUsages auf der Node-Ebene (STM-Diagramme
        // rendern StateDefinitions direkt; Transitionen werden Edges, keine
        // Knoten), aber der Dispatcher braucht Branches, damit ein direkter
        // Aufruf nicht in den UML-Fallback rutscht.
        is StateUsage -> renderUsageBox(element, layout, builder, stereotype = "state")
        is TransitionUsage -> renderUsageBox(element, layout, builder, stereotype = "transition")
        // V2.0.10: ACT-Usage-Kinds. Die Bridge zeigt im V2.0.10-MVP keine
        // ActionUsages/ControlFlowUsages/ObjectFlowUsages auf der Node-Ebene
        // (ACT-Diagramme rendern ActionDefinitions direkt; Flows werden Edges,
        // keine Knoten), aber der Dispatcher braucht Branches, damit ein
        // direkter Aufruf nicht in den UML-Fallback rutscht.
        is ActionUsage -> renderUsageBox(element, layout, builder, stereotype = "action")
        is ControlFlowUsage -> renderUsageBox(element, layout, builder, stereotype = "control flow")
        is ObjectFlowUsage -> renderUsageBox(element, layout, builder, stereotype = "object flow")
        // V2.0.11: SEQ-Usage-Kinds. Die Bridge zeigt im V2.0.11-MVP keine
        // LifelineUsages/MessageUsages auf der Node-Ebene (SEQ-Diagramme
        // rendern LifelineDefinitions direkt; Nachrichten werden im Renderer
        // direkt gezeichnet, keine Knoten), aber der Dispatcher braucht
        // Branches, damit ein direkter Aufruf nicht in den UML-Fallback rutscht.
        is LifelineUsage -> renderUsageBox(element, layout, builder, stereotype = "lifeline")
        is MessageUsage -> renderUsageBox(element, layout, builder, stereotype = "message")
        // V2.0.12: PAR-Usage-Kinds. Die Bridge zeigt im V2.0.12-MVP keine
        // ConstraintUsages/BindingConnectorUsages auf der Node-Ebene (PAR-
        // Diagramme rendern ConstraintDefinitions direkt; Bindings werden
        // Edges, keine Knoten), aber der Dispatcher braucht Branches, damit
        // ein direkter Aufruf nicht in den UML-Fallback rutscht.
        is ConstraintUsage -> renderUsageBox(element, layout, builder, stereotype = "constraint")
        is BindingConnectorUsage -> renderUsageBox(element, layout, builder, stereotype = "binding")
        // V2.0.15: SEQ-Combined-Fragment / Execution-Spec. Diese Usages
        // rendert der SEQ-Renderer normalerweise *direkt* (renderer-direct,
        // siehe Sysml2SequenceSvg) — sie tauchen nie als LayoutGraph-Knoten
        // auf. Der Dispatcher braucht trotzdem Branches, damit ein direkter
        // Aufruf — z. B. aus einem Unit-Test — nicht in den UML-Fallback rutscht.
        is CombinedFragmentUsage ->
            renderUsageBox(element, layout, builder, stereotype = "combined fragment")
        is ExecutionSpecificationUsage ->
            renderUsageBox(element, layout, builder, stereotype = "execution spec")
        // V2.0.16: ACT-Diagramm — ActivityPartition-Usage. Die Bridge zeigt
        // im V2.0.16-MVP keine ActivityPartitionUsages auf der Node-Ebene
        // (ACT-Diagramme rendern ActivityPartitionDefinitions als Gruppen-
        // Container, keine Knoten), aber der Dispatcher braucht einen
        // Branch, damit ein direkter Aufruf — z. B. aus einem Unit-Test —
        // nicht in den UML-Fallback rutscht.
        is ActivityPartitionUsage ->
            renderUsageBox(element, layout, builder, stereotype = "activity partition")
    }
}

/**
 * Gemeinsamer Renderer für alle Usage-Kinds. Zweizeiliges Layout: Stereotyp +
 * Inhaltszeile mit `name : Type [multiplicity]`.
 *
 * [ibdPorts] enthält Port-Positionen in lokalen Koordinaten (relativ zum Box-Ursprung),
 * die für IBD-PartUsage-Knoten durch
 * [dev.kuml.layout.bridge.Sysml2LayoutBridge.enrichIbdPortPositions] befüllt werden.
 * Alle anderen Usage-Kinds übergeben `emptyMap()` (Default).
 */
private fun renderUsageBox(
    usage: Sysml2Usage,
    layout: NodeLayout,
    builder: SvgBuilder,
    stereotype: String,
    ibdPorts: Map<PortId, Point> = emptyMap(),
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
        ) { text(usage.formatIbd()) }

        // IBD boundary ports — small squares on the box edge, one per connected port.
        if (ibdPorts.isNotEmpty()) {
            renderIbdBoundaryPorts(ibdPorts, w, h)
        }
    }
}

/**
 * Renders boundary-port markers for an IBD Part-Usage box.
 *
 * Each port is a [IBD_PORT_SIZE]×[IBD_PORT_SIZE] square (`kuml-port` CSS class)
 * centered on the point where the connection exits/enters the box. The port name
 * is displayed in small type (`kuml-port-label`) on the outside of the boundary.
 *
 * Coordinate space: **local** — same as the enclosing `<g transform="translate(x,y)">`.
 * Port points are already in local coordinates (converted from absolute canvas
 * coordinates by [dev.kuml.layout.bridge.Sysml2LayoutBridge.enrichIbdPortPositions]).
 */
private fun SvgBuilder.renderIbdBoundaryPorts(
    ports: Map<PortId, Point>,
    w: Float,
    h: Float,
) {
    val ps = IBD_PORT_SIZE
    val half = ps / 2f
    val gap = IBD_PORT_LABEL_GAP

    for ((portId, local) in ports) {
        val px = local.x
        val py = local.y

        // Port square — centered on the boundary attachment point.
        tag(
            "rect",
            mapOf(
                "x" to fmt(px - half),
                "y" to fmt(py - half),
                "width" to fmt(ps),
                "height" to fmt(ps),
                "class" to "kuml-port",
            ),
        )

        // Determine which side of the box this port sits on, then place the label
        // on the outside.
        val onLeft = px <= half + 1f
        val onRight = px >= w - half - 1f
        val onTop = py <= half + 1f

        val (labelX, anchor) =
            when {
                onLeft -> Pair(px - half - gap, "end")
                onRight -> Pair(px + half + gap, "start")
                else -> Pair(px, "middle")
            }
        val labelY =
            when {
                onTop -> py - half - gap
                onLeft || onRight -> py + 4f // vertically centred on port square
                else -> py + half + gap + 9f // below (bottom side), +9 for font ascent
            }

        tag(
            "text",
            mapOf(
                "class" to "kuml-port-label",
                "x" to fmt(labelX),
                "y" to fmt(labelY),
                "text-anchor" to anchor,
            ),
        ) { text(portId.value) }
    }
}

private const val IBD_PORT_SIZE = 10f
private const val IBD_PORT_LABEL_GAP = 4f

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
