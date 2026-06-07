package dev.kuml.io.svg

import dev.kuml.c4.model.C4Diagram
import dev.kuml.c4.model.C4Model
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.io.svg.sysml2.edge.Sysml2EdgeRenderer
import dev.kuml.layout.EdgeId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.sysml2.ActDiagram
import dev.kuml.sysml2.ActionDefinition
import dev.kuml.sysml2.ActivityPartitionDefinition
import dev.kuml.sysml2.ActorDefinition
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.ConstraintDefinition
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.LifelineDefinition
import dev.kuml.sysml2.MessageUsage
import dev.kuml.sysml2.ParDiagram
import dev.kuml.sysml2.PartDefinition
import dev.kuml.sysml2.ReqDiagram
import dev.kuml.sysml2.RequirementDefinition
import dev.kuml.sysml2.SeqDiagram
import dev.kuml.sysml2.StateDefinition
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.UcDiagram
import dev.kuml.sysml2.UseCaseDefinition
import dev.kuml.sysml2.edge.ActEdgeAdapter
import dev.kuml.sysml2.edge.ParEdgeAdapter
import dev.kuml.sysml2.edge.ReqEdgeAdapter
import dev.kuml.sysml2.edge.StmEdgeAdapter
import dev.kuml.sysml2.edge.Sysml2EdgeAdapter
import dev.kuml.sysml2.edge.UcEdgeAdapter
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
     * Renders a synthetic SysML 2 [KumlDiagram] hull with an adapter-driven
     * edge dispatch (V2.0.13).
     *
     * Nodes follow the same shifted-bounds + [NodeRendererDispatcher.dispatch]
     * path as [toSvg]. Edges, however, run through a three-way fallback:
     *
     *  1. If the synthetic hull has a `KumlElement` for the edge id, the
     *     legacy [EdgeRendererDispatcher.dispatch] path renders it — keeps
     *     the BDD KermlSpecialization / UML / C4 edges working unchanged.
     *  2. Otherwise, the [Sysml2EdgeAdapter] is asked for metadata. If
     *     present, [Sysml2EdgeRenderer.render] draws the line + dash +
     *     arrow head + stereotype / label.
     *  3. Otherwise, the plain solid line fallback used to be the V2.0.7—12
     *     default behaviour — kept here for safety so unknown edges still
     *     surface visually.
     */
    private fun renderSysml2Synthetic(
        synthetic: KumlDiagram,
        layoutResult: LayoutResult,
        theme: KumlTheme,
        options: SvgRenderOptions,
        sysml2EdgeAdapter: Sysml2EdgeAdapter,
    ): String =
        SvgDocument.render(layoutResult, theme, options) { nodesBuilder, edgesBuilder ->
            val padding = options.paddingPx

            // Nodes — same logic as the UML/C4 path.
            for ((nodeId, nodeLayout) in layoutResult.nodes) {
                val element = synthetic.elements.find { it.id == nodeId.value }
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

            // Edges — adapter-aware three-way fallback.
            val elementIndex = synthetic.elements.associateBy { it.id }
            for ((edgeId, route) in layoutResult.edges) {
                val shiftedRoute = shiftRoute(route, padding)
                val element = elementIndex[edgeId.value]
                if (element != null) {
                    EdgeRendererDispatcher.dispatch(element, shiftedRoute, theme, edgesBuilder)
                } else {
                    val meta = sysml2EdgeAdapter.metadataFor(edgeId.value)
                    if (meta != null) {
                        Sysml2EdgeRenderer.render(shiftedRoute, meta, theme, edgesBuilder)
                    } else {
                        // V2.0.7–12 fallback: plain solid line. Reached only if
                        // the adapter doesn't claim the edge, which should not
                        // happen for the five SysML-2 diagram kinds the
                        // adapters cover — kept for safety.
                        val (tag, attrs) = EdgePathBuilder.build(shiftedRoute)
                        edgesBuilder.tag(tag, attrs + mapOf("class" to "kuml-edge"))
                    }
                }
            }
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
        return renderSysml2Synthetic(synthetic, layoutResult, theme, options, UcEdgeAdapter(diagram))
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
     * Rendert ein SysML-2 REQ-Diagramm als SVG (V2.0.8).
     *
     * Wickelt das REQ in ein synthetisches [KumlDiagram] mit den sichtbaren
     * [RequirementDefinition]s, [PartDefinition]s, [UseCaseDefinition]s und
     * [ActorDefinition]s als `elements`. Der [NodeRendererDispatcher] hat
     * seit V2.0.8 einen Branch in
     * [dev.kuml.io.svg.sysml2.renderSysml2Definition], der
     * [RequirementDefinition] als dreikompartimentige `«requirement»`-Box
     * rendert; Parts/UseCases/Actors behalten ihre BDD-/UC-Renderpfade.
     *
     * **Edge-Styling**: der V2.0.8-MVP rendert alle vier REQ-Edge-Kinds
     * (Satisfy, Verify, Derive, Contains) als dieselbe einfache solide
     * Linie. Die synthetische `KumlDiagram`-Hülle enthält keine
     * `UmlRelationship`-Elemente für REQ-Edges, deshalb fällt der
     * [EdgeRendererDispatcher] auf den Plain-Edge-Pfad zurück — das ist gut
     * genug für die V2.0.8-Wave. Gestricheltes `«satisfy»` / `«verify»` /
     * `«deriveReqt»`-Styling und Stereotyp-Labels sind V2.x-Polish (siehe
     * [[kUML V2.0]]-Roadmap).
     */
    public fun toSvg(
        model: Sysml2Model,
        diagram: ReqDiagram,
        layoutResult: LayoutResult,
        theme: KumlTheme = PlainTheme(),
        options: SvgRenderOptions = SvgRenderOptions.DEFAULT,
    ): String {
        val visible = diagram.elementIds.toSet()
        val elements =
            model.definitions
                .filter { it.id in visible }
                .filter {
                    it is RequirementDefinition ||
                        it is PartDefinition ||
                        it is UseCaseDefinition ||
                        it is ActorDefinition
                }
        val synthetic =
            KumlDiagram(
                name = diagram.name,
                type = DiagramType.CLASS,
                elements = elements,
            )
        return renderSysml2Synthetic(synthetic, layoutResult, theme, options, ReqEdgeAdapter(diagram))
    }

    /** [toSvg]-Variante für SysML 2 REQ-Diagramme, schreibt direkt auf Platte. */
    public fun toSvgFile(
        model: Sysml2Model,
        diagram: ReqDiagram,
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
     * Rendert ein SysML-2 STM-Diagramm als SVG (V2.0.9).
     *
     * Wickelt das STM in ein synthetisches [KumlDiagram] mit den sichtbaren
     * [StateDefinition]s als `elements`. Der [NodeRendererDispatcher] hat
     * seit V2.0.9 einen Branch in
     * [dev.kuml.io.svg.sysml2.renderSysml2Definition], der
     * [StateDefinition] je nach `isInitial`/`isFinal`-Flags als gefüllten
     * Kreis (Initial), Donut (Final) oder abgerundetes Rechteck (regulär,
     * mit optionalen `entry/exit/do`-Action-Zeilen) rendert.
     *
     * **Edge-Styling**: der V2.0.9-MVP rendert Transitionen als dieselbe
     * einfache solide Linie. Die synthetische `KumlDiagram`-Hülle enthält
     * keine `UmlRelationship`-Elemente für TransitionUsages, deshalb fällt
     * der [EdgeRendererDispatcher] auf den Plain-Edge-Pfad zurück — das ist
     * gut genug für die V2.0.9-Wave. Der `trigger [guard] / effect`-Label
     * und gestricheltes Styling sind V2.x-Polish (siehe
     * [[kUML V2.0]]-Roadmap).
     */
    public fun toSvg(
        model: Sysml2Model,
        diagram: StmDiagram,
        layoutResult: LayoutResult,
        theme: KumlTheme = PlainTheme(),
        options: SvgRenderOptions = SvgRenderOptions.DEFAULT,
    ): String {
        val visible = diagram.elementIds.toSet()
        val elements =
            model.definitions
                .filter { it.id in visible }
                .filter { it is StateDefinition }
        val synthetic =
            KumlDiagram(
                name = diagram.name,
                type = DiagramType.CLASS,
                elements = elements,
            )
        return renderSysml2Synthetic(synthetic, layoutResult, theme, options, StmEdgeAdapter(model, diagram))
    }

    /** [toSvg]-Variante für SysML 2 STM-Diagramme, schreibt direkt auf Platte. */
    public fun toSvgFile(
        model: Sysml2Model,
        diagram: StmDiagram,
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
     * Rendert ein SysML-2 ACT-Diagramm als SVG (V2.0.10).
     *
     * Wickelt das ACT in ein synthetisches [KumlDiagram] mit den sichtbaren
     * [ActionDefinition]s als `elements`. Der [NodeRendererDispatcher] hat
     * seit V2.0.10 einen Branch in
     * [dev.kuml.io.svg.sysml2.renderSysml2Definition], der
     * [ActionDefinition] je nach [dev.kuml.sysml2.ActivityNodeKind] rendert:
     * abgerundetes Rechteck (Action), gefüllter Kreis (Initial), Donut
     * (Final), Kreis mit X (FlowFinal), Raute (Decision / Merge) oder
     * Synchronisations-Bar (Fork / Join).
     *
     * **Edge-Styling**: der V2.0.10-MVP rendert Control Flows und Object
     * Flows als dieselbe einfache solide Linie. Die synthetische
     * `KumlDiagram`-Hülle enthält keine `UmlRelationship`-Elemente für
     * `ControlFlowUsage` / `ObjectFlowUsage`, deshalb fällt der
     * [EdgeRendererDispatcher] auf den Plain-Edge-Pfad zurück — das ist gut
     * genug für die V2.0.10-Wave. Der `[guard]`-Label (Control Flow) und
     * `[ObjectType]`-Label (Object Flow) sind V2.x-Polish (siehe
     * [[kUML V2.0]]-Roadmap).
     */
    public fun toSvg(
        model: Sysml2Model,
        diagram: ActDiagram,
        layoutResult: LayoutResult,
        theme: KumlTheme = PlainTheme(),
        options: SvgRenderOptions = SvgRenderOptions.DEFAULT,
    ): String {
        val visible = diagram.elementIds.toSet()
        // V2.0.16: Include both ActionDefinitions and ActivityPartitionDefinitions
        //          in the synthetic-elements list for cross-reference, but
        //          only ActionDefinitions render as Layout-Nodes. Partitions
        //          surface as LayoutGroups via the bridge; the renderer
        //          iterates layoutResult.groups and draws each lane before
        //          the node loop so action boxes sit on top of their lane.
        val elements =
            model.definitions
                .filter { it.id in visible }
                .filter { it is ActionDefinition }
        val synthetic =
            KumlDiagram(
                name = diagram.name,
                type = DiagramType.CLASS,
                elements = elements,
            )
        // V2.0.16: Partitions are looked up from the full model (not the
        //          diagram-visible filter) so a partition that is referenced
        //          only via `actionDef(partition = …)` — not explicitly
        //          listed in the diagram — still surfaces. The bridge
        //          auto-picks-up the partition via `groupId` on the action
        //          node; the renderer just matches the group id to a
        //          partition definition here.
        val partitionsById: Map<String, ActivityPartitionDefinition> =
            model.definitions
                .filterIsInstance<ActivityPartitionDefinition>()
                .associateBy { it.id }
        val adapter = ActEdgeAdapter(model, diagram)

        return SvgDocument.render(layoutResult, theme, options) { nodesBuilder, edgesBuilder ->
            val padding = options.paddingPx

            // 1. V2.0.16: render swimlane outlines + header bars FIRST so
            //    action nodes and edges layer on top.
            for ((groupId, groupLayout) in layoutResult.groups) {
                val partition = partitionsById[groupId.value] ?: continue
                dev.kuml.io.svg.sysml2
                    .renderActivityPartitionGroup(partition, groupLayout, padding, nodesBuilder)
            }

            // 2. Standard node loop (action boxes + pins, pseudo nodes,
            //    diamonds, bars) — identical logic to renderSysml2Synthetic.
            for ((nodeId, nodeLayout) in layoutResult.nodes) {
                val element = synthetic.elements.find { it.id == nodeId.value }
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

            // 3. Edges — adapter-aware three-way fallback (identical to
            //    renderSysml2Synthetic).
            val elementIndex = synthetic.elements.associateBy { it.id }
            for ((edgeId, route) in layoutResult.edges) {
                val shiftedRoute = shiftRoute(route, padding)
                val element = elementIndex[edgeId.value]
                if (element != null) {
                    EdgeRendererDispatcher.dispatch(element, shiftedRoute, theme, edgesBuilder)
                } else {
                    val meta = adapter.metadataFor(edgeId.value)
                    if (meta != null) {
                        Sysml2EdgeRenderer.render(shiftedRoute, meta, theme, edgesBuilder)
                    } else {
                        val (tag, attrs) = EdgePathBuilder.build(shiftedRoute)
                        edgesBuilder.tag(tag, attrs + mapOf("class" to "kuml-edge"))
                    }
                }
            }
        }
    }

    /** [toSvg]-Variante für SysML 2 ACT-Diagramme, schreibt direkt auf Platte. */
    public fun toSvgFile(
        model: Sysml2Model,
        diagram: ActDiagram,
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
     * Rendert ein SysML-2 SEQ-Diagramm als SVG (V2.0.11).
     *
     * **Architektur-Divergenz** gegenüber den anderen sechs SysML-2-Diagramm-
     * Overloads: SEQ verarbeitet Nachrichten **direkt im Renderer**, nicht
     * über den [EdgeRendererDispatcher]. Die [dev.kuml.layout.bridge.Sysml2LayoutBridge]
     * (SEQ-Overload) emittiert nur Lifelines als Layout-Knoten — keine Edges —
     * weil ELKs hierarchisches Layout für Sequence-Diagramme strukturell
     * ungeeignet ist (Lifelines = feste X-Spuren, Messages = horizontale
     * Pfeile an seqNo-indizierten Y-Positionen). Siehe ausführliche
     * Begründung in [SeqDiagram] und [dev.kuml.io.svg.sysml2.renderLifelineHead].
     *
     * **Render-Pipeline** (V2.0.11):
     *  1. Synthetische [KumlDiagram]-Hülle mit den sichtbaren
     *     [LifelineDefinition]s als `elements` — Standard-Knoten-Loop
     *     rendert sie via [dev.kuml.io.svg.sysml2.renderLifelineHead]
     *     (Kopf-Box + vertikale gestrichelte Zeit-Achse).
     *  2. **Nach** dem Knoten-Loop: direkter Aufruf von
     *     [dev.kuml.io.svg.sysml2.renderSysml2SeqMessages] mit allen
     *     [MessageUsage]s aus `model.usages` — der Renderer filtert auf
     *     sichtbare Endpunkte, sortiert nach seqNo und zeichnet jede
     *     Nachricht als horizontalen Pfeil zwischen den Lifeline-
     *     Mittelpunkten.
     *
     * Diese SVG-Methode unterscheidet sich strukturell von den anderen
     * SysML-2-Overloads — sie kann nicht einfach `toSvg(synthetic, ...)`
     * delegieren, weil sie nach dem `populate`-Callback der `SvgDocument.render`
     * noch die Nachrichten in den Edges-Builder injizieren muss. Daher die
     * direkte Verwendung von `SvgDocument.render` mit eigenem `populate`.
     */
    public fun toSvg(
        model: Sysml2Model,
        diagram: SeqDiagram,
        layoutResult: LayoutResult,
        theme: KumlTheme = PlainTheme(),
        options: SvgRenderOptions = SvgRenderOptions.DEFAULT,
    ): String {
        val visibleIds = diagram.elementIds.toSet()
        val visibleLifelines: List<LifelineDefinition> =
            diagram.elementIds.mapNotNull { id ->
                model.definitions.firstOrNull { it.id == id } as? LifelineDefinition
            }
        val synthetic =
            KumlDiagram(
                name = diagram.name,
                type = DiagramType.CLASS,
                elements = visibleLifelines,
            )
        val messages = model.usages.filterIsInstance<MessageUsage>()
        // V2.0.15: Combined Fragments + Execution Specs — auch renderer-direkt
        // (analog zu Messages, keine LayoutGraph-Edges). Werden vor den
        // Messages gezeichnet, damit Message-Pfeile visuell über
        // Aktivierungs-Bars / Frames liegen.
        val fragments = model.usages.filterIsInstance<dev.kuml.sysml2.CombinedFragmentUsage>()
        val execSpecs =
            model.usages
                .filterIsInstance<dev.kuml.sysml2.ExecutionSpecificationUsage>()
                .filter { it.lifelineId in visibleIds }

        return SvgDocument.render(layoutResult, theme, options) { nodesBuilder, edgesBuilder ->
            val padding = options.paddingPx

            // 1. Standard-Knoten-Loop — rendert Lifeline-Köpfe + vertikale
            //    gestrichelte Zeit-Achse pro sichtbarer Lifeline.
            val shiftedLayouts = mutableMapOf<NodeId, dev.kuml.layout.NodeLayout>()
            for ((nodeId, nodeLayout) in layoutResult.nodes) {
                val element = synthetic.elements.find { it.id == nodeId.value }
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
                    shiftedLayouts[nodeId] = shifted
                }
            }

            // 2. V2.0.15: Execution Specs FIRST — die Aktivierungs-Bar liegt
            //    *unter* den Message-Pfeilen, damit Pfeile durchgehend
            //    sichtbar bleiben.
            for (es in execSpecs) {
                val lifelineLayout = shiftedLayouts[NodeId(es.lifelineId)] ?: continue
                dev.kuml.io.svg.sysml2
                    .renderExecutionSpec(es, lifelineLayout, edgesBuilder)
            }

            // 3. V2.0.15: Combined Fragments — gestrichelter Rahmen + Operator-
            //    Tag-Pentagon. Reihenfolge nach Frame-Größe absteigend, damit
            //    bei zukünftigen Nested-CFs (V2.x) die äußeren zuerst kommen.
            val visibleLifelineLayouts: List<dev.kuml.layout.NodeLayout> =
                visibleLifelines.mapNotNull { shiftedLayouts[NodeId(it.id)] }
            for (fragment in fragments) {
                dev.kuml.io.svg.sysml2.renderCombinedFragment(
                    fragment = fragment,
                    visibleLifelineLayouts = visibleLifelineLayouts,
                    builder = edgesBuilder,
                )
            }

            // 4. Direkt-Render der Nachrichten — siehe Architektur-Divergenz
            //    oben. Die geshifteten Layouts werden an den Sequence-Renderer
            //    durchgereicht, damit X-/Y-Berechnungen mit dem Padding
            //    konsistent bleiben. Nachrichten kommen ZULETZT, damit
            //    Pfeile visuell über Frames + Aktivierungs-Bars liegen.
            dev.kuml.io.svg.sysml2.renderSysml2SeqMessages(
                messages = messages,
                visibleLifelineIds = visibleIds,
                nodeLayouts = shiftedLayouts,
                builder = edgesBuilder,
            )
        }
    }

    /** [toSvg]-Variante für SysML 2 SEQ-Diagramme, schreibt direkt auf Platte. */
    public fun toSvgFile(
        model: Sysml2Model,
        diagram: SeqDiagram,
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
     * Rendert ein SysML-2 PAR-Diagramm als SVG (V2.0.12) — die schließende
     * achte Welle der SysML-2-Diagramm-Typ-Serie.
     *
     * Wickelt das PAR in ein synthetisches [KumlDiagram] mit den sichtbaren
     * [ConstraintDefinition]s und [PartDefinition]s als `elements`. Der
     * [NodeRendererDispatcher] hat seit V2.0.12 einen Branch in
     * [dev.kuml.io.svg.sysml2.renderSysml2Definition], der
     * [ConstraintDefinition] als dreikompartimentige `«constraint»`-Box mit
     * Expression-Body und Parameter-Pin-Liste rendert; PartDefinitions
     * behalten ihren BDD-Renderpfad.
     *
     * **Edge-Styling**: der V2.0.12-MVP rendert Bindings als dieselbe einfache
     * solide Linie. Die synthetische `KumlDiagram`-Hülle enthält keine
     * `UmlRelationship`-Elemente für [dev.kuml.sysml2.BindingConnectorUsage],
     * deshalb fällt der [EdgeRendererDispatcher] auf den Plain-Edge-Pfad
     * zurück — das ist gut genug für die V2.0.12-Wave. Parameter-Pin-Endpunkt-
     * Anchoring (Bindings docken direkt am Pin statt am Box-Mittelpunkt an)
     * ist V2.x-Polish (siehe [[kUML V2.0]]-Roadmap).
     */
    public fun toSvg(
        model: Sysml2Model,
        diagram: ParDiagram,
        layoutResult: LayoutResult,
        theme: KumlTheme = PlainTheme(),
        options: SvgRenderOptions = SvgRenderOptions.DEFAULT,
    ): String {
        val visible = diagram.elementIds.toSet()
        val elements =
            model.definitions
                .filter { it.id in visible }
                .filter { it is ConstraintDefinition || it is PartDefinition }
        val synthetic =
            KumlDiagram(
                name = diagram.name,
                type = DiagramType.CLASS,
                elements = elements,
            )
        return renderSysml2Synthetic(synthetic, layoutResult, theme, options, ParEdgeAdapter(model, diagram))
    }

    /** [toSvg]-Variante für SysML 2 PAR-Diagramme, schreibt direkt auf Platte. */
    public fun toSvgFile(
        model: Sysml2Model,
        diagram: ParDiagram,
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
