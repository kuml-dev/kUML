package dev.kuml.layout.bridge

import dev.kuml.core.model.KumlDiagram
import dev.kuml.layout.EdgeHints
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EndpointRef
import dev.kuml.layout.GroupId
import dev.kuml.layout.Insets
import dev.kuml.layout.LayoutEdge
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutGroup
import dev.kuml.layout.LayoutNode
import dev.kuml.layout.NodeId
import dev.kuml.layout.PortId
import dev.kuml.layout.Size
import dev.kuml.uml.UmlActivityNode
import dev.kuml.uml.UmlActivityNodeKind
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlFinalState
import dev.kuml.uml.UmlInteraction
import dev.kuml.uml.UmlNamedElement
import dev.kuml.uml.UmlPackage
import dev.kuml.uml.UmlPseudostate
import dev.kuml.uml.UmlRelationship
import dev.kuml.uml.UmlState
import dev.kuml.uml.UmlStateMachine
import dev.kuml.uml.UmlUseCaseSubject
import dev.kuml.uml.UmlVertex

/**
 * Übersetzt ein UML-Diagramm in einen [LayoutGraph].
 *
 * Liest `kuml.layout.*` aus den Element-`metadata`-Maps und materialisiert
 * sie als [LayoutNode.hints] (NodeHints). Beziehungen werden zu [LayoutEdge].
 * UML-Packages werden zu [LayoutGroup]s (V1: keine Verschachtelung).
 *
 * Diagrammtypen: Class, Component, UseCase, State. Sequenzdiagramme sind
 * nicht im Scope — sie haben ihre eigene Pipeline.
 *
 * Beispiel:
 * ```kotlin
 * val graph = UmlLayoutBridge.toLayoutGraph(diagram)
 * ```
 */
public object UmlLayoutBridge {
    /**
     * Insets eines UML-Pakets (Folder-Tab + Name-Bereich oben).
     *
     * 18 px Tab-Höhe (siehe `KumlSvgRenderer.renderPackageGroup`) plus 10 px
     * Atemluft bis zum ersten Member-Knoten = 28 px Top-Padding. Seiten und
     * Boden kriegen 12 px, damit Klassenboxen nicht direkt an die Paket-Wand
     * stoßen.
     */
    internal val PACKAGE_GROUP_INSETS: Insets = Insets(top = 28f, right = 12f, bottom = 12f, left = 12f)

    /**
     * Insets einer UML-Use-Case-Subject-Box (Systemgrenze).
     *
     * 28 px Top-Padding: 18 px für den Namens-Label (Text-Baseline bei y=18)
     * plus 10 px Atemluft bis zum ersten enthaltenen Use-Case-Ellipsen-Rand.
     * Seiten und Boden bekommen 20 px, damit Ellipsen nicht an den Rand stoßen.
     */
    internal val USE_CASE_SUBJECT_INSETS: Insets = Insets(top = 28f, right = 20f, bottom = 20f, left = 20f)

    // ── UML 2.x Activity-Diagramm: per-Kind Default-Größen (V2.0.46) ──
    //
    // Die `dev.kuml.io.svg.uml.renderUmlActivityNode`-SVG-Routine zeichnet
    // INITIAL/ACTIVITY_FINAL/FLOW_FINAL als kleine Kreise (r = 10 bzw. 12)
    // und DECISION/MERGE als bounds-füllende Raute. Wenn das Layout pro
    // Knoten die Default-160×80-Box reserviert, ist die gezeichnete Form
    // entweder ein winziger Kreis in einer riesigen leeren Box (Pseudo-
    // Knoten) oder eine bounds-füllende Riesen-Raute (Decision). In beiden
    // Fällen enden Kanten an der **Bounding-Box-Kante**, also weit
    // außerhalb des sichtbaren Shapes — Pfeile „schweben". Diese Konstanten
    // geben dem `UmlLayoutBridge` Activity-Pfad realistische Default-Größen,
    // damit die Bounding-Box etwa dem sichtbaren Shape entspricht.

    /**
     * Default-Größe (quadratisch) für Pseudo- und Schaltknoten in UML-1.1-
     * Activity-Diagrammen: Initial (10-px-Kreis), Activity-Final (12-px-
     * Donut), Flow-Final (10-px-Kreis mit X), Decision / Merge (bounds-
     * füllende Raute). 28 px gibt der Bounding-Box gerade so viel Atem-
     * luft, dass ein 12-px-Final-Kreis bequem hineinpasst.
     */
    public const val ACTIVITY_PSEUDO_SIZE: Float = 28f

