package dev.kuml.io.svg

import dev.kuml.c4.model.C4Diagram
import dev.kuml.c4.model.C4Model
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.layout.EdgeId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.sysml2.ActorDefinition
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.UcDiagram
import dev.kuml.sysml2.UseCaseDefinition
import java.io.File
import java.nio.file.Path

/**
 * Rendert kUML-Diagramme als SVG-String oder -Datei.
 *
 * Der Renderer arbeitet direkt auf [KumlDiagram] / [C4Diagram] + [LayoutResult] und
 * benötigt keinen Compose-Kontext (GraalVM-Native-Image-tauglich).
 *
 * Beispiel:
 * ```kotlin
 * val svg = KumlSvgRenderer.toSvg(diagram, layoutResult, PlainTheme())
 * File("out.svg").writeText(svg)
 * ```
 *
 * @see SvgRenderOptions
 * @see dev.kuml.renderer.theme.core.PlainTheme
 */
public object KumlSvgRenderer {
    /**
     * Rendert ein UML-Diagramm + Layout-Ergebnis als SVG-String.
     *
     * @param diagram das UML-Diagramm mit allen Elementen
     * @param layoutResult berechnete Positionen und Routing-Pfade
     * @param theme visuelles Theme; Standard: [PlainTheme]
     * @param options Renderer-Optionen; Standard: [SvgRenderOptions.DEFAULT]
     * @return wohlgeformter SVG-String
     */
    public fun toSvg(
        diagram: KumlDiagram,
        layoutResult: LayoutResult,
        theme: KumlTheme = PlainTheme(),
        options: SvgRenderOptions = SvgRenderOptions.DEFAULT,
    ): String =
        SvgDocument.render(layoutResult, theme, options) { nodesBuilder, edgesBuilder ->
            val padding = options.paddingPx

            // Nodes
            for ((nodeId, nodeLayout) in layoutResult.nodes) {
                val element = diagram.elements.find { it.id == nodeId.value }
                if (element != null) {
                    val shifted =
                        nodeLayout.copy(
                            bounds =
                                nodeLayout.bounds.copy(
                                    origin =
                                        nodeLayout.bounds.origin.copy(
                                            x = nodeLayout.bounds.origin.x + padding,
                                            y = nodeLayout.bounds.origin.y + padding,
                                        ),
                                ),
                        )
                    NodeRendererDispatcher.dispatch(element, shifted, theme, nodesBuilder)
                }
            }

            // Groups (C4 SoftwareSystem groupings)
            for ((groupId, groupLayout) in layoutResult.groups) {
                val gx = groupLayout.bounds.origin.x + padding
                val gy = groupLayout.bounds.origin.y + padding
                val gw = groupLayout.bounds.size.width
                val gh = groupLayout.bounds.size.height
                nodesBuilder.tag(
                    "g",
                    mapOf(
                        "id" to xmlEscapeAttr("system-${groupId.value}"),
                        "transform" to "translate(${fmt(gx)},${fmt(gy)})",
                    ),
                ) {
                    tag(
                        "rect",
                        mapOf(
                            "width" to fmt(gw),
                            "height" to fmt(gh),
                            "class" to "kuml-system",
                            "rx" to fmt(theme.borders.cornerRadiusPx),
                            "ry" to fmt(theme.borders.cornerRadiusPx),
                        ),
                    )
                }
            }

            // Edges
            val elementIndex = diagram.elements.associateBy { it.id }
            for ((edgeId, route) in layoutResult.edges) {
                val element = elementIndex[edgeId.value]
                if (element != null) {
                    val shiftedRoute = shiftRoute(route, padding)
                    EdgeRendererDispatcher.dispatch(element, shiftedRoute, theme, edgesBuilder)
                }
            }
        }

