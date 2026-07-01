package dev.kuml.io.svg

import dev.kuml.blueprint.model.BlueprintDiagram
import dev.kuml.blueprint.model.BlueprintModel
import dev.kuml.bpmn.model.BpmnConversation
import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.bpmn.model.BpmnParticipant
import dev.kuml.bpmn.model.BpmnSubProcess
import dev.kuml.bpmn.model.ChoreographyDiagram
import dev.kuml.bpmn.model.ChoreographyEvent
import dev.kuml.bpmn.model.ChoreographyGateway
import dev.kuml.bpmn.model.ChoreographySequenceFlow
import dev.kuml.bpmn.model.ChoreographyTask
import dev.kuml.bpmn.model.CollaborationDiagram
import dev.kuml.bpmn.model.ConversationDiagram
import dev.kuml.bpmn.model.ConversationLink
import dev.kuml.bpmn.model.ConversationNodeElement
import dev.kuml.c4.model.C4Container
import dev.kuml.c4.model.C4Diagram
import dev.kuml.c4.model.C4Model
import dev.kuml.c4.model.C4SoftwareSystem
import dev.kuml.c4.model.ComponentDiagram
import dev.kuml.c4.model.ContainerDiagram
import dev.kuml.c4.model.DeploymentDiagram
import dev.kuml.c4.model.DynamicDiagram
import dev.kuml.c4.model.SystemContextDiagram
import dev.kuml.c4.model.SystemLandscapeDiagram
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlElement
import dev.kuml.core.model.PackageDiagramConfig
import dev.kuml.io.svg.blueprint.renderBlueprintJourney
import dev.kuml.io.svg.bpmn.renderChoreoSequenceFlow
import dev.kuml.io.svg.bpmn.renderChoreographyEvent
import dev.kuml.io.svg.bpmn.renderChoreographyGateway
import dev.kuml.io.svg.bpmn.renderChoreographyTask
import dev.kuml.io.svg.bpmn.renderConversationLink
import dev.kuml.io.svg.bpmn.renderConversationNode
import dev.kuml.io.svg.bpmn.renderConversationParticipant
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
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.Sysml2LayoutBridge
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
import dev.kuml.uml.UmlNode
import dev.kuml.uml.UmlPackage
import dev.kuml.uml.UmlStateMachine
import dev.kuml.uml.UmlUseCaseSubject
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

        // V3.x Composite-Structure: collect UmlConnectors whose BOTH endpoint
        // nodeIds (split by "::") are within a given top-level component's
        // subtree. These "internal connectors" are drawn by the SVG renderer
        // inside the parent box — without ELK routing. The map is keyed by the
        // top-level component id. For COMPONENT diagrams and all other types the
        // map remains empty (no internal connectors).
        //
        // Classification: an endpoint id "X::portName" → nodeId "X". If "X" is
        // the parent component id OR is one of its nestedComponents ids, the
        // endpoint belongs to the parent's subtree. A connector qualifies as
        // internal iff BOTH endpoints resolve into the same parent's subtree.
        //
        // Note: the layout bridge already filtered these out from LayoutEdges, so
        // effectiveLayoutResult.edges contains NO entry for them — the ELK edge
        // loop below will never see them. The only renderer for these connectors
        // is the one added to drawComponentBox.
        val internalConnectorsByParentId: Map<String, List<UmlConnector>> =
            if (diagram.type == DiagramType.COMPOSITE_STRUCTURE ||
                diagram.type == DiagramType.COMPONENT
            ) {
                buildInternalConnectorIndex(diagram.elements)
            } else {
                emptyMap()
            }

        val needsContractPadding =
            diagram.type == DiagramType.COMPONENT &&
                elementIndex.values.any {
                    it is UmlComponent &&
                        dev.kuml.io.svg.uml.UmlComponentContracts.hasUnboundContracts(it) { id ->
                            id in layoutNodeIdSet
                        }
                }

        // V2.0.48 — Port-Connector-Routen werden vom [ComponentPortEdgeClipper]
        // erst NACH der Canvas-Größen-Berechnung gebaut und ragen mit ihren
        // seitlichen Stubs bis zu OUTWARD_EXTENT_PX über die Layout-Bounding-Box
        // hinaus. Ohne Korrektur laufen diese Stubs bei den äußersten
        // Komponenten auf bzw. über den Diagrammrahmen (Vault-Beispiel
        // [[35 AUTOSAR Classic – SW-Komponenten]]: linker Stub auf x=2 = Rahmen,
        // rechter Stub jenseits der viewBox). Das Canvas-Padding wird deshalb so
        // weit aufgezogen, dass die Stubs einen Spalt zum Rahmen behalten.
        // FRAME_GAP_PX deckt den 2-px-Rahmen-Inset aus DiagramFrameSvg plus
        // sichtbaren Abstand ab.
        val needsPortStubPadding =
            diagram.type == DiagramType.COMPONENT &&
                elementIndex.values.any {
                    it is UmlConnector &&
                        dev.kuml.io.svg.uml.ComponentPortEdgeClipper
                            .bindsPorts(it.end1Id, it.end2Id)
                }
        val frameGapPx = 10f
        val requiredPadding =
            maxOf(
                options.paddingPx,
                if (needsContractPadding) {
                    dev.kuml.io.svg.uml.UmlComponentContracts.TOTAL_UPWARD_EXTENT_PX + 4f
                } else {
                    0f
                },
                if (needsPortStubPadding) {
                    dev.kuml.io.svg.uml.ComponentPortEdgeClipper.OUTWARD_EXTENT_PX + frameGapPx
                } else {
                    0f
                },
            )
        val effectiveOptions =
            if (requiredPadding > options.paddingPx) {
                options.copy(paddingPx = requiredPadding)
            } else {
                options
            }

        val frameTypeLabel =
            when (diagram.type) {
                DiagramType.CLASS -> "class"
                DiagramType.OBJECT -> "object"
                DiagramType.PACKAGE -> "package"
                DiagramType.COMPONENT -> "component"
                DiagramType.COMPOSITE_STRUCTURE -> "composite structure"
                DiagramType.DEPLOYMENT -> "deployment"
                DiagramType.PROFILE -> "profile"
                DiagramType.USE_CASE -> "use case"
                DiagramType.ACTIVITY -> "activity"
                DiagramType.COMMUNICATION -> "communication"
                DiagramType.TIMING -> "timing"
                DiagramType.INTERACTION_OVERVIEW -> "interaction overview"
                DiagramType.BPMN_COLLABORATION -> "collaboration"
                else -> null
            }

        return SvgDocument.render(
            effectiveLayoutResult,
            theme,
            effectiveOptions,
            frameName = diagram.name.takeIf { frameTypeLabel != null },
            frameTypeLabel = frameTypeLabel,
        ) { nodesBuilder, edgesBuilder ->
            val padding = effectiveOptions.paddingPx

            // Groups FIRST — paint backgrounds before children so node boxes
            // appear on top of the group rectangle.
            //
            // Deployment-Diagramme können verschachtelte UmlNode-Groups haben
            // (z.B. EKS Cluster → Pod). Damit der äußere Rahmen hinter dem
            // inneren liegt, werden Groups nach Fläche absteigend sortiert
            // (größte = äußerste zuerst). Für alle anderen Diagrammtypen ist
            // nur eine Verschachtelungsebene möglich — die Sortierung ist
            // dann eine no-op.
            val sortedGroups =
                effectiveLayoutResult.groups.entries.sortedByDescending { (_, gl) ->
                    gl.bounds.size.width * gl.bounds.size.height
                }
            for ((groupId, groupLayout) in sortedGroups) {
                val gx = groupLayout.bounds.origin.x + padding
                val gy = groupLayout.bounds.origin.y + padding
                val gw = groupLayout.bounds.size.width
                val gh = groupLayout.bounds.size.height
                val pkg = packagesById[groupId.value]
                val subject = subjectsById[groupId.value]
                val deployNode = elementIndex[groupId.value] as? UmlNode
                if (pkg != null && showFolderTabs) {
                    renderPackageGroup(pkg, gx, gy, gw, gh, theme, nodesBuilder)
                } else if (subject != null) {
                    renderSubjectGroup(subject, gx, gy, gw, gh, theme, nodesBuilder)
                } else if (deployNode != null) {
                    // Deployment-Diagramm: UmlNode-Gruppe als 3D-Cube-Rahmen rendern.
                    // Gleiche Technik wie renderUmlStateDiagram für den UmlStateMachine-
                    // Rahmen: NodeLayout aus den Group-Bounds konstruieren und durch den
                    // NodeRendererDispatcher schicken (→ renderUmlNode).
                    val nodeLayout =
                        dev.kuml.layout.NodeLayout(
                            bounds =
                                dev.kuml.layout.Rect(
                                    origin = dev.kuml.layout.Point(gx, gy),
                                    size = dev.kuml.layout.Size(gw, gh),
                                ),
                        )
                    NodeRendererDispatcher.dispatch(deployNode, nodeLayout, theme, nodesBuilder)
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
                    // V3.x Composite-Structure: pass internal connectors for
                    // UmlComponent nodes so they are drawn inside the parent box.
                    val connectors = internalConnectorsByParentId[nodeId.value]
                    if (connectors != null && element is UmlComponent) {
                        dev.kuml.io.svg.uml
                            .renderUmlComponent(element, shifted, theme, nodesBuilder, connectors)
                    } else {
                        NodeRendererDispatcher.dispatch(element, shifted, theme, nodesBuilder)
                    }
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
    ): String {
        val c4TypeLabel =
            when (diagram) {
                is SystemContextDiagram -> "system context"
                is ContainerDiagram -> "container"
                is ComponentDiagram -> "component"
                is DeploymentDiagram -> "deployment"
                is DynamicDiagram -> "dynamic"
                is SystemLandscapeDiagram -> "system landscape"
                else -> "c4"
            }
        return SvgDocument.render(
            layoutResult,
            theme,
            options,
            frameName = diagram.name,
            frameTypeLabel = c4TypeLabel,
        ) { nodesBuilder, edgesBuilder ->
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
                // Resolve the anchor element so we can render its name label
                // in the 36px top-inset that C4LayoutBridge.C4_BOUNDARY_INSETS reserves.
                val anchorElement = elementIndex[groupId.value]
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
                    // Render boundary label inside the top inset area.
                    // ContainerDiagram anchor = C4SoftwareSystem → "[Software System]" + name
                    // ComponentDiagram anchor = C4Container → "[Container: <technology>]" + name
                    when (anchorElement) {
                        is C4Container -> {
                            val tech = anchorElement.technology?.let { " $it" } ?: ""
                            tag(
                                "text",
                                mapOf(
                                    "class" to "kuml-stereotype",
                                    "x" to fmt(gw / 2f),
                                    "y" to "14",
                                    "text-anchor" to "middle",
                                ),
                            ) { text("[Container:$tech]") }
                            tag(
                                "text",
                                mapOf(
                                    "class" to "kuml-title",
                                    "x" to fmt(gw / 2f),
                                    "y" to "30",
                                    "text-anchor" to "middle",
                                ),
                            ) { text(anchorElement.name) }
                        }
                        is C4SoftwareSystem -> {
                            tag(
                                "text",
                                mapOf(
                                    "class" to "kuml-stereotype",
                                    "x" to fmt(gw / 2f),
                                    "y" to "14",
                                    "text-anchor" to "middle",
                                ),
                            ) { text("[Software System]") }
                            tag(
                                "text",
                                mapOf(
                                    "class" to "kuml-title",
                                    "x" to fmt(gw / 2f),
                                    "y" to "30",
                                    "text-anchor" to "middle",
                                ),
                            ) { text(anchorElement.name) }
                        }
                        else -> Unit // Unknown anchor type — boundary rect only, no label
                    }
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

        return SvgDocument.render(
            layoutResult,
            theme,
            effectiveOptions,
            frameName = diagram.name,
            frameTypeLabel = "sequence",
        ) { nodesBuilder, edgesBuilder ->
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

            // 1. Render state machine frame (group background) and composite state frames.
            // Composite states surface as LayoutGroups (nested inside the SM group) so
            // ELK can position their substates correctly. We draw them here, in z-order
            // BEFORE the individual vertex nodes so that substate boxes appear on top.
            for ((groupId, groupLayout) in layoutResult.groups) {
                val gx = groupLayout.bounds.origin.x + padding
                val gy = groupLayout.bounds.origin.y + padding
                val gw = groupLayout.bounds.size.width
                val gh = groupLayout.bounds.size.height
                val groupNodeLayout =
                    dev.kuml.layout.NodeLayout(
                        bounds =
                            dev.kuml.layout.Rect(
                                origin = dev.kuml.layout.Point(gx, gy),
                                size = dev.kuml.layout.Size(gw, gh),
                            ),
                    )
                if (groupId.value == sm.id) {
                    // State machine outer frame
                    NodeRendererDispatcher.dispatch(sm, groupNodeLayout, theme, nodesBuilder)
                } else {
                    // Composite state frame — look up the vertex by group ID
                    val compositeVertex = vertexIndex[groupId.value]
                    if (compositeVertex != null) {
                        NodeRendererDispatcher.dispatch(compositeVertex, groupNodeLayout, theme, nodesBuilder)
                    }
                }
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

                // V3.x — Back-edge label repositioning: in a top-to-bottom STM
                // the longest segment of a back-edge (e.g. Yellow→Red) is the
                // long vertical run on the left side of the diagram.  Its
                // midpoint sits at the y-level of an intermediate state and far
                // to the left, which causes the label to overlap that state and
                // protrude outside the diagram frame.  Instead we anchor the
                // label at 8 % of the arc length from the source — this lands
                // in the short upward stub that exits the source state, which
                // is always in the whitespace BELOW the nearest intermediate
                // node and ABOVE the source state, well inside the SM frame.
                val isBackEdge =
                    label.isNotEmpty() &&
                        shiftedRoute.source.y > shiftedRoute.target.y + 5f
                val backEdgeLabelAnchor: Pair<Float, Float>? =
                    if (isBackEdge) {
                        val a = EdgeLabelGeometry.anchorAt(shiftedRoute, 0.08f)
                        a.x to a.y
                    } else {
                        null
                    }

                dev.kuml.io.svg.sysml2.edge.Sysml2EdgeRenderer
                    .render(
                        shiftedRoute,
                        meta,
                        theme,
                        edgesBuilder,
                        labelStackIndex = stmStackIndices[edgeId] ?: 0,
                        overrideLabelAnchor = backEdgeLabelAnchor,
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
        typeLabel: String,
    ): String =
        SvgDocument.render(
            layoutResult,
            theme,
            options,
            frameName = synthetic.name,
            frameTypeLabel = typeLabel,
        ) { nodesBuilder, edgesBuilder ->
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
        return renderSysml2Synthetic(synthetic, layoutResult, theme, options, BddEdgeAdapter(model, diagram), typeLabel = "bdd")
    }

    /** [toSvg]-Variante für SysML 2 BDDs, schreibt direkt auf Platte. */

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
        val enrichedLayout = Sysml2LayoutBridge.enrichIbdPortPositions(model, diagram, layoutResult)
        return renderSysml2Synthetic(synthetic, enrichedLayout, theme, options, IbdEdgeAdapter(model, diagram), typeLabel = "ibd")
    }

    /** [toSvg]-Variante für SysML 2 IBDs, schreibt direkt auf Platte. */

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
        return renderSysml2Synthetic(synthetic, layoutResult, theme, options, UcEdgeAdapter(diagram), typeLabel = "use case")
    }

    /** [toSvg]-Variante für SysML 2 UC-Diagramme, schreibt direkt auf Platte. */

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
        return renderSysml2Synthetic(synthetic, layoutResult, theme, options, ReqEdgeAdapter(diagram), typeLabel = "requirement")
    }

    /** [toSvg]-Variante für SysML 2 REQ-Diagramme, schreibt direkt auf Platte. */

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
        return renderSysml2Synthetic(synthetic, layoutResult, theme, options, StmEdgeAdapter(model, diagram), typeLabel = "state machine")
    }

    /** [toSvg]-Variante für SysML 2 STM-Diagramme, schreibt direkt auf Platte. */

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

        return SvgDocument.render(
            layoutResult,
            theme,
            options,
            frameName = diagram.name,
            frameTypeLabel = "activity",
        ) { nodesBuilder, edgesBuilder ->
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

        return SvgDocument.render(
            layoutResult,
            theme,
            effectiveOptions,
            frameName = diagram.name,
            frameTypeLabel = "sequence",
        ) { nodesBuilder, edgesBuilder ->
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
        return renderSysml2Synthetic(synthetic, layoutResult, theme, options, ParEdgeAdapter(model, diagram), typeLabel = "parametric")
    }

    /** [toSvg]-Variante für SysML 2 PAR-Diagramme, schreibt direkt auf Platte. */

    /**
     * Rendert ein Blueprint / Journey-Map-Diagramm als SVG-String (V3.1.24).
     *
     * Blueprint-Diagramme durchlaufen **keine** ELK-Pipeline — das Layout ist
     * deterministisch tabellarisch (Phasen = Spalten, Layer = Zeilen). Der
     * interne Renderer [dev.kuml.io.svg.blueprint.renderBlueprintJourney] baut
     * den SVG-String direkt aus dem [BlueprintModel] und dem [BlueprintDiagram].
     *
     * @param model das Blueprint-Modell mit Phasen, Steps, Touchpoints, Connections
     * @param diagram das konkrete Diagramm-View (JourneyDiagram oder BlueprintDiagramFull)
     * @return wohlgeformter SVG-String
     */
    public fun toSvg(
        model: BlueprintModel,
        diagram: BlueprintDiagram,
        theme: KumlTheme = PlainTheme(),
    ): String = renderBlueprintJourney(model, diagram, theme)

    /** [toSvg]-Variante für Blueprint-Diagramme, schreibt direkt auf Platte. */

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
     * Post-processes inter-package dependency routes so they (a) land on the
     * **body** rectangle of the target package — not on the narrow folder-tab
     * top — and (b) **never cross an intermediate package** that sits between
     * client and supplier.
     *
     * Root cause of (a): ELK anchors inter-compound edges at the compound node's
     * outer boundary, which is the very top of the tab area (y = groupOrigin.y).
     * The folder tab is narrower than the body — so the arrowhead's X position is
     * often outside the tab, landing in the empty "notch" between tab end and
     * body start. Visually the arrow appears to float before touching the box.
     *
     * Root cause of (b): the previous fix replaced every route with a single
     * straight [EdgeRoute.Direct] from client body to supplier body. In a
     * vertical stack (e.g. `payment` → `shop` → `shared`) the long
     * `payment → shared` edge then runs as a diagonal straight through the
     * `shop` box in the middle.
     *
     * Fix: anchor both ends on the package **body** boundary as before, but if
     * the straight segment would cross any other package body, detour it around
     * that obstacle band with an orthogonal route through the side (vertical
     * stack) or top/bottom (horizontal row) gutter.
     */
    private fun snapPackageEdgesToBodyBoundary(
        elementIndex: Map<String, dev.kuml.core.model.KumlElement>,
        layoutResult: LayoutResult,
    ): LayoutResult {
        val fixedEdges = linkedMapOf<EdgeId, EdgeRoute>()
        fixedEdges.putAll(layoutResult.edges)

        val packageBounds: List<Pair<GroupId, Rect>> =
            layoutResult.groups.map { (id, group) -> id to group.bounds }

        for ((edgeId, _) in layoutResult.edges) {
            val dep = elementIndex[edgeId.value] as? UmlDependency ?: continue
            val clientId = GroupId(dep.clientId)
            val supplierId = GroupId(dep.supplierId)
            val clientGroup = layoutResult.groups[clientId] ?: continue
            val supplierGroup = layoutResult.groups[supplierId] ?: continue

            val endpoints = packageBodyEndpoints(clientGroup.bounds, supplierGroup.bounds)

            // Obstacles = every other package body. Skip the two endpoints and
            // any package that *contains* an endpoint (a nesting parent), which
            // a child→outside edge would legitimately pass through.
            val crossed =
                packageBounds
                    .asSequence()
                    .filter { (id, _) -> id != clientId && id != supplierId }
                    .map { it.second }
                    .filterNot { rectContains(it, endpoints.source) || rectContains(it, endpoints.target) }
                    .filter { segmentIntersectsRect(endpoints.source, endpoints.target, it) }
                    .toList()

            fixedEdges[edgeId] =
                if (crossed.isEmpty()) {
                    EdgeRoute.Direct(source = endpoints.source, target = endpoints.target)
                } else {
                    routeAroundPackages(endpoints, crossed, layoutResult.canvas)
                }
        }
        return layoutResult.copy(edges = fixedEdges)
    }

    /** Endpoints of a package-dependency route on the two package **body** boundaries. */
    private data class PackageEndpoints(
        val source: Point,
        val target: Point,
        /** `true` when the connection is vertical-dominant (stack), `false` for a horizontal row. */
        val vertical: Boolean,
    )

    /**
     * Computes the [PackageEndpoints] on the **body boundaries** of two package
     * groups. When the connection is vertical (most common), the source exits at
     * the body bottom and the target is entered at the body top
     * (`y = groupOrigin.y + PACKAGE_TAB_HEIGHT`). Horizontal connections use the
     * full body-left/right edges without tab adjustment.
     */
    private fun packageBodyEndpoints(
        client: Rect,
        supplier: Rect,
    ): PackageEndpoints {
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

        return if (kotlin.math.abs(dy) >= kotlin.math.abs(dx)) {
            if (dy > 0f) {
                // supplier is below client
                PackageEndpoints(Point(clientCx, clientBodyBottom), Point(supplierCx, supplierBodyTop), vertical = true)
            } else {
                // supplier is above client
                PackageEndpoints(Point(clientCx, clientBodyTop), Point(supplierCx, supplierBodyBottom), vertical = true)
            }
        } else {
            if (dx > 0f) {
                // supplier is to the right
                PackageEndpoints(
                    Point(client.origin.x + client.size.width, clientBodyCy),
                    Point(supplier.origin.x, supplierBodyCy),
                    vertical = false,
                )
            } else {
                // supplier is to the left
                PackageEndpoints(
                    Point(client.origin.x, clientBodyCy),
                    Point(supplier.origin.x + supplier.size.width, supplierBodyCy),
                    vertical = false,
                )
            }
        }
    }

    /**
     * Builds an orthogonal [EdgeRoute.OrthogonalRounded] that leaves the source
     * body boundary, detours through a gutter past the [obstacles] band and
     * re-enters the target body boundary — so it never crosses an intervening
     * package. The gutter side is chosen on whichever flank of the obstacle band
     * stays inside the [canvas] and is closest to the source/target centre line.
     */
    private fun routeAroundPackages(
        ep: PackageEndpoints,
        obstacles: List<Rect>,
        canvas: Size,
    ): EdgeRoute {
        val gutter = PACKAGE_OBSTACLE_GUTTER
        val margin = PACKAGE_OBSTACLE_MARGIN
        val obsLeft = obstacles.minOf { it.origin.x }
        val obsRight = obstacles.maxOf { it.origin.x + it.size.width }
        val obsTop = obstacles.minOf { it.origin.y }
        val obsBottom = obstacles.maxOf { it.origin.y + it.size.height }

        val waypoints: List<Point> =
            if (ep.vertical) {
                val center = (ep.source.x + ep.target.x) / 2f
                val leftX = obsLeft - gutter
                val rightX = obsRight + gutter
                val leftOk = leftX >= margin
                val rightOk = rightX <= canvas.width - margin
                val corridorX =
                    when {
                        leftOk && rightOk -> if (kotlin.math.abs(leftX - center) <= kotlin.math.abs(rightX - center)) leftX else rightX
                        leftOk -> leftX
                        rightOk -> rightX
                        else ->
                            if (kotlin.math.abs(leftX - center) <= kotlin.math.abs(rightX - center)) {
                                leftX.coerceAtLeast(margin)
                            } else {
                                rightX.coerceAtMost(canvas.width - margin)
                            }
                    }
                val downward = ep.target.y >= ep.source.y
                val edgeNearSource = if (downward) obsTop else obsBottom
                val edgeNearTarget = if (downward) obsBottom else obsTop
                val yA = (ep.source.y + edgeNearSource) / 2f
                val yB = (ep.target.y + edgeNearTarget) / 2f
                listOf(
                    Point(ep.source.x, yA),
                    Point(corridorX, yA),
                    Point(corridorX, yB),
                    Point(ep.target.x, yB),
                )
            } else {
                val center = (ep.source.y + ep.target.y) / 2f
                val topY = obsTop - gutter
                val bottomY = obsBottom + gutter
                val topOk = topY >= margin
                val bottomOk = bottomY <= canvas.height - margin
                val corridorY =
                    when {
                        topOk && bottomOk -> if (kotlin.math.abs(topY - center) <= kotlin.math.abs(bottomY - center)) topY else bottomY
                        topOk -> topY
                        bottomOk -> bottomY
                        else ->
                            if (kotlin.math.abs(topY - center) <= kotlin.math.abs(bottomY - center)) {
                                topY.coerceAtLeast(margin)
                            } else {
                                bottomY.coerceAtMost(canvas.height - margin)
                            }
                    }
                val rightward = ep.target.x >= ep.source.x
                val edgeNearSource = if (rightward) obsLeft else obsRight
                val edgeNearTarget = if (rightward) obsRight else obsLeft
                val xA = (ep.source.x + edgeNearSource) / 2f
                val xB = (ep.target.x + edgeNearTarget) / 2f
                listOf(
                    Point(xA, ep.source.y),
                    Point(xA, corridorY),
                    Point(xB, corridorY),
                    Point(xB, ep.target.y),
                )
            }

        // cornerRadiusPx = 0f: the edge renderer applies its own rounding (Spec Z4),
        // mirroring the ELK ResultMapper convention.
        return EdgeRoute.OrthogonalRounded(
            source = ep.source,
            target = ep.target,
            waypoints = waypoints,
            cornerRadiusPx = 0f,
        )
    }

    /** `true` when [p] lies inside (or on the border of) the axis-aligned [r]. */
    private fun rectContains(
        r: Rect,
        p: Point,
    ): Boolean =
        p.x >= r.origin.x &&
            p.x <= r.origin.x + r.size.width &&
            p.y >= r.origin.y &&
            p.y <= r.origin.y + r.size.height

    /**
     * Liang–Barsky segment/AABB clip test: `true` when the segment `p0→p1`
     * intersects the interior of the axis-aligned rectangle [r].
     */
    private fun segmentIntersectsRect(
        p0: Point,
        p1: Point,
        r: Rect,
    ): Boolean {
        val xMin = r.origin.x
        val yMin = r.origin.y
        val xMax = r.origin.x + r.size.width
        val yMax = r.origin.y + r.size.height
        val dx = p1.x - p0.x
        val dy = p1.y - p0.y
        var t0 = 0f
        var t1 = 1f
        val edges =
            arrayOf(
                -dx to (p0.x - xMin),
                dx to (xMax - p0.x),
                -dy to (p0.y - yMin),
                dy to (yMax - p0.y),
            )
        for ((p, q) in edges) {
            if (p == 0f) {
                if (q < 0f) return false // parallel and outside this slab
            } else {
                val t = q / p
                if (p < 0f) {
                    if (t > t1) return false
                    if (t > t0) t0 = t
                } else {
                    if (t < t0) return false
                    if (t < t1) t1 = t
                }
            }
        }
        return t0 < t1
    }

    /** Abstand des Umweg-Korridors zur Hindernis-Paketbande, wenn eine
     *  Paket-Abhängigkeit ein dazwischenliegendes Paket umrouten muss. */
    private const val PACKAGE_OBSTACLE_GUTTER: Float = 24f

    /** Mindestabstand des Umweg-Korridors zum Canvas-Rand, damit die Kante
     *  nicht aus dem Diagramm-Rahmen herausläuft. */
    private const val PACKAGE_OBSTACLE_MARGIN: Float = 8f

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
            if (element is UmlNode) {
                // Deployment: verschachtelte Kind-Nodes und Artefakte in den Index
                // aufnehmen, damit der Renderer sie in der LayoutResult.nodes-Schleife
                // findet. Ohne diese Rekursion bleiben children/artifacts unsichtbar,
                // weil sie nicht direkt in diagram.elements auftauchen.
                element.children.forEach { visit(it) }
                element.artifacts.forEach { visit(it) }
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
        dx: Float,
        dy: Float = dx,
    ): dev.kuml.layout.EdgeRoute {
        fun dev.kuml.layout.Point.shift() = dev.kuml.layout.Point(x + dx, y + dy)
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

    private fun fmt(v: Float): String = fmt2(v)

    // ── BPMN label-overflow margins ────────────────────────────────────────────
    // Approx. width of a BPMN label glyph (11px sans-serif, ~0.56 em + slack).
    private const val BPMN_LABEL_CHAR_PX: Float = 6.2f

    // y-offset of the label baseline below the shape (matches BpmnEventSvg /
    // BpmnGatewaySvg) plus the descent below the baseline.
    private const val BPMN_LABEL_BELOW_PX: Float = 12f
    private const val BPMN_LABEL_DESCENT_PX: Float = 4f

    /**
     * BPMN events and gateways render their text label *below and centred on*
     * the shape — i.e. OUTSIDE the node footprint that
     * [dev.kuml.layout.LayoutResult.canvas] is sized from (ResultMapper only
     * sees node/group/edge geometry, never text). A node near the canvas edge
     * therefore has its wider/taller label clipped by the viewBox. This computes
     * how far those labels overflow the layout canvas on each side.
     *
     * @return `[left, top, right, bottom]` overflow in px (each ≥ 0).
     */
    private fun bpmnLabelMargins(
        elements: List<dev.kuml.core.model.KumlElement>,
        layoutResult: LayoutResult,
    ): FloatArray {
        val elementIndex = elements.associateBy { it.id }
        var left = 0f
        var right = 0f
        var bottom = 0f
        val w = layoutResult.canvas.width
        val h = layoutResult.canvas.height
        for ((nodeId, nodeLayout) in layoutResult.nodes) {
            val name =
                when (val el = elementIndex[nodeId.value]) {
                    is dev.kuml.bpmn.model.BpmnEvent -> el.name
                    is dev.kuml.bpmn.model.BpmnGateway -> el.name
                    else -> null
                }
            if (name.isNullOrBlank()) continue
            val b = nodeLayout.bounds
            val cx = b.origin.x + b.size.width / 2f
            // The label is rendered at y + SHAPE_H + 12, not y + bounds.h + 12.
            // Use the actual shape height so the margin reflects the true label position.
            val shapeH =
                when (elementIndex[nodeId.value]) {
                    is dev.kuml.bpmn.model.BpmnEvent -> 36f
                    is dev.kuml.bpmn.model.BpmnGateway -> 50f
                    else -> b.size.height
                }
            val halfTextW = name.length * BPMN_LABEL_CHAR_PX / 2f
            val labelLeft = cx - halfTextW
            val labelRight = cx + halfTextW
            val labelBottom = b.origin.y + shapeH + BPMN_LABEL_BELOW_PX + BPMN_LABEL_DESCENT_PX
            if (-labelLeft > left) left = -labelLeft
            if (labelRight - w > right) right = labelRight - w
            if (labelBottom - h > bottom) bottom = labelBottom - h
        }
        return floatArrayOf(maxOf(left, 0f), 0f, maxOf(right, 0f), maxOf(bottom, 0f))
    }

    /**
     * Returns a copy of [layoutResult] with all content shifted by ([left], [top])
     * and the canvas inflated by the per-side margins, so out-of-footprint labels
     * fit inside the viewBox. The symmetric [SvgRenderOptions.paddingPx] is still
     * applied on top by the caller / [SvgDocument].
     */
    private fun applyCanvasMargins(
        layoutResult: LayoutResult,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): LayoutResult {
        if (left == 0f && top == 0f && right == 0f && bottom == 0f) return layoutResult

        fun Rect.shifted() = copy(origin = Point(origin.x + left, origin.y + top))
        return layoutResult.copy(
            canvas =
                dev.kuml.layout.Size(
                    layoutResult.canvas.width + left + right,
                    layoutResult.canvas.height + top + bottom,
                ),
            nodes = layoutResult.nodes.mapValues { (_, n) -> n.copy(bounds = n.bounds.shifted()) },
            groups = layoutResult.groups.mapValues { (_, g) -> g.copy(bounds = g.bounds.shifted()) },
            edges = layoutResult.edges.mapValues { (_, r) -> shiftRoute(r, left, top) },
        )
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

        return SvgDocument.render(
            layoutResult,
            theme,
            options,
            frameName = diagram.name,
            frameTypeLabel = "collaboration",
        ) { nodesBuilder, edgesBuilder ->
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

            // IDs that were added as phantom LayoutNodes (size 0×0) to serve as
            // message-flow anchors for pool participants. These same IDs are already
            // rendered above via the group loop as full pool frames, so we must skip
            // them here to avoid a second, degenerate render derived from the 0×0
            // phantom bounds.
            val phantomNodeIds: Set<String> =
                layoutResult.groups.keys
                    .map { it.value }
                    .toSet()

            // Nodes (flow nodes inside pools)
            for ((nodeId, nodeLayout) in layoutResult.nodes) {
                if (nodeId.value in phantomNodeIds) continue
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

    /**
     * Rendert ein BPMN-Choreografie-Diagramm als SVG.
     *
     * Render-Reihenfolge (flach, keine Groups/Pools):
     *  1. ChoreographyTasks mit doppeltem Rahmen und Participant-Bands.
     *  2. ChoreographyGateways als Rauten.
     *  3. ChoreographyEvents als Kreise.
     *  4. ChoreographySequenceFlows als Pfeile.
     *
     * @param model Das BPMN-Modell mit der referenzierten Choreography.
     * @param diagram Das [ChoreographyDiagram] mit dem Verweis auf eine Choreography.
     * @param layoutResult Berechnete Positionen und Routing-Pfade.
     * @param theme Visuelles Theme; Standard: [PlainTheme].
     * @param options Renderer-Optionen; Standard: [SvgRenderOptions.DEFAULT].
     *
     * V3.2.2 — BPMN Choreography SVG-Renderer
     */
    public fun toSvg(
        model: BpmnModel,
        diagram: ChoreographyDiagram,
        layoutResult: LayoutResult,
        theme: KumlTheme = PlainTheme(),
        options: SvgRenderOptions = SvgRenderOptions.DEFAULT,
    ): String = renderBpmnChoreography(model, diagram, layoutResult, theme, options)

    /** [toSvg]-Variante für BPMN-Choreografie-Diagramme, schreibt direkt auf Platte. */

    /**
     * Rendert ein BPMN-Conversation-Diagramm als SVG.
     *
     * Render-Reihenfolge (flach, keine Groups/Pools):
     *  1. Participants als Rechtecke.
     *  2. Konversationsknoten als Hexagons (ConversationNode / CallConversation / SubConversation).
     *  3. Conversation Links als Linien OHNE Pfeilkopf (BPMN 2.0 §9.5.3).
     *
     * @param model Das BPMN-Modell mit der referenzierten Conversation.
     * @param diagram Das [ConversationDiagram] mit dem Verweis auf eine Conversation.
     * @param layoutResult Berechnete Positionen und Routing-Pfade.
     * @param theme Visuelles Theme; Standard: [PlainTheme].
     * @param options Renderer-Optionen; Standard: [SvgRenderOptions.DEFAULT].
     *
     * V3.2.3 — BPMN Conversation Diagram: SVG-Renderer
     */
    public fun toSvg(
        model: BpmnModel,
        diagram: ConversationDiagram,
        layoutResult: LayoutResult,
        theme: KumlTheme = PlainTheme(),
        options: SvgRenderOptions = SvgRenderOptions.DEFAULT,
    ): String = renderBpmnConversation(model, diagram, layoutResult, theme, options)

    /** [toSvg]-Variante für BPMN-Conversation-Diagramme, schreibt direkt auf Platte. */

    /**
     * Dedizierter Render-Pfad für BPMN-Choreografie-Diagramme.
     *
     * Flache Struktur (keine Groups): Tasks, Gateways und Events werden direkt
     * als Nodes gerendert, SequenceFlows als Kanten. Keine Canvas-Inflation
     * für Event-/Gateway-Labels (TODO V3.2.3).
     *
     * V3.2.2 — BPMN Choreography SVG-Renderer
     */
    private fun renderBpmnChoreography(
        model: BpmnModel,
        diagram: ChoreographyDiagram,
        layoutResult: LayoutResult,
        theme: KumlTheme,
        options: SvgRenderOptions,
    ): String {
        val choreography =
            model.choreographies.firstOrNull { it.id == diagram.choreographyId }
                ?: return SvgDocument.render(layoutResult, theme, options) { _, _ -> }

        // Build element index
        val taskById: Map<String, ChoreographyTask> = choreography.tasks.associateBy { it.id }
        val gatewayById: Map<String, ChoreographyGateway> = choreography.gateways.associateBy { it.id }
        val eventById: Map<String, ChoreographyEvent> = choreography.events.associateBy { it.id }
        val flowById: Map<String, ChoreographySequenceFlow> = choreography.sequenceFlows.associateBy { it.id }

        return SvgDocument.render(
            layoutResult,
            theme,
            options,
            frameName = diagram.name,
            frameTypeLabel = "choreography",
        ) { nodesBuilder, edgesBuilder ->
            val padding = options.paddingPx

            // Nodes
            for ((nodeId, nodeLayout) in layoutResult.nodes) {
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
                when {
                    taskById.containsKey(nodeId.value) ->
                        renderChoreographyTask(taskById[nodeId.value]!!, shifted, theme, nodesBuilder)
                    gatewayById.containsKey(nodeId.value) ->
                        renderChoreographyGateway(gatewayById[nodeId.value]!!, shifted, theme, nodesBuilder)
                    eventById.containsKey(nodeId.value) ->
                        renderChoreographyEvent(eventById[nodeId.value]!!, shifted, theme, nodesBuilder)
                }
            }

            // Edges
            for ((edgeId, route) in layoutResult.edges) {
                val flow = flowById[edgeId.value] ?: continue
                val shiftedRoute = shiftRoute(route, padding)
                renderChoreoSequenceFlow(flow, shiftedRoute, edgesBuilder, theme)
            }
        }
    }

    /**
     * Dedizierter Render-Pfad für BPMN-Conversation-Diagramme.
     *
     * Flache Struktur (keine Groups): Participants werden als Rechteck-Knoten
     * gerendert, ConversationNodes (Hexagons) als Hexagon-Shapes, Conversation
     * Links als ungerichtete Linien OHNE Pfeilkopf (BPMN 2.0 §9.5.3).
     *
     * V3.2.3 — BPMN Conversation Diagram: SVG-Renderer
     */
    private fun renderBpmnConversation(
        model: BpmnModel,
        diagram: ConversationDiagram,
        layoutResult: LayoutResult,
        theme: KumlTheme,
        options: SvgRenderOptions,
    ): String {
        val conversation: BpmnConversation =
            model.conversations.firstOrNull { it.id == diagram.conversationId }
                ?: return SvgDocument.render(layoutResult, theme, options) { _, _ -> }

        // Build element indexes
        val participantNames: Set<String> = conversation.participants.toSet()
        val nodeById: Map<String, ConversationNodeElement> = conversation.nodes.associateBy { it.id }
        val linkById: Map<String, ConversationLink> = conversation.links.associateBy { it.id }

        return SvgDocument.render(
            layoutResult,
            theme,
            options,
            frameName = diagram.name,
            frameTypeLabel = "conversation",
        ) { nodesBuilder, edgesBuilder ->
            val padding = options.paddingPx

            // Nodes — Participants (Rechtecke) und ConversationNodes (Hexagons)
            for ((nodeId, nodeLayout) in layoutResult.nodes) {
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
                when {
                    nodeId.value in participantNames ->
                        renderConversationParticipant(nodeId.value, shifted, theme, nodesBuilder)
                    nodeById.containsKey(nodeId.value) ->
                        renderConversationNode(nodeById[nodeId.value]!!, shifted, theme, nodesBuilder)
                }
            }

            // Edges — Conversation Links (keine Pfeilköpfe)
            for ((edgeId, route) in layoutResult.edges) {
                val link = linkById[edgeId.value] ?: continue
                val shiftedRoute = shiftRoute(route, padding)
                renderConversationLink(link, shiftedRoute, edgesBuilder, theme)
            }
        }
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

        // Inflate the canvas so event/gateway labels rendered outside the node
        // footprint (below + horizontally centred) are not clipped at the edge.
        val (mLeft, mTop, mRight, mBottom) = bpmnLabelMargins(diagram.elements, layoutResult)
        val renderLayout = applyCanvasMargins(layoutResult, mLeft, mTop, mRight, mBottom)

        return SvgDocument.render(
            renderLayout,
            theme,
            options,
            frameName = diagram.name,
            frameTypeLabel = "process",
        ) { nodesBuilder, edgesBuilder ->
            val padding = options.paddingPx

            // 1. Groups FIRST — render expanded SubProcess frames behind their contents.
            //    Any group whose id matches an expanded SubProcess is rendered as a
            //    SubProcess background rect; unknown groups get the generic kuml-system rect.
            for ((groupId, groupLayout) in renderLayout.groups) {
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
            for ((nodeId, nodeLayout) in renderLayout.nodes) {
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
            // Note: no endpoint clipper here. Events and gateways use enlarged layout
            // bounds (shape height + 20 px label space below) so ELK already routes
            // edges from below the label area. Applying a clipper would snap the route
            // start back into the label space, re-introducing the coverage issue.
            for ((edgeId, route) in renderLayout.edges) {
                val element = elementIndex[edgeId.value] ?: continue
                val shiftedRoute = shiftRoute(route, padding)
                EdgeRendererDispatcher.dispatch(element, shiftedRoute, theme, edgesBuilder)
            }
        }
    }

    /**
     * Builds a map from top-level [UmlComponent] id to the list of [UmlConnector]s
     * that are "internal" to that component — i.e. both endpoint nodeIds (the part
     * before the last `"::"` separator) fall within that component's subtree
     * (the component itself for boundary ports, or any of its nestedComponents for
     * part ports).
     *
     * Internal connectors are NOT routed by ELK ([dev.kuml.layout.bridge.UmlLayoutBridge]
     * filters them out). They are drawn by the SVG renderer inside the component's
     * local coordinate frame via `drawComponentBox`.
     *
     * Only top-level [UmlComponent]s in [elements] are considered as potential
     * parent containers. UmlConnectors that do not qualify as internal (at least
     * one endpoint is a top-level ELK node) are excluded from the result.
     */
    private fun buildInternalConnectorIndex(elements: List<dev.kuml.core.model.KumlElement>): Map<String, List<UmlConnector>> {
        val result = mutableMapOf<String, MutableList<UmlConnector>>()
        val sep = "::"

        // Collect top-level components and their nested subtree ids.
        data class ComponentSubtree(
            val parent: UmlComponent,
            val subtreeIds: Set<String>,
        )

        // Collects all component ids in the subtree rooted at [comp].
        // [visited] is a shared cycle-detection set: a component id already in
        // [visited] is skipped to prevent infinite recursion on cyclic graphs
        // (e.g. XMI-imported models where A.nestedComponents contains B and
        // B.nestedComponents contains A).
        fun collectSubtreeIds(
            comp: UmlComponent,
            visited: MutableSet<String> = mutableSetOf(),
        ): Set<String> {
            if (!visited.add(comp.id)) return emptySet() // cycle guard
            val ids = mutableSetOf(comp.id)
            for (nested in comp.nestedComponents) ids += collectSubtreeIds(nested, visited)
            return ids
        }

        val subtrees: List<ComponentSubtree> =
            elements.filterIsInstance<UmlComponent>().map { comp ->
                ComponentSubtree(comp, collectSubtreeIds(comp))
            }

        // For each UmlConnector in elements, check whether both endpoint nodeIds
        // fall within a single top-level component's subtree.
        for (element in elements) {
            if (element !is UmlConnector) continue
            val end1NodeId =
                element.end1Id.let { id ->
                    val sepIdx = id.lastIndexOf(sep)
                    if (sepIdx > 0) id.substring(0, sepIdx) else id
                }
            val end2NodeId =
                element.end2Id.let { id ->
                    val sepIdx = id.lastIndexOf(sep)
                    if (sepIdx > 0) id.substring(0, sepIdx) else id
                }
            // Find the unique top-level component whose subtree contains both endpoints.
            val parentSubtree =
                subtrees.firstOrNull { st ->
                    end1NodeId in st.subtreeIds && end2NodeId in st.subtreeIds
                } ?: continue
            // The connector is internal only if at least one endpoint is a NESTED part
            // (not the parent component itself on both sides). A connector between two
            // boundary ports of the same component has both nodeIds == parent.id, which
            // would still qualify here — include it (a self-boundary connector is still
            // drawn inside the box, not by ELK).
            result.getOrPut(parentSubtree.parent.id) { mutableListOf() } += element
        }
        return result
    }
}

// Suppress unused import warning for NodeId/EdgeId/GroupId — used via layoutResult
@Suppress("UnusedPrivateMember")
private fun unusedImportSuppressor(
    a: NodeId,
    b: EdgeId,
) = Unit
