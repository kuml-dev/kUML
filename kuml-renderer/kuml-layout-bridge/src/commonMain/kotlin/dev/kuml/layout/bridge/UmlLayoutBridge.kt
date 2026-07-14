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
import dev.kuml.uml.UmlComment
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlConnector
import dev.kuml.uml.UmlFinalState
import dev.kuml.uml.UmlInteraction
import dev.kuml.uml.UmlNamedElement
import dev.kuml.uml.UmlNode
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
     * Insets eines zusammengesetzten UML-Zustands (Composite State).
     *
     * 28 px Top-Padding: 14 px Name-Label-Baseline + 14 px Atemluft bis zum
     * ersten Substate-Knoten. Seiten und Boden auf 12 px, damit Substates
     * nicht direkt an die Composite-Wand stoßen.
     */
    internal val COMPOSITE_STATE_INSETS: Insets = Insets(top = 28f, right = 12f, bottom = 12f, left = 12f)

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

    /**
     * Insets eines Deployment-Node-Groups (3-D-Cube-Rahmen mit Stereotyp + Name oben).
     *
     * Der `renderUmlNode`-Renderer zeichnet:
     *  - Oberes 3-D-Dach: 8 px (depth)
     *  - Stereotyp-Text bei cy = depth + 16 = 24 px (Baseline)
     *  - Name-Text bei cy = 38 px (Baseline, wenn Stereotyp vorhanden)
     *  - Plus ~8 px Atemluft bis zum ersten Kindknoten → Top-Padding = 50 px
     *
     * Seiten und Boden: 14 px, damit Kinder nicht direkt an den Rahmen stoßen.
     */
    internal val DEPLOYMENT_NODE_GROUP_INSETS: Insets = Insets(top = 50f, right = 14f, bottom = 14f, left = 14f)

    /**
     * Insets eines UML-State-Machine-Frames.
     *
     * `renderUmlStateMachine` zeichnet:
     *  - Label `stateMachine` oben links (Baseline y = 16)
     *  - State-Machine-Name zentriert (Baseline y = 46, ~18 px Texthöhe → Boden ≈ 50)
     *
     * Top-Padding = 60 px, damit der erste Vertex (typischerweise der Initial-
     * Pseudostate, den ELK horizontal mittig platziert) klar **unter** dem
     * zentrierten Titel beginnt und nicht mit ihm kollidiert. Seiten/Boden
     * 16/24 px wie zuvor.
     */
    internal val STATE_MACHINE_GROUP_INSETS: Insets = Insets(top = 60f, right = 16f, bottom = 24f, left = 16f)

    /** Intrinsische Größe eines UML-Artefakts innerhalb eines Deployment-Nodes.
     *
     * Passend zu `renderUmlArtifact`: «artifact»-Label bei y=20, Name bei y=36,
     * dog-eared rectangle füllt die gesamte Box.
     */
    internal val DEPLOYMENT_ARTIFACT_SIZE: Size = Size(120f, 52f)

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

        // Pre-pass: collect IDs of nested-part components (i.e. components that
        // appear as nestedComponents of another component). These are NOT top-level
        // ELK nodes — they are drawn by the SVG renderer inside their parent box.
        // UmlConnectors whose BOTH endpoints resolve to nested-part node IDs are
        // "internal connectors" that must NOT become LayoutEdges — the SVG renderer
        // draws them with pure math inside the parent's local coordinate frame.
        // Also includes the parent component id for boundary ports
        // (e.g. "OrderService::api" → nodeId="OrderService", which IS a top-level
        // ELK node and must NOT be filtered).
        val nestedPartIds: Set<String> = collectNestedPartIds(diagram)

        // Note: boundary-to-boundary connectors (both endpoint nodeIds resolve to the
        // SAME top-level component, e.g. "OrderService::api1" → "OrderService::api2")
        // are excluded from ELK unconditionally — see the same-node guard below at the
        // UmlConnector filter. This covers both composite-structure parents (with nested
        // parts) and flat components (no nested parts), preventing spurious ELK self-edges.

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
                    // Skip UmlConnectors whose BOTH endpoint nodeIds are nested
                    // parts — these are "internal connectors" drawn by the SVG
                    // renderer inside the parent component's local frame.
                    // A boundary-port connector (e.g. "OrderService::api" → nodeId
                    // "OrderService") has at least one top-level node id, so it
                    // only qualifies as internal if BOTH resolved nodeIds are in
                    // the nested-part set.
                    if (element is UmlConnector) {
                        val endpoints = EndpointResolver.resolveWithPorts(element, componentPorts)
                        if (endpoints != null &&
                            (
                                endpoints.sourceNodeId in nestedPartIds ||
                                    endpoints.targetNodeId in nestedPartIds
                            )
                        ) {
                            // At least one endpoint is a nested part → internal connector,
                            // skip from ELK graph entirely.
                            // This covers both assembly connectors (part→part, both in
                            // nestedPartIds) and delegation connectors (boundary-port→part,
                            // only the target is a nested part). In either case the SVG
                            // renderer handles drawing inside the parent's local frame.
                        } else if (endpoints != null &&
                            endpoints.sourceNodeId == endpoints.targetNodeId
                        ) {
                            // Both endpoints resolve to the same node
                            // (e.g. "OrderService::api1" → "OrderService::api2",
                            // both resolving to nodeId "OrderService"). This covers:
                            //  • Flat components (no nested parts): boundary-to-boundary
                            //    connectors would produce a meaningless ELK self-edge and
                            //    are not drawn by the SVG renderer either; drop silently.
                            //  • Composite-structure parents (with nested parts): the SVG
                            //    renderer draws these boundary-to-boundary connectors inside
                            //    the parent box via drawInternalConnectors(); routing them
                            //    through ELK as well would cause a double-draw.
                            // In both cases: exclude from ELK unconditionally.
                        } else if (endpoints != null) {
                            edges.add(toEdge(element.id, endpoints))
                        }
                    } else {
                        val endpoints = EndpointResolver.resolveWithPorts(element, componentPorts)
                        if (endpoints != null) {
                            edges.add(toEdge(element.id, endpoints))
                        }
                    }
                }
                is UmlInteraction -> {
                    // SEQ: one LayoutNode per lifeline, height from message count. No edges —
                    // messages and fragments are rendered directly by KumlSvgRenderer.
                    val maxSeq = element.messages.maxOfOrNull { it.sequence } ?: 0
                    val rowCount = if (maxSeq < 1) 1 else maxSeq
                    // Each non-empty combined-fragment operand reserves one header
                    // band of extra vertical space above its first message (so the
                    // guard is never overpainted by that message's label — see
                    // FRAGMENT_HEADER_BAND in UmlSequenceSvg). Grow the lifeline
                    // height by the total so those bands are not clipped.
                    val msgIds = element.messages.mapTo(HashSet()) { it.id }
                    val operandBandCount =
                        element.fragments.sumOf { frag ->
                            frag.operands.count { op -> op.messageIds.any { it in msgIds } }
                        }
                    val nodeH =
                        Sysml2LayoutBridge.SEQ_LIFELINE_HEAD_HEIGHT +
                            (rowCount + 1) * Sysml2LayoutBridge.SEQ_MESSAGE_ROW_HEIGHT +
                            operandBandCount * Sysml2LayoutBridge.SEQ_FRAGMENT_HEADER_BAND +
                            Sysml2LayoutBridge.SEQ_LIFELINE_TAIL_PADDING
                    for (lifeline in element.lifelines) {
                        // Content-aware width: the header box must be at least wide enough
                        // for the lifeline's own name (single-line, no wrapping) — otherwise
                        // long names (e.g. "Hostsprachen-Compiler") overflow the box. Falls
                        // back to the SEQ_LIFELINE_WIDTH default for short names.
                        val nameWidth =
                            lifeline.name.length * UmlContentSizeProvider.TITLE_CHAR_PX +
                                UmlContentSizeProvider.BOX_H_PADDING
                        val lifelineWidth = maxOf(Sysml2LayoutBridge.SEQ_LIFELINE_WIDTH, nameWidth)
                        nodes.add(
                            LayoutNode(
                                id = NodeId(lifeline.id),
                                intrinsicSize = Size(lifelineWidth, nodeH),
                            ),
                        )
                    }
                }
                is UmlStateMachine -> {
                    // Create a group for the state machine frame so ELK encloses all vertices
                    val smGroupId = GroupId(element.id)
                    // layoutAsCompound = true so ELK treats the SM frame as a real
                    // compound node.  Without this flag, all vertex nodes (Draft,
                    // Confirmed, …) are placed flat at root and ResultMapper computes
                    // the SM-frame bounds only from those nodes — ignoring nested
                    // composite-state LayoutGroups (e.g. Processing with Picking +
                    // Packing side by side), which causes the SM frame to be too
                    // narrow and the composite state to overflow.
                    groups.add(
                        LayoutGroup(
                            id = smGroupId,
                            parent = null,
                            padding = STATE_MACHINE_GROUP_INSETS,
                            layoutAsCompound = true,
                        ),
                    )

                    // Collect vertices recursively.
                    // Composite states (substates.isNotEmpty()) become a nested LayoutGroup so
                    // ELK positions their substates inside the composite state's bounds.
                    // Simple states and pseudo-states remain flat LayoutNodes in their parent group.
                    fun collectVertices(
                        vertices: List<UmlVertex>,
                        parentGroupId: GroupId,
                    ) {
                        for (vertex in vertices) {
                            if (vertex is UmlState && vertex.substates.isNotEmpty()) {
                                // Composite state → dedicated LayoutGroup nested inside parent
                                val compositeGroupId = GroupId(vertex.id)
                                groups.add(
                                    LayoutGroup(
                                        id = compositeGroupId,
                                        parent = parentGroupId,
                                        padding = COMPOSITE_STATE_INSETS,
                                        layoutAsCompound = true,
                                    ),
                                )
                                collectVertices(vertex.substates, compositeGroupId)
                            } else {
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
                                        groupId = parentGroupId,
                                    ),
                                )
                            }
                        }
                    }
                    collectVertices(element.vertices, smGroupId)

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
                is UmlComment -> {
                    // V0.23.1 — UML note. Not a UmlNamedElement (no name/visibility),
                    // so it needs its own branch here instead of falling into the
                    // UmlNamedElement case below. Sized via UmlCommentLayout so wrapped
                    // body text fits; positioned freely by ELK like any other node.
                    // Its dashed anchor line(s) to annotated elements are separate
                    // UmlCommentLink relationships, handled by the UmlRelationship
                    // branch above like any other edge.
                    nodes.add(
                        LayoutNode(
                            id = NodeId(element.id),
                            intrinsicSize = sizeProvider.sizeOf(element.id, "UmlComment"),
                            hints = HintsReader.read(element.metadata),
                        ),
                    )
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
                    //
                    // Follow-up fix: ACTION/OBJECT boxes used to get the
                    // hardcoded ACTIVITY_ACTION_WIDTH × ACTIVITY_ACTION_HEIGHT
                    // (160×60) regardless of label length — long action names
                    // ran past the box edge because `renderUmlActivityNode`
                    // draws a single-line, non-wrapping `<text>`. ACTION/OBJECT
                    // now delegate to `sizeProvider.sizeOf(...)` so a
                    // content-aware provider (see `UmlContentSizeProvider`,
                    // which measures `element.name` analogous to its
                    // classifier-box heuristic) can grow the width to fit the
                    // label. PSEUDO/BAR kinds stay hardcoded — they're shape-
                    // constrained, not text-constrained.
                    //
                    // Caveat: callers that pass the plain `SizeProvider.constant()`
                    // (no activity-aware provider — e.g. `McpRenderPipeline`,
                    // `RenderingTools`, `RenderSmokeCheck`, the asciidoc/markdown
                    // doc pipelines) now get its generic 160×80 default for
                    // ACTION/OBJECT instead of the previous hardcoded 160×60 —
                    // a minor, intentional trade-off for routing all activity
                    // sizing through one seam. The primary render path (CLI,
                    // web, desktop, wasm playground) always passes
                    // `UmlContentSizeProvider`, so it gets accurate content-aware
                    // sizing rather than either fallback.
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
                            -> sizeProvider.sizeOf(element.id, "UmlActivityNode")
                        }
                    nodes.add(
                        LayoutNode(
                            id = NodeId(element.id),
                            intrinsicSize = size,
                            hints = HintsReader.read(element.metadata),
                        ),
                    )
                }
                is UmlNode -> {
                    // Deployment diagram node (node / executionEnvironment / device).
                    // If the node has nested children or artifacts it becomes a LayoutGroup
                    // (compound node) so ELK places children inside its bounds. Without
                    // children/artifacts it stays as a plain flat LayoutNode.
                    addDeploymentNode(
                        node = element,
                        parentGroupId = null,
                        nodes = nodes,
                        groups = groups,
                        sizeProvider = sizeProvider,
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

    /**
     * Registriert einen [UmlNode] (Deployment-Diagramm) als [LayoutNode] oder [LayoutGroup].
     *
     * - Kein Kind + kein Artefakt → flacher [LayoutNode] (Blattknoten im Graph).
     * - Kinder oder Artefakte vorhanden → [LayoutGroup] (Compound-Node); Kinder und
     *   Artefakte werden rekursiv in der Gruppe registriert.
     *
     * @param node       Der zu registrierende Deployment-Knoten.
     * @param parentGroupId  Übergeordnete Gruppe (null = Top-Level).
     * @param nodes      Ausgabeliste für [LayoutNode]-Einträge.
     * @param groups     Ausgabeliste für [LayoutGroup]-Einträge.
     * @param sizeProvider  Liefert die intrinsische Größe per Element.
     */
    private fun addDeploymentNode(
        node: UmlNode,
        parentGroupId: GroupId?,
        nodes: MutableList<LayoutNode>,
        groups: MutableList<LayoutGroup>,
        sizeProvider: SizeProvider,
    ) {
        if (node.children.isEmpty() && node.artifacts.isEmpty()) {
            // Leaf node — plain flat LayoutNode; no compound nesting required.
            nodes.add(
                LayoutNode(
                    id = NodeId(node.id),
                    intrinsicSize = sizeProvider.sizeOf(node.id, "UmlNode"),
                    hints = HintsReader.read(node.metadata),
                    groupId = parentGroupId,
                ),
            )
        } else {
            // Compound node — becomes a LayoutGroup so ELK positions children inside.
            val groupId = GroupId(node.id)
            groups.add(
                LayoutGroup(
                    id = groupId,
                    parent = parentGroupId,
                    padding = DEPLOYMENT_NODE_GROUP_INSETS,
                    layoutAsCompound = true,
                ),
            )
            // Nested child UmlNodes — handled recursively (supports arbitrary depth).
            for (child in node.children.filterIsInstance<UmlNode>()) {
                addDeploymentNode(
                    node = child,
                    parentGroupId = groupId,
                    nodes = nodes,
                    groups = groups,
                    sizeProvider = sizeProvider,
                )
            }
            // Artifacts directly inside this node — leaf LayoutNodes in the group.
            for (artifact in node.artifacts) {
                nodes.add(
                    LayoutNode(
                        id = NodeId(artifact.id),
                        intrinsicSize = DEPLOYMENT_ARTIFACT_SIZE,
                        hints = HintsReader.read(artifact.metadata),
                        groupId = groupId,
                    ),
                )
            }
        }
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

    /**
     * Collects the IDs of all components that appear as [UmlComponent.nestedComponents]
     * of another component in [diagram]. These are NOT top-level ELK layout nodes —
     * they are drawn by the SVG renderer inside their parent component's local frame.
     *
     * Used by [toLayoutGraph] to determine which [dev.kuml.uml.UmlConnector] instances
     * are "internal connectors" that must be excluded from the ELK layout graph.
     *
     * Walks the element tree recursively (nested inside nested is also collected).
     */
    private fun collectNestedPartIds(diagram: KumlDiagram): Set<String> {
        val result = mutableSetOf<String>()
        for (element in diagram.elements) {
            if (element is UmlComponent) collectNestedPartsFromComponent(element, result)
        }
        return result
    }

    private fun collectNestedPartsFromComponent(
        component: UmlComponent,
        out: MutableSet<String>,
    ) {
        for (nested in component.nestedComponents) {
            if (nested.id in out) continue // cycle guard: skip already-visited nodes
            out += nested.id
            collectNestedPartsFromComponent(nested, out)
        }
    }
}