    /**
     * Rendert ein C4-Diagramm + Layout-Ergebnis als SVG-String.
     *
     * @param diagram das C4-Diagramm
     * @param model das übergeordnete C4-Modell für Element-Lookup
     * @param layoutResult berechnete Positionen und Routing-Pfade
     * @param theme visuelles Theme; Standard: [PlainTheme]
     * @param options Renderer-Optionen; Standard: [SvgRenderOptions.DEFAULT]
     * @return wohlgeformter SVG-String
     */
    public fun toSvg(
        diagram: C4Diagram,
        model: C4Model,
        layoutResult: LayoutResult,
        theme: KumlTheme = PlainTheme(),
        options: SvgRenderOptions = SvgRenderOptions.DEFAULT,
    ): String =
        SvgDocument.render(layoutResult, theme, options) { nodesBuilder, edgesBuilder ->
            val padding = options.paddingPx

            val elementIndex = model.elements.associateBy { it.id }
            val relationshipIndex = model.relationships.associateBy { it.id }

            // Nodes
            for ((nodeId, nodeLayout) in layoutResult.nodes) {
                val element = elementIndex[nodeId.value]
                if (element != null) {
                    val shifted =
                        nodeLayout.copy(
                            bounds =
                                nodeLayout.bounds.copy(
                                    origin =
                                        nodeLayout.bounds.origin.copy(
                                            x = nodeLayout.bounds.origin.x + padding,
                                            y = nodeLayout.bounds.origin.y + padding,
                                        ),
                                ),
                        )
                    NodeRendererDispatcher.dispatch(element, shifted, theme, nodesBuilder)
                }
            }

            // Groups (C4 SoftwareSystem groupings)
            for ((groupId, groupLayout) in layoutResult.groups) {
                val gx = groupLayout.bounds.origin.x + padding
                val gy = groupLayout.bounds.origin.y + padding
                val gw = groupLayout.bounds.size.width
                val gh = groupLayout.bounds.size.height
                nodesBuilder.tag(
                    "g",
                    mapOf(
                        "id" to xmlEscapeAttr("system-${groupId.value}"),
                        "transform" to "translate(${fmt(gx)},${fmt(gy)})",
                    ),
                ) {
                    tag(
                        "rect",
                        mapOf(
                            "width" to fmt(gw),
                            "height" to fmt(gh),
                            "class" to "kuml-system",
                            "rx" to fmt(theme.borders.cornerRadiusPx),
                            "ry" to fmt(theme.borders.cornerRadiusPx),
                        ),
                    )
                }
            }

            // Edges
            for ((edgeId, route) in layoutResult.edges) {
                val element = relationshipIndex[edgeId.value]
                if (element != null) {
                    val shiftedRoute = shiftRoute(route, padding)
                    EdgeRendererDispatcher.dispatch(element, shiftedRoute, theme, edgesBuilder)
                }
            }
        }

    /**
     * Schreibt ein UML-Diagramm als SVG in eine Datei und gibt sie zurück.
     *
     * @param diagram das UML-Diagramm
     * @param layoutResult berechnete Positionen und Routing-Pfade
     * @param out Zieldatei-Pfad
     * @param theme visuelles Theme; Standard: [PlainTheme]
     * @param options Renderer-Optionen; Standard: [SvgRenderOptions.DEFAULT]
     * @return die geschriebene Datei
     */
    public fun toSvgFile(
        diagram: KumlDiagram,
        layoutResult: LayoutResult,
        out: Path,
        theme: KumlTheme = PlainTheme(),
        options: SvgRenderOptions = SvgRenderOptions.DEFAULT,
    ): File {
        val svg = toSvg(diagram, layoutResult, theme, options)
        val file = out.toFile()
        file.parentFile?.mkdirs()
        file.writeText(svg, Charsets.UTF_8)
        return file
    }

    /**
     * Rendert ein SysML-2-BDD als SVG (V2.0.4).
     *
     * Wickelt das BDD in ein synthetisches [KumlDiagram] mit den sichtbaren
     * SysML-2-Definitionen als `elements`. Der [NodeRendererDispatcher] hat
     * seit V2.0.4 einen Branch für [dev.kuml.sysml2.Sysml2Definition] und
     * rendert die Vier-Sektions-BDD-Box ohne weitere Pipeline-Eingriffe.
     *
     * Der `DiagramType` der Hülle ist [DiagramType.CLASS] — visuell trägt die
     * BDD genau das Layout-Profil eines UML-Klassen-Diagramms (Boxen mit
     * Compartments + Generalisations als Edges), und ein eigener
     * `DiagramType.SYSML2_BDD`-Eintrag ist erst sinnvoll, wenn der Renderer
     * sich darauf konkret anders verhält.
     */
    public fun toSvg(
        model: Sysml2Model,
        diagram: BdDiagram,
        layoutResult: LayoutResult,
        theme: KumlTheme = PlainTheme(),
        options: SvgRenderOptions = SvgRenderOptions.DEFAULT,
    ): String {
        val visible = diagram.elementIds.toSet()
        val elements = model.definitions.filter { it.id in visible }
        val synthetic =
            KumlDiagram(
                name = diagram.name,
                type = DiagramType.CLASS,
                elements = elements,
            )
        return toSvg(synthetic, layoutResult, theme, options)
    }