    /**
     * Default-Breite einer Action- oder Object-Node-Box in einem UML-1.1-
     * Activity-Diagramm. 160 px reicht für Standard-Action-Namen ohne
     * Wort-Wrap (V2.x bringt content-aware Sizing analog zur
     * [UmlContentSizeProvider]-Heuristik für Klassendiagramme).
     */
    public const val ACTIVITY_ACTION_WIDTH: Float = 160f

    /** Default-Höhe einer Action- oder Object-Node-Box (V2.0.46). */
    public const val ACTIVITY_ACTION_HEIGHT: Float = 60f

    /**
     * Default-Breite einer Fork-/Join-Synchronisations-Bar. Die SVG-Routine
     * zeichnet einen 8-px-Balken zentriert in der Bounds — eine breite Bar
     * gibt der ELK-Engine genug Andock-Fläche für die typischerweise drei
     * bis fünf parallelen Edges einer Fork.
     */
    public const val ACTIVITY_BAR_WIDTH: Float = 120f

    /**
     * Default-Höhe einer Fork-/Join-Bar. 12 px sorgt für 2 px Atemluft
     * unter und über dem gezeichneten 8-px-Balken.
     */
    public const val ACTIVITY_BAR_HEIGHT: Float = 12f

    /**
     * Übersetzt [diagram] in einen [LayoutGraph].
     *
     * Elemente in [KumlDiagram.elements] werden wie folgt verarbeitet:
     * - [UmlPackage] → [LayoutGroup] (parent = null, V1: keine Verschachtelung);
     *   direkte Members werden zu [LayoutNode] mit gesetztem groupId.
     * - Weitere [UmlNamedElement]-Subtypen (außer [UmlPackage]) → [LayoutNode].
     * - [UmlRelationship]-Subtypen → [LayoutEdge] (EdgeHints.NONE in V1).
     * - Alles andere wird schweigend ignoriert.
     *
     * @param diagram Das zu übersetzende UML-Diagramm.
     * @param sizeProvider Liefert die intrinsische Größe pro Knoten.
     */
    public fun toLayoutGraph(
        diagram: KumlDiagram,
        sizeProvider: SizeProvider = SizeProvider.constant(),
    ): LayoutGraph {
        val nodes = mutableListOf<LayoutNode>()
        val edges = mutableListOf<LayoutEdge>()
        val groups = mutableListOf<LayoutGroup>()

        // Pre-pass: collect port names per component for connector-endpoint
        // splitting (componentId::portName → nodeId+portId). Walks packages,
        // sub-packages and nested components transitively.
        val componentPorts: Map<String, Set<String>> = collectComponentPorts(diagram)

        // Pre-pass: collect UmlUseCaseSubject elements → LayoutGroups and build
        // a reverse map (useCaseId → groupId) so that individual UmlUseCase nodes
        // receive the correct groupId when encountered in the main loop below.
        // Must run before the main loop because the subject may appear after its
        // contained use cases in diagram.elements.
        val useCaseGroupMap = mutableMapOf<String, GroupId>()
        for (element in diagram.elements) {
            if (element is UmlUseCaseSubject) {
                val groupId = GroupId(element.id)
                groups.add(
                    LayoutGroup(
                        id = groupId,
                        parent = null,
                        padding = USE_CASE_SUBJECT_INSETS,
                        layoutAsCompound = true,
                    ),
                )
                for (ucId in element.useCaseIds) {
                    useCaseGroupMap[ucId] = groupId
                }
            }
        }

        for (element in diagram.elements) {
            when (element) {
                is UmlPackage -> {
                    // V1: max. 1 Ebene — Package wird zur Group, Members zu Nodes.
                    // Top-Padding reserviert Platz für den UML-Folder-Tab + Paketnamen,
                    // damit der KumlSvgRenderer die Tab-Lasche zeichnen kann, ohne dass
                    // Mitglieds-Klassen darunter geclippt werden. Side/bottom auf 12,
                    // top auf 28 (Tab-Höhe 18 + 10 px Atemluft zum ersten Member-Knoten).
                    val groupId = GroupId(element.id)
                    groups.add(
                        LayoutGroup(
                            id = groupId,
                            parent = null,
                            padding = PACKAGE_GROUP_INSETS,
                            layoutAsCompound = true,
                        ),
                    )
                    for (member in element.members) {
                        when (member) {
                            is UmlPackage -> {
                                // Sub-Package: eigene eigenständige Group ohne parent (V1: keine Verschachtelung)
                                val subGroupId = GroupId(member.id)
                                groups.add(
                                    LayoutGroup(
                                        id = subGroupId,
                                        parent = null,
                                        padding = PACKAGE_GROUP_INSETS,
                                        layoutAsCompound = true,
                                    ),
                                )
                                for (subMember in member.members) {
                                    if (subMember !is UmlPackage && subMember !is UmlRelationship) {
                                        nodes.add(
                                            LayoutNode(
                                                id = NodeId(subMember.id),
                                                intrinsicSize =
                                                    sizeProvider.sizeOf(
                                                        subMember.id,
                                                        subMember::class.simpleName ?: "Unknown",
                                                    ),
                                                hints = HintsReader.read(subMember.metadata),
                                                groupId = subGroupId,
                                            ),
                                        )
                                    }
                                }
                            }
                            is UmlRelationship -> {
                                // Relationships inside packages are treated as edges
                                val endpoints = EndpointResolver.resolveWithPorts(member, componentPorts)
                                if (endpoints != null) {
                                    edges.add(toEdge(member.id, endpoints))
                                }
                            }
                            else -> {
                                // Regular named element inside a package
                                nodes.add(
                                    LayoutNode(
                                        id = NodeId(member.id),
                                        intrinsicSize =
                                            sizeProvider.sizeOf(
                                                member.id,
                                                member::class.simpleName ?: "Unknown",
                                            ),
                                        hints = HintsReader.read(member.metadata),
                                        groupId = groupId,
                                    ),
                                )
                            }
                        }
                    }
                }
                is UmlRelationship -> {
                    val endpoints = EndpointResolver.resolveWithPorts(element, componentPorts)
                    if (endpoints != null) {
                        edges.add(toEdge(element.id, endpoints))
                    }
                }
                is UmlInteraction -> {
                    // SEQ: one LayoutNode per lifeline, height from message count. No edges —
                    // messages and fragments are rendered directly by KumlSvgRenderer.
                    val maxSeq = element.messages.maxOfOrNull { it.sequence } ?: 0
                    val rowCount = if (maxSeq < 1) 1 else maxSeq
                    val nodeH =
                        Sysml2LayoutBridge.SEQ_LIFELINE_HEAD_HEIGHT +
                            (rowCount + 1) * Sysml2LayoutBridge.SEQ_MESSAGE_ROW_HEIGHT +
                            Sysml2LayoutBridge.SEQ_LIFELINE_TAIL_PADDING
                    for (lifeline in element.lifelines) {
                        nodes.add(
                            LayoutNode(
                                id = NodeId(lifeline.id),
                                intrinsicSize = Size(Sysml2LayoutBridge.SEQ_LIFELINE_WIDTH, nodeH),
                            ),
                        )
                    }
                }
                is UmlStateMachine -> {
                    // Create a group for the state machine frame so ELK encloses all vertices
                    val smGroupId = GroupId(element.id)
                    groups.add(LayoutGroup(id = smGroupId, parent = null, padding = Insets(32f, 16f, 24f, 16f)))

                    // Collect all vertices flat (including substates) and add as LayoutNodes
                    fun collectVertices(vertices: List<UmlVertex>) {
                        for (vertex in vertices) {
                            val size =
                                when (vertex) {
                                    is UmlPseudostate -> Size(24f, 24f)
                                    is UmlFinalState -> Size(28f, 28f)
                                    is UmlState -> sizeProvider.sizeOf(vertex.id, "UmlState")
                                }
                            nodes.add(
                                LayoutNode(
                                    id = NodeId(vertex.id),
                                    intrinsicSize = size,
                                    hints = HintsReader.read(vertex.metadata),
                                    groupId = smGroupId,
                                ),
                            )
                            // Recurse into composite state substates (flat — same group)
                            if (vertex is UmlState && vertex.substates.isNotEmpty()) {
                                collectVertices(vertex.substates)
                            }
                        }
                    }
                    collectVertices(element.vertices)

                    // Transitions as edges
                    for (transition in element.transitions) {
                        edges.add(
                            LayoutEdge(
                                id = EdgeId(transition.id),
                                source = EndpointRef(nodeId = NodeId(transition.sourceId)),
                                target = EndpointRef(nodeId = NodeId(transition.targetId)),
                                hints = EdgeHints.NONE,
                            ),
                        )
                    }
                }
                is UmlUseCaseSubject -> {
                    // Already handled in the pre-pass above (LayoutGroup + useCaseGroupMap).
                    // Do NOT add as a LayoutNode — the subject is the bounding box, not a node.
                }
                is UmlActivityNode -> {
                    // V2.0.46: per-kind intrinsic size for activity nodes.
                    //
                    // Before V2.0.46 these fell through to the generic
                    // `is UmlNamedElement` branch below and got the default
                    // 160×80 box for *every* kind — including the Initial
                    // marker (a 10px filled circle) and the Decision diamond
                    // (a polygon that filled the whole bounds). The result
                    // was an oversized diamond that dominated the canvas and
                    // edges that floated in empty space because the
                    // bounding-box edge is far from the actual visible
                    // shape (diamond polygon, small fixed-radius circle).
                    //
                    // Per-kind defaults are aligned with the hard-coded
                    // radii / heights in `dev.kuml.io.svg.uml.renderUmlActivityNode`:
                    //   - INITIAL / ACTIVITY_FINAL / FLOW_FINAL — small
                    //     square containing the 10/12-px pseudo-marker
                    //     circle plus a little breathing room.
                    //   - DECISION / MERGE — same small square so the
                    //     diamond is **no larger than** the pseudo-nodes
                    //     (Vault feedback from
                    //     [[03 Bereiche/kUML/Beispiele/17 UML Activity – Checkout Flow]]).
                    //   - FORK / JOIN — wide thin horizontal synchronisation
                    //     bar (renderer draws the 8-px-thick rect centred
                    //     vertically inside these bounds).
                    //   - ACTION / OBJECT — regular content-bearing box.
                    val size =
                        when (element.kind) {
                            UmlActivityNodeKind.INITIAL,
                            UmlActivityNodeKind.ACTIVITY_FINAL,
                            UmlActivityNodeKind.FLOW_FINAL,
                            UmlActivityNodeKind.DECISION,
                            UmlActivityNodeKind.MERGE,
                            -> Size(ACTIVITY_PSEUDO_SIZE, ACTIVITY_PSEUDO_SIZE)
                            UmlActivityNodeKind.FORK,
                            UmlActivityNodeKind.JOIN,
                            -> Size(ACTIVITY_BAR_WIDTH, ACTIVITY_BAR_HEIGHT)
                            UmlActivityNodeKind.ACTION,
                            UmlActivityNodeKind.OBJECT,
                            -> Size(ACTIVITY_ACTION_WIDTH, ACTIVITY_ACTION_HEIGHT)
                        }
                    nodes.add(
                        LayoutNode(
                            id = NodeId(element.id),
                            intrinsicSize = size,
                            hints = HintsReader.read(element.metadata),
                        ),
                    )
                }
                is UmlNamedElement -> {
                    // Any other named element (classifier, use case, actor, …).
                    // Use-Case nodes that belong to a subject get the matching groupId so
                    // ELK places them inside the compound/group node (= system boundary box).
                    nodes.add(
                        LayoutNode(
                            id = NodeId(element.id),
                            intrinsicSize =
                                sizeProvider.sizeOf(
                                    element.id,
                                    element::class.simpleName ?: "Unknown",
                                ),
                            hints = HintsReader.read(element.metadata),
                            groupId = useCaseGroupMap[element.id],
                        ),
                    )
                }
                else -> {
                    // Non-UML or non-named elements: silently ignored
                }
            }
        }

        return LayoutGraph(nodes = nodes, edges = edges, groups = groups)
    }

