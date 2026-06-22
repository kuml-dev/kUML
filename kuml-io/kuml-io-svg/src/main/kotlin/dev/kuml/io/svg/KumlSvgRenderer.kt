package dev.kuml.io.svg

import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.bpmn.model.BpmnParticipant
import dev.kuml.bpmn.model.BpmnSubProcess
import dev.kuml.bpmn.model.CollaborationDiagram
import dev.kuml.c4.model.C4Diagram
import dev.kuml.c4.model.C4Model
import dev.kuml.c4.model.DynamicDiagram
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlElement
import dev.kuml.core.model.PackageDiagramConfig
import dev.kuml.io.svg.c4.c4RelationshipLabel
import dev.kuml.io.svg.c4.renderC4Interaction
import dev.kuml.io.svg.c4.renderC4Relationship
import dev.kuml.io.svg.sysml2.edge.Sysml2EdgeRenderer
import dev.kuml.io.svg.sysml2.sysml2SeqFragmentLeftPad
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.GroupId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
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
import dev.kuml.sysml2.MessageKind
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
import dev.kuml.sysml2.edge.BddEdgeAdapter
import dev.kuml.sysml2.edge.IbdEdgeAdapter
import dev.kuml.sysml2.edge.ParEdgeAdapter
import dev.kuml.sysml2.edge.ReqEdgeAdapter
import dev.kuml.sysml2.edge.StmEdgeAdapter
import dev.kuml.sysml2.edge.Sysml2EdgeAdapter
import dev.kuml.sysml2.edge.UcEdgeAdapter
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlConnector
import dev.kuml.uml.UmlDependency
import dev.kuml.uml.UmlInteraction
import dev.kuml.uml.UmlNamedElement
import dev.kuml.uml.UmlPackage
import dev.kuml.uml.UmlStateMachine
import dev.kuml.uml.UmlUseCaseSubject
import java.io.File
import java.nio.file.Path
import kotlin.math.abs

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
    ): String {
        // SEQ diagrams have their own renderer-direct path (no edge routing through ELK)
        if (diagram.type == DiagramType.SEQUENCE) {
            return renderUmlSequence(diagram, layoutResult, theme, options)
        }
        // STATE diagrams have their own renderer-direct path (vertex/transition dispatch)
        if (diagram.type == DiagramType.STATE) {
            return renderUmlStateDiagram(diagram, layoutResult, theme, options)
        }
        // BPMN_PROCESS diagrams have a dedicated rendering path with explicit z-order:
        // expanded SubProcess frames (groups) are painted BEFORE their child nodes so the
        // frame background never occludes its contents — identical fix as C4/SysML-2 groups.
        if (diagram.type == DiagramType.BPMN_PROCESS) {
            return renderBpmnProcess(diagram, layoutResult, theme, options)
        }
        // V11.x package-diagram fix: build a flat (id → element) lookup that
        // recurses into UmlPackage.members. Without this, classes/interfaces
        // declared inside `packageOf { … }` never reach the dispatcher because
        // they are NOT in `diagram.elements` at the top level — they live as
        // members of their owning UmlPackage. Same recursion as
        // `collectComponentPorts` in UmlLayoutBridge.
        val elementIndex = buildKumlElementIndex(diagram.elements)

        // V11.x edge-endpoint fix: ELK anchors inter-package edges at the
        // compound-node outer boundary (the very top of the tab area). The
        // folder tab is narrower than the package body, so the arrowhead often
        // lands in the empty "notch" between the tab end and the body start —
        // visually the arrow appears to float before reaching the box.
        // Post-processing snaps every package-dependency route to a Direct line
        // that enters/exits at the body rectangle (y = groupOrigin + tabH),
        // which is always full-width and therefore always visually reachable.
        val effectiveLayoutResult: LayoutResult =
            if (diagram.type == DiagramType.PACKAGE) {
                snapPackageEdgesToBodyBoundary(elementIndex, layoutResult)
            } else {
                layoutResult
            }
        // Package groups need name + folder tab; collect them keyed by id so
        // the group loop can decorate each LayoutGroup with the correct shape.
        val packagesById: Map<String, UmlPackage> =
            if (diagram.type == DiagramType.PACKAGE) collectUmlPackages(diagram.elements) else emptyMap()
        val showFolderTabs =
            (diagram.config as? PackageDiagramConfig)?.showFolderTabs ?: true

        // Use-Case subject groups need a name label; collect them so the group
        // loop can draw the system-boundary rect + name text.
        val subjectsById: Map<String, UmlUseCaseSubject> =
            diagram.elements.filterIsInstance<UmlUseCaseSubject>().associateBy { it.id }

        // V2.0.46: Activity-Diagramme bekommen einen Edge-Clipper, der die
        //          Endpunkte jedes Routings auf den tatsächlichen Shape-Rand
        //          des Quell-/Zielknotens snappt (Raute für Decision/Merge,
        //          Kreis mit fixem Radius für Initial/Final/FlowFinal,
        //          Rechteck für Action/Object/Bar). Sonst enden Pfeile an
        //          der ELK-Bounding-Box, die für nicht-rechteckige Shapes
        //          deutlich über den sichtbaren Rand hinausragt — Folge:
        //          schwebende Pfeile (Vault-Feedback aus
        //          [[03 Bereiche/kUML/Beispiele/17 UML Activity – Checkout Flow]]).
        //
        //          Interaction-Overview-Diagramme verwenden dieselben Shapes
        //          (Kreis für Initial/Final, Raute für Decision/Merge,
        //          gerundetes Rechteck für InteractionRef) und denselben
        //          [dev.kuml.uml.UmlActivityEdge]-Kantentyp, also bekommen
        //          sie den Clipper ebenfalls (Vault-Feedback aus
        //          [[03 Bereiche/kUML/Beispiele/22 UML Interaction Overview – Order Process]]:
        //          schwebende Pfeile zwischen `initial`/`final` und den
        //          `ref`-Frames).
        //
        //          Für andere Diagrammtypen bleibt der Index leer und die
        //          unten folgende Edge-Schleife läuft clipping-frei.
        val activityShapeByNodeId: Map<String, dev.kuml.io.svg.sysml2.Sysml2ActivityEdgeClipper.Shape> =
            if (diagram.type == DiagramType.ACTIVITY ||
                diagram.type == DiagramType.INTERACTION_OVERVIEW
            ) {
                buildActivityShapeIndex(diagram.elements, effectiveLayoutResult, options.paddingPx)
            } else {
                emptyMap()
            }

        // V2.0.47 — Komponenten-Vertrags-Symbole (Lollipop / Socket) ragen
        // ca. 47 px über die Oberkante der Komponente hinaus (Stub + Kreis +
        // Label-Gap + Labelhöhe). Damit weder Symbol noch Label oberhalb der
        // viewBox abgeschnitten werden, wird `paddingPx` für Komponenten-
        // diagramme, die mindestens einen ungebundenen Vertrag haben, auf
        // mindestens diesen Wert angehoben. Andere Diagrammtypen bleiben
        // unverändert.
        val layoutNodeIdSet: Set<String> =
            effectiveLayoutResult.nodes.keys
                .map { it.value }
                .toSet()
        val needsContractPadding =
            diagram.type == DiagramType.COMPONENT &&
                elementIndex.values.any {
                    it is UmlComponent &&
                        dev.kuml.io.svg.uml.UmlComponentContracts.hasUnboundContracts(it) { id ->
                            id in layoutNodeIdSet
                        }
                }
        val effectiveOptions =
            if (needsContractPadding) {
                options.copy(
                    paddingPx =
                        maxOf(
                            options.paddingPx,
                            dev.kuml.io.svg.uml.UmlComponentContracts.TOTAL_UPWARD_EXTENT_PX + 4f,
                        ),
                )
            } else {
                options
            }

        return SvgDocument.render(effectiveLayoutResult, theme, effectiveOptions) { nodesBuilder, edgesBuilder ->
            val padding = effectiveOptions.paddingPx

            // Groups FIRST — paint backgrounds before children so node boxes
            // appear on top of the group rectangle.
            for ((groupId, groupLayout) in effectiveLayoutResult.groups) {
                val gx = groupLayout.bounds.origin.x + padding
                val gy = groupLayout.bounds.origin.y + padding
                val gw = groupLayout.bounds.size.width
                val gh = groupLayout.bounds.size.height
                val pkg = packagesById[groupId.value]
                val subject = subjectsById[groupId.value]
                if (pkg != null && showFolderTabs) {
                    renderPackageGroup(pkg, gx, gy, gw, gh, theme, nodesBuilder)
                } else if (subject != null) {
                    renderSubjectGroup(subject, gx, gy, gw, gh, theme, nodesBuilder)
                } else {
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
            }

            // Nodes
            for ((nodeId, nodeLayout) in effectiveLayoutResult.nodes) {
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

            // V2.0.47 — Komponenten-Vertrags-Kurznotation. Für jede
            //           UmlComponent, die `provides`/`requires` auf eine
            //           Interface-ID hat, die NICHT als sichtbarer Knoten
            //           existiert, wird ein Lollipop- bzw. Socket-Symbol
            //           über der Komponente gezeichnet (siehe
            //           [UmlComponentContracts]-KDoc).
            if (diagram.type == DiagramType.COMPONENT) {
                for ((nodeId, nodeLayout) in effectiveLayoutResult.nodes) {
                    val element = elementIndex[nodeId.value] as? UmlComponent ?: continue
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
                    dev.kuml.io.svg.uml.UmlComponentContracts.render(
                        component = element,
                        layout = shifted,
                        isDiagramNode = { id -> id in layoutNodeIdSet },
                        builder = nodesBuilder,
                    )
                }
            }

            // Edges
            // V2.0.47 — `flatElementIndex` muss bei Komponentendiagrammen auch
            // verschachtelte Komponenten kennen, damit der
            // [ComponentPortEdgeClipper] für Connectors zwischen Ports
            // verschachtelter Komponenten die UmlComponent-Instanz findet.
            // Bei allen anderen Diagrammtypen ändert die Rekursion über
            // `buildKumlElementIndex` nichts (Member-Elemente werden eh nicht
            // direkt referenziert).
            val flatElementIndex: Map<String, KumlElement> = elementIndex
            val nodeLookup: (String) -> dev.kuml.layout.NodeLayout? = { id ->
                effectiveLayoutResult.nodes[NodeId(id)]
            }
            // V2.0.47 — Bounds-Lookup für den ComponentPortEdgeClipper.
            //          Liefert die *bereits um Padding verschobene* Bounding-
            //          Box einer Komponente, also exakt die Box die der
            //          NodeRenderer für sie zeichnet. So treffen die
            //          Connector-Endpunkte das Port-Quadrat punktgenau.
            val componentBoundsLookup: (String) -> Rect? = { id ->
                effectiveLayoutResult.nodes[NodeId(id)]?.let { nl ->
                    nl.bounds.copy(
                        origin =
                            nl.bounds.origin.copy(
                                x = nl.bounds.origin.x + padding,
                                y = nl.bounds.origin.y + padding,
                            ),
                    )
                }
            }
            val isComponentDiagram = diagram.type == DiagramType.COMPONENT
            for ((edgeId, route) in effectiveLayoutResult.edges) {
                val element = flatElementIndex[edgeId.value]
                if (element != null) {
                    // V2.x — Self-Loops bekommen eine vergrößerte C-Loop-Route,
                    // statt der von ELK gelieferten 10-px-U-Form, damit Self-FKs
                    // (z.B. `UserPosts.parent → UserPosts`) sichtbar bleiben.
                    val routed = SelfLoopRouter.adjust(element, route, nodeLookup)
                    val shiftedRoute = shiftRoute(routed, padding)
                    // V2.0.46: für Activity-Diagramme die Endpunkte auf den
                    //          tatsächlichen Shape-Rand des Quell-/Zielknotens
                    //          snappen (siehe `activityShapeByNodeId`-KDoc).
                    //          Für andere Diagrammtypen ist die Map leer, also
                    //          ist diese Schleife dann eine reine no-op.
                    val activityClippedRoute =
                        if (activityShapeByNodeId.isNotEmpty() && element is dev.kuml.uml.UmlActivityEdge) {
                            dev.kuml.io.svg.sysml2.Sysml2ActivityEdgeClipper.clip(
                                route = shiftedRoute,
                                sourceShape = activityShapeByNodeId[element.sourceId],
                                targetShape = activityShapeByNodeId[element.targetId],
                            )
                        } else {
                            shiftedRoute
                        }
                    // V2.0.47: Connector-Endpunkte in Komponentendiagrammen
                    //          auf die tatsächlichen Port-Quadrate snappen
                    //          (siehe [ComponentPortEdgeClipper]-KDoc). ELK
                    //          kennt keine Port-Geometrie und routet sonst an
                    //          irgendeine Stelle der Komponentenkante. Self-
                    //          Loops im Komponentendiagramm bleiben der vom
                    //          SelfLoopRouter erzeugten C-Loop-Geometrie
                    //          überlassen.
                    val clippedRoute =
                        if (isComponentDiagram &&
                            element is UmlConnector &&
                            element.end1Id != element.end2Id
                        ) {
                            dev.kuml.io.svg.uml.ComponentPortEdgeClipper.clip(
                                route = activityClippedRoute,
                                end1Id = element.end1Id,
                                end2Id = element.end2Id,
                                componentLookup = { id -> flatElementIndex[id] as? UmlComponent },
                                boundsLookup = componentBoundsLookup,
                            )
                        } else {
                            activityClippedRoute
                        }
                    EdgeRendererDispatcher.dispatch(element, clippedRoute, theme, edgesBuilder)
                }
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

            // Groups FIRST — paint backgrounds before children so node boxes
            // appear on top of the group rectangle.
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

            // Edges
            //
            // Für reguläre C4-Diagramme (SystemContext, Container, Component,
            // Landscape, Deployment) sind die Layout-Edges per Relationship-ID
            // adressiert; [renderC4Relationship] rendert eine durchgezogene
            // Linie + Label.
            //
            // Für ein [DynamicDiagram] emittiert die [C4LayoutBridge] zusätzlich
            // pro [dev.kuml.c4.model.C4Interaction] eine Edge mit der
            // Interaction-ID — diese ID existiert nicht im `relationshipIndex`,
            // also löst der Fallback unten auf den interaction-Index auf und
            // dispatcht in [renderC4Interaction] (durchgezogen für request,
            // gestrichelt für response, Label mit Sequenznummer-Prefix).
            //
            // V11.x — Label-Überlappungserkennung: wenn zwei C4-Relationship-
            // Labels nach [EdgeLabelGeometry.midAnchor] räumlich zu nah
            // beieinander landen (x-Abstand < 40 px, y-Abstand < 120 px),
            // werden sie senkrecht gestaffelt (±LABEL_STAGGER_PX). Tritt
            // typischerweise auf, wenn zwei Kanten dasselbe Element als
            // Source/Target haben und ELK sie durch denselben vertikalen
            // Korridor routet — z.B. Customer→InternetBanking und
            // InternetBanking→EmailService im C4-Container-Beispiel.
            val interactionIndex: Map<String, dev.kuml.c4.model.C4Interaction> =
                if (diagram is DynamicDiagram) {
                    diagram.interactions.associateBy { it.id }
                } else {
                    emptyMap()
                }

            // Pre-compute all shifted routes so the stagger-detection and the
            // rendering pass both use the same (already-padded) coordinates.
            val shiftedEdgeRoutes: Map<EdgeId, EdgeRoute> =
                layoutResult.edges.mapValues { (_, route) -> shiftRoute(route, padding) }

            val labelYOffsets: Map<String, Float> =
                computeC4LabelStaggerOffsets(shiftedEdgeRoutes, relationshipIndex)

            for ((edgeId, shiftedRoute) in shiftedEdgeRoutes) {
                val rel = relationshipIndex[edgeId.value]
                if (rel != null) {
                    val yOff = labelYOffsets[edgeId.value] ?: 0f
                    renderC4Relationship(rel, shiftedRoute, theme, edgesBuilder, yOff)
                    continue
                }
                val interaction = interactionIndex[edgeId.value]
                if (interaction != null) {
                    renderC4Interaction(interaction, shiftedRoute, theme, edgesBuilder)
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

    private fun renderUmlSequence(
        diagram: KumlDiagram,
        layoutResult: LayoutResult,
        theme: KumlTheme,
        options: SvgRenderOptions,
    ): String {
        val interaction =
            diagram.elements.filterIsInstance<UmlInteraction>().firstOrNull()
                ?: return SvgDocument.render(layoutResult, theme, options) { _, _ -> } // fallback

        val visibleIds = interaction.lifelines.map { it.id }.toSet()

        // V3.0.x: Wenn das Interaktion Combined Fragments enthält, ragt der
        // Fragment-Rahmen FRAGMENT_PADDING (24 px) links und rechts über die
        // äußersten Lifelines hinaus. Das Default-Canvas-Padding (16 px)
        // reicht dafür nicht — die rechte Frame-Kante wäre außerhalb der
        // SVG-`viewBox` und würde geclippt (Symptom: ALT-Box rechts
        // abgeschnitten). Heben wir das effektive Padding deshalb auf mindestens
        // `FRAGMENT_PADDING + 4` an, sobald Fragments im Spiel sind. Das ist
        // sowohl im Layout-Shift der Lifelines als auch in der Canvas-Größe in
        // `SvgDocument.render` wirksam, weil beide `options.paddingPx` lesen.
        val effectiveOptions =
            if (interaction.fragments.isNotEmpty()) {
                options.copy(
                    paddingPx = maxOf(options.paddingPx, dev.kuml.io.svg.uml.UML_SEQ_FRAGMENT_PADDING + 4f),
                )
            } else {
                options
            }

        return SvgDocument.render(layoutResult, theme, effectiveOptions) { nodesBuilder, edgesBuilder ->
            val padding = effectiveOptions.paddingPx
            val shiftedLayouts = mutableMapOf<dev.kuml.layout.NodeId, dev.kuml.layout.NodeLayout>()

            // 1. Pre-compute shifted lifeline layouts WITHOUT emitting SVG.
            //    We need them already when the fragments are rendered so the
            //    fragment frame spans the correct X-range, but the lifeline
            //    heads + dashed time axes must be painted AFTER the fragment
            //    background — otherwise the alt/opt/loop frame (in <g id="edges">,
            //    or — previously — even in nodesBuilder after the lifelines)
            //    overpaints the dashed verticals inside the frame. Classic
            //    z-order bug; same family as the C4-groups-before-nodes fix.
            for ((nodeId, nodeLayout) in layoutResult.nodes) {
                if (interaction.lifelines.find { it.id == nodeId.value } == null) continue
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
                shiftedLayouts[nodeId] = shifted
            }

            // 2. Render combined fragments FIRST into the nodes layer, so they
            //    sit BEHIND the lifeline dashed verticals (and behind everything
            //    in <g id="edges"> — execution specs, messages, …). The fragment
            //    frame already uses fill="none", so only the operator-tag pentagon
            //    in the top-left corner paints opaque, and that corner sits
            //    OUTSIDE the leftmost lifeline's centre-line (frame starts at
            //    minLifelineX - FRAGMENT_PADDING).
            val visibleLifelineLayouts =
                interaction.lifelines
                    .mapNotNull { shiftedLayouts[dev.kuml.layout.NodeId(it.id)] }
            dev.kuml.io.svg.uml.renderUmlCombinedFragments(
                interaction.fragments,
                interaction,
                visibleLifelineLayouts,
                nodesBuilder,
            )

            // 3. NOW render the lifeline heads + dashed time axes on top of the
            //    fragment background. The dashed verticals stay visible inside
            //    every alt/opt/loop frame.
            for ((nodeId, shifted) in shiftedLayouts) {
                val lifeline = interaction.lifelines.find { it.id == nodeId.value } ?: continue
                NodeRendererDispatcher.dispatch(lifeline, shifted, theme, nodesBuilder)
            }

            // 4. Render messages directly into the edges layer — they paint
            //    last (after the entire nodes layer), so arrows always sit on
            //    top of frames and lifelines.
            dev.kuml.io.svg.uml.renderUmlSeqMessages(
                interaction.messages,
                visibleIds,
                shiftedLayouts,
                edgesBuilder,
            )
        }
    }

    /**
     * Rendert ein UML STATE-Diagramm als SVG.
     *
     * Flat-layout: der [UmlStateMachine]-Rahmen wird als LayoutGroup gerendert,
     * alle Vertices (States, Pseudostates, FinalStates) und Transitionen
     * als Nodes + Edges. Dieser Pfad wird aufgerufen wenn
     * [DiagramType.STATE] erkannt wird, bevor der generische UML-Pfad greift.
     */
    private fun renderUmlStateDiagram(
        diagram: KumlDiagram,
        layoutResult: LayoutResult,
        theme: KumlTheme,
        options: SvgRenderOptions,
    ): String {
        val sm =
            diagram.elements.filterIsInstance<UmlStateMachine>().firstOrNull()
                ?: return SvgDocument.render(layoutResult, theme, options) { _, _ -> } // fallback

        // Flatten all vertices (including substates) into a lookup map
        val vertexIndex = mutableMapOf<String, dev.kuml.uml.UmlVertex>()

        fun collectVertices(vertices: List<dev.kuml.uml.UmlVertex>) {
            for (v in vertices) {
                vertexIndex[v.id] = v
                if (v is dev.kuml.uml.UmlState && v.substates.isNotEmpty()) collectVertices(v.substates)
            }
        }
        collectVertices(sm.vertices)

        // Transition lookup by ID
        val transitionIndex = sm.transitions.associateBy { it.id }

        return SvgDocument.render(layoutResult, theme, options) { nodesBuilder, edgesBuilder ->
            val padding = options.paddingPx

            // 1. Render state machine frame (group background)
            for ((groupId, groupLayout) in layoutResult.groups) {
                if (groupId.value != sm.id) continue
                val gx = groupLayout.bounds.origin.x + padding
                val gy = groupLayout.bounds.origin.y + padding
                val gw = groupLayout.bounds.size.width
                val gh = groupLayout.bounds.size.height
                val smLayout =
                    dev.kuml.layout.NodeLayout(
                        bounds =
                            dev.kuml.layout.Rect(
                                origin = dev.kuml.layout.Point(gx, gy),
                                size = dev.kuml.layout.Size(gw, gh),
                            ),
                    )
                NodeRendererDispatcher.dispatch(sm, smLayout, theme, nodesBuilder)
            }

            // 2. Render vertices (states, pseudostates, final states)
            for ((nodeId, nodeLayout) in layoutResult.nodes) {
                val vertex = vertexIndex[nodeId.value] ?: continue
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
                NodeRendererDispatcher.dispatch(vertex, shifted, theme, nodesBuilder)
            }

            // V2.0.43: highlight ring overlay — injected AFTER vertices, BEFORE transitions
            if (options.highlightVertexIds.isNotEmpty()) {
                for ((nodeId, nodeLayout) in layoutResult.nodes) {
                    if (nodeId.value !in options.highlightVertexIds) continue
                    val gx = nodeLayout.bounds.origin.x + padding - options.highlightRingOffsetPx
                    val gy = nodeLayout.bounds.origin.y + padding - options.highlightRingOffsetPx
                    val gw = nodeLayout.bounds.size.width + 2 * options.highlightRingOffsetPx
                    val gh = nodeLayout.bounds.size.height + 2 * options.highlightRingOffsetPx
                    nodesBuilder.tag(
                        "rect",
                        mapOf(
                            "id" to xmlEscapeAttr("highlight-ring-${nodeId.value}"),
                            "class" to "kuml-highlight-ring",
                            "x" to fmt(gx),
                            "y" to fmt(gy),
                            "width" to fmt(gw),
                            "height" to fmt(gh),
                            "fill" to "none",
                            "stroke" to options.highlightStrokeColor,
                            "stroke-width" to fmt(options.highlightStrokeWidthPx),
                            "rx" to "4",
                            "ry" to "4",
                        ),
                    )
                }
            }

            // 3. Render transitions with labels
            // V11.x — Stack-Indizes für parallele Edges vorberechnen, damit
            // benachbarte Transition-Labels einander nicht überschreiben.
            // V2.x — Label-Text wird mitgegeben, damit das Clustering
            // Bounding-Box-Overlap statt reiner Euklid-Distanz nutzt
            // (siehe KDoc auf Sysml2EdgeRenderer.computeLabelStackIndices).
            val stmStackIndices =
                dev.kuml.io.svg.sysml2.edge.Sysml2EdgeRenderer.computeLabelStackIndices(
                    layoutResult.edges.entries.map { (edgeId, route) ->
                        val transition = transitionIndex[edgeId.value]
                        val labelText =
                            if (transition == null) {
                                null
                            } else {
                                buildList {
                                    if (transition.trigger != null) add(transition.trigger)
                                    if (transition.guard != null) add(transition.guard)
                                    if (transition.effect != null) add("/ ${transition.effect}")
                                }.joinToString(" ").ifEmpty { null }
                            }
                        Triple(edgeId, route, labelText)
                    },
                )
            for ((edgeId, route) in layoutResult.edges) {
                val transition = transitionIndex[edgeId.value] ?: continue
                val shiftedRoute = shiftRoute(route, padding)

                // Build label text: "trigger [guard] / effect"
                val parts =
                    buildList {
                        if (transition.trigger != null) add(transition.trigger)
                        // Guard is stored as-is from DSL (may already include "[...]" brackets)
                        if (transition.guard != null) add(transition.guard)
                        if (transition.effect != null) add("/ ${transition.effect}")
                    }
                val label = parts.joinToString(" ")

                val meta =
                    dev.kuml.sysml2.edge.Sysml2EdgeMetadata(
                        stereotype = null,
                        label = label.ifEmpty { null },
                        dashArray = null,
                        arrowHead = dev.kuml.sysml2.edge.Sysml2ArrowHead.OpenAngle,
                    )
                dev.kuml.io.svg.sysml2.edge.Sysml2EdgeRenderer
                    .render(
                        shiftedRoute,
                        meta,
                        theme,
                        edgesBuilder,
                        labelStackIndex = stmStackIndices[edgeId] ?: 0,
                    )
            }
        }
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
            // V11.x — Stack-Indizes für parallele Edges vorberechnen (siehe
            // Sysml2EdgeRenderer.computeLabelStackIndices). Greift z.B. bei
            // «derive» + «containment» zwischen zwei Requirements im
            // req-traceability-Sample.
            // V2.x — Bbox-Overlap-Clustering: Label-Text aus der Adapter-
            // Metadata wird mitgegeben. Liefert der Adapter Stereotype +
            // Plain-Label parallel, wählen wir das längere der beiden für
            // die Clustering-Heuristik (sichere Seite).
            val syntheticStackIndices =
                Sysml2EdgeRenderer.computeLabelStackIndices(
                    layoutResult.edges.entries.map { (edgeId, route) ->
                        val meta = sysml2EdgeAdapter.metadataFor(edgeId.value)
                        val labelText = widestLabelText(meta)
                        Triple(edgeId, route, labelText)
                    },
                )
            for ((edgeId, route) in layoutResult.edges) {
                val shiftedRoute = shiftRoute(route, padding)
                val element = elementIndex[edgeId.value]
                if (element != null) {
                    EdgeRendererDispatcher.dispatch(element, shiftedRoute, theme, edgesBuilder)
                } else {
                    val meta = sysml2EdgeAdapter.metadataFor(edgeId.value)
                    if (meta != null) {
                        Sysml2EdgeRenderer.render(
                            shiftedRoute,
                            meta,
                            theme,
                            edgesBuilder,
                            labelStackIndex = syntheticStackIndices[edgeId] ?: 0,
                        )
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
        return renderSysml2Synthetic(synthetic, layoutResult, theme, options, BddEdgeAdapter(model, diagram))
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
        return renderSysml2Synthetic(synthetic, layoutResult, theme, options, IbdEdgeAdapter(model, diagram))
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

        // V2.0.46: Index der Activity-Knoten-Formen für den Edge-Clipper.
        //          ELK liefert Edge-Endpunkte auf dem Rand der achsparallelen
        //          Bounding-Box; für Raute (Decision/Merge) und Kreis
        //          (Initial/Final/FlowFinal) sitzt der Endpunkt dadurch
        //          sichtbar **außerhalb** des gezeichneten Shapes. Wir bauen
        //          einmal pro Render-Lauf einen Index, der pro ActionDefinition
        //          die geometrische Form (Rectangle / Circle / Diamond) und
        //          die ELK-Bounds mit appliziertem Padding-Shift (wie
        //          [shiftRoute] beim Routen) enthält. Im Edge-Loop wird die
        //          geshiftete Route dann pro Endpunkt an den jeweiligen
        //          Shape-Rand gesnappt — siehe
        //          [dev.kuml.io.svg.sysml2.Sysml2ActivityEdgeClipper].
        val actionKindById: Map<String, dev.kuml.sysml2.ActivityNodeKind> =
            model.definitions
                .filterIsInstance<ActionDefinition>()
                .associate { it.id to it.kind }
        val shapeByNodeId: Map<String, dev.kuml.io.svg.sysml2.Sysml2ActivityEdgeClipper.Shape> =
            buildMap {
                for ((nodeId, nodeLayout) in layoutResult.nodes) {
                    val kind = actionKindById[nodeId.value] ?: continue
                    val shiftedBounds =
                        nodeLayout.bounds.copy(
                            origin =
                                nodeLayout.bounds.origin.copy(
                                    x = nodeLayout.bounds.origin.x + options.paddingPx,
                                    y = nodeLayout.bounds.origin.y + options.paddingPx,
                                ),
                        )
                    val shape: dev.kuml.io.svg.sysml2.Sysml2ActivityEdgeClipper.Shape =
                        when (kind) {
                            dev.kuml.sysml2.ActivityNodeKind.Decision,
                            dev.kuml.sysml2.ActivityNodeKind.Merge,
                            ->
                                dev.kuml.io.svg.sysml2.Sysml2ActivityEdgeClipper.Shape.Diamond(
                                    bounds = shiftedBounds,
                                )
                            dev.kuml.sysml2.ActivityNodeKind.Initial,
                            dev.kuml.sysml2.ActivityNodeKind.Final,
                            dev.kuml.sysml2.ActivityNodeKind.FlowFinal,
                            -> {
                                // SysML-2-Pseudo-Knoten: der gezeichnete
                                // Außenradius ist 0.45 * min(halfW, halfH)
                                // (Final/FlowFinal) bzw. 0.40 (Initial). Wir
                                // docken an 0.45 — die 5 %-Differenz beim
                                // Initial-Knoten ist visuell unauffällig und
                                // lässt Pfeile knapp vor (nicht in) der
                                // gefüllten Scheibe enden.
                                val r =
                                    minOf(
                                        shiftedBounds.size.width,
                                        shiftedBounds.size.height,
                                    ) / 2f * SYSML2_PSEUDO_RADIUS_FACTOR
                                dev.kuml.io.svg.sysml2.Sysml2ActivityEdgeClipper.Shape.Circle(
                                    bounds = shiftedBounds,
                                    radiusPx = r,
                                )
                            }
                            dev.kuml.sysml2.ActivityNodeKind.Action,
                            dev.kuml.sysml2.ActivityNodeKind.Fork,
                            dev.kuml.sysml2.ActivityNodeKind.Join,
                            ->
                                dev.kuml.io.svg.sysml2.Sysml2ActivityEdgeClipper.Shape.Rectangle(
                                    bounds = shiftedBounds,
                                )
                        }
                    put(nodeId.value, shape)
                }
            }
        // V2.0.46: Index `edgeId -> (sourceNodeId, targetNodeId)` für ACT-
        //          Edges. Quelle sind ControlFlowUsage + ObjectFlowUsage aus
        //          dem Modell — identische Auswahl wie in [ActEdgeAdapter],
        //          damit jeder Edge, der im Render-Loop einen Adapter-Treffer
        //          hat, auch hier seine Endpunkte findet. Edges ohne Eintrag
        //          (theoretisch nicht möglich, defensiv für künftige
        //          Edge-Quellen wie Pin-zu-Pin-Routen) durchlaufen den
        //          Render-Loop dann clipping-frei.
        val endpointsByEdgeId: Map<String, Pair<String, String>> =
            buildMap {
                for (flow in model.usages.filterIsInstance<dev.kuml.sysml2.ControlFlowUsage>()) {
                    put(flow.id, flow.sourceNodeId to flow.targetNodeId)
                }
                for (flow in model.usages.filterIsInstance<dev.kuml.sysml2.ObjectFlowUsage>()) {
                    put(flow.id, flow.sourceNodeId to flow.targetNodeId)
                }
            }

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
            //
            //    V2.0.46: vor dem eigentlichen Render-Aufruf wird die Route
            //    durch den [Sysml2ActivityEdgeClipper] geschickt. Der snappt
            //    `route.source` / `route.target` auf den Rand der tatsächlichen
            //    Knoten-Form (Raute für Decision/Merge, Kreis für Initial/
            //    Final/FlowFinal). Ohne diesen Schritt enden Pfeile an der
            //    Bounding-Box des Knotens — bei Rauten und Kreisen sichtbar
            //    daneben (siehe KDoc auf [Sysml2ActivityEdgeClipper]).
            val elementIndex = synthetic.elements.associateBy { it.id }
            // V11.x — Stack-Indizes für parallele Edges vorberechnen.
            // V2.x — Bbox-Overlap-Clustering mit Label-Text aus dem ACT-Adapter.
            val actStackIndices =
                Sysml2EdgeRenderer.computeLabelStackIndices(
                    layoutResult.edges.entries.map { (edgeId, route) ->
                        val meta = adapter.metadataFor(edgeId.value)
                        Triple(edgeId, route, widestLabelText(meta))
                    },
                )
            for ((edgeId, route) in layoutResult.edges) {
                val shiftedRoute = shiftRoute(route, padding)
                val endpoints = endpointsByEdgeId[edgeId.value]
                val clippedRoute =
                    if (endpoints != null) {
                        dev.kuml.io.svg.sysml2.Sysml2ActivityEdgeClipper.clip(
                            route = shiftedRoute,
                            sourceShape = shapeByNodeId[endpoints.first],
                            targetShape = shapeByNodeId[endpoints.second],
                        )
                    } else {
                        shiftedRoute
                    }
                val element = elementIndex[edgeId.value]
                if (element != null) {
                    EdgeRendererDispatcher.dispatch(element, clippedRoute, theme, edgesBuilder)
                } else {
                    val meta = adapter.metadataFor(edgeId.value)
                    if (meta != null) {
                        Sysml2EdgeRenderer.render(
                            clippedRoute,
                            meta,
                            theme,
                            edgesBuilder,
                            labelStackIndex = actStackIndices[edgeId] ?: 0,
                        )
                    } else {
                        val (tag, attrs) = EdgePathBuilder.build(clippedRoute)
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
        // V3.0.x: Per-Lifeline Create-Offset — Lifelines, die als Target einer
        // `MessageKind.Create`-Nachricht auftreten, werden mid-sequence "geboren".
        // Der MVP-Renderer (V2.0.15) zeichnete deren Kopf-Box weiterhin am
        // oberen Kanten-Rand, sodass die Create-Pfeilspitze bei
        // `(tgtBoxLeft, srcHeadBottom + (seqNo + 1) * ROW)` IM LEEREN RAUM
        // unter der Kopf-Box landete. Mit V3.0.x verschieben wir die Kopf-Box
        // dieser Targets nach unten um `(createSeqNo + 1) * ROW`, sodass die
        // Pfeilspitze exakt auf die untere Ecke der (jetzt tiefer liegenden)
        // Kopf-Box trifft. Der Offset wird unten an `renderLifelineHead`
        // weitergereicht; die `bounds.origin.y` der LayoutNode bleibt für alle
        // Lifelines konstant (= padding), damit die Y-Referenz der Messages
        // (`srcLayout.bounds.origin.y + HEAD_HEIGHT`) gleichmäßig bleibt.
        val createOffsetById: Map<String, Float> =
            messages
                .asSequence()
                .filter { it.kind == MessageKind.Create && it.targetLifelineId in visibleIds }
                .associate {
                    it.targetLifelineId to
                        (it.seqNo + 1) *
                        dev.kuml.io.svg.sysml2.SYSML2_SEQ_MESSAGE_ROW_HEIGHT
                }
        // V2.0.15: Combined Fragments + Execution Specs — auch renderer-direkt
        // (analog zu Messages, keine LayoutGraph-Edges). Werden vor den
        // Messages gezeichnet, damit Message-Pfeile visuell über
        // Aktivierungs-Bars / Frames liegen.
        val fragments = model.usages.filterIsInstance<dev.kuml.sysml2.CombinedFragmentUsage>()
        val execSpecs =
            model.usages
                .filterIsInstance<dev.kuml.sysml2.ExecutionSpecificationUsage>()
                .filter { it.lifelineId in visibleIds }

        // V3.0.x: Wenn Fragments existieren, ragt der Frame FRAGMENT_PADDING
        // links und rechts über die äußersten Lifelines hinaus. Das Default-
        // Canvas-Padding (16 px) reicht nicht — die rechte Frame-Kante würde
        // geclippt (ALT-Box rechts abgeschnitten). Effektives Padding auf
        // mindestens `FRAGMENT_PADDING + 4` heben, sobald Fragments im Spiel
        // sind. Wirkt sowohl im Lifeline-Shift als auch in der Canvas-Größe.
        val effectiveOptions =
            if (fragments.isNotEmpty()) {
                // V3.0.x: Per-Fragment-Left-Pad ist jetzt dynamisch und richtet
                // sich nach dem längsten Guard-Text. Canvas-Padding muss
                // mindestens so groß sein, damit Frames + rechtsbündige Guards
                // nicht links ge-clippt werden.
                val maxFragmentLeftPad = fragments.maxOf { sysml2SeqFragmentLeftPad(it) }
                options.copy(
                    paddingPx = maxOf(options.paddingPx, maxFragmentLeftPad + 4f),
                )
            } else {
                options
            }

        return SvgDocument.render(layoutResult, theme, effectiveOptions) { nodesBuilder, edgesBuilder ->
            val padding = effectiveOptions.paddingPx

            // 1. Geshiftete Lifeline-Layouts pre-computen, OHNE noch SVG zu
            //    emittieren. Die Layouts braucht der Fragment-Renderer (für die
            //    Frame-X-Spanne) bereits in Schritt 2; die Lifeline-Köpfe + die
            //    vertikale gestrichelte Zeit-Achse werden aber erst nach den
            //    Fragments gemalt — sonst überpinselt der Fragment-Rahmen die
            //    Lifeline-Verticals innerhalb der alt/opt/loop-Box. V3.0.11
            //    z-order-Fix (gleiche Bug-Familie wie der C4-Groups-Loop-Fix).
            val shiftedLayouts = mutableMapOf<NodeId, dev.kuml.layout.NodeLayout>()
            for ((nodeId, nodeLayout) in layoutResult.nodes) {
                if (synthetic.elements.find { it.id == nodeId.value } == null) continue
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
                shiftedLayouts[nodeId] = shifted
            }

            // 2. V3.0.11: Combined Fragments ZUERST in den nodes-Layer rendern,
            //    damit der dashed Frame UNTER den Lifeline-Verticals liegt. Der
            //    Frame hat ohnehin fill="none"; nur das Operator-Tag-Pentagon in
            //    der oberen-linken Ecke ist opak, und das sitzt außerhalb der
            //    Mittellinie der linkesten Lifeline (frame startet bei
            //    minLifelineX - FRAGMENT_PADDING).
            val visibleLifelineLayouts: List<dev.kuml.layout.NodeLayout> =
                visibleLifelines.mapNotNull { shiftedLayouts[NodeId(it.id)] }
            for (fragment in fragments) {
                dev.kuml.io.svg.sysml2.renderCombinedFragment(
                    fragment = fragment,
                    visibleLifelineLayouts = visibleLifelineLayouts,
                    builder = nodesBuilder,
                )
            }

            // 3. Lifeline-Köpfe + dashed Zeit-Achsen auf die Fragments draufmalen.
            //    Dadurch bleibt die gestrichelte Vertikale jeder Lifeline auch
            //    innerhalb jedes alt/opt/loop-Frames sichtbar.
            //
            //    V3.0.x: Statt über `NodeRendererDispatcher.dispatch` wird
            //    `renderLifelineHead` HIER direkt aufgerufen, damit der
            //    SEQ-spezifische `createOffsetY` durchgereicht werden kann.
            //    Andere Diagrammtypen (BDD/IBD/STM/UC) verwenden weiterhin den
            //    Dispatcher-Pfad; dort gibt es keine Create-Nachrichten und
            //    damit auch keinen Offset.
            for ((nodeId, shifted) in shiftedLayouts) {
                val element =
                    synthetic.elements.find { it.id == nodeId.value } as? LifelineDefinition
                        ?: continue
                dev.kuml.io.svg.sysml2.renderLifelineHead(
                    element = element,
                    layout = shifted,
                    theme = theme,
                    builder = nodesBuilder,
                    createOffsetY = createOffsetById[nodeId.value] ?: 0f,
                )
            }

            // 4. V2.0.15: Execution Specs in den edges-Layer — die Aktivierungs-Bar
            //    liegt nach diesem Reordering ÜBER der Lifeline-Vertikale (gut so:
            //    eine aktive Lifeline soll als Bar erkennbar bleiben) und UNTER
            //    den Message-Pfeilen.
            for (es in execSpecs) {
                val lifelineLayout = shiftedLayouts[NodeId(es.lifelineId)] ?: continue
                dev.kuml.io.svg.sysml2
                    .renderExecutionSpec(es, lifelineLayout, edgesBuilder)
            }

            // 5. Direkt-Render der Nachrichten — siehe Architektur-Divergenz
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

    /**
     * Post-processes inter-package dependency routes so arrowheads land on the
     * **body** rectangle of the target package, not on the narrow folder-tab top.
     *
     * Root cause: ELK anchors inter-compound edges at the compound node's outer
     * boundary, which is the very top of the tab area (y = groupOrigin.y). The
     * folder tab is narrower than the body — so the arrowhead's X position is
     * often outside the tab, landing in the empty "notch" between tab end and
     * body start. Visually the arrow appears to float before touching the box.
     *
     * Fix: replace every package-dependency route with a [EdgeRoute.Direct] line
     * from the client body boundary to the supplier body boundary. The body
     * rectangle starts at [PACKAGE_TAB_HEIGHT] below the group origin and spans
     * the full package width, so it is always visually reachable.
     */
    private fun snapPackageEdgesToBodyBoundary(
        elementIndex: Map<String, dev.kuml.core.model.KumlElement>,
        layoutResult: LayoutResult,
    ): LayoutResult {
        val fixedEdges = linkedMapOf<EdgeId, EdgeRoute>()
        fixedEdges.putAll(layoutResult.edges)

        for ((edgeId, _) in layoutResult.edges) {
            val dep = elementIndex[edgeId.value] as? UmlDependency ?: continue
            val clientGroup = layoutResult.groups[GroupId(dep.clientId)] ?: continue
            val supplierGroup = layoutResult.groups[GroupId(dep.supplierId)] ?: continue
            fixedEdges[edgeId] = directRouteBetweenPackageBodyBounds(clientGroup.bounds, supplierGroup.bounds)
        }
        return layoutResult.copy(edges = fixedEdges)
    }

    /**
     * Computes a [EdgeRoute.Direct] between the **body boundaries** of two
     * package groups. When the connection is vertical (most common), the source
     * exits at the body bottom and the target is entered at the body top
     * (`y = groupOrigin.y + PACKAGE_TAB_HEIGHT`). Horizontal connections use
     * the full body-left/right edges without tab adjustment.
     */
    private fun directRouteBetweenPackageBodyBounds(
        client: Rect,
        supplier: Rect,
    ): EdgeRoute.Direct {
        val clientCx = client.origin.x + client.size.width / 2f
        val clientBodyTop = client.origin.y + PACKAGE_TAB_HEIGHT
        val clientBodyBottom = client.origin.y + client.size.height
        val clientBodyCy = (clientBodyTop + clientBodyBottom) / 2f

        val supplierCx = supplier.origin.x + supplier.size.width / 2f
        val supplierBodyTop = supplier.origin.y + PACKAGE_TAB_HEIGHT
        val supplierBodyBottom = supplier.origin.y + supplier.size.height
        val supplierBodyCy = (supplierBodyTop + supplierBodyBottom) / 2f

        val dx = supplierCx - clientCx
        val dy = supplierBodyCy - clientBodyCy

        val source: Point
        val target: Point

        if (kotlin.math.abs(dy) >= kotlin.math.abs(dx)) {
            if (dy > 0f) {
                // supplier is below client
                source = Point(clientCx, clientBodyBottom)
                target = Point(supplierCx, supplierBodyTop)
            } else {
                // supplier is above client
                source = Point(clientCx, clientBodyTop)
                target = Point(supplierCx, supplierBodyBottom)
            }
        } else {
            if (dx > 0f) {
                // supplier is to the right
                source = Point(client.origin.x + client.size.width, clientBodyCy)
                target = Point(supplier.origin.x, supplierBodyCy)
            } else {
                // supplier is to the left
                source = Point(client.origin.x, clientBodyCy)
                target = Point(supplier.origin.x + supplier.size.width, supplierBodyCy)
            }
        }
        return EdgeRoute.Direct(source = source, target = target)
    }

    /** Höhe der Folder-Tab-Lasche eines UML-Pakets. Muss mit dem Top-Padding
     *  in `UmlLayoutBridge.PACKAGE_GROUP_INSETS` zusammenpassen (18 px Tab +
     *  10 px Atemluft = 28 px Top-Insets). */
    private const val PACKAGE_TAB_HEIGHT: Float = 18f

    /** Höhe der vertikalen Aussparung unter der Lasche, bevor die
     *  Tab-Linie das obere Pakets-Rechteck schneidet. */
    private const val PACKAGE_TAB_GAP: Float = 0f

    /** Approximation der Textbreite pro Zeichen für den Folder-Tab-Namen
     *  (12 px Schriftgröße, system-ui). Für die Tab-Breite ausreichend
     *  großzügig, damit auch breite Buchstaben passen. */
    private const val PACKAGE_TAB_CHAR_WIDTH: Float = 7.5f

    /** Mindestbreite des Folder-Tabs, damit auch sehr kurze Paketnamen
     *  (z. B. `ui`) eine sichtbare Lasche bekommen. */
    private const val PACKAGE_TAB_MIN_WIDTH: Float = 56f

    /**
     * Renders a single UML package shape (folder tab + main rectangle) at the
     * given layout origin. Tab sits flush with the package's top-left corner,
     * carries the package name, and the main body extends from `(0, tabH)` to
     * `(gw, gh)`. The layout bridge reserves `PACKAGE_GROUP_INSETS.top` px so
     * that contained classifiers do not overlap the tab area.
     */
    private fun renderPackageGroup(
        pkg: UmlPackage,
        gx: Float,
        gy: Float,
        gw: Float,
        gh: Float,
        @Suppress("UNUSED_PARAMETER") theme: dev.kuml.renderer.theme.core.KumlTheme,
        nodesBuilder: SvgBuilder,
    ) {
        val tabH = PACKAGE_TAB_HEIGHT
        val tabW =
            (pkg.name.length * PACKAGE_TAB_CHAR_WIDTH + 16f)
                .coerceAtLeast(PACKAGE_TAB_MIN_WIDTH)
                .coerceAtMost(gw - 4f)
        nodesBuilder.tag(
            "g",
            mapOf(
                "id" to xmlEscapeAttr("package-${pkg.id}"),
                "transform" to "translate(${fmt(gx)},${fmt(gy)})",
            ),
        ) {
            // Tab (top-left)
            tag(
                "rect",
                mapOf(
                    "x" to "0",
                    "y" to "0",
                    "width" to fmt(tabW),
                    "height" to fmt(tabH),
                    "class" to "kuml-system",
                ),
            )
            // Main body — sits flush below the tab on its left side and
            // extends the full group width on its right. Top edge stops at
            // tabH on the left half, drops down on the tab's right edge,
            // then runs across to the right side.
            tag(
                "rect",
                mapOf(
                    "x" to "0",
                    "y" to fmt(tabH + PACKAGE_TAB_GAP),
                    "width" to fmt(gw),
                    "height" to fmt(gh - tabH - PACKAGE_TAB_GAP),
                    "class" to "kuml-system",
                ),
            )
            // Package name centered in the tab
            tag(
                "text",
                mapOf(
                    "class" to "kuml-title",
                    "x" to fmt(tabW / 2f),
                    "y" to fmt(tabH - 5f),
                    "text-anchor" to "middle",
                ),
            ) { text(pkg.name) }
        }
    }

    /**
     * Renders a UML Use-Case subject (system boundary) as an SVG rectangle with
     * the subject name as a label in the top-left corner.
     *
     * The top-padding reserved by [dev.kuml.layout.bridge.UmlLayoutBridge.USE_CASE_SUBJECT_INSETS]
     * (28 px) gives exactly enough room for the name text (baseline at y = 18)
     * plus 10 px breathing space before the first contained use-case ellipse.
     */
    private fun renderSubjectGroup(
        subject: UmlUseCaseSubject,
        gx: Float,
        gy: Float,
        gw: Float,
        gh: Float,
        theme: dev.kuml.renderer.theme.core.KumlTheme,
        nodesBuilder: SvgBuilder,
    ) {
        nodesBuilder.tag(
            "g",
            mapOf(
                "id" to xmlEscapeAttr("subject-${subject.id}"),
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
            // Subject name in the top-left corner, inside the top-padding area
            tag(
                "text",
                mapOf(
                    "class" to "kuml-title",
                    "x" to "8",
                    "y" to "18",
                    "text-anchor" to "start",
                ),
            ) { text(subject.name) }
        }
    }

    /**
     * Builds a flat `id → KumlElement` map by walking each diagram element
     * recursively into `UmlPackage.members`. Without this, the renderer's
     * dispatcher would miss every classifier declared inside `packageOf { … }`
     * because such members are NOT in `KumlDiagram.elements`.
     */
    private fun buildKumlElementIndex(elements: List<KumlElement>): Map<String, KumlElement> {
        val out = mutableMapOf<String, KumlElement>()

        fun visit(element: KumlElement) {
            out[element.id] = element
            if (element is UmlPackage) {
                element.members.forEach { visit(it) }
            }
            if (element is UmlComponent) {
                // V2.0.47 — nested components werden ebenfalls in den Index
                // aufgenommen. Damit findet der ComponentPortEdgeClipper
                // auch Connectors zwischen Ports von verschachtelten
                // Komponenten (analog zur Rekursion in
                // UmlLayoutBridge.collectComponentPorts).
                element.nestedComponents.forEach { visit(it) }
            }
        }
        elements.forEach { visit(it) }
        return out
    }

    /** Same recursion as [buildKumlElementIndex], but narrowed to packages —
     *  used to look up the originating UmlPackage for each LayoutGroup so the
     *  renderer can draw the folder tab + name. */
    private fun collectUmlPackages(elements: List<KumlElement>): Map<String, UmlPackage> {
        val out = mutableMapOf<String, UmlPackage>()

        fun visit(element: KumlElement) {
            if (element is UmlPackage) {
                out[element.id] = element
                element.members.filterIsInstance<UmlNamedElement>().forEach { member -> visit(member) }
            }
        }
        elements.forEach { visit(it) }
        return out
    }

    /**
     * V2.0.46 — Baut den Activity-Shape-Index für den
     * [dev.kuml.io.svg.sysml2.Sysml2ActivityEdgeClipper] aus den Diagramm-
     * Elementen plus dem (bereits effektiven) Layout-Result. Pro
     * [dev.kuml.uml.UmlActivityNode] bzw.
     * [dev.kuml.uml.UmlInteractionOverviewFrame] wird die geometrische Form
     * (Rectangle / Circle / Diamond) und die Bounding-Box mit appliziertem
     * Padding-Shift (`+ paddingPx`, gleiche Verschiebung wie [shiftRoute])
     * ermittelt.
     *
     * Activity-Kind → Form-Mapping spiegelt die SVG-Routine
     * `dev.kuml.io.svg.uml.renderUmlActivityNode`:
     *  - DECISION / MERGE → Raute (bounds-füllendes Polygon)
     *  - INITIAL / ACTIVITY_FINAL / FLOW_FINAL → Kreis mit dem **fixen**
     *    Radius der jeweiligen Renderer-Konstante (10 px für Initial /
     *    Flow-Final, 12 px für Activity-Final — siehe `UmlV11Svg.kt`).
     *    Wir übergeben die Renderer-Konstanten 1:1 an den Clipper, damit
     *    Pfeile genau am sichtbaren Kreisrand enden.
     *  - ACTION / OBJECT / FORK / JOIN → Rechteck (ELK liefert ohnehin
     *    schon korrekte Endpunkte, der Snap ist idempotent).
     *
     * Interaction-Overview-Kind → Form-Mapping spiegelt
     * `dev.kuml.io.svg.uml.renderUmlInteractionOverviewFrame`:
     *  - DECISION / MERGE → Raute
     *  - INITIAL → Kreis mit Radius 10 px (`r="10"`)
     *  - FINAL → Kreis mit Außenradius 12 px (`r="12"`)
     *  - INTERACTION_REF → gerundetes Rechteck (Bounding-Box ≈ Shape, der
     *    Clipper ist hier idempotent, aber wir tragen den Knoten ein damit
     *    die kombinierten Kanten ref ↔ initial/final beidseitig clipped
     *    werden — gerade Source bzw. Target an einem Kreis braucht den
     *    Snap).
     */
    private fun buildActivityShapeIndex(
        elements: List<dev.kuml.core.model.KumlElement>,
        layoutResult: LayoutResult,
        paddingPx: Float,
    ): Map<String, dev.kuml.io.svg.sysml2.Sysml2ActivityEdgeClipper.Shape> {
        val activityKindById: Map<String, dev.kuml.uml.UmlActivityNodeKind> =
            elements
                .filterIsInstance<dev.kuml.uml.UmlActivityNode>()
                .associate { it.id to it.kind }
        val interactionFrameKindById: Map<String, dev.kuml.uml.UmlInteractionFrameKind> =
            elements
                .filterIsInstance<dev.kuml.uml.UmlInteractionOverviewFrame>()
                .associate { it.id to it.kind }
        if (activityKindById.isEmpty() && interactionFrameKindById.isEmpty()) return emptyMap()
        return buildMap {
            for ((nodeId, nodeLayout) in layoutResult.nodes) {
                val shiftedBounds =
                    nodeLayout.bounds.copy(
                        origin =
                            nodeLayout.bounds.origin.copy(
                                x = nodeLayout.bounds.origin.x + paddingPx,
                                y = nodeLayout.bounds.origin.y + paddingPx,
                            ),
                    )
                val shape: dev.kuml.io.svg.sysml2.Sysml2ActivityEdgeClipper.Shape? =
                    when {
                        activityKindById.containsKey(nodeId.value) ->
                            when (activityKindById.getValue(nodeId.value)) {
                                dev.kuml.uml.UmlActivityNodeKind.DECISION,
                                dev.kuml.uml.UmlActivityNodeKind.MERGE,
                                ->
                                    dev.kuml.io.svg.sysml2.Sysml2ActivityEdgeClipper.Shape.Diamond(
                                        bounds = shiftedBounds,
                                    )
                                dev.kuml.uml.UmlActivityNodeKind.INITIAL ->
                                    dev.kuml.io.svg.sysml2.Sysml2ActivityEdgeClipper.Shape.Circle(
                                        bounds = shiftedBounds,
                                        radiusPx = UML_ACTIVITY_INITIAL_RADIUS_PX,
                                    )
                                dev.kuml.uml.UmlActivityNodeKind.ACTIVITY_FINAL ->
                                    dev.kuml.io.svg.sysml2.Sysml2ActivityEdgeClipper.Shape.Circle(
                                        bounds = shiftedBounds,
                                        radiusPx = UML_ACTIVITY_FINAL_OUTER_RADIUS_PX,
                                    )
                                dev.kuml.uml.UmlActivityNodeKind.FLOW_FINAL ->
                                    dev.kuml.io.svg.sysml2.Sysml2ActivityEdgeClipper.Shape.Circle(
                                        bounds = shiftedBounds,
                                        radiusPx = UML_ACTIVITY_FLOWFINAL_RADIUS_PX,
                                    )
                                dev.kuml.uml.UmlActivityNodeKind.ACTION,
                                dev.kuml.uml.UmlActivityNodeKind.OBJECT,
                                dev.kuml.uml.UmlActivityNodeKind.FORK,
                                dev.kuml.uml.UmlActivityNodeKind.JOIN,
                                ->
                                    dev.kuml.io.svg.sysml2.Sysml2ActivityEdgeClipper.Shape.Rectangle(
                                        bounds = shiftedBounds,
                                    )
                            }
                        interactionFrameKindById.containsKey(nodeId.value) ->
                            when (interactionFrameKindById.getValue(nodeId.value)) {
                                dev.kuml.uml.UmlInteractionFrameKind.DECISION,
                                dev.kuml.uml.UmlInteractionFrameKind.MERGE,
                                ->
                                    dev.kuml.io.svg.sysml2.Sysml2ActivityEdgeClipper.Shape.Diamond(
                                        bounds = shiftedBounds,
                                    )
                                dev.kuml.uml.UmlInteractionFrameKind.INITIAL ->
                                    dev.kuml.io.svg.sysml2.Sysml2ActivityEdgeClipper.Shape.Circle(
                                        bounds = shiftedBounds,
                                        radiusPx = UML_INTERACTION_INITIAL_RADIUS_PX,
                                    )
                                dev.kuml.uml.UmlInteractionFrameKind.FINAL ->
                                    dev.kuml.io.svg.sysml2.Sysml2ActivityEdgeClipper.Shape.Circle(
                                        bounds = shiftedBounds,
                                        radiusPx = UML_INTERACTION_FINAL_OUTER_RADIUS_PX,
                                    )
                                dev.kuml.uml.UmlInteractionFrameKind.INTERACTION_REF ->
                                    dev.kuml.io.svg.sysml2.Sysml2ActivityEdgeClipper.Shape.Rectangle(
                                        bounds = shiftedBounds,
                                    )
                            }
                        else -> null
                    }
                if (shape != null) put(nodeId.value, shape)
            }
        }
    }

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

    /**
     * Anteil von `min(halfW, halfH)`, den die SysML-2-Pseudo-Knoten (Initial
     * / Final / FlowFinal) als Außenradius gezeichnet bekommen — spiegelt die
     * Renderer-Konstanten in [dev.kuml.io.svg.sysml2.renderActionDefinition]
     * (0.45 für Final/FlowFinal, 0.40 für Initial). 0.45 ist der gemeinsame
     * Andock-Radius (V2.0.46); siehe KDoc auf
     * [dev.kuml.io.svg.sysml2.Sysml2ActivityEdgeClipper.Shape.Circle].
     */
    private const val SYSML2_PSEUDO_RADIUS_FACTOR: Float = 0.45f

    /**
     * Renderer-Konstanten der UML-1.1-Activity-Pseudo-Knoten — spiegeln
     * die hard-coded Radien in `dev.kuml.io.svg.uml.renderUmlActivityNode`
     * (Initial r=10, Activity-Final äußerer r=12, Flow-Final r=10).
     * Der Edge-Clipper braucht die exakten Pixel-Radien, weil die
     * Bounding-Box (28×28 ab V2.0.46) den sichtbaren Kreis nicht
     * tangential berührt — wir docken am tatsächlich gezeichneten
     * Kreisrand an, nicht an der Bounding-Box-Kante.
     */
    private const val UML_ACTIVITY_INITIAL_RADIUS_PX: Float = 10f
    private const val UML_ACTIVITY_FINAL_OUTER_RADIUS_PX: Float = 12f
    private const val UML_ACTIVITY_FLOWFINAL_RADIUS_PX: Float = 10f

    /**
     * Renderer-Konstanten der UML-1.1-Interaction-Overview-Pseudo-Knoten
     * — spiegeln die hard-coded Radien in
     * `dev.kuml.io.svg.uml.renderUmlInteractionOverviewFrame`
     * (Initial r=10, Final äußerer r=12). Werden vom Edge-Clipper
     * verwendet, damit Pfeile genau am sichtbaren Kreisrand und nicht
     * an der ELK-Bounding-Box-Kante enden (Vault-Feedback aus
     * [[03 Bereiche/kUML/Beispiele/22 UML Interaction Overview – Order Process]]).
     */
    private const val UML_INTERACTION_INITIAL_RADIUS_PX: Float = 10f
    private const val UML_INTERACTION_FINAL_OUTER_RADIUS_PX: Float = 12f

    /**
     * Berechnet vertikale Stagger-Offsets für C4-Relationship-Labels, die durch
     * denselben vertikalen Korridor (gleiche x-Koordinate) laufen und sich
     * deshalb räumlich überlappen würden.
     *
     * Zwei Labels gelten als überlappend, wenn ihr via [EdgeLabelGeometry.midAnchor]
     * berechneter Ankerpunkt in x **und** y näher als [LABEL_PROX_X] / [LABEL_PROX_Y]
     * liegt. Überlappende Paare werden durch [LABEL_STAGGER_PX] auseinandergezogen:
     * der erste Eintrag des Paares wandert um [LABEL_STAGGER_PX] nach oben, der
     * zweite um [LABEL_STAGGER_PX] nach unten. Bei mehr als zwei Labels in einer
     * engen Gruppe summieren sich die Versätze additiv (jede weitere Überlappung
     * schiebt einen der Partner erneut um [LABEL_STAGGER_PX] weiter).
     *
     * Gibt eine leere Map zurück wenn das Diagramm ≤ 1 beschriftete Kante hat
     * oder kein Paar die Schwellenwerte unterschreitet.
     */
    private fun computeC4LabelStaggerOffsets(
        shiftedRoutes: Map<EdgeId, EdgeRoute>,
        relationshipIndex: Map<String, dev.kuml.c4.model.C4Relationship>,
    ): Map<String, Float> {
        /** x-Nähe: beide Labels müssen in derselben vertikalen Spur liegen. */
        val labelProxX = 40f

        /**
         * y-Nähe: entspricht etwa 1,5× dem [ElkEngineConfiguration.layerSpacing]
         * (90 px Default). Fängt den typischen Fall ab, bei dem zwei Kanten dasselbe
         * Element als Source/Target teilen und ihre Labels im gemeinsamen Korridor landen.
         */
        val labelProxY = 120f

        /** Versatz pro erkanntem Überlappungspaar — eine Schriftzeilen-Höhe + Halo-Puffer. */
        val labelStaggerPx = 18f

        // Nur Kanten, die tatsächlich ein Label rendern, zählen.
        val anchors = mutableMapOf<String, EdgeLabelGeometry.LabelAnchor>()
        for ((edgeId, route) in shiftedRoutes) {
            val rel = relationshipIndex[edgeId.value] ?: continue
            if (c4RelationshipLabel(rel).isEmpty()) continue
            anchors[edgeId.value] = EdgeLabelGeometry.midAnchor(route)
        }

        if (anchors.size < 2) return emptyMap()

        val result = mutableMapOf<String, Float>()
        val ids = anchors.keys.toList()

        for (i in ids.indices) {
            for (j in i + 1 until ids.size) {
                val idA = ids[i]
                val idB = ids[j]
                val anchorA = anchors[idA] ?: continue
                val anchorB = anchors[idB] ?: continue

                if (abs(anchorA.x - anchorB.x) < labelProxX &&
                    abs(anchorA.y - anchorB.y) < labelProxY
                ) {
                    // Erstes Element des Paares wandert nach oben, zweites nach unten.
                    result[idA] = (result[idA] ?: 0f) - labelStaggerPx
                    result[idB] = (result[idB] ?: 0f) + labelStaggerPx
                }
            }
        }

        return result
    }

    /**
     * Pick the wider of an edge's stereotype / plain-label texts for the
     * [Sysml2EdgeRenderer.computeLabelStackIndices] clustering decision.
     *
     * Bbox-overlap clustering errs on the side of safety: when an edge
     * carries both a stereotype like `«derive»` and a plain `[guard]`, we
     * pick whichever string would paint the wider background rectangle.
     * Picking the shorter one would under-estimate the rectangle and miss
     * overlaps; picking a concatenation would over-estimate (the renderer
     * stacks the two labels on top of each other, not side by side).
     *
     * Returns `null` if neither slot is populated — the renderer's
     * "no-label early return" path is taken and clustering treats this edge
     * as zero-width.
     */
    private fun widestLabelText(meta: dev.kuml.sysml2.edge.Sysml2EdgeMetadata?): String? {
        if (meta == null) return null
        val s = meta.stereotype
        val l = meta.label
        return when {
            s == null && l == null -> null
            s == null -> l
            l == null -> s
            s.length >= l.length -> s
            else -> l
        }
    }

    /**
     * Rendert ein BPMN-Collaboration-Diagramm (Swimlanes) als SVG.
     *
     * Render-Reihenfolge:
     *  1. Pools (Participant-Rahmen mit Titel-Band) — als Hintergrund.
     *  2. Lanes (Trennlinien + Lane-Titel) — über den Pool-Rahmen, unter den Knoten.
     *  3. Flow-Nodes der referenzierten Prozesse — über den Lanes.
     *  4. MessageFlows — als gestrichelte Kanten ganz oben.
     *
     * @param model Das BPMN-Modell mit Collaborations und Prozessen.
     * @param diagram Das [CollaborationDiagram] mit dem Verweis auf eine Collaboration.
     * @param layoutResult Berechnete Positionen und Routing-Pfade.
     * @param theme Visuelles Theme; Standard: [PlainTheme].
     * @param options Renderer-Optionen; Standard: [SvgRenderOptions.DEFAULT].
     *
     * V3.1.4/3.1.5 — BPMN Collaboration: Metamodell, DSL und SVG-Renderer
     */
    public fun toSvg(
        model: BpmnModel,
        diagram: CollaborationDiagram,
        layoutResult: LayoutResult,
        theme: KumlTheme = PlainTheme(),
        options: SvgRenderOptions = SvgRenderOptions.DEFAULT,
    ): String {
        val collaboration =
            model.collaborations.firstOrNull { it.id == diagram.collaborationId }
                ?: return SvgDocument.render(layoutResult, theme, options) { _, _ -> }

        // Build element index: participants, lanes (flat), message flows, and
        // flow-nodes from referenced processes.
        val participantIndex: Map<String, BpmnParticipant> =
            collaboration.participants.associateBy { it.id }

        val laneIndex: Map<String, dev.kuml.bpmn.model.BpmnLane> =
            buildMap {
                fun collectLanes(lanes: List<dev.kuml.bpmn.model.BpmnLane>) {
                    for (lane in lanes) {
                        put(lane.id, lane)
                        collectLanes(lane.childLanes)
                    }
                }
                for (p in collaboration.participants) collectLanes(p.lanes)
            }

        val messageFlowIndex: Map<String, dev.kuml.bpmn.model.MessageFlow> =
            collaboration.messageFlows.associateBy { it.id }

        // Flow-nodes from the referenced processes (for rendering inside pools)
        val flowNodeIndex: Map<String, dev.kuml.core.model.KumlElement> =
            buildMap {
                for (participant in collaboration.participants) {
                    val proc =
                        participant.processRef?.let { ref ->
                            model.processes.firstOrNull { it.id == ref }
                        } ?: continue
                    for (node in proc.flowNodes) put(node.id, node)
                    for (sf in proc.sequenceFlows) put(sf.id, sf)
                }
            }

        val allElementIndex: Map<String, dev.kuml.core.model.KumlElement> =
            participantIndex + laneIndex + messageFlowIndex + flowNodeIndex

        // Track lane orientation per parent pool
        val laneHorizontalByParticipantId: Map<String, Boolean> =
            collaboration.participants.associate { it.id to it.horizontal }

        // Lane's parent participant id (for orientation lookup)
        val laneParticipantId: Map<String, String> =
            buildMap {
                for (p in collaboration.participants) {
                    fun track(lanes: List<dev.kuml.bpmn.model.BpmnLane>) {
                        for (lane in lanes) {
                            put(lane.id, p.id)
                            track(lane.childLanes)
                        }
                    }
                    track(p.lanes)
                }
            }

        return SvgDocument.render(layoutResult, theme, options) { nodesBuilder, edgesBuilder ->
            val padding = options.paddingPx

            // Groups (pools and lanes rendered as group backgrounds)
            for ((groupId, groupLayout) in layoutResult.groups) {
                val gx = groupLayout.bounds.origin.x + padding
                val gy = groupLayout.bounds.origin.y + padding
                val gw = groupLayout.bounds.size.width
                val gh = groupLayout.bounds.size.height

                val participant = participantIndex[groupId.value]
                val lane = laneIndex[groupId.value]
                when {
                    participant != null -> {
                        val nl =
                            dev.kuml.layout.NodeLayout(
                                bounds =
                                    dev.kuml.layout.Rect(
                                        origin = dev.kuml.layout.Point(gx, gy),
                                        size = dev.kuml.layout.Size(gw, gh),
                                    ),
                            )
                        NodeRendererDispatcher.dispatch(participant, nl, theme, nodesBuilder)
                    }

                    lane != null -> {
                        val parentParticipantId = laneParticipantId[groupId.value]
                        val horizontal =
                            parentParticipantId?.let {
                                laneHorizontalByParticipantId[it]
                            } ?: true
                        val nl =
                            dev.kuml.layout.NodeLayout(
                                bounds =
                                    dev.kuml.layout.Rect(
                                        origin = dev.kuml.layout.Point(gx, gy),
                                        size = dev.kuml.layout.Size(gw, gh),
                                    ),
                            )
                        dev.kuml.io.svg.bpmn
                            .renderBpmnLane(lane, nl, horizontal, theme, nodesBuilder)
                    }

                    else -> {
                        // Generic group fallback
                        nodesBuilder.tag(
                            "g",
                            mapOf(
                                "id" to xmlEscapeAttr("bpmn-collab-group-${groupId.value}"),
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
                }
            }

            // Nodes (flow nodes inside pools)
            for ((nodeId, nodeLayout) in layoutResult.nodes) {
                val element = allElementIndex[nodeId.value] ?: continue
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

            // Edges (MessageFlows and SequenceFlows inside processes)
            for ((edgeId, route) in layoutResult.edges) {
                val element = allElementIndex[edgeId.value] ?: continue
                val shiftedRoute = shiftRoute(route, padding)
                EdgeRendererDispatcher.dispatch(element, shiftedRoute, theme, edgesBuilder)
            }
        }
    }

    /** [toSvg]-Variante für BPMN-Collaboration-Diagramme, schreibt direkt auf Platte. */
    public fun toSvgFile(
        model: BpmnModel,
        diagram: CollaborationDiagram,
        layoutResult: LayoutResult,
        out: java.nio.file.Path,
        theme: KumlTheme = PlainTheme(),
        options: SvgRenderOptions = SvgRenderOptions.DEFAULT,
    ): java.io.File {
        val svg = toSvg(model, diagram, layoutResult, theme, options)
        val file = out.toFile()
        file.parentFile?.mkdirs()
        file.writeText(svg, Charsets.UTF_8)
        return file
    }

    /**
     * Rendert ein BPMN-Prozess-Diagramm als SVG.
     *
     * Dieser dedizierte Pfad stellt die korrekte Z-Reihenfolge für expandierte
     * SubProcesses sicher: der Rahmen eines [BpmnSubProcess] wird in einem
     * ersten Pass gerendert (Gruppen-Schleife), danach werden die Knoten im
     * zweiten Pass darüber gelegt. Ohne diesen Pfad würden SubProcess-Frames
     * im generischen [toSvg]-Loop (der maps in Einfügereihenfolge iteriert)
     * ihre Kind-Knoten überdecken — derselbe Bug wie der C4-Groups-Fix.
     *
     * Render-Reihenfolge:
     *  1. Expanded-SubProcess-Rahmen (Gruppen-Loop) — immer im Hintergrund.
     *  2. Alle anderen Flow-Nodes (Knoten-Loop) — im Vordergrund der Rahmen.
     *  3. SequenceFlows (Kanten-Loop) — ganz oben.
     */
    private fun renderBpmnProcess(
        diagram: KumlDiagram,
        layoutResult: LayoutResult,
        theme: KumlTheme,
        options: SvgRenderOptions,
    ): String {
        // Build a flat element index (id → element) for all BPMN elements.
        // BPMN diagrams are flat (no recursive containers beyond SubProcess, which
        // the bridge handles as a group rather than nesting in the element list).
        val elementIndex = diagram.elements.associateBy { it.id }

        // Collect the IDs of expanded SubProcesses that surface as LayoutGroups,
        // so the groups loop can identify them for SubProcess-frame rendering.
        val expandedSubProcessIds: Set<String> =
            diagram.elements
                .filterIsInstance<BpmnSubProcess>()
                .filter { it.expanded }
                .map { it.id }
                .toSet()

        return SvgDocument.render(layoutResult, theme, options) { nodesBuilder, edgesBuilder ->
            val padding = options.paddingPx

            // 1. Groups FIRST — render expanded SubProcess frames behind their contents.
            //    Any group whose id matches an expanded SubProcess is rendered as a
            //    SubProcess background rect; unknown groups get the generic kuml-system rect.
            for ((groupId, groupLayout) in layoutResult.groups) {
                val gx = groupLayout.bounds.origin.x + padding
                val gy = groupLayout.bounds.origin.y + padding
                val gw = groupLayout.bounds.size.width
                val gh = groupLayout.bounds.size.height

                if (groupId.value in expandedSubProcessIds) {
                    val sp = elementIndex[groupId.value] as? BpmnSubProcess
                    if (sp != null) {
                        // Render the expanded SubProcess as a rounded frame.
                        // We delegate to the existing activity renderer via a synthetic
                        // NodeLayout so the frame + label + transactional inner rect
                        // are all drawn consistently.
                        val groupNodeLayout =
                            dev.kuml.layout.NodeLayout(
                                bounds =
                                    dev.kuml.layout.Rect(
                                        origin = dev.kuml.layout.Point(gx, gy),
                                        size = dev.kuml.layout.Size(gw, gh),
                                    ),
                            )
                        NodeRendererDispatcher.dispatch(sp, groupNodeLayout, theme, nodesBuilder)
                    } else {
                        // Fallback: generic frame rect if element not found
                        nodesBuilder.tag(
                            "g",
                            mapOf(
                                "id" to xmlEscapeAttr("bpmn-subprocess-${groupId.value}"),
                                "transform" to "translate(${fmt(gx)},${fmt(gy)})",
                            ),
                        ) {
                            tag(
                                "rect",
                                mapOf(
                                    "width" to fmt(gw),
                                    "height" to fmt(gh),
                                    "rx" to "8",
                                    "fill" to "white",
                                    "stroke" to "#333",
                                    "stroke-width" to "1.5",
                                ),
                            )
                        }
                    }
                } else {
                    // Generic group rect (pools, lanes, or unknown groups)
                    nodesBuilder.tag(
                        "g",
                        mapOf(
                            "id" to xmlEscapeAttr("bpmn-group-${groupId.value}"),
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
            }

            // 2. Nodes SECOND — all flow nodes (events, gateways, tasks, collapsed
            //    sub-processes, call activities, data objects, boundary events) are
            //    rendered on top of the group backgrounds.
            for ((nodeId, nodeLayout) in layoutResult.nodes) {
                val element = elementIndex[nodeId.value] ?: continue
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

            // 3. Edges LAST — SequenceFlows and any other BPMN edges render on top.
            for ((edgeId, route) in layoutResult.edges) {
                val element = elementIndex[edgeId.value] ?: continue
                val shiftedRoute = shiftRoute(route, padding)
                EdgeRendererDispatcher.dispatch(element, shiftedRoute, theme, edgesBuilder)
            }
        }
    }
}

// Suppress unused import warning for NodeId/EdgeId/GroupId — used via layoutResult
@Suppress("UnusedPrivateMember")
private fun unusedImportSuppressor(
    a: NodeId,
    b: EdgeId,
) = Unit