    /** [toSvg]-Variante für SysML 2 BDDs, schreibt direkt auf Platte. */
    public fun toSvgFile(
        model: Sysml2Model,
        diagram: BdDiagram,
        layoutResult: LayoutResult,
        out: Path,
        theme: KumlTheme = PlainTheme(),
        options: SvgRenderOptions = SvgRenderOptions.DEFAULT,
    ): File {
        val svg = toSvg(model, diagram, layoutResult, theme, options)
        val file = out.toFile()
        file.parentFile?.mkdirs()
        file.writeText(svg, Charsets.UTF_8)
        return file
    }

    /**
     * Rendert ein SysML-2-IBD als SVG (V2.0.6).
     *
     * Wickelt das IBD in ein synthetisches [KumlDiagram] mit den sichtbaren
     * Part-Usages (gemäß `diagram.elementIds` bzw. — wenn leer — *allen*
     * Part-Usages des Owners) als `elements`. Der [NodeRendererDispatcher] hat
     * seit V2.0.6 einen Branch für [dev.kuml.sysml2.Sysml2Usage] und rendert
     * die zweizeilige IBD-Box ohne weitere Pipeline-Eingriffe.
     *
     * Auswahllogik der sichtbaren Part-Usages:
     *  - Bridge-Sicht: alle `KermlFeature`s des Owners, deren `typeId` auf eine
     *    [PartDefinition] zeigt. Hier rekonstruieren wir dieselbe Auswahl auf
     *    Usage-Ebene, indem wir `model.usages.filterIsInstance<PartUsage>()`
     *    auf "owner-eigen" filtern (`qualifiedName` beginnt mit `"<ownerId>::"`)
     *    und optional auf `diagram.elementIds` weiter eingrenzen.
     */
    public fun toSvg(
        model: Sysml2Model,
        diagram: IbdDiagram,
        layoutResult: LayoutResult,
        theme: KumlTheme = PlainTheme(),
        options: SvgRenderOptions = SvgRenderOptions.DEFAULT,
    ): String {
        val visible = visiblePartUsageElements(model, diagram)
        val synthetic =
            KumlDiagram(
                name = diagram.name,
                type = DiagramType.CLASS,
                elements = visible,
            )
        return toSvg(synthetic, layoutResult, theme, options)
    }

    /** [toSvg]-Variante für SysML 2 IBDs, schreibt direkt auf Platte. */
    public fun toSvgFile(
        model: Sysml2Model,
        diagram: IbdDiagram,
        layoutResult: LayoutResult,
        out: Path,
        theme: KumlTheme = PlainTheme(),
        options: SvgRenderOptions = SvgRenderOptions.DEFAULT,
    ): File {
        val svg = toSvg(model, diagram, layoutResult, theme, options)
        val file = out.toFile()
        file.parentFile?.mkdirs()
        file.writeText(svg, Charsets.UTF_8)
        return file
    }