    private fun toEdge(
        edgeId: String,
        endpoints: ResolvedEndpoints,
    ): LayoutEdge =
        LayoutEdge(
            id = EdgeId(edgeId),
            source =
                EndpointRef(
                    nodeId = NodeId(endpoints.sourceNodeId),
                    portId = endpoints.sourcePortId?.let(::PortId),
                ),
            target =
                EndpointRef(
                    nodeId = NodeId(endpoints.targetNodeId),
                    portId = endpoints.targetPortId?.let(::PortId),
                ),
            hints = EdgeHints.NONE,
        )

    /**
     * Walks the diagram and collects, per component ID, the set of declared port
     * names. Required so [EndpointResolver.resolveWithPorts] can split
     * qualified connector endpoint IDs (`"compId::portName"`) into node + port.
     *
     * Handles components at top level, inside packages (incl. sub-packages),
     * and nested components inside other components.
     */
    private fun collectComponentPorts(diagram: KumlDiagram): Map<String, Set<String>> {
        val result = mutableMapOf<String, Set<String>>()
        for (element in diagram.elements) {
            when (element) {
                is UmlComponent -> collectFromComponent(element, result)
                is UmlPackage -> collectFromPackage(element, result)
                else -> {} // ignore
            }
        }
        return result
    }

    private fun collectFromPackage(
        pkg: UmlPackage,
        out: MutableMap<String, Set<String>>,
    ) {
        for (member in pkg.members) {
            when (member) {
                is UmlComponent -> collectFromComponent(member, out)
                is UmlPackage -> collectFromPackage(member, out)
                else -> {}
            }
        }
    }

    private fun collectFromComponent(
        component: UmlComponent,
        out: MutableMap<String, Set<String>>,
    ) {
        out[component.id] = component.ports.map { it.name }.toSet()
        for (nested in component.nestedComponents) {
            collectFromComponent(nested, out)
        }
    }
}