    /**
     * Rendert ein SysML-2 UC-Diagramm als SVG (V2.0.7).
     *
     * Wickelt das UC in ein synthetisches [KumlDiagram] mit den sichtbaren
     * [ActorDefinition]s + [UseCaseDefinition]s als `elements`. Der
     * [NodeRendererDispatcher] hat seit V2.0.7 einen Branch in
     * [dev.kuml.io.svg.sysml2.renderSysml2Definition], der ActorDefinition als
     * Strichmännchen und UseCaseDefinition als Ellipse rendert.
     *
     * **Edge-Styling**: der V2.0.7-MVP rendert alle drei UC-Edge-Kinds
     * (Association, `«include»`, `«extend»`) als dieselbe einfache solide
     * Linie. Die synthetische `KumlDiagram`-Hülle enthält keine
     * `UmlRelationship`-Elemente für UC-Edges, deshalb fällt der
     * [EdgeRendererDispatcher] auf den Plain-Edge-Pfad zurück — das ist gut
     * genug für die V2.0.7-Wave. Gestricheltes `«include»`/`«extend»`-
     * Styling und Stereotyp-Labels sind V2.x-Polish (siehe
     * [[kUML V2.0]]-Roadmap).
     */
    public fun toSvg(
        model: Sysml2Model,
        diagram: UcDiagram,
        layoutResult: LayoutResult,
        theme: KumlTheme = PlainTheme(),
        options: SvgRenderOptions = SvgRenderOptions.DEFAULT,
    ): String {
        val visible = diagram.elementIds.toSet()
        val elements =
            model.definitions
                .filter { it.id in visible }
                .filter { it is ActorDefinition || it is UseCaseDefinition }
        val synthetic =
            KumlDiagram(
                name = diagram.name,
                type = DiagramType.CLASS,
                elements = elements,
            )
        return toSvg(synthetic, layoutResult, theme, options)
    }

    /** [toSvg]-Variante für SysML 2 UC-Diagramme, schreibt direkt auf Platte. */
    public fun toSvgFile(
        model: Sysml2Model,
        diagram: UcDiagram,
        layoutResult: LayoutResult,
        out: Path,
        theme: KumlTheme = PlainTheme(),
        options: SvgRenderOptions = SvgRenderOptions.DEFAULT,
    ): File {
        val svg = toSvg(model, diagram, layoutResult, theme, options)
        val file = out.toFile()
        file.parentFile?.mkdirs()
        file.writeText(svg, Charsets.UTF_8)
        return file
    }

    /**
     * Berechnet die Liste der sichtbaren Part-Usages für ein IBD. Spiegelt die
     * Auswahllogik der [dev.kuml.layout.bridge.Sysml2LayoutBridge].
     *
     * Owner-eigene Part-Usages: in `model.usages` per `qualifiedName`-Präfix
     * gefiltert. Wenn `diagram.elementIds` gesetzt ist, wird auf diese
     * Teilmenge weiter eingegrenzt.
     */
    private fun visiblePartUsageElements(
        model: Sysml2Model,
        diagram: IbdDiagram,
    ): List<dev.kuml.sysml2.PartUsage> {
        val ownerPrefix = "${diagram.ownerId}::"
        val ownerPartUsages =
            model.usages
                .filterIsInstance<dev.kuml.sysml2.PartUsage>()
                .filter { it.id.startsWith(ownerPrefix) }
        val filter: Set<String>? = diagram.elementIds.takeIf { it.isNotEmpty() }?.toSet()
        return if (filter == null) ownerPartUsages else ownerPartUsages.filter { it.id in filter }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun shiftRoute(
        route: dev.kuml.layout.EdgeRoute,
        padding: Float,
    ): dev.kuml.layout.EdgeRoute {
        fun dev.kuml.layout.Point.shift() = dev.kuml.layout.Point(x + padding, y + padding)
        return when (route) {
            is dev.kuml.layout.EdgeRoute.Direct ->
                route.copy(source = route.source.shift(), target = route.target.shift())
            is dev.kuml.layout.EdgeRoute.OrthogonalRounded ->
                route.copy(
                    source = route.source.shift(),
                    target = route.target.shift(),
                    waypoints = route.waypoints.map { it.shift() },
                )
            is dev.kuml.layout.EdgeRoute.TreeRounded ->
                route.copy(
                    source = route.source.shift(),
                    target = route.target.shift(),
                    waypoints = route.waypoints.map { it.shift() },
                )
            is dev.kuml.layout.EdgeRoute.Bezier ->
                route.copy(
                    source = route.source.shift(),
                    target = route.target.shift(),
                    controlPoints = route.controlPoints.map { it.shift() },
                )
        }
    }

    private fun fmt(v: Float): String {
        val i = v.toInt()
        return if (v == i.toFloat()) "$i" else "%.2f".format(java.util.Locale.ROOT, v)
    }
}

// Suppress unused import warning for NodeId/EdgeId/GroupId — used via layoutResult
@Suppress("UnusedPrivateMember")
private fun unusedImportSuppressor(
    a: NodeId,
    b: EdgeId,
) = Unit
