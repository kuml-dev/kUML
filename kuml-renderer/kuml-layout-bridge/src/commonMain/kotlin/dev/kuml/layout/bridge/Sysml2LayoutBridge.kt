package dev.kuml.layout.bridge

import dev.kuml.layout.EdgeHints
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.EndpointRef
import dev.kuml.layout.GroupId
import dev.kuml.layout.GroupLayout
import dev.kuml.layout.HeaderTextWidths
import dev.kuml.layout.Insets
import dev.kuml.layout.LayoutDirection
import dev.kuml.layout.LayoutEdge
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutGroup
import dev.kuml.layout.LayoutNode
import dev.kuml.layout.LayoutPort
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeHints
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.PortId
import dev.kuml.layout.PortSide
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.port.IbdObstacleRouter
import dev.kuml.layout.bridge.port.PortAttachment
import dev.kuml.layout.bridge.port.PortEdgeClamp
import dev.kuml.layout.bridge.port.PortFace
import dev.kuml.sysml2.ActDiagram
import dev.kuml.sysml2.ActionDefinition
import dev.kuml.sysml2.ActivityNodeKind
import dev.kuml.sysml2.ActivityPartitionDefinition
import dev.kuml.sysml2.ActorDefinition
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.BindingConnectorUsage
import dev.kuml.sysml2.CombinedFragmentUsage
import dev.kuml.sysml2.ConnectionUsage
import dev.kuml.sysml2.ConstraintDefinition
import dev.kuml.sysml2.ControlFlowUsage
import dev.kuml.sysml2.DurationConstraintUsage
import dev.kuml.sysml2.ExecutionSpecificationUsage
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.InteractionUseUsage
import dev.kuml.sysml2.LifelineDefinition
import dev.kuml.sysml2.MessageUsage
import dev.kuml.sysml2.ObjectFlowUsage
import dev.kuml.sysml2.ParDiagram
import dev.kuml.sysml2.PartDefinition
import dev.kuml.sysml2.PartUsage
import dev.kuml.sysml2.PortDirection
import dev.kuml.sysml2.ReqDiagram
import dev.kuml.sysml2.RequirementDefinition
import dev.kuml.sysml2.SeqDiagram
import dev.kuml.sysml2.StateDefinition
import dev.kuml.sysml2.StateKind
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.Sysml2Definition
import dev.kuml.sysml2.Sysml2LayoutFlow
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.TestCaseDefinition
import dev.kuml.sysml2.TimeObservationUsage
import dev.kuml.sysml2.TransitionUsage
import dev.kuml.sysml2.UcDiagram
import dev.kuml.sysml2.UseCaseDefinition
import dev.kuml.sysml2.constraint.Sysml2StmChecker
import dev.kuml.sysml2.edge.IbdEdgeAdapter
import dev.kuml.sysml2.edge.ReqEdgeAdapter
import dev.kuml.sysml2.effectivePortDirection
import dev.kuml.sysml2.projection.BddBoxGeometry
import dev.kuml.sysml2.projection.sysml2BddBoxContent
import dev.kuml.sysml2.projection.sysml2BddProjection
import dev.kuml.sysml2.projection.sysml2IbdProjection
import dev.kuml.sysml2.stateIndex
import dev.kuml.sysml2.usesStmStructure
import dev.kuml.sysml2.visibleStateIds

/**
 * Übersetzt eine SysML-2 Block Definition Diagram-Projektion eines
 * [Sysml2Model] in einen [LayoutGraph].
 *
 * V2.0.4 MVP-Scope (BDD):
 *  - Jede in [BdDiagram.elementIds] referenzierte [Sysml2Definition] wird ein
 *    [LayoutNode].
 *  - Jede `:>`-Spezialisierung (KermlSpecialization) zwischen zwei in der BDD
 *    sichtbaren Definitionen wird eine [LayoutEdge]. Composition-Edges aus
 *    `PartUsage` sind explizit V2.x — BDD zeigt klassischerweise die
 *    Strukturhierarchie über Vererbung; die Komposition gehört in IBD.
 *  - Keine Gruppen / Container in V2.0.4 — die Hierarchie wird komplett über
 *    Edges kodiert.
 *
 * Genau wie [UmlLayoutBridge] arbeitet die Bridge auf den IDs aus dem Modell.
 * Größen kommen vom übergebenen [SizeProvider]; die Default-Größe ist
 * großzügig, weil PartDefinitions Stereotyp + Name + Attribute + Ports + Subparts
 * in einer Vier-Sektions-Box brauchen.
 */
public object Sysml2LayoutBridge {
    /**
     * Übersetzt das gegebene BDD in einen [LayoutGraph].
     *
     * Definitionen, die das Diagramm referenziert aber nicht im Modell findet,
     * werden schweigend übersprungen — Konsistenz-Prüfung ist Aufgabe des
     * Validators, nicht der Bridge.
     *
     * @param model Container mit allen Definitionen + Usages.
     * @param diagram Die BDD-Projektion (`elementIds` selektiert die sichtbaren
     *   Definitionen).
     * @param sizeProvider Liefert die intrinsische Größe pro Definition. Default
     *   ist 220×140 — Platz für 3-Kompartment-Layout (Stereotyp/Header,
     *   Attribute, Ports).
     */
    public fun toLayoutGraph(
        model: Sysml2Model,
        diagram: BdDiagram,
        sizeProvider: SizeProvider = bddContentAwareSizeProvider(model = model, diagram = diagram),
    ): LayoutGraph {
        // Snapshot der sichtbaren Definitionen — ein Set für O(1)-Edge-Filter weiter unten.
        val visibleIds: Set<String> = diagram.elementIds.toSet()

        val nodes = mutableListOf<LayoutNode>()
        for (id in diagram.elementIds) {
            val def = model.definitions.firstOrNull { it.id == id } ?: continue
            nodes +=
                LayoutNode(
                    id = NodeId(def.id),
                    intrinsicSize = sizeProvider.sizeOf(elementId = def.id, elementKind = def::class.simpleName ?: "Sysml2Definition"),
                )
        }

        // Generalisations: jede Specialization, deren beide Endpunkte in der BDD sichtbar sind.
        // Edge-ID: `gen:<child>::<parent>` — deterministisch, kollisionsfrei für eindeutige
        // (specificId, generalId)-Paare.
        val edges = mutableListOf<LayoutEdge>()
        for (def in model.definitions) {
            if (def.id !in visibleIds) continue
            for (spec in def.specializations) {
                if (spec.specificId !in visibleIds || spec.generalId !in visibleIds) continue
                edges +=
                    LayoutEdge(
                        id = EdgeId("gen:${spec.specificId}::${spec.generalId}"),
                        source = EndpointRef(nodeId = NodeId(spec.specificId)),
                        target = EndpointRef(nodeId = NodeId(spec.generalId)),
                        hints = EdgeHints.NONE,
                    )
            }
        }

        // L6/L7: Kompositions-, Aggregations-, Assoziations- und Allokationskanten.
        // Owner → Teil, damit der Besitzer oben liegt; nur wenn beide Enden sichtbar sind.
        val projection = sysml2BddProjection(model = model, diagram = diagram)
        for (e in projection.edges) {
            if (e.sourceId !in visibleIds || e.targetId !in visibleIds) continue
            edges +=
                LayoutEdge(
                    id = EdgeId(e.id),
                    source = EndpointRef(nodeId = NodeId(e.sourceId)),
                    target = EndpointRef(nodeId = NodeId(e.targetId)),
                    hints =
                        if (e.kind == dev.kuml.sysml2.projection.BddEdgeKind.Allocation) {
                            EdgeHints(targetAnchorOffset = allocationRowOffset(model = model, projection = projection, edge = e))
                        } else {
                            EdgeHints.NONE
                        },
                )
        }

        return LayoutGraph(nodes = nodes, edges = edges).withLayoutSpec(diagram = diagram)
    }

    /**
     * Wave 2E-b: offset from the top of the target block at which an allocation edge docks - the row of the allocated
     * action in the `actions` compartment - or `null` when the target block shows no such action.
     */
    private fun allocationRowOffset(
        model: Sysml2Model,
        projection: dev.kuml.sysml2.projection.BddProjection,
        edge: dev.kuml.sysml2.projection.BddEdge,
    ): Float? {
        val target = model.definitions.firstOrNull { it.id == edge.targetId } ?: return null
        val source = model.definitions.firstOrNull { it.id == edge.sourceId } ?: return null
        val lines = projection.actionsByDef[target.id] ?: return null
        val actionUsages =
            model.usages.filterIsInstance<dev.kuml.sysml2.ActionUsage>().filter { it.id.startsWith("${target.id}::") }
        val usage =
            actionUsages.firstOrNull { it.definitionId == source.id }
                ?: actionUsages.firstOrNull { it.name.equals(source.name, ignoreCase = true) }
                ?: return null
        val index = lines.indexOf(usage.name).takeIf { it >= 0 } ?: return null
        val content = sysml2BddBoxContent(model = model, def = target, projection = projection)
        return dev.kuml.sysml2.projection
            .bddActionRowCenter(content = content, index = index)
    }

    /**
     * Default-Größe pro Definition. Großzügig gewählt, weil PartDefinitions
     * mit Attributen + Ports schnell hoch werden; eine spätere
     * `Sysml2SizeProvider`-Variante kann die Höhe content-aware messen.
     */
    public const val DEFAULT_WIDTH: Float = 220f
    public const val DEFAULT_HEIGHT: Float = 140f

    // ── Content-aware sizing constants ────────────────────────────────────────

    /** Height of the stereotype line (e.g. `«part def»`) in a BDD box. */
    public const val STEREOTYPE_LINE_H: Float = 16f

    /** Height of the name line in a BDD/REQ/STM box. */
    public const val NAME_LINE_H: Float = 18f

    /** Height per feature line in the BDD attribute compartment. */
    public const val FEATURE_LINE_H: Float = 14f

    /** Height per action line in an STM state box. */
    public const val ACTION_LINE_H: Float = 13f

    /** Gap above an `actions` / `signals` compartment (divider + spacing). */
    public const val EXTRA_COMPARTMENT_GAP: Float = 14f

    /** Vertical padding (top + bottom) inside a node box. */
    public const val BOX_V_PADDING: Float = 16f

    /** Height of one line of the IBD attribute compartment (PAL wave 2C-b2). */
    public const val IBD_ATTR_LINE_H: Float = 14f

    /** Vertical padding of the IBD attribute compartment (separator line plus gaps). */
    public const val IBD_ATTR_PAD: Float = 10f

    /** Estimated width per character of an attribute line. */
    public const val IBD_ATTR_CHAR_PX: Float = 6.2f

    /** Height the attribute compartment adds to a part box; `0` when it is empty. One extra line carries `... n more`. */
    public fun ibdAttributeCompartmentHeight(c: dev.kuml.sysml2.projection.IbdAttributeCompartment): Float {
        if (c.isEmpty) return 0f
        val lines = c.lines.size + if (c.hiddenCount > 0) 1 else 0
        return IBD_ATTR_PAD + lines * IBD_ATTR_LINE_H
    }

    /** Vertical gap before the feature/action compartment divider. */
    public const val DIVIDER_GAP: Float = 6f

    /** Height per word-wrapped text line in a REQ box. */
    public const val WRAP_LINE_H: Float = 13f

    /**
     * Legacy (pre-C1) max characters per wrapped line of a REQ text compartment.
     * Since wave C1 the wrapping lives solely in [Sysml2ReqCard] (shared by bridge and
     * SVG renderer); this constant is kept for API compatibility only.
     */
    public const val REQ_WRAP_CHARS_PER_LINE: Int = 25

    /**
     * Horizontal padding (left + right) reserved around a Use-Case ellipse's
     * name text (V2.x overflow fix). Wider than a rectangle's [IBD_H_PAD]
     * because text sitting near the ellipse's horizontal extremes visually
     * crowds the curved boundary — the ellipse only reaches full width at
     * its vertical center, so long names need extra breathing room on both
     * sides to avoid appearing to touch the edge.
     */
    public const val UC_ELLIPSE_H_PAD: Float = 30f

    /** Wave 2E-b: extension-point block of a use-case ellipse (shared by the layout and the renderer). */
    public const val UC_EXTENSION_LINE_H: Float = 13f
    public const val UC_EXTENSION_BLOCK_GAP: Float = 8f
    public const val UC_EXTENSION_POINT_MAX_CHARS: Int = 40
    public const val UC_EXTENSION_POINTS_MAX: Int = 6

    /**
     * Horizontal padding (left + right) reserved around an actor's name
     * label (V2.x overflow fix). Deliberately much smaller than
     * [UC_ELLIPSE_H_PAD] — an actor box is a plain rectangle with the name
     * centered directly below the stick figure
     * ([dev.kuml.io.svg.sysml2.renderSysml2Actor]), not a curved boundary
     * that needs extra breathing room.
     */
    public const val UC_ACTOR_NAME_H_PAD: Float = 4f

    /**
     * Default-Größe pro IBD-Box (V2.0.6). Kleiner als die BDD-Default-Größe,
     * weil IBD-Boxen nur `name : Type [mult]` zeigen — keine Compartments für
     * Attribute/Ports/Sub-Parts. Die `«part»`-Stereotyp-Zeile plus eine
     * Titelzeile passen bequem in 180×80.
     */
    public const val IBD_DEFAULT_WIDTH: Float = 180f
    public const val IBD_DEFAULT_HEIGHT: Float = 80f

    /**
     * Default-Größe für eine Actor-Stickfigur in einem UC-Diagramm (V2.0.7).
     * Schmal + hoch — Kopf + Körper + Beine + Name darunter brauchen vertikal
     * Platz, horizontal reichen ~60 px.
     */
    public const val UC_ACTOR_WIDTH: Float = 60f
    public const val UC_ACTOR_HEIGHT: Float = 100f

    /**
     * Default-Größe für eine Use-Case-Ellipse in einem UC-Diagramm (V2.0.7).
     * Breit + flach — UC-Namen sind häufig mehrere Worte (`BorrowBook`,
     * `PayLateFee`), passen aber in eine Zeile.
     */
    public const val UC_USECASE_WIDTH: Float = 160f
    public const val UC_USECASE_HEIGHT: Float = 70f

    /** Minimum ellipse width of a use case that carries a description (wave C1). */
    public const val UC_USECASE_WIDTH_WITH_DESCRIPTION: Float = 200f

    /**
     * Default-Größe für eine [RequirementDefinition]-Box in einem REQ-Diagramm
     * (V2.0.8). Höher als die BDD-Default-Größe, weil die Box drei Kompartimente
     * tragen muss (`«requirement»`-Stereotyp, Name + optionale `R-NNN ::`-
     * Präfix, und das eigentliche Anforderungstext-Kompartment mit ein bis
     * drei wortgewrappten Zeilen).
     */
    public const val REQ_DEFAULT_WIDTH: Float = 280f
    public const val REQ_DEFAULT_HEIGHT: Float = 120f

    /** Minimum width of an empty part def in a REQ diagram (Nacharbeit 49). */
    internal const val REQ_EMPTY_PART_MIN_WIDTH: Float = 100f

    /** Cap of the edge-driven height of an empty part def in a REQ diagram (Nacharbeit 49). */
    internal const val REQ_EMPTY_PART_MAX_HEIGHT: Float = 200f

    /** Number of attached edges up to which an empty part def keeps the compact box (Nacharbeit 49). */
    internal const val REQ_EMPTY_PART_COMPACT_EDGES: Int = 2

    /**
     * Default-Größe für eine reguläre [StateDefinition]-Box in einem
     * STM-Diagramm (V2.0.9). Spiegelt die IBD-Box-Größe — auch die STM-Box
     * trägt nur Name + optional ein, zwei Action-Zeilen, mehr Platz braucht
     * sie nicht. Composite-States (V2.x) werden eine größere Box brauchen,
     * weil sie geschachtelte STM-Diagramme enthalten.
     */
    public const val STM_STATE_WIDTH: Float = 180f
    public const val STM_STATE_HEIGHT: Float = 80f

    /**
     * Default-Größe für eine Pseudo-State-Bounding-Box in einem STM-Diagramm
     * (V2.0.9) — gilt sowohl für Initial (gefüllter Kreis) als auch für Final
     * (Donut). Klein gehalten, damit Pseudo-States visuell als Marker und
     * nicht als reguläre Zustände wahrgenommen werden. Der Renderer zeichnet
     * den Kreis bzw. Donut innerhalb dieser quadratischen Bounds zentriert.
     */
    public const val STM_PSEUDO_SIZE: Float = 24f

    /**
     * Zusätzliche Pixel pro ein-/ausgehender Transition, addiert auf der
     * Andock-Seite einer regulären STM-State-Box. Gleiche Heuristik wie
     * [UmlContentSizeProvider.CONNECTION_PUFFER_PX] (CLAUDE.md
     * "Renderer-Sizing-Heuristik (Knotengröße ∝ Anzahl Anschluss-Kanten)"):
     * mehr anliegende Transitionen → größere Andock-Seite → ELK darf die
     * Kanten-Endpunkte räumlich entzerren, damit `trigger [guard] / effect`-
     * Labels nicht aufeinander rutschen.
     *
     * 14 px liegt in der Mitte des in der Heuristik genannten 12–16-px-Bands
     * und entspricht ungefähr dem horizontalen Abstand, den ELK pro
     * Edge-Endpunkt am Knotenrand reserviert. Self-Transitionen zählen
     * zweifach (sie greifen an zwei Punkten derselben Box an).
     */
    public const val STM_CONNECTION_PUFFER_PX: Float = 14f

    /**
     * Obergrenze für den Anschluss-Puffer pro Seite einer STM-State-Box.
     * Bewusst niedriger als der UML-Default ([UmlContentSizeProvider
     * .CONNECTION_PUFFER_MAX_PX] = 200 px), weil STM-Boxen schmaler sind als
     * Class-/Component-Boxen und mehr als ~8 Transitionen pro State in
     * realistischen Modellen so gut wie nie vorkommen. 112 px ≈ 8 Edges,
     * danach gewinnt nur noch ELK über zusätzliche Layer-Sprünge.
     */
    public const val STM_CONNECTION_PUFFER_MAX_PX: Float = 112f

    /** Wave A1 — kind hints handed to the [SizeProvider] for the new STM pseudo-states. */
    public const val STM_HINT_CHOICE: String = "ChoicePseudoState"
    public const val STM_HINT_JUNCTION: String = "JunctionPseudoState"
    public const val STM_HINT_HISTORY: String = "HistoryPseudoState"
    public const val STM_HINT_FORK_JOIN: String = "ForkJoinBar"

    /** Wave A1 — pseudo-state shape sizes (px). */
    public const val STM_CHOICE_SIZE: Float = 28f
    public const val STM_JUNCTION_SIZE: Float = 12f
    public const val STM_HISTORY_SIZE: Float = 28f
    public const val STM_BAR_THICKNESS: Float = 6f
    public const val STM_BAR_BASE_LENGTH: Float = 64f
    public const val STM_BAR_PER_EDGE: Float = 14f
    public const val STM_BAR_MAX_LENGTH: Float = 160f

    /** Wave C1 — REQ group frame (tab strip on top) and UC subject frame (title strip on top). */
    internal val REQ_GROUP_PADDING: Insets = Insets(top = 36f, right = 16f, bottom = 16f, left = 16f)
    internal val UC_SUBJECT_PADDING: Insets = Insets(top = 46f, right = 24f, bottom = 24f, left = 24f)
    internal val REQ_GROUP_MIN_SIZE: Size = Size(width = 120f, height = 60f)
    internal val UC_SUBJECT_MIN_SIZE: Size = Size(width = 200f, height = 120f)

    /** Wave C1 limits (also enforced by the constraint checker; here as a DoS guard for plain renders). */
    public const val C1_MAX_GROUPS: Int = 64
    public const val C1_MAX_SUBJECTS: Int = 16
    public const val C1_MAX_GROUP_DEPTH: Int = 3

    /** Wave A1 — composite-state frame: padding (header strip on top) and minimum size. */
    internal val STM_COMPOSITE_PADDING: Insets = Insets(top = 36f, right = 16f, bottom = 16f, left = 16f)
    internal val STM_COMPOSITE_MIN_SIZE: Size = Size(width = 140f, height = 72f)
    internal val STM_REGION_PADDING: Insets = Insets(top = 20f, right = 12f, bottom = 12f, left = 12f)
    internal val STM_REGION_MIN_SIZE: Size = Size(width = 100f, height = 48f)

    /**
     * Id prefix of the invisible edges that chain the regions of one composite in declaration
     * order (ELK would otherwise place unconnected regions in arbitrary order). Renderers drop
     * edges with this prefix via [withoutStmOrderEdges].
     */
    public const val STM_ORDER_EDGE_PREFIX: String = "stm:order:"

    /** Removes the layout-only region-ordering edges (see [STM_ORDER_EDGE_PREFIX]) from [layoutResult]. */
    public fun withoutStmOrderEdges(layoutResult: LayoutResult): LayoutResult =
        if (layoutResult.edges.keys.none { it.value.startsWith(STM_ORDER_EDGE_PREFIX) }) {
            layoutResult
        } else {
            layoutResult.copy(edges = layoutResult.edges.filterKeys { !it.value.startsWith(STM_ORDER_EDGE_PREFIX) })
        }

    /** Height of one `entry/exit/do` action line in a composite state's header strip. */
    internal const val STM_COMPOSITE_ACTION_LINE_H: Float = 13f

    /** Width of a 13 px bold title character relative to an 11 px body character (header-band edge avoidance estimate). */
    internal const val STM_COMPOSITE_TITLE_CHAR_FACTOR: Float = 1.35f

    /**
     * Default-Breite einer regulären [ActionDefinition]-Box in einem
     * Activity-Diagramm (V2.0.10). Etwas breiter als die STM-State-Box,
     * weil Actions typischerweise eine zweite Zeile mit einem
     * Action-Body-Ausdruck tragen — der ist in der Praxis länger als der
     * State-Name.
     */
    public const val ACT_ACTION_WIDTH: Float = 160f

    /** Default-Höhe einer regulären [ActionDefinition]-Box (V2.0.10). */
    public const val ACT_ACTION_HEIGHT: Float = 60f

    /**
     * Default-Größe für eine Pseudo-Node-Bounding-Box in einem
     * Activity-Diagramm (V2.0.10) — gilt für Initial (gefüllter Kreis),
     * Final (Donut) und FlowFinal (Kreis mit X). Etwas größer als die
     * STM-Pseudo-Größe, weil die FlowFinal-Variante ein internes X-Muster
     * tragen muss und 24px dafür knapp werden.
     */
    public const val ACT_PSEUDO_SIZE: Float = 28f

    /**
     * Default-Breite einer Decision-/Merge-Raute in einem Activity-Diagramm
     * (V2.0.10). Quadratisch, weil der Renderer die Raute durch vier Punkte
     * (oben/rechts/unten/links der Bounds) zeichnet.
     *
     * V2.0.46: bewusst auf [ACT_PSEUDO_SIZE] (28 px) gesetzt, damit die
     * Raute visuell **nicht größer** ist als die kleinen Pseudo-Knoten
     * (Initial / Final / FlowFinal). Die Erfahrung aus dem Checkout-Beispiel
     * ([[03 Bereiche/kUML/Beispiele/17 UML Activity – Checkout Flow]]) zeigte
     * eine 50-px-Raute zwischen 28-px-Endpunkten als optisch dominant —
     * die Raute soll im Token-Fluss als kleiner Schaltpunkt wirken, nicht
     * als visuelles Schwergewicht. Bei mehreren ausgehenden Kanten mit
     * Guard-Labels darf die Raute bei Bedarf später content-aware wachsen
     * (V2.x-Polish, analog zur [UmlContentSizeProvider]-Heuristik).
     */
    public const val ACT_DIAMOND_WIDTH: Float = ACT_PSEUDO_SIZE

    /** Default-Höhe einer Decision-/Merge-Raute (V2.0.10, V2.0.46 = [ACT_PSEUDO_SIZE]). */
    public const val ACT_DIAMOND_HEIGHT: Float = ACT_PSEUDO_SIZE

    /**
     * Default-Breite einer Fork-/Join-Synchronisations-Bar in einem
     * Activity-Diagramm (V2.0.10). Die Bridge gibt eine horizontale Bar als
     * Default; die Layout-Engine kann die Orientierung später drehen,
     * wenn das Routing das nahelegt.
     */
    public const val ACT_BAR_WIDTH: Float = 120f

    /** Wave C2b — top padding of a swimlane: clears the 24 px header band plus air for the first row. */
    public const val ACT_PARTITION_TOP_PADDING: Float = 36f

    /** Wave C2b — minimum spacing (px) of edge attachment points on a fork / join bar. */
    public const val ACT_BAR_ANCHOR_GAP: Float = 16f

    /** Default-Höhe einer Fork-/Join-Synchronisations-Bar (V2.0.10). */
    public const val ACT_BAR_HEIGHT: Float = 10f

    /** Wave C2 — send-signal / accept-event default width. */
    public const val ACT_SIGNAL_WIDTH: Float = 110f

    /** Wave C2 — send-signal / accept-event default height. */
    public const val ACT_SIGNAL_HEIGHT: Float = 40f

    /** Wave C2 — accept-time-event hourglass symbol width (the label sits below, see [ACT_TIME_EVENT_LABEL_HEIGHT]). */
    public const val ACT_TIME_EVENT_W: Float = 20f

    /** Wave C2 — accept-time-event hourglass symbol height. */
    public const val ACT_TIME_EVENT_H: Float = 28f

    /** Wave C2 — vertical room under the hourglass for its label (gap plus 11 px text). */
    public const val ACT_TIME_EVENT_LABEL_HEIGHT: Float = 18f

    /** Wave C2 — maximum width of an accept-time-event node (label clipped beyond). */
    public const val ACT_TIME_EVENT_MAX_WIDTH: Float = 120f

    /** Wave C2 — extra width of a call-behavior action (room for the rake symbol). */
    public const val ACT_CALL_FORK_EXTRA_W: Float = 14f

    /** Wave C2 — growth of an action box per pin on an explicitly chosen side. */
    public const val ACT_PIN_GROWTH: Float = 18f

    /**
     * Default-Breite einer [ConstraintDefinition]-Box in einem
     * Parametric-Diagramm (V2.0.12). Etwas breiter als die BDD-Default-Größe,
     * weil eine Constraint-Box drei Kompartimente trägt — `«constraint»`-
     * Stereotyp + Name, Expression-Body (typischerweise einzeilige
     * Gleichung), Parameterliste (eine Zeile pro Parameter mit `«in»`/`«out»`/
     * `«inout»`-Stereotyp-Präfix). Die Breite muss Gleichungen wie `F = m * a`
     * und Parameterzeilen wie `«in» m : Mass` bequem aufnehmen.
     */
    public const val PAR_CONSTRAINT_WIDTH: Float = 220f

    /**
     * Default-Höhe einer [ConstraintDefinition]-Box in einem
     * Parametric-Diagramm (V2.0.12). Reserviert vertikalen Platz für die drei
     * Kompartimente — Stereotyp/Name (~30px), Expression-Body (~20px),
     * Parameterliste mit ein bis drei Parametern (~50px). Größere Parameter-
     * Listen werden vom Renderer abgeschnitten; eine content-aware Höhe ist
     * V2.x-Polish.
     */
    public const val PAR_CONSTRAINT_HEIGHT: Float = 100f

    /**
     * Default-Breite einer Lifeline-Box in einem Sequence-Diagramm (V2.0.11).
     *
     * Schmal — der Lifeline-Kopf trägt nur das `«lifeline»`-Stereotyp und
     * den Namen; die eigentliche vertikale Zeit-Achse darunter braucht keine
     * Breite. Eine Breite knapp über der eines IBD-Boxes reicht für die
     * meisten Namen.
     */
    public const val SEQ_LIFELINE_WIDTH: Float = 140f

    /**
     * Default-Höhe des Lifeline-Kopfes — der Box-Anteil ganz oben auf der
     * Lifeline (V2.0.11). Aufnahmebereich für die Stereotyp-Zeile +
     * Namenszeile.
     */
    public const val SEQ_LIFELINE_HEAD_HEIGHT: Float = 40f

    /**
     * Default-Vertikalabstand pro Nachricht in einem Sequence-Diagramm
     * (V2.0.11). Die Bridge berechnet die Gesamthöhe einer Lifeline als
     * `LIFELINE_HEAD_HEIGHT + maxSeqNo * MESSAGE_ROW_HEIGHT +
     * LIFELINE_TAIL_PADDING`, damit ELK genug vertikalen Platz für alle
     * Nachrichten reserviert. Der Renderer nutzt denselben Wert beim
     * Zeichnen der horizontalen Pfeile.
     */
    public const val SEQ_MESSAGE_ROW_HEIGHT: Float = 32f

    /**
     * Default-Padding unterhalb der letzten Nachricht in einem
     * Sequence-Diagramm (V2.0.11). Sorgt für visuellen Atemraum am unteren
     * Ende der gestrichelten Zeit-Achse — eine Lifeline endet nicht direkt
     * unter dem letzten Pfeil.
     */
    public const val SEQ_LIFELINE_TAIL_PADDING: Float = 40f

    /** Wave 2F: reserve per fragment for the row gap after it (px). */
    public const val SEQ_FRAGMENT_END_GAP: Float = 16f

    /**
     * Zusätzlicher vertikaler Freiraum pro Combined-Fragment-Operand, der über
     * dessen erster Nachricht reserviert wird, damit der Operand-Guard (bzw. das
     * Fragment-Pentagon) nicht vom Label der ersten Nachricht überdeckt wird.
     *
     * Die Gesamthöhe einer Lifeline wächst um `nichtLeereOperandenAnzahl *
     * SEQ_FRAGMENT_HEADER_BAND`, damit die eingefügten Bänder nicht abgeschnitten
     * werden. Dieser Wert MUSS mit `FRAGMENT_HEADER_BAND` in `UmlSequenceSvg`
     * bzw. dem entsprechenden Wert in `Sysml2SequenceSvg` (kuml-io-svg) synchron
     * bleiben.
     */
    public const val SEQ_FRAGMENT_HEADER_BAND: Float = 24f

    /** C3: a `ref` (interaction use) frame is 48 px high, i.e. this much more than one message row. */
    public const val SEQ_REF_EXTRA_HEIGHT: Float = 0f

    /**
     * Content-aware [SizeProvider] for BDD nodes. Computes node height from
     * the number of features and node **width** from the longest text line
     * (stereotype / name / feature-body), so that long German compound names
     * like `Fusions- und Verarbeitungseinheit` no longer overflow the box.
     *
     * Width formula (mirrors [ibdContentAwareSizeProvider]):
     *   `max(stereoW, nameW, bodyMax) + 2 × IBD_H_PAD`, floored at [DEFAULT_WIDTH].
     *
     * Feature-body lines are formatted as `"name : Type [mult]"` — identical to
     * [dev.kuml.io.svg.sysml2.formatBdd] in the SVG renderer so the estimate
     * matches the actual rendered text.
     */
    public fun bddContentAwareSizeProvider(model: Sysml2Model): SizeProvider = bddContentAwareSizeProvider(model = model, diagram = null)

    /**
     * Wie [bddContentAwareSizeProvider] (ein Argument), aber diagrammbewusst: Zeilen gezeichneter
     * Kompositions-/Aggregationskanten entfallen, die Kompartments `actions` und `signals` zählen mit.
     * Nutzt dieselben Zeilenlisten wie der SVG-Renderer.
     */
    public fun bddContentAwareSizeProvider(
        model: Sysml2Model,
        diagram: BdDiagram?,
    ): SizeProvider {
        val projection = diagram?.let { sysml2BddProjection(model = model, diagram = it) }
        // Wave 2E-b: a block grows with the number of edges that meet it (14 px per edge beyond two, at most 112 px),
        // across the flow so that the end points of the edges are spread apart.
        val horizontalFlow =
            diagram?.layout?.direction.let {
                it == dev.kuml.core.model.layout.LayoutFlow.Right || it == dev.kuml.core.model.layout.LayoutFlow.Left
            }
        val edgeCount: Map<String, Int> =
            if (diagram == null || projection == null) {
                emptyMap()
            } else {
                val visible = diagram.elementIds.toSet()
                val counts = HashMap<String, Int>()
                for (e in projection.edges) {
                    if (e.sourceId !in visible || e.targetId !in visible) continue
                    counts[e.sourceId] = (counts[e.sourceId] ?: 0) + 1
                    counts[e.targetId] = (counts[e.targetId] ?: 0) + 1
                }
                for (d in model.definitions) {
                    if (d.id !in visible) continue
                    for (sp in d.specializations) {
                        if (sp.specificId !in visible || sp.generalId !in visible) continue
                        counts[sp.specificId] = (counts[sp.specificId] ?: 0) + 1
                        counts[sp.generalId] = (counts[sp.generalId] ?: 0) + 1
                    }
                }
                counts
            }
        return SizeProvider { id, _ ->
            val def = model.definitions.firstOrNull { it.id == id }
            val content = def?.let { sysml2BddBoxContent(model = model, def = it, projection = projection) }
            val lines = content?.featureLines?.size ?: 0
            var h =
                STEREOTYPE_LINE_H + NAME_LINE_H +
                    (if (lines > 0) DIVIDER_GAP + lines * FEATURE_LINE_H else 0f) +
                    BOX_V_PADDING
            val actions = content?.actions ?: emptyList()
            val flows = content?.flows ?: emptyList()
            if (actions.isNotEmpty()) h += EXTRA_COMPARTMENT_GAP + (actions.size + 1) * FEATURE_LINE_H
            if (flows.isNotEmpty()) h += EXTRA_COMPARTMENT_GAP + (flows.size + 1) * FEATURE_LINE_H
            val compartments = content?.compartments ?: emptyList()
            for (c in compartments) h += EXTRA_COMPARTMENT_GAP + (c.lines.size + 1) * FEATURE_LINE_H
            val stereoW =
                when (def) {
                    is dev.kuml.sysml2.EnumerationDefinition -> "«enumeration»"
                    is dev.kuml.sysml2.ActionDefinition -> "«action def»"
                    is dev.kuml.sysml2.AttributeDefinition -> "«attribute def»"
                    is dev.kuml.sysml2.PortDefinition -> "«port def»"
                    is dev.kuml.sysml2.ConnectionDefinition -> "«connection def»"
                    is dev.kuml.sysml2.CalculationDefinition -> "«calc def»"
                    is dev.kuml.sysml2.AnalysisCaseDefinition -> "«analysis def»"
                    else -> "«part def»"
                }.length * IBD_STEREO_CHAR_PX
            val nameW = (def?.name?.length ?: 0) * IBD_TITLE_CHAR_PX
            val bodyMax =
                ((content?.featureLines ?: emptyList()) + actions + flows).maxOfOrNull { it.length * BDD_BODY_CHAR_PX } ?: 0f
            val compartmentMax =
                compartments
                    .flatMap { c ->
                        (listOf(c.header) + c.lines).map { it.length * (if (c.monospace) BDD_MONO_CHAR_PX else BDD_BODY_CHAR_PX) }
                    }.maxOrNull() ?: 0f
            val contentW = maxOf(stereoW, nameW, bodyMax, compartmentMax) + 2 * IBD_H_PAD
            val puffer = minOf(BDD_EDGE_PUFFER_MAX_PX, BDD_EDGE_PUFFER_PX * maxOf(0, (edgeCount[id] ?: 0) - 2))
            Size(
                width = maxOf(contentW, DEFAULT_WIDTH) + if (horizontalFlow) 0f else puffer,
                height = maxOf(h, 70f) + if (horizontalFlow) puffer else 0f,
            )
        }
    }

    /** Wave 2E-b: extra box size per edge beyond two that meet a BDD block (see [bddContentAwareSizeProvider]). */
    public const val BDD_EDGE_PUFFER_PX: Float = 14f

    /** Upper bound of the edge puffer of a BDD block. */
    public const val BDD_EDGE_PUFFER_MAX_PX: Float = 112f

    /**
     * Avg width of a 14pt bold sans-serif char (incl. ~10% slack) — used to
     * estimate the visual width of the IBD title line `name : Type [mult]`.
     * Matches the value in [C4ContentSizeProvider.TITLE_CHAR_PX].
     */
    public const val IBD_TITLE_CHAR_PX: Float = 8.4f

    /**
     * Avg width of a 10pt italic sans-serif char (stereotype label `«part»`).
     */
    public const val IBD_STEREO_CHAR_PX: Float = 5.6f

    /** Horizontal padding (left + right) inside an IBD box. */
    public const val IBD_H_PAD: Float = 16f

    /**
     * Avg width of an 11pt regular sans-serif char — BDD feature compartment
     * lines (`kuml-body` CSS class). Slightly wider than [IBD_STEREO_CHAR_PX].
     */
    public const val BDD_BODY_CHAR_PX: Float = 6.6f

    /** Estimated width per character of a monospaced line in a BDD box (calculation expressions, wave 2I-b). */
    public const val BDD_MONO_CHAR_PX: Float = 7.4f

    /**
     * Content-aware [SizeProvider] for IBD nodes (V2.0.6 → V2.x).
     *
     * Height: stereotype + name line + vertical padding — analog zu V2.0.6.
     * Breite: wächst mit der Label-Länge der Part-Usage (`name : Type [mult]`),
     * gedeckelt auf untere Schranke [IBD_DEFAULT_WIDTH]. Ohne diese Erweiterung
     * laufen lange Klartextlabels wie `electricMotor : ElectricMotor` oder
     * `iceEngine : InternalCombustionEngine` über den Rand der Box hinaus,
     * weil der SVG-Renderer den Text horizontal zentriert und nicht clippt.
     *
     * Wird kein [model] übergeben, fällt der Provider auf die V2.0.6-Default-
     * Breite zurück.
     */
    public fun ibdContentAwareSizeProvider(model: Sysml2Model? = null): SizeProvider =
        ibdContentAwareSizeProvider(model = model, diagram = null, layoutDirection = LayoutDirection.TopToBottom)

    /**
     * Content-aware [SizeProvider] for IBD nodes, **with edge-fan awareness**
     * (mirrors [stmContentAwareSizeProvider]'s two-arg overload — see its
     * KDoc for the general "box grows with connected-edge count, direction
     * follows layout direction" heuristic, documented project-wide in
     * CLAUDE.md's "Renderer-Sizing-Heuristik").
     *
     * IBD boxes commonly have several port connections (e.g. a `BankUsers`
     * hub part with 20+ connectors) — without this puffer, connector
     * endpoints on a box's edge cram together and edge labels overlap,
     * exactly the symptom the heuristic exists to prevent everywhere else
     * (UML classes, STM states). The single-arg overload never had this axis;
     * this overload adds it without changing the single-arg one's behaviour
     * (which still delegates here with `diagram = null` → zero puffer).
     *
     * Re-derives the visible part-usage id set independently of
     * [toLayoutGraph]'s own resolution — same duplication precedent as
     * [stmContentAwareSizeProvider], which re-counts transitions rather than
     * sharing [toLayoutGraph]'s edge-building loop.
     */
    public fun ibdContentAwareSizeProvider(
        model: Sysml2Model?,
        diagram: IbdDiagram?,
        layoutDirection: LayoutDirection = LayoutDirection.TopToBottom,
    ): SizeProvider {
        val connectionsById: Map<String, Int> =
            if (model == null || diagram == null) {
                emptyMap()
            } else {
                val p = sysml2IbdProjection(model = model, diagram = diagram)
                buildMap<String, Int> {
                    val ends =
                        p.connections.flatMap { listOf(it.source, it.target) } +
                            p.ownLineFlows.flatMap { listOf(it.source, it.target) } +
                            p.bindings.flatMap { listOf(it.source, it.target) }
                    for (e in ends) {
                        val n = e.nodeId ?: continue
                        put(n, (get(n) ?: 0) + 1)
                    }
                }
            }

        // PAL 2C-b1: width a box needs so that its ports keep their label-dependent pitch (worst case: the ports are
        // spread over the two horizontal sides), capped like the connection puffer.
        val portWidthNeeded: Map<String, Float> =
            if (model == null || diagram == null) {
                emptyMap()
            } else {
                val p = sysml2IbdProjection(model = model, diagram = diagram)
                val labels = ibdPartPortLabels(model = model, wires = ibdWires(p))
                labels.entries.associate { (node, ports) ->
                    val perSide = (ports.size + 1) / 2
                    val pitch = ports.values.maxOfOrNull { ibdPortPitchHorizontal(labelWidth = estimateLabelSize(text = it).width) } ?: 0f
                    node.value to 2f * (IBD_PORT_SIZE_PX / 2f + 8f) + (perSide - 1) * pitch
                }
            }

        val attributes: Map<String, dev.kuml.sysml2.projection.IbdAttributeCompartment> =
            if (model == null || diagram == null || !diagram.showAttributes) {
                emptyMap()
            } else {
                model.usages.filterIsInstance<PartUsage>().associate {
                    it.id to
                        dev.kuml.sysml2.projection
                            .ibdPartAttributes(model = model, usage = it)
                }
            }

        // PAL 2C-b2b: parts nested inside a visible part box grow that box (box in box).
        val nested: Map<String, List<IbdNesting.Node>> =
            if (model == null || diagram == null) {
                emptyMap()
            } else {
                IbdNesting.treeOf(model = model, diagram = diagram, projection = sysml2IbdProjection(model = model, diagram = diagram))
            }

        return SizeProvider { id, _ ->
            val compartment = attributes[id]
            val h = STEREOTYPE_LINE_H + NAME_LINE_H + BOX_V_PADDING + (compartment?.let { ibdAttributeCompartmentHeight(it) } ?: 0f)
            val baseW =
                if (model == null) {
                    IBD_DEFAULT_WIDTH
                } else {
                    // The node-id IS the part-usage qualifiedName (DSL-Konvention).
                    val usage = model.usages.filterIsInstance<PartUsage>().firstOrNull { it.id == id }
                    val titleLine =
                        if (usage != null) {
                            val multSpec = usage.multiplicity.toSpecForm()
                            val multSuffix = if (multSpec == "1") "" else " [$multSpec]"
                            "${usage.name} : ${usage.definitionId}$multSuffix"
                        } else {
                            ""
                        }
                    val stereoW = "«part»".length * IBD_STEREO_CHAR_PX
                    val titleW = titleLine.length * IBD_TITLE_CHAR_PX
                    val attrW =
                        compartment?.lines?.maxOfOrNull { it.text.length * IBD_ATTR_CHAR_PX } ?: 0f
                    val contentW = maxOf(stereoW, titleW, attrW) + 2 * IBD_H_PAD
                    maxOf(contentW, IBD_DEFAULT_WIDTH)
                }
            val wPorts = minOf(maxOf(baseW, portWidthNeeded[id] ?: 0f), baseW + IBD_PORT_PITCH_GROWTH_CAP_PX)
            val area = nested[id]?.let { IbdNesting.areaSize(roots = it, topArea = h) }
            val w = maxOf(wPorts, area?.width ?: 0f)
            val boxH = area?.height ?: maxOf(h, 50f)
            val (wExtra, hExtra) =
                stmConnectionPuffer(transitionCount = connectionsById[id] ?: 0, layoutDirection = layoutDirection)
            dev.kuml.layout.Size(width = w + wExtra, height = boxH + hExtra)
        }
    }

    /**
     * Content-aware [SizeProvider] for STM nodes. Pseudo-states keep the
     * fixed square size; regular states grow with their action count.
     *
     * Diese Form ist die schlanke Variante ohne Diagramm-Kontext: sie kennt
     * weder die sichtbaren Transitionen noch die Layout-Direction, daher
     * **kein Anschluss-Puffer**. Für die "Knotengröße ∝ Anzahl Anschluss-
     * Kanten"-Heuristik (CLAUDE.md, Renderer-Sizing-Heuristik vom 2026-06-14)
     * existiert die Überladung [stmContentAwareSizeProvider] mit zusätzlichem
     * `diagram` und `layoutDirection` — die [toLayoutGraph]-Default-Route
     * verwendet jene.
     */
    public fun stmContentAwareSizeProvider(model: Sysml2Model): SizeProvider =
        stmContentAwareSizeProvider(model = model, diagram = null, layoutDirection = LayoutDirection.TopToBottom)

    /**
     * Content-aware [SizeProvider] for STM nodes, **with edge-fan awareness**.
     *
     * Pseudo-states (Initial/Final) keep the fixed square size unconditionally.
     * Regular [StateDefinition] boxes grow along three axes:
     *
     *  1. **Content axis (height)** — height tracks the number of `entry`/
     *     `exit`/`do` action slots (`NAME_LINE_H + n × ACTION_LINE_H +
     *     padding`). Identical to the single-argument overload.
     *  2. **Content axis (width)** — width grows to fit the longest of the
     *     state name and each `entry`/`exit`/`do` action line, formatted
     *     exactly as `Sysml2StateSvg.renderRegularState` renders them
     *     (`"entry / $action"` etc.), estimated via [IBD_TITLE_CHAR_PX]/
     *     [BDD_BODY_CHAR_PX] — the same char-width constants already
     *     calibrated for the `.kuml-title`/`.kuml-body` CSS classes STM boxes
     *     actually render with, so the estimate matches the real font/size.
     *     Bugfix, mirrors [bddContentAwareSizeProvider]'s identical "grow with
     *     the longest line" fix: action text is a single unwrapped `<text>`
     *     line with no width clamp on the SVG side, so before this fix a
     *     state box stayed at the fixed [STM_STATE_WIDTH] regardless of how
     *     long its action text was — any action string longer than roughly
     *     STM_STATE_WIDTH / BDD_BODY_CHAR_PX chars overflowed the box outline
     *     (observed: a Lapis-Cloud README diagram with full-sentence
     *     `entryAction`/`doAction` strings, 2026-08-11).
     *  3. **Fan axis** — when [diagram] is non-null, the provider pre-counts
     *     transitions whose source *or* target is the state (self-loops count
     *     twice) and adds [STM_CONNECTION_PUFFER_PX] per anliegender Kante on
     *     the side(s) where ELK is most likely to dock edges, capped at
     *     [STM_CONNECTION_PUFFER_MAX_PX]:
     *      - [LayoutDirection.TopToBottom]/[LayoutDirection.BottomToTop] →
     *        Edges andocken oben/unten → Puffer in die **Breite**.
     *      - [LayoutDirection.LeftToRight]/[LayoutDirection.RightToLeft] →
     *        Edges andocken links/rechts → Puffer in die **Höhe**.
     *
     * Symptom ohne Fan-Puffer (siehe Vault-Note `04 SysML 2 STM – Traffic
     * Light`, 2026-06-15): die `Red`-Box des Traffic-Light-Beispiels hat vier
     * anliegende Kanten (`Initial→Red`, `Red→Green`, `Yellow→Red`,
     * `Red→Off`); ohne Fan-Puffer reserviert ELK nur den Default-Andockraum,
     * und die unteren drei Kantenlabels (`timer60s`, `timer5s`, `powerOff
     * [!emergency] / shutdownLights()`) stapeln sich auf derselben
     * Pixel-Spur, wobei der `timer5s`-Pfeil zusätzlich den `powerOff`-Pfeil
     * kreuzt.
     *
     * @param model Container mit Definitionen + Usages — liefert die Action-
     *   Strings für die Höhenberechnung.
     * @param diagram Optional: das STM-Diagramm, dessen `elementIds` die
     *   sichtbaren States definieren. Nur sichtbare Transitionen (beide
     *   Endpunkte sichtbar) zählen in den Fan-Puffer. `null` schaltet den
     *   Fan-Puffer ab — Verhalten dann identisch mit der Single-Arg-
     *   Überladung.
     * @param layoutDirection Andockseite der Edges am Knotenrand. Default
     *   [LayoutDirection.TopToBottom] — die Standardrichtung für STM.
     */
    public fun stmContentAwareSizeProvider(
        model: Sysml2Model,
        diagram: StmDiagram?,
        layoutDirection: LayoutDirection = LayoutDirection.TopToBottom,
    ): SizeProvider {
        // Pre-count visible transitions per state — self-loops add two
        // (both endpoints dock on the same box). Edges whose endpoints are
        // not both in the visible state set are ignored, matching the
        // skip-silently policy in `toLayoutGraph`.
        val connectionsById: Map<String, Int> =
            if (diagram == null) {
                emptyMap()
            } else {
                val visibleStateIds: Set<String> =
                    diagram
                        .visibleStateIds(model)
                        .mapNotNull { id ->
                            (model.definitions.firstOrNull { it.id == id } as? StateDefinition)?.id
                        }.toSet()
                buildMap<String, Int> {
                    for (t in model.usages.filterIsInstance<TransitionUsage>()) {
                        if (t.sourceStateId !in visibleStateIds ||
                            t.targetStateId !in visibleStateIds
                        ) {
                            continue
                        }
                        // Self-loop → bump twice so the box grows enough to
                        // host both endpoints on the same side.
                        put(t.sourceStateId, (get(t.sourceStateId) ?: 0) + 1)
                        put(t.targetStateId, (get(t.targetStateId) ?: 0) + 1)
                    }
                }
            }

        return SizeProvider { id, kindHint ->
            when {
                // Wave A1 pseudo-state kinds: fixed shapes, no fan puffer (checked before the
                // generic "Pseudo" rule below, which would give them the 24 px initial size).
                kindHint == STM_HINT_CHOICE -> dev.kuml.layout.Size(width = STM_CHOICE_SIZE, height = STM_CHOICE_SIZE)
                kindHint == STM_HINT_JUNCTION -> dev.kuml.layout.Size(width = STM_JUNCTION_SIZE, height = STM_JUNCTION_SIZE)
                kindHint == STM_HINT_HISTORY -> dev.kuml.layout.Size(width = STM_HISTORY_SIZE, height = STM_HISTORY_SIZE)
                kindHint == STM_HINT_FORK_JOIN ->
                    stmForkJoinBarSize(
                        transitionCount = connectionsById[id] ?: 0,
                        layoutDirection = layoutDirection,
                    )
                kindHint.contains("Pseudo") ||
                    kindHint.contains("Initial") ||
                    kindHint.contains("Final") ->
                    dev.kuml.layout.Size(width = STM_PSEUDO_SIZE, height = STM_PSEUDO_SIZE)
                else -> {
                    val def = model.definitions.firstOrNull { it.id == id } as? StateDefinition
                    val compartment =
                        StateActionCompartment.lines(
                            invariant = def?.invariant,
                            entry = def?.entryAction,
                            doActivity = def?.doAction,
                            exit = def?.exitAction,
                        )
                    val baseH = NAME_LINE_H + StateActionCompartment.height(compartment) + BOX_V_PADDING
                    val (wExtra, hExtra) =
                        stmConnectionPuffer(
                            transitionCount = connectionsById[id] ?: 0,
                            layoutDirection = layoutDirection,
                        )
                    // Bugfix: width previously ignored entry/exit/do action text entirely, so a
                    // long action string (e.g. "entry / LiveKit CreateRoom via server-internal
                    // admin token") overflowed the fixed STM_STATE_WIDTH -- Sysml2StateSvg emits
                    // each action as ONE unwrapped "entry / $action" line, never clipped or
                    // wrapped, matching bddContentAwareSizeProvider's own "grow with the longest
                    // line" fix for the identical class of BDD-box overflow. IBD_TITLE_CHAR_PX/
                    // BDD_BODY_CHAR_PX are already calibrated for exactly the `.kuml-title`/
                    // `.kuml-body` CSS classes STM boxes render with -- no new constants needed.
                    val nameW = (def?.name?.length ?: 0) * IBD_TITLE_CHAR_PX
                    val actionMaxW = (compartment.maxOfOrNull { it.length * BDD_BODY_CHAR_PX } ?: 0f)
                    val contentW = maxOf(nameW, actionMaxW) + 2 * IBD_H_PAD
                    dev.kuml.layout.Size(
                        width = maxOf(STM_STATE_WIDTH, contentW) + wExtra,
                        height = maxOf(baseH, 44f) + hExtra,
                    )
                }
            }
        }
    }

    /**
     * Size of a fork/join bar: [STM_BAR_THICKNESS] thick, perpendicular to the layout
     * direction (horizontal for top-to-bottom layouts), [STM_BAR_BASE_LENGTH] long plus
     * [STM_BAR_PER_EDGE] for every attached transition beyond two, capped at [STM_BAR_MAX_LENGTH].
     */
    internal fun stmForkJoinBarSize(
        transitionCount: Int,
        layoutDirection: LayoutDirection,
    ): dev.kuml.layout.Size {
        val length =
            (STM_BAR_BASE_LENGTH + STM_BAR_PER_EDGE * maxOf(0, transitionCount - 2)).coerceAtMost(STM_BAR_MAX_LENGTH)
        return when (layoutDirection) {
            LayoutDirection.TopToBottom,
            LayoutDirection.BottomToTop,
            -> dev.kuml.layout.Size(width = length, height = STM_BAR_THICKNESS)
            LayoutDirection.LeftToRight,
            LayoutDirection.RightToLeft,
            -> dev.kuml.layout.Size(width = STM_BAR_THICKNESS, height = length)
        }
    }

    /** The flow direction of [diagram] (its `layout { direction }`, default top to bottom): the connection puffer grows across it. */
    private fun stmFlowDirectionOf(diagram: StmDiagram): LayoutDirection =
        when (diagram.layout?.direction) {
            dev.kuml.core.model.layout.LayoutFlow.Right -> LayoutDirection.LeftToRight
            dev.kuml.core.model.layout.LayoutFlow.Left -> LayoutDirection.RightToLeft
            dev.kuml.core.model.layout.LayoutFlow.Up -> LayoutDirection.BottomToTop
            else -> LayoutDirection.TopToBottom
        }

    /**
     * Anschluss-Puffer in px für eine STM-State-Box mit [transitionCount]
     * anliegenden Transitionen. Die Wachstumsrichtung folgt der
     * Layout-Direction (TopToBottom → Breite, LeftToRight → Höhe), analog
     * zu [UmlContentSizeProvider.connectionPuffer]. Returns
     * `Pair(widthExtra, heightExtra)`.
     */

    private fun stmConnectionPuffer(
        transitionCount: Int,
        layoutDirection: LayoutDirection,
    ): Pair<Float, Float> {
        if (transitionCount <= 0) return 0f to 0f
        val raw = (transitionCount * STM_CONNECTION_PUFFER_PX).coerceAtMost(STM_CONNECTION_PUFFER_MAX_PX)
        return when (layoutDirection) {
            LayoutDirection.TopToBottom,
            LayoutDirection.BottomToTop,
            -> raw to 0f
            LayoutDirection.LeftToRight,
            LayoutDirection.RightToLeft,
            -> 0f to raw
        }
    }

    /**
     * Content-aware [SizeProvider] for REQ nodes. RequirementDefinitions grow
     * with their text content; other definition kinds use fixed sizes.
     */
    public fun reqContentAwareSizeProvider(model: Sysml2Model): SizeProvider =
        SizeProvider { id, kindHint ->
            when (kindHint) {
                "RequirementDefinition" -> {
                    val req = model.definitions.firstOrNull { it.id == id } as? RequirementDefinition
                    if (req == null) {
                        dev.kuml.layout.Size(width = REQ_DEFAULT_WIDTH, height = REQ_DEFAULT_HEIGHT)
                    } else {
                        val resolved =
                            Sysml2ReqCard.withResolvedSubject(req = req) { ref -> model.definitions.firstOrNull { it.id == ref }?.name }
                        val card = Sysml2ReqCard.forRequirement(resolved)
                        dev.kuml.layout.Size(width = card.width, height = card.height)
                    }
                }
                "TestCaseDefinition" -> {
                    val tc = model.definitions.firstOrNull { it.id == id } as? TestCaseDefinition
                    if (tc == null) {
                        dev.kuml.layout.Size(width = Sysml2ReqCard.MIN_WIDTH, height = 50f)
                    } else {
                        val card = Sysml2ReqCard.forTestCase(tc)
                        dev.kuml.layout.Size(width = card.width, height = card.height)
                    }
                }
                "ActorDefinition" -> {
                    val actor = model.definitions.firstOrNull { it.id == id } as? ActorDefinition
                    ucActorContentSize(name = actor?.name)
                }
                "UseCaseDefinition" -> {
                    val uc = model.definitions.firstOrNull { it.id == id } as? UseCaseDefinition
                    ucUseCaseContentSize(name = uc?.name, description = uc?.description.orEmpty())
                }
                "PartDefinition" -> {
                    val part = model.definitions.firstOrNull { it.id == id } as? PartDefinition
                    if (part != null && reqPartIsEmpty(part)) {
                        reqEmptyPartSize(part)
                    } else {
                        dev.kuml.layout.Size(width = REQ_DEFAULT_WIDTH, height = REQ_DEFAULT_HEIGHT)
                    }
                }
                else -> dev.kuml.layout.Size(width = REQ_DEFAULT_WIDTH, height = REQ_DEFAULT_HEIGHT)
            }
        }

    /** Nacharbeit 49: a part def without features, constraints, performs and exhibits shows only stereotype and name. */
    private fun reqPartIsEmpty(part: PartDefinition): Boolean =
        part.features.isEmpty() && part.constraints.isEmpty() && part.performs.isEmpty() && part.exhibits.isEmpty()

    /** Compact box of an empty part def in a REQ diagram: stereotype line + name line + padding, width from the text. */
    private fun reqEmptyPartSize(part: PartDefinition): dev.kuml.layout.Size {
        val stereoW = "«part def»".length * IBD_STEREO_CHAR_PX
        val nameW = part.name.length * IBD_TITLE_CHAR_PX
        return dev.kuml.layout.Size(
            width = maxOf(REQ_EMPTY_PART_MIN_WIDTH, maxOf(stereoW, nameW) + 2 * IBD_H_PAD),
            height = STEREOTYPE_LINE_H + NAME_LINE_H + BOX_V_PADDING,
        )
    }

    /**
     * Content-aware size for a UC/REQ actor node — the stick-figure glyph
     * itself is fixed ([UC_ACTOR_WIDTH]), but its name label
     * ([dev.kuml.io.svg.sysml2.renderSysml2Actor] draws it centered below the
     * figure with no clamp) needs the box to grow for long actor names.
     * Shared by [reqContentAwareSizeProvider] and [ucContentAwareSizeProvider]
     * so both diagram kinds size an `ActorDefinition` identically.
     */
    private fun ucActorContentSize(name: String?): dev.kuml.layout.Size {
        val nameW = (name?.length ?: 0) * BDD_BODY_CHAR_PX
        val contentW = nameW + 2 * UC_ACTOR_NAME_H_PAD
        return dev.kuml.layout.Size(width = maxOf(contentW, UC_ACTOR_WIDTH), height = UC_ACTOR_HEIGHT)
    }

    /**
     * Content-aware size for a UC/REQ use-case node — mirrors the width
     * formula already used by [reqContentAwareSizeProvider]'s
     * `UseCaseDefinition` branch; factored out so
     * [ucContentAwareSizeProvider] reuses the identical calculation.
     */
    private fun ucUseCaseContentSize(
        name: String?,
        description: String = "",
        extensionPoints: List<String> = emptyList(),
    ): dev.kuml.layout.Size {
        val nameW = (name?.length ?: 0) * BDD_BODY_CHAR_PX
        val pointW = (extensionPoints.maxOfOrNull { it.length } ?: 0) * BDD_BODY_CHAR_PX
        val contentW = maxOf(nameW, pointW) + 2 * UC_ELLIPSE_H_PAD
        // Wave 2E-b: the extension-point block (divider, heading, one line per point) grows the ellipse.
        val extH = if (extensionPoints.isEmpty()) 0f else UC_EXTENSION_BLOCK_GAP + (1 + extensionPoints.size) * UC_EXTENSION_LINE_H
        if (description.isBlank()) {
            return dev.kuml.layout.Size(
                width = maxOf(contentW, UC_USECASE_WIDTH),
                height = if (extH == 0f) UC_USECASE_HEIGHT else maxOf(UC_USECASE_HEIGHT, (20f + extH) / 0.62f),
            )
        }
        // Wave C1: a description (max. 4 italic lines) makes the ellipse wider and taller so
        // that name + description fit inside the curved boundary.
        val width = maxOf(contentW, UC_USECASE_WIDTH_WITH_DESCRIPTION)
        val lines =
            Sysml2ReqCard.wrap(
                text = Sysml2ReqCard.clip(s = description, max = Sysml2ReqCard.UC_DESCRIPTION_MAX_CHARS),
                maxChars = Sysml2ReqCard.ucDescriptionChars(width),
            )
        val n = lines.size.coerceIn(1, Sysml2ReqCard.UC_DESCRIPTION_MAX_LINES)
        val height = maxOf(UC_USECASE_HEIGHT, (24f + n * Sysml2ReqCard.UC_DESCRIPTION_LINE_H + extH) / 0.62f)
        return dev.kuml.layout.Size(width = width, height = height)
    }

    /** Wave 2E-b: the extension points of each use case, taken from the `extend` relations (declaration order, distinct, capped). */
    public fun ucExtensionPoints(diagram: UcDiagram): Map<String, List<String>> =
        diagram.extends
            .filter { !it.extensionPoint.isNullOrBlank() }
            .groupBy({ it.targetUseCaseId }, { it.extensionPoint!!.trim().take(UC_EXTENSION_POINT_MAX_CHARS) })
            .mapValues { (_, v) -> v.distinct().take(UC_EXTENSION_POINTS_MAX) }

    /** Diagnose-Helfer: extrahiert die in [diagram] referenzierten Definitionen aus [model]. */
    public fun resolveVisibleDefinitions(
        model: Sysml2Model,
        diagram: BdDiagram,
    ): List<Sysml2Definition> = diagram.elementIds.mapNotNull { id -> model.definitions.firstOrNull { it.id == id } }

    /**
     * Übersetzt das gegebene IBD (V2.0.6) in einen [LayoutGraph].
     *
     * MVP-Scope:
     *  - Jede [PartUsage] des Owners (`diagram.ownerId`) wird ein [LayoutNode].
     *    Wenn `diagram.elementIds` leer ist, sind das alle Part-Usages des
     *    Owners; ist die Liste gesetzt, wird auf diese Teilmenge gefiltert.
     *  - Jede [ConnectionUsage], die der Owner besitzt (qualified-name-Prefix
     *    `"<ownerId>::"`), wird eine [LayoutEdge] — sofern beide Endpunkte auf
     *    sichtbare Part-Usages mapen. Endpunkte folgen der SysML-2-Konvention
     *    `Owner::partUsage::portUsage`; per Longest-Prefix-Match wird die
     *    enthaltende Part-Usage ermittelt.
     *  - Dangling-Connections (Endpunkt in keiner sichtbaren Part-Usage) werden
     *    stillschweigend übersprungen — Konsistenzprüfung ist Validator-Sache.
     *  - Owner nicht gefunden → leerer Graph; analog zum BDD-Verhalten.
     *
     * Bewusst ausgenommen (V2.x):
     *  - Boundary-Port-Marker am IBD-Rahmen (brauchen Port-Position-Hints).
     *  - Geschachtelte IBDs (IBD-in-IBD).
     *  - Typed Connection-Linienstile pro `ConnectionDefinition`.
     *
     * @param model Container mit Definitionen + Usages.
     * @param diagram Das IBD (`ownerId` adressiert die PartDefinition, deren
     *   Innenleben projiziert wird).
     * @param sizeProvider Liefert die intrinsische Größe pro Part-Usage. Default
     *   ist [IBD_DEFAULT_WIDTH] × [IBD_DEFAULT_HEIGHT].
     */
    public fun toLayoutGraph(
        model: Sysml2Model,
        diagram: IbdDiagram,
        sizeProvider: SizeProvider = ibdContentAwareSizeProvider(model = model, diagram = diagram),
    ): LayoutGraph {
        // 1. Owner auflösen — fehlt der Owner, ist der Graph leer (analog BDD).
        model.definitions
            .filterIsInstance<PartDefinition>()
            .firstOrNull { it.id == diagram.ownerId }
            ?: return LayoutGraph(nodes = emptyList(), edges = emptyList())

        // 2. Alles Sichtbare kommt aus der Projektion (Single Source of Truth für
        //    Bridge, Edge-Adapter und Renderer).
        val projection = sysml2IbdProjection(model = model, diagram = diagram)

        val nodes =
            projection.partNodes
                .map { feature ->
                    LayoutNode(
                        id = NodeId(feature.id),
                        intrinsicSize = sizeProvider.sizeOf(elementId = feature.id, elementKind = "PartUsage"),
                    )
                }.toMutableList()
        // 3. Owner-Ports als kleine synthetische Randknoten; addIbdOwnerFrame legt sie später auf den Rahmen.
        for (port in projection.boundaryPorts) {
            nodes += LayoutNode(id = NodeId(port.id), intrinsicSize = Size(width = IBD_PORT_SIZE_PX, height = IBD_PORT_SIZE_PX))
        }

        fun endNode(end: dev.kuml.sysml2.projection.ResolvedEnd): String = end.nodeId ?: end.ownerPort!!.id

        // 4. Kanten: Connections (conn:), Flows ohne Connector (flow:), Bindings (eigene Id).
        val edges = mutableListOf<LayoutEdge>()

        fun addEdge(
            id: String,
            src: dev.kuml.sysml2.projection.ResolvedEnd,
            tgt: dev.kuml.sysml2.projection.ResolvedEnd,
        ) {
            edges +=
                LayoutEdge(
                    id = EdgeId(id),
                    source = EndpointRef(nodeId = NodeId(endNode(src))),
                    target = EndpointRef(nodeId = NodeId(endNode(tgt))),
                    hints = EdgeHints.NONE,
                )
        }
        for (w in projection.connections) addEdge("conn:${w.usage.id}", w.source, w.target)
        for (w in projection.ownLineFlows) addEdge("flow:${w.usage.id}", w.source, w.target)
        for (w in projection.bindings) addEdge(w.usage.id, w.source, w.target)

        return LayoutGraph(nodes = nodes, edges = edges).withLayoutSpec(diagram = diagram)
    }

    /** Kantenlänge (px) der synthetischen Randknoten für Owner-Ports; muss mit dem Renderer übereinstimmen. */
    public const val IBD_PORT_SIZE_PX: Float = 12f

    /**
     * Übersetzt das gegebene UC-Diagramm (V2.0.7) in einen [LayoutGraph].
     *
     * MVP-Scope:
     *  - Jede in [UcDiagram.elementIds] referenzierte [ActorDefinition] +
     *    [UseCaseDefinition] wird ein [LayoutNode]. Andere Definition-Kinds
     *    (Part, Attribute, Port, Connection) sind im UC-Diagramm konzeptionell
     *    nicht vorgesehen und werden stillschweigend übersprungen — Konsistenz-
     *    prüfung ist Validator-Sache.
     *  - Größen pro Kind via [sizeProvider]; Default ist [UC_ACTOR_WIDTH] ×
     *    [UC_ACTOR_HEIGHT] für Actors und [UC_USECASE_WIDTH] ×
     *    [UC_USECASE_HEIGHT] für Use Cases.
     *  - [UcDiagram.associations], [UcDiagram.includes] und [UcDiagram.extends]
     *    werden zu [LayoutEdge]s. Endpunkte, die nicht beide auf sichtbare
     *    Knoten zeigen, werden stillschweigend übersprungen — gleiche
     *    Skip-Logik wie BDD/IBD.
     *
     * Edge-Stylunterscheidung: alle drei Edge-Kinds tragen [EdgeHints.NONE]
     * — der Renderer differenziert über das Edge-ID-Präfix (`assoc:` /
     * `include:` / `extend:`). Im V2.0.7-MVP rendern alle drei optisch als
     * dieselbe einfache Linie; gestricheltes `«include»`/`«extend»`-Styling
     * und Stereotyp-Labels sind V2.x.
     *
     * @param model Container mit allen Definitionen.
     * @param diagram Das UC-Diagramm (`elementIds` selektiert die sichtbaren
     *   Actor- + UseCase-Definitionen; die drei Edge-Listen liefern die
     *   Edges).
     * @param sizeProvider Liefert die intrinsische Größe pro Definition. Default
     *   nutzt die `UC_*`-Konstanten je nach Definition-Kind.
     */
    public fun toLayoutGraph(
        model: Sysml2Model,
        diagram: UcDiagram,
        sizeProvider: SizeProvider = ucContentAwareSizeProvider(model = model, extensionPoints = ucExtensionPoints(diagram)),
    ): LayoutGraph {
        // Wave 2E-b (P-UC-01): use cases that only appear inside a subject frame are diagram members too.
        val memberIds: List<String> = (diagram.elementIds + diagram.subjects.flatMap { it.useCaseIds }).distinct()

        val nodes = mutableListOf<LayoutNode>()
        for (id in memberIds) {
            val def = model.definitions.firstOrNull { it.id == id } ?: continue
            // UC-Diagramme zeigen Actors + UseCases; andere Definition-Kinds
            // gehören nicht in dieses Diagramm und werden stillschweigend
            // übersprungen.
            if (def !is ActorDefinition && def !is UseCaseDefinition) continue
            val kind = def::class.simpleName ?: "Sysml2Definition"
            nodes +=
                LayoutNode(
                    id = NodeId(def.id),
                    intrinsicSize = sizeProvider.sizeOf(elementId = def.id, elementKind = kind),
                    // read by UcFrameGrid only: actors stand beside the subject frame
                    hints = if (def is ActorDefinition) NodeHints(rail = true) else NodeHints.NONE,
                )
        }
        val visibleNodeIds: Set<String> = nodes.map { it.id.value }.toSet()

        val edges = mutableListOf<LayoutEdge>()

        for (assoc in diagram.associations) {
            if (assoc.actorId !in visibleNodeIds || assoc.useCaseId !in visibleNodeIds) continue
            edges +=
                LayoutEdge(
                    id = EdgeId(assoc.id),
                    source = EndpointRef(nodeId = NodeId(assoc.actorId)),
                    target = EndpointRef(nodeId = NodeId(assoc.useCaseId)),
                    hints = EdgeHints.NONE,
                )
        }

        for (inc in diagram.includes) {
            if (inc.sourceUseCaseId !in visibleNodeIds || inc.targetUseCaseId !in visibleNodeIds) continue
            edges +=
                LayoutEdge(
                    id = EdgeId(inc.id),
                    source = EndpointRef(nodeId = NodeId(inc.sourceUseCaseId)),
                    target = EndpointRef(nodeId = NodeId(inc.targetUseCaseId)),
                    hints = EdgeHints.NONE,
                )
        }

        for (ext in diagram.extends) {
            if (ext.sourceUseCaseId !in visibleNodeIds || ext.targetUseCaseId !in visibleNodeIds) continue
            edges +=
                LayoutEdge(
                    id = EdgeId(ext.id),
                    source = EndpointRef(nodeId = NodeId(ext.sourceUseCaseId)),
                    target = EndpointRef(nodeId = NodeId(ext.targetUseCaseId)),
                    hints = EdgeHints.NONE,
                )
        }

        // Wave C1: actor generalisation (specific -> general), only between visible actors.
        val actorIds: Set<String> =
            nodes
                .filter { n ->
                    model.definitions.any { it.id == n.id.value && it is ActorDefinition }
                }.map { it.id.value }
                .toSet()
        for (gen in diagram.actorGeneralizations) {
            if (gen.specificId !in actorIds || gen.generalId !in actorIds || gen.specificId == gen.generalId) continue
            edges +=
                LayoutEdge(
                    id = EdgeId(gen.id),
                    source = EndpointRef(nodeId = NodeId(gen.specificId)),
                    target = EndpointRef(nodeId = NodeId(gen.generalId)),
                    hints = EdgeHints.NONE,
                )
        }

        // Wave C1: system-boundary frames. Use cases become children of the frame (compound
        // group), actors stay outside by construction (they never get a groupId).
        val groups = mutableListOf<LayoutGroup>()
        val assigned = mutableMapOf<String, GroupId>()
        for (subject in diagram.subjects.take(C1_MAX_SUBJECTS)) {
            val members =
                subject.useCaseIds.filter { id ->
                    id in visibleNodeIds && id !in assigned && model.definitions.any { it.id == id && it is UseCaseDefinition }
                }
            if (members.isEmpty()) continue
            groups +=
                LayoutGroup(
                    id = GroupId(subject.id),
                    padding = UC_SUBJECT_PADDING,
                    layoutAsCompound = true,
                    minSize = UC_SUBJECT_MIN_SIZE,
                )
            members.forEach { assigned[it] = GroupId(subject.id) }
        }
        val finalNodes = if (assigned.isEmpty()) nodes else nodes.map { n -> assigned[n.id.value]?.let { n.copy(groupId = it) } ?: n }

        return LayoutGraph(nodes = finalNodes, edges = edges, groups = groups)
            .withLayoutSpec(diagram = diagram)
    }

    /**
     * Übersetzt das gegebene REQ-Diagramm (V2.0.8) in einen [LayoutGraph].
     *
     * MVP-Scope:
     *  - Jede in [ReqDiagram.elementIds] referenzierte [RequirementDefinition]
     *    (sowie ggf. mit-projizierte Parts / UseCases / Actors als Satisfier
     *    / Verifier) wird ein [LayoutNode]. Andere Definition-Kinds (Attribute,
     *    Port, Connection) sind im REQ-Diagramm konzeptionell nicht vorgesehen
     *    und werden stillschweigend übersprungen — Konsistenzprüfung ist
     *    Validator-Sache.
     *  - Größen pro Kind via [sizeProvider]; Default nutzt [REQ_DEFAULT_WIDTH] ×
     *    [REQ_DEFAULT_HEIGHT] für Requirements und die jeweiligen `UC_*` /
     *    BDD-Default-Maße für Actors / UseCases / Parts.
     *  - Die vier Edge-Listen ([ReqDiagram.satisfies], `verifies`, `derives`,
     *    `contains`) werden zu [LayoutEdge]s. Endpunkte, die nicht beide auf
     *    sichtbare Knoten zeigen, werden stillschweigend übersprungen —
     *    gleiche Skip-Logik wie BDD/IBD/UC.
     *
     * Edge-Stilunterscheidung: alle vier Edge-Kinds tragen [EdgeHints.NONE]
     * — der Renderer differenziert über das Edge-ID-Präfix (`satisfy:` /
     * `verify:` / `derive:` / `contains:`). Im V2.0.8-MVP rendern alle vier
     * optisch als dieselbe einfache Linie; gestricheltes
     * `«satisfy»`/`«verify»`/`«deriveReqt»`-Styling und Stereotyp-Labels sind
     * V2.x.
     *
     * @param model Container mit allen Definitionen.
     * @param diagram Das REQ-Diagramm (`elementIds` selektiert die sichtbaren
     *   Definitionen; die vier Edge-Listen liefern die Edges).
     * @param sizeProvider Liefert die intrinsische Größe pro Definition. Default
     *   nutzt die `REQ_*`/`UC_*`/BDD-Konstanten je nach Definition-Kind.
     */
    public fun toLayoutGraph(
        model: Sysml2Model,
        diagram: ReqDiagram,
        sizeProvider: SizeProvider = reqContentAwareSizeProvider(model),
    ): LayoutGraph {
        val visibleIds: Set<String> = diagram.elementIds.toSet()

        val nodes = mutableListOf<LayoutNode>()
        for (id in diagram.elementIds) {
            val def = model.definitions.firstOrNull { it.id == id } ?: continue
            // REQ-Diagramme zeigen primär Requirements; daneben sind
            // Parts/UseCases/Actors als Satisfier/Verifier-Endpunkte sinnvoll.
            // Andere Definition-Kinds (Attribute, Port, Connection) werden
            // stillschweigend übersprungen.
            if (def !is RequirementDefinition &&
                def !is TestCaseDefinition &&
                def !is PartDefinition &&
                def !is UseCaseDefinition &&
                def !is ActorDefinition
            ) {
                continue
            }
            val kind = def::class.simpleName ?: "Sysml2Definition"
            var size = sizeProvider.sizeOf(elementId = def.id, elementKind = kind)
            // Nacharbeit 49: a compact (empty) part def that carries many edges (a hub) keeps the old default box.
            if (def is PartDefinition && reqPartIsEmpty(def) && size.height <= reqEmptyPartSize(def).height) {
                val attached =
                    diagram.satisfies.count { it.sourceId == def.id } +
                        diagram.verifies.count { it.sourceId == def.id } +
                        diagram.refines.count { it.sourceId == def.id } +
                        diagram.traces.count { it.sourceId == def.id || it.targetId == def.id } +
                        (
                            if (diagram.layout?.showSubjectEdges == true) {
                                model.definitions.count { it is RequirementDefinition && it.id in visibleIds && it.subjectRef == def.id }
                            } else {
                                0
                            }
                        )
                // Up to REQ_EMPTY_PART_COMPACT_EDGES edges the compact box carries them; a block with more edges keeps the
                // old default box, because the route bands (end spreading, nudging) were tuned on that size and a smaller
                // hub moved parallel segments closer than 6 px in the vault fixtures.
                if (attached > REQ_EMPTY_PART_COMPACT_EDGES) {
                    val needed = 2 * ReqEdgeBands.CORNER_MARGIN_PX + (attached - 1) * ReqEdgeBands.MIN_END_GAP_PX
                    size =
                        dev.kuml.layout.Size(
                            width = REQ_DEFAULT_WIDTH,
                            height = minOf(REQ_EMPTY_PART_MAX_HEIGHT, maxOf(REQ_DEFAULT_HEIGHT, needed)),
                        )
                }
            }
            nodes +=
                LayoutNode(
                    id = NodeId(def.id),
                    intrinsicSize = size,
                    hints = if (def is PartDefinition) NodeHints(rail = true) else NodeHints.NONE,
                )
        }
        val visibleNodeIds: Set<String> = nodes.map { it.id.value }.toSet()

        // Determinism (wave 2E-a): nodes follow `elementIds`, edges are emitted in a fixed kind order
        // (satisfy, verify, derive, contains, refine, trace, copy, subject), each list in declaration order.
        val edges = mutableListOf<LayoutEdge>()

        for (sat in diagram.satisfies) {
            if (sat.sourceId !in visibleNodeIds || sat.requirementId !in visibleNodeIds) continue
            edges +=
                LayoutEdge(
                    id = EdgeId(sat.id),
                    source = EndpointRef(nodeId = NodeId(sat.sourceId)),
                    target = EndpointRef(nodeId = NodeId(sat.requirementId)),
                    hints = EdgeHints.NONE,
                )
        }

        for (ver in diagram.verifies) {
            if (ver.sourceId !in visibleNodeIds || ver.requirementId !in visibleNodeIds) continue
            edges +=
                LayoutEdge(
                    id = EdgeId(ver.id),
                    source = EndpointRef(nodeId = NodeId(ver.sourceId)),
                    target = EndpointRef(nodeId = NodeId(ver.requirementId)),
                    hints = EdgeHints.NONE,
                )
        }

        for (der in diagram.derives) {
            if (der.sourceRequirementId !in visibleNodeIds || der.targetRequirementId !in visibleNodeIds) continue
            edges +=
                LayoutEdge(
                    id = EdgeId(der.id),
                    source = EndpointRef(nodeId = NodeId(der.sourceRequirementId)),
                    target = EndpointRef(nodeId = NodeId(der.targetRequirementId)),
                    hints = EdgeHints.NONE,
                )
        }

        for (con in diagram.contains) {
            if (con.parentRequirementId !in visibleNodeIds || con.childRequirementId !in visibleNodeIds) continue
            edges +=
                LayoutEdge(
                    id = EdgeId(con.id),
                    source = EndpointRef(nodeId = NodeId(con.parentRequirementId)),
                    target = EndpointRef(nodeId = NodeId(con.childRequirementId)),
                    hints = EdgeHints.NONE,
                )
        }

        // Wave C1: refine / trace / copy edges and the «subject» edge derived from subjectRef.
        for (r in diagram.refines) {
            if (r.sourceId !in visibleNodeIds || r.requirementId !in visibleNodeIds) continue
            edges += reqEdge(id = r.id, source = r.sourceId, target = r.requirementId)
        }
        for (t in diagram.traces) {
            if (t.sourceId !in visibleNodeIds || t.targetId !in visibleNodeIds) continue
            edges += reqEdge(id = t.id, source = t.sourceId, target = t.targetId)
        }
        for (c in diagram.copies) {
            if (c.sourceRequirementId !in visibleNodeIds || c.targetRequirementId !in visibleNodeIds) continue
            edges += reqEdge(id = c.id, source = c.sourceRequirementId, target = c.targetRequirementId)
        }
        // Wave 2E-a: «subject» edges are opt-in (`layout { showSubjectEdges = true }`); by default the subject is a text
        // line in the card. Pairs that already have a «satisfy» edge (either direction) never get a second line.
        val satisfied = diagram.satisfies.flatMap { listOf(it.sourceId to it.requirementId, it.requirementId to it.sourceId) }.toSet()
        for (id in if (diagram.layout?.showSubjectEdges == true) diagram.elementIds else emptyList()) {
            val req = model.definitions.firstOrNull { it.id == id } as? RequirementDefinition ?: continue
            val ref = req.subjectRef ?: continue
            if (id !in visibleNodeIds || ref !in visibleNodeIds || ref == id) continue
            if (id to ref in satisfied) continue
            edges += reqEdge(id = "${ReqEdgeAdapter.SUBJECT_EDGE_PREFIX}$id::$ref", source = id, target = ref)
        }

        val liveGroups = c1RequirementGroups(diagram = diagram, visibleNodeIds = visibleNodeIds)
        val membership =
            ReqNoteRoom.membership(
                diagram = diagram,
                liveGroupIds = liveGroups.map { it.id.value }.toSet(),
                visibleNodeIds = visibleNodeIds,
            )
        val groupOfNode = membership.groupOfNode
        // Nacharbeit 55: a group reserves room inside its frame for the notes only it owns.
        val strips =
            ReqNoteRoom
                .ownedNotes(model = model, diagram = diagram, groupOfNode = groupOfNode, visibleNodeIds = visibleNodeIds)
                .mapValues { (_, notes) -> ReqNoteRoom.stripOf(notes) }
        val groups = liveGroups.map { g -> strips[g.id]?.let { g.copy(noteStrip = it) } ?: g }
        val finalNodes = if (groupOfNode.isEmpty()) nodes else nodes.map { n -> groupOfNode[n.id.value]?.let { n.copy(groupId = it) } ?: n }

        return LayoutGraph(nodes = finalNodes, edges = edges, groups = groups, ignoredMemberships = membership.ignored)
            .withLayoutSpec(diagram = diagram)
    }

    /** Package diagram (wave 2I-a): nested frames, `import` / `satisfy` edges; see [Sysml2PackageLayout]. */
    public fun toLayoutGraph(
        model: Sysml2Model,
        diagram: dev.kuml.sysml2.Sysml2PackageDiagram,
    ): LayoutGraph = Sysml2PackageLayout.toLayoutGraph(model = model, diagram = diagram)

    private fun reqEdge(
        id: String,
        source: String,
        target: String,
    ): LayoutEdge =
        LayoutEdge(
            id = EdgeId(id),
            source = EndpointRef(nodeId = NodeId(source)),
            target = EndpointRef(nodeId = NodeId(target)),
            hints = EdgeHints.NONE,
        )

    /**
     * Wave C1: turns [ReqDiagram.groups] into nested compound [LayoutGroup]s. Groups without any
     * visible member (directly or through a child group) are dropped, nesting deeper than
     * [C1_MAX_GROUP_DEPTH] is flattened to that depth, cycles and unknown child ids are ignored,
     * and at most [C1_MAX_GROUPS] groups are processed.
     */
    private fun c1RequirementGroups(
        diagram: ReqDiagram,
        visibleNodeIds: Set<String>,
    ): List<LayoutGroup> {
        val all = diagram.groups.take(C1_MAX_GROUPS)
        if (all.isEmpty()) return emptyList()
        val byId = all.associateBy { it.id }
        val parentOf = mutableMapOf<String, String>()
        for (g in all) for (c in g.childGroupIds) if (c in byId && c != g.id && c !in parentOf) parentOf[c] = g.id

        fun depthOf(id: String): Int {
            var d = 1
            var cur = id
            val seen = mutableSetOf(id)
            while (true) {
                val p = parentOf[cur] ?: return d
                if (!seen.add(p)) return Int.MAX_VALUE // cycle
                d++
                cur = p
            }
        }

        val live = mutableMapOf<String, Boolean>()

        fun hasVisible(
            id: String,
            visiting: Set<String>,
        ): Boolean {
            live[id]?.let { return it }
            if (id in visiting) return false
            val g = byId[id] ?: return false
            val result =
                g.memberIds.any { it in visibleNodeIds } ||
                    g.childGroupIds.any { hasVisible(it, visiting + id) }
            live[id] = result
            return result
        }

        fun effectiveParent(id: String): String? {
            // Flatten: a group deeper than the limit is attached to its ancestor at depth limit-1.
            var depth = depthOf(id)
            var parent = parentOf[id]
            var steps = 0
            while (parent != null && depth > C1_MAX_GROUP_DEPTH && steps < 64) {
                parent = parentOf[parent]
                depth--
                steps++
            }
            return parent
        }

        return all
            .filter { depthOf(it.id) != Int.MAX_VALUE && hasVisible(it.id, emptySet()) }
            .map { g ->
                val parent = effectiveParent(g.id)?.takeIf { p -> live[p] == true || hasVisible(p, emptySet()) }
                LayoutGroup(
                    id = GroupId(g.id),
                    parent = parent?.let { GroupId(it) },
                    padding = REQ_GROUP_PADDING,
                    layoutAsCompound = true,
                    minSize = REQ_GROUP_MIN_SIZE,
                    wrapColumns = g.wrapColumns,
                )
            }
    }

    /**
     * Übersetzt das gegebene STM-Diagramm (V2.0.9) in einen [LayoutGraph].
     *
     * MVP-Scope:
     *  - Jede in [StmDiagram.elementIds] referenzierte [StateDefinition]
     *    (reguläre Zustände + Pseudo-States) wird ein [LayoutNode]. Andere
     *    Definition-Kinds (Part, Attribute, Port, Connection, Actor, …) sind
     *    im STM-Diagramm konzeptionell nicht vorgesehen und werden
     *    stillschweigend übersprungen — Konsistenzprüfung ist Validator-Sache.
     *  - Größen pro State-Kind via [sizeProvider]; Default nutzt
     *    [STM_PSEUDO_SIZE] × [STM_PSEUDO_SIZE] für Pseudo-States (Initial /
     *    Final) und [STM_STATE_WIDTH] × [STM_STATE_HEIGHT] für reguläre
     *    Zustände.
     *  - Transitionen werden aus `model.usages.filterIsInstance<TransitionUsage>()`
     *    gezogen — nicht aus dem Diagramm. Eine Transition wird genau dann
     *    zur [LayoutEdge], wenn beide Endpunkte
     *    ([TransitionUsage.sourceStateId] + `targetStateId`) im sichtbaren
     *    Knoten-Set liegen. Dangling-Transitionen werden stillschweigend
     *    übersprungen.
     *
     * **Architektur-Begründung** für "Transitionen leben auf dem Modell, nicht
     * auf dem Diagramm" (anders als V2.0.7-UC / V2.0.8-REQ): Transitionen
     * sind ein integraler Teil der State-Machine-Semantik (die zukünftige
     * Behaviour-Runtime-Welle braucht sie zur Laufzeit), während UC-/REQ-Edges
     * reine Diagramm-Aussagen sind. Das STM-Diagramm ist eine *Projektion*
     * der Knoten; die Edges entstehen automatisch aus dem Modell.
     *
     * Edge-Stilunterscheidung: alle Transitionen tragen [EdgeHints.NONE] —
     * der `trigger [guard] / effect`-Label ist V2.x-Polish (kein Konsument
     * im V2.0.9-MVP, weil die synthetische `KumlDiagram`-Hülle keine
     * `UmlRelationship`-Elemente für TransitionUsages hat — gleiche
     * Limitation wie UC / REQ).
     *
     * @param model Container mit allen Definitionen + Usages
     *   (Transitionen werden aus `model.usages` gelesen).
     * @param diagram Das STM-Diagramm (`elementIds` selektiert die sichtbaren
     *   Zustände; Transitionen kommen vom Modell).
     * @param sizeProvider Liefert die intrinsische Größe pro State. Default
     *   nutzt die `STM_*`-Konstanten je nach State-Kind (Pseudo vs. regulär).
     */
    public fun toLayoutGraph(
        model: Sysml2Model,
        diagram: StmDiagram,
        sizeProvider: SizeProvider =
            stmContentAwareSizeProvider(model = model, diagram = diagram, layoutDirection = stmFlowDirectionOf(diagram)),
    ): LayoutGraph {
        // Wave A1: composite states, regions and the new pseudo-state kinds take the structured
        // path; flat machines (the vast majority) keep the original code below bit-for-bit.
        if (model.usesStmStructure(diagram.visibleStateIds(model))) {
            return toStructuredStmLayoutGraph(model = model, diagram = diagram, sizeProvider = sizeProvider)
        }
        val nodes = mutableListOf<LayoutNode>()
        for (id in diagram.elementIds) {
            val def = model.definitions.firstOrNull { it.id == id } ?: continue
            // STM-Diagramme zeigen nur StateDefinitions; alles andere
            // ist konzeptionell nicht vorgesehen und wird ignoriert.
            if (def !is StateDefinition) continue
            val kindHint =
                when {
                    def.isInitial -> "InitialPseudoState"
                    def.isFinal -> "FinalPseudoState"
                    else -> "StateDefinition"
                }
            nodes +=
                LayoutNode(
                    id = NodeId(def.id),
                    intrinsicSize = sizeProvider.sizeOf(elementId = def.id, elementKind = kindHint),
                    hints = if (def.isInitial) NodeHints(initialState = true) else NodeHints.NONE,
                )
        }
        val visibleNodeIds: Set<String> = nodes.map { it.id.value }.toSet()

        // Transitions aus dem Modell ziehen (nicht aus dem Diagramm) — siehe
        // KDoc-Begründung oben. Eine Transition wird zur Edge, wenn beide
        // Endpunkte sichtbar sind; sonst stillschweigend übersprungen.
        val edges = mutableListOf<LayoutEdge>()
        for (transition in model.usages.filterIsInstance<TransitionUsage>()) {
            if (transition.sourceStateId !in visibleNodeIds ||
                transition.targetStateId !in visibleNodeIds
            ) {
                continue
            }
            edges +=
                LayoutEdge(
                    id = EdgeId(transition.id),
                    source = EndpointRef(nodeId = NodeId(transition.sourceStateId)),
                    target = EndpointRef(nodeId = NodeId(transition.targetStateId)),
                    hints = EdgeHints.NONE,
                )
        }

        val initialIds =
            diagram.elementIds
                .mapNotNull { id -> (model.definitions.firstOrNull { it.id == id } as? StateDefinition)?.takeIf { it.isInitial }?.id }
                .toSet()
        return LayoutGraph(nodes = nodes, edges = edges).withLayoutSpec(diagram = diagram, initialIds = initialIds)
    }

    /**
     * Wave A1 STM layout: composite states become nested compound [LayoutGroup]s (group id =
     * state id, so transitions can attach to the composite frame itself via the ELK builder's
     * "endpoint id resolves to a group" fallback), orthogonal regions become compound sub-groups
     * (group id = region id), everything else a [LayoutNode] inside its innermost group.
     *
     * Limits (nesting depth, regions, states, name length, parent cycles) are enforced up front
     * with an [IllegalArgumentException] naming the checker code.
     */
    private fun toStructuredStmLayoutGraph(
        model: Sysml2Model,
        diagram: StmDiagram,
        sizeProvider: SizeProvider,
    ): LayoutGraph {
        val limitIssues = Sysml2StmChecker.checkLimits(model = model, diagram = diagram)
        require(limitIssues.isEmpty()) {
            "STM structure limit exceeded (code ${limitIssues.first().code}): ${limitIssues.first().message} ${limitIssues.first().hint}"
        }
        val index = model.stateIndex()
        val states = diagram.visibleStateIds(model).mapNotNull { index[it] }
        val visibleIds = states.map { it.id }.toSet()
        val childrenByParent: Map<String, List<StateDefinition>> =
            states.filter { it.parentStateId != null && it.parentStateId in visibleIds }.groupBy { it.parentStateId!! }

        fun isComposite(s: StateDefinition) = s.effectiveKind == StateKind.State && !childrenByParent[s.id].isNullOrEmpty()

        fun containerOf(s: StateDefinition): GroupId? {
            val pid = s.parentStateId ?: return null
            val parent = index[pid]?.takeIf { it.id in visibleIds && isComposite(it) } ?: return null
            val region = s.regionId?.let { rid -> parent.regions.firstOrNull { it.id == rid } }
            return if (region != null) GroupId(region.id) else GroupId(parent.id)
        }

        val groups = mutableListOf<LayoutGroup>()
        val groupIds = mutableSetOf<String>()
        for (s in states) {
            if (!isComposite(s)) continue
            val compartment =
                StateActionCompartment.lines(invariant = s.invariant, entry = s.entryAction, doActivity = s.doAction, exit = s.exitAction)
            val actionLines = compartment.size
            groups +=
                LayoutGroup(
                    id = GroupId(s.id),
                    parent = containerOf(s),
                    padding = STM_COMPOSITE_PADDING.copy(top = STM_COMPOSITE_PADDING.top + actionLines * STM_COMPOSITE_ACTION_LINE_H),
                    layoutAsCompound = true,
                    // The action lines must fit the frame's width as well (PAL wave 2D-b).
                    minSize =
                        STM_COMPOSITE_MIN_SIZE.copy(
                            width = maxOf(STM_COMPOSITE_MIN_SIZE.width, StateActionCompartment.width(compartment)),
                        ),
                    headerTextWidths =
                        HeaderTextWidths(
                            title = s.name.length * StateActionCompartment.CHAR_PX * STM_COMPOSITE_TITLE_CHAR_FACTOR,
                            actions = StateActionCompartment.width(compartment),
                        ),
                )
            groupIds += s.id
            val usedRegions = childrenByParent[s.id].orEmpty().mapNotNull { it.regionId }.toSet()
            for (r in s.regions) {
                if (r.id !in usedRegions) continue
                groups +=
                    LayoutGroup(
                        id = GroupId(r.id),
                        parent = GroupId(s.id),
                        padding = STM_REGION_PADDING,
                        layoutAsCompound = true,
                        minSize = STM_REGION_MIN_SIZE,
                    )
                groupIds += r.id
            }
        }

        val nodes = mutableListOf<LayoutNode>()
        for (s in states) {
            if (isComposite(s)) continue
            val kindHint =
                when (s.effectiveKind) {
                    StateKind.Initial -> "InitialPseudoState"
                    StateKind.Final -> "FinalPseudoState"
                    StateKind.Choice -> STM_HINT_CHOICE
                    StateKind.Junction -> STM_HINT_JUNCTION
                    StateKind.Fork, StateKind.Join -> STM_HINT_FORK_JOIN
                    StateKind.ShallowHistory, StateKind.DeepHistory -> STM_HINT_HISTORY
                    StateKind.State -> "StateDefinition"
                }
            nodes +=
                LayoutNode(
                    id = NodeId(s.id),
                    intrinsicSize = sizeProvider.sizeOf(elementId = s.id, elementKind = kindHint),
                    hints = if (s.effectiveKind == StateKind.Initial) NodeHints(initialState = true) else NodeHints.NONE,
                    groupId = containerOf(s),
                )
        }

        val edges = mutableListOf<LayoutEdge>()
        for (t in model.usages.filterIsInstance<TransitionUsage>()) {
            if (t.sourceStateId !in visibleIds || t.targetStateId !in visibleIds) continue
            edges +=
                LayoutEdge(
                    id = EdgeId(t.id),
                    source = EndpointRef(nodeId = NodeId(t.sourceStateId)),
                    target = EndpointRef(nodeId = NodeId(t.targetStateId)),
                    hints = EdgeHints.NONE,
                )
        }
        // Chain the regions of each composite (declaration order) with invisible layout-only edges.
        for (s in states) {
            val used = s.regions.filter { it.id in groupIds }
            for ((a, b) in used.zipWithNext()) {
                edges +=
                    LayoutEdge(
                        id = EdgeId("$STM_ORDER_EDGE_PREFIX${a.id}"),
                        source = EndpointRef(nodeId = NodeId(a.id)),
                        target = EndpointRef(nodeId = NodeId(b.id)),
                        hints = EdgeHints.NONE,
                    )
            }
        }
        return LayoutGraph(nodes = nodes, edges = edges, groups = groups)
            .withLayoutSpec(diagram = diagram, initialIds = states.filter { it.effectiveKind == StateKind.Initial }.map { it.id }.toSet())
    }

    /**
     * Übersetzt das gegebene ACT-Diagramm (V2.0.10) in einen [LayoutGraph].
     *
     * MVP-Scope:
     *  - Jede in [ActDiagram.elementIds] referenzierte [ActionDefinition]
     *    (alle sieben Aktivitäts-Knoten-Kinds — regulärer Action, Initial /
     *    Final / FlowFinal Pseudo-Nodes, Decision / Merge / Fork / Join) wird
     *    ein [LayoutNode]. Andere Definition-Kinds (Part, Attribute, Port,
     *    Connection, Actor, UseCase, Requirement, State) sind im
     *    ACT-Diagramm konzeptionell nicht vorgesehen und werden
     *    stillschweigend übersprungen — Konsistenzprüfung ist Validator-Sache.
     *  - Größen pro Node-Kind via [sizeProvider]; Default nutzt die
     *    `ACT_*`-Konstanten je nach [ActivityNodeKind]:
     *    - Action: [ACT_ACTION_WIDTH] × [ACT_ACTION_HEIGHT]
     *    - Initial / Final / FlowFinal: [ACT_PSEUDO_SIZE] × [ACT_PSEUDO_SIZE]
     *    - Decision / Merge: [ACT_DIAMOND_WIDTH] × [ACT_DIAMOND_HEIGHT]
     *    - Fork / Join: [ACT_BAR_WIDTH] × [ACT_BAR_HEIGHT]
     *  - Control flows und object flows werden aus
     *    `model.usages.filterIsInstance<ControlFlowUsage>()` und
     *    `…<ObjectFlowUsage>()` gezogen — nicht aus dem Diagramm. Eine Flow
     *    wird genau dann zur [LayoutEdge], wenn beide Endpunkte im
     *    sichtbaren Knoten-Set liegen. Dangling-Flows werden stillschweigend
     *    übersprungen.
     *
     * **Architektur-Begründung** für "Flows leben auf dem Modell, nicht auf
     * dem Diagramm" (wie STM, anders als UC / REQ): Token-Flow ist ein
     * integraler Teil der Activity-Semantik (die zukünftige
     * Behaviour-Runtime-Welle braucht sie zur Laufzeit), während UC-/REQ-
     * Edges reine Diagramm-Aussagen sind. Das ACT-Diagramm ist eine
     * *Projektion* der Knoten; die Edges entstehen automatisch aus dem
     * Modell.
     *
     * Edge-Stilunterscheidung: alle Flows tragen [EdgeHints.NONE] — der
     * `[guard]`-Label (Control Flow) bzw. `[ObjectType]`-Label (Object Flow)
     * ist V2.x-Polish. Die synthetische `KumlDiagram`-Hülle hat keine
     * `UmlRelationship`-Elemente für `ControlFlowUsage` / `ObjectFlowUsage`,
     * deshalb fällt der Edge-Renderer auf den Plain-Pfad zurück — gleiche
     * Limitation wie UC / REQ / STM.
     *
     * @param model Container mit allen Definitionen + Usages (Flows werden
     *   aus `model.usages` gelesen).
     * @param diagram Das ACT-Diagramm (`elementIds` selektiert die sichtbaren
     *   Knoten; Flows kommen vom Modell).
     * @param sizeProvider Liefert die intrinsische Größe pro Knoten. Default
     *   nutzt die `ACT_*`-Konstanten je nach [ActivityNodeKind].
     */
    public fun toLayoutGraph(
        model: Sysml2Model,
        diagram: ActDiagram,
        sizeProvider: SizeProvider = actContentAwareSizeProvider(model),
    ): LayoutGraph {
        // V2.0.16: ActivityPartitionDefinitions sammeln, die im Diagramm
        //          sichtbar sind. Eine Partition wird genau dann zur
        //          LayoutGroup, wenn sie in `diagram.elementIds` steht ODER
        //          mindestens ein sichtbarer ActionDefinition-Knoten sie
        //          per `partitionId` referenziert. Das erlaubt zwei
        //          DSL-Schreibweisen:
        //           a) Partitionen explizit in `actDiagram { include(...) }`
        //              auflisten (Vault-Convention für sehr explizite Modelle).
        //           b) Partitionen nur über `actionDef(partition = …)`
        //              referenzieren — der Bridge zieht sie automatisch in
        //              die Group-Emission (analog zu Flows, die ebenfalls
        //              automatisch aus dem Modell gezogen werden).
        //          Beide Pfade landen im selben Group-Output.
        val visiblePartitionIds: MutableSet<String> = mutableSetOf()
        for (id in diagram.elementIds) {
            // V2.0.44: kind-typed lookup — collision-safe (same fix as below).
            val partitionDef =
                model.definitions
                    .filterIsInstance<ActivityPartitionDefinition>()
                    .firstOrNull { it.id == id }
            if (partitionDef != null) {
                visiblePartitionIds += partitionDef.id
            }
        }

        val nodes = mutableListOf<LayoutNode>()
        for (id in diagram.elementIds) {
            // V2.0.44: id collisions across definition kinds (e.g. a `partDef("X")`
            // and an `activityPartition("X", represents = "X")` in the same model)
            // were silently dropped by `firstOrNull { it.id == id }` returning
            // whichever was declared first. Use a kind-typed lookup that prefers
            // an ActionDefinition with the matching id.
            val def =
                model.definitions
                    .filterIsInstance<ActionDefinition>()
                    .firstOrNull { it.id == id }
                    ?: continue
            val kindHint = def.kind.name
            // V2.0.16: Wenn der Action eine partitionId trägt und die
            //          referenzierte Partition im Modell existiert,
            //          ziehen wir sie automatisch in die sichtbaren
            //          Partitionen — auch wenn das Diagramm sie nicht
            //          explizit auflistet.
            val partitionId = def.partitionId
            if (partitionId != null) {
                // V2.0.44: kind-typed lookup — same collision protection as above.
                val partitionDef =
                    model.definitions
                        .filterIsInstance<ActivityPartitionDefinition>()
                        .firstOrNull { it.id == partitionId }
                if (partitionDef != null) {
                    visiblePartitionIds += partitionDef.id
                }
            }
            // V2.0.16: groupId auf dem LayoutNode setzen, wenn die
            //          partitionId auf eine sichtbare Partition referenziert.
            //          Dangling-partitionIds (Validator-Sache) führen zu
            //          groupId=null — der Knoten landet außerhalb jeder Lane.
            val nodeGroupId: GroupId? =
                if (partitionId != null && partitionId in visiblePartitionIds) {
                    GroupId(partitionId)
                } else {
                    null
                }
            nodes +=
                LayoutNode(
                    id = NodeId(def.id),
                    intrinsicSize = sizeProvider.sizeOf(elementId = def.id, elementKind = kindHint),
                    groupId = nodeGroupId,
                )
        }
        val visibleNodeIds: Set<String> = nodes.map { it.id.value }.toSet()

        // V2.0.16: LayoutGroups für die sichtbaren Partitionen emittieren —
        //          eine pro Partition. Die Engine (ELK) füllt
        //          `layoutResult.groups` mit den berechneten Bounds, und der
        //          SVG-Renderer iteriert darüber, um die gestrichelten
        //          Swimlane-Rechtecke + die Header-Bars zu zeichnen.
        //          Die Reihenfolge der Groups folgt der Reihenfolge der
        //          Partition-Definition im `model.definitions`-Slot, damit
        //          die Lane-Anordnung deterministisch ist (links-nach-rechts
        //          in DSL-Deklarationsreihenfolge).
        // V2.0.45: ACT-Partitionen bekommen einen horizontalen Außen-Puffer,
        //          damit das gestrichelte Lane-Rechteck Pin-Labels umschließt,
        //          die per Konvention außerhalb der Action-Box-Bounds liegen
        //          (Input-Label links auf x=−9 mit text-anchor=end, Output-
        //          Label rechts auf x=w+9 mit text-anchor=start; siehe
        //          `Sysml2ActivitySvg.renderActionPins`). 80 px deckt das
        //          längste in der Praxis vorkommende Pin-Label (
        //          "orderDetails", "validation") plus ein paar Pixel Luft ab
        //          — knapp genug, damit Partitionen ohne Pin-Aktionen nicht
        //          übermäßig in die Breite gezogen werden. Top/Bottom bleiben
        //          0, weil Pins nur seitlich an Action-Boxen sitzen.
        val partitionPadding = Insets(top = ACT_PARTITION_TOP_PADDING, right = 80f, bottom = 16f, left = 80f)
        val groups: List<LayoutGroup> =
            model.definitions
                .filterIsInstance<ActivityPartitionDefinition>()
                .filter { it.id in visiblePartitionIds }
                // Wave 2F: top-to-bottom diagrams get flat (non-compound) lanes: ELK sees one flow graph and
                // [normalizeActLayout] draws the lanes as columns. Other directions keep the compound lanes.
                .map {
                    LayoutGroup(
                        id = GroupId(it.id),
                        parent = null,
                        padding = partitionPadding,
                        layoutAsCompound = !actLanesAsColumns(diagram),
                    )
                }

        // Flows aus dem Modell ziehen (nicht aus dem Diagramm) — siehe
        // KDoc-Begründung oben. Eine Flow wird zur Edge, wenn beide
        // Endpunkte sichtbar sind; sonst stillschweigend übersprungen.
        val edges = mutableListOf<LayoutEdge>()
        for (flow in model.usages.filterIsInstance<ControlFlowUsage>()) {
            if (flow.sourceNodeId !in visibleNodeIds ||
                flow.targetNodeId !in visibleNodeIds
            ) {
                continue
            }
            edges +=
                LayoutEdge(
                    id = EdgeId(flow.id),
                    source = EndpointRef(nodeId = NodeId(flow.sourceNodeId)),
                    target = EndpointRef(nodeId = NodeId(flow.targetNodeId)),
                    hints = EdgeHints.NONE,
                )
        }
        for (flow in model.usages.filterIsInstance<ObjectFlowUsage>()) {
            if (flow.sourceNodeId !in visibleNodeIds ||
                flow.targetNodeId !in visibleNodeIds
            ) {
                continue
            }
            edges +=
                LayoutEdge(
                    id = EdgeId(flow.id),
                    source = EndpointRef(nodeId = NodeId(flow.sourceNodeId)),
                    target = EndpointRef(nodeId = NodeId(flow.targetNodeId)),
                    hints = EdgeHints.NONE,
                )
        }

        // Wave C2: exception handlers are ordinary edges from the protected to the handler node;
        // the handler pin square is drawn by the SVG renderer.
        for (h in model.usages.filterIsInstance<dev.kuml.sysml2.ExceptionHandlerUsage>()) {
            if (h.protectedNodeId !in visibleNodeIds || h.handlerNodeId !in visibleNodeIds) continue
            if (h.protectedNodeId == h.handlerNodeId) continue
            edges +=
                LayoutEdge(
                    id = EdgeId(h.id),
                    source = EndpointRef(nodeId = NodeId(h.protectedNodeId)),
                    target = EndpointRef(nodeId = NodeId(h.handlerNodeId)),
                    hints = EdgeHints.NONE,
                )
        }

        return LayoutGraph(nodes = nodes, edges = edges, groups = groups).withLayoutSpec(diagram = diagram)
    }

    /** Wave 2F: swimlanes are columns (flat for ELK) unless the diagram flows in another direction than down. */
    internal fun actLanesAsColumns(diagram: ActDiagram): Boolean {
        val flow = diagram.layout?.direction
        return flow == null || flow == Sysml2LayoutFlow.Down
    }

    /**
     * Übersetzt das gegebene SEQ-Diagramm (V2.0.11) in einen [LayoutGraph].
     *
     * **Architektur-Divergenz** gegenüber den anderen sechs SysML-2-Diagrammen:
     * SEQ ist *fundamental anders*. Statt eines freien Graphen, den ELK
     * hierarchisch layoutet, hat SEQ eine zeit-geordnete, achsen-beschränkte
     * Darstellung — Lifelines liegen auf einer horizontalen Achse oben, die
     * Zeit fließt vertikal nach unten, Nachrichten sind horizontale Pfeile
     * an seqNo-indizierten Y-Positionen. ELKs hierarchisches Layout passt
     * darauf nicht: Nachrichten sind keine "zu routenden Edges" — sie sind
     * horizontale Pfeile an festen Y-Positionen zwischen festen X-Spuren.
     *
     * **Konsequenz für die Bridge (V2.0.11 MVP)**:
     *  1. Nur Lifelines werden als [LayoutNode]e ausgegeben — *keine
     *     Edges*. Die unverbundenen Lifeline-Knoten ordnet ELK als
     *     horizontale Reihe an, was genau der SEQ-Konvention entspricht.
     *  2. Jede Lifeline-Box wird **vorab in der Höhe skaliert**, damit ELK
     *     genug vertikalen Platz für alle Nachrichten reserviert. Die Höhe
     *     ergibt sich aus dem maximalen `seqNo` aller sichtbaren Nachrichten:
     *     `LIFELINE_HEIGHT = [SEQ_LIFELINE_HEAD_HEIGHT] +
     *     (maxSeqNo + 1) * [SEQ_MESSAGE_ROW_HEIGHT] +
     *     [SEQ_LIFELINE_TAIL_PADDING]`. Der `+1` lässt Platz für die erste
     *     Nachricht unterhalb des Kopfes.
     *  3. Nachrichten reichen den Renderer **direkt** — der SVG-Renderer
     *     iteriert `model.usages.filterIsInstance<MessageUsage>()` nach
     *     dem Standard-Knoten-Loop und zeichnet die horizontalen Pfeile
     *     selbst. Siehe `Sysml2SequenceSvg.renderSysml2SeqMessage`.
     *
     * **Warum diese Divergenz?** Die anderen SysML-2-Diagramme sind
     * fundamental Graphen — Box-und-Linie-Topologien, die ein
     * Layout-Algorithmus sinnvoll positionieren kann. SEQ ist eine
     * *axis-orientierte Tabelle* (Spalten = Lifelines, Zeilen =
     * seqNo-indizierte Zeit-Schritte). Ein gemeinsamer Edge-Pfad würde
     * SEQ-Nachrichten als generische Edges interpretieren, die der
     * Layout-Algorithmus dann hierarchisch routet — das Ergebnis wäre
     * weder lesbar noch SEQ-konventionsgerecht. Die Bridge → Renderer-
     * Aufteilung respektiert die strukturelle Andersartigkeit von SEQ.
     *
     * Dies ist die **zweite bewusste Pattern-Divergenz** in der SysML-2-Linie;
     * die erste war V2.0.9 STM, die Pattern A (Transitionen auf dem Modell)
     * gegenüber UC / REQs Pattern B (Edges auf dem Diagramm) wählte.
     *
     * **MVP-Scope** (V2.0.11 + V2.0.15-Polish):
     *  - V2.0.11: flache Interaktionen ohne Combined Fragments / Execution
     *    Specs / Create / Destroy.
     *  - V2.0.15: Combined Fragments (alle 8 commonly-used Operatoren) +
     *    Execution Specifications + Create / Destroy als renderer-direkte
     *    Erweiterung. Die Bridge erweitert nur die Höhenrechnung, damit der
     *    Rahmen / die Aktivierungs-Bar genug vertikalen Platz hat — die
     *    eigentliche Darstellung übernimmt der SVG-Renderer direkt.
     *  - Weiterhin V2.x: nested Combined Fragments, nested Execution Specs,
     *    die restlichen 4 CF-Operatoren (assert / neg / consider / ignore),
     *    Found / Lost Messages, LaTeX-Rendering für CF / ExecSpec /
     *    Create / Destroy.
     *
     * @param model Container mit allen Definitionen + Usages (Nachrichten
     *   werden aus `model.usages` gelesen, aber nicht in den LayoutGraph
     *   eingetragen — sie werden direkt vom Renderer konsumiert).
     * @param diagram Das SEQ-Diagramm (`elementIds` selektiert die sichtbaren
     *   Lifelines in Reihenfolge links → rechts).
     * @param sizeProvider Liefert die intrinsische Breite pro Lifeline; die
     *   Höhe wird pro Lifeline aus der Nachrichtenanzahl berechnet (siehe
     *   oben) und überschreibt die vom SizeProvider gelieferte Höhe.
     */
    public fun toLayoutGraph(
        model: Sysml2Model,
        diagram: SeqDiagram,
        sizeProvider: SizeProvider = seqContentAwareSizeProvider(model),
    ): LayoutGraph {
        // 1. Sichtbare Lifelines auflösen — nicht-LifelineDefinitions werden
        //    stillschweigend übersprungen (Validator-Sache).
        val visibleIds: Set<String> = diagram.elementIds.toSet()
        val visibleLifelines: List<LifelineDefinition> =
            diagram.elementIds
                .mapNotNull { id ->
                    model.definitions.firstOrNull { it.id == id } as? LifelineDefinition
                }
        val visibleLifelineIds: Set<String> = visibleLifelines.map { it.id }.toSet()

        // 2. Maximalen seqNo bestimmen — Vorausberechnung für die
        //    Lifeline-Höhe. Nur Nachrichten zählen, deren Endpunkte BEIDE
        //    in den sichtbaren Lifelines liegen (Dangling-Endpunkte sollen
        //    die Höhe nicht aufblähen).
        val visibleMessages: List<MessageUsage> =
            model.usages
                .filterIsInstance<MessageUsage>()
                .filter {
                    it.sourceLifelineId in visibleLifelineIds &&
                        it.targetLifelineId in visibleLifelineIds
                }
        val maxMessageSeqNo: Int = visibleMessages.maxOfOrNull { it.seqNo } ?: -1

        // 2b. V2.0.15: Combined-Fragments und Execution-Specifications können
        //     ebenfalls über das letzte Nachrichten-seqNo hinausreichen
        //     (z. B. ein `loop`-Frame, das auch Slot 5 abdeckt, obwohl die
        //     letzte Nachricht bei seqNo=4 liegt; oder ein exec-spec, das
        //     bis seqNo=6 aktiviert bleibt). Beide tragen zur
        //     Höhen-Berechnung bei, damit der Renderer genug vertikalen Platz
        //     bekommt. Wir nehmen das Maximum aller drei Quellen.
        val maxFragmentSeqNo: Int =
            model.usages
                .filterIsInstance<CombinedFragmentUsage>()
                .flatMap { it.operands }
                .maxOfOrNull { it.endSeqNo }
                ?: -1
        val maxExecSpecSeqNo: Int =
            model.usages
                .filterIsInstance<ExecutionSpecificationUsage>()
                .filter { it.lifelineId in visibleLifelineIds }
                .maxOfOrNull { it.endSeqNo }
                ?: -1
        val maxSeqNo: Int = maxOf(maxMessageSeqNo, maxFragmentSeqNo, maxExecSpecSeqNo)

        // 3. Gesamthöhe der Lifeline berechnen.
        //    `maxSeqNo + 1` Zeilen Platz (eine pro Nachricht), `+1` zusätzlich
        //    für den Abstand zwischen Kopf und erster Nachricht.
        val rowCount: Int = if (maxSeqNo < 0) 0 else maxSeqNo + 1
        // Jeder Combined-Fragment-Operand reserviert ein Header-Band vertikalen
        // Freiraum über seiner ersten Nachricht (Guard-Sichtbarkeit — siehe
        // SYSML2_FRAGMENT_HEADER_BAND im Sysml2SequenceSvg-Renderer). Die
        // Lifeline-Höhe wächst um die Summe, damit die Bänder nicht abgeschnitten
        // werden.
        val operandBandCount: Int =
            model.usages
                .filterIsInstance<CombinedFragmentUsage>()
                .sumOf { it.operands.size }
        // C3: a `ref` frame is 48 px high (instead of the 32 px row) and pushes the following rows down.
        val interactionUseCount: Int =
            model.usages
                .filterIsInstance<InteractionUseUsage>()
                .count { use -> use.lifelineIds.any { it in visibleLifelineIds } }
        val maxExtraSeqNo: Int =
            maxOf(
                model.usages
                    .filterIsInstance<InteractionUseUsage>()
                    .filter { use -> use.lifelineIds.any { it in visibleLifelineIds } }
                    .maxOfOrNull { it.seqNo } ?: -1,
                model.usages
                    .filterIsInstance<DurationConstraintUsage>()
                    .filter { it.lifelineId in visibleLifelineIds }
                    .maxOfOrNull { maxOf(it.fromSeqNo, it.toSeqNo) } ?: -1,
                model.usages
                    .filterIsInstance<TimeObservationUsage>()
                    .filter { it.lifelineId in visibleLifelineIds }
                    .maxOfOrNull { it.seqNo } ?: -1,
            )
        val rowCountWithExtras: Int = maxOf(rowCount, maxExtraSeqNo + 1)
        val lifelineHeight: Float =
            SEQ_LIFELINE_HEAD_HEIGHT +
                (rowCountWithExtras + 1) * SEQ_MESSAGE_ROW_HEIGHT +
                operandBandCount * SEQ_FRAGMENT_HEADER_BAND +
                interactionUseCount * SEQ_REF_EXTRA_HEIGHT +
                // wave 2F: room for the gap after a fragment (self loop, distance to the next frame)
                model.usages.filterIsInstance<CombinedFragmentUsage>().size * SEQ_FRAGMENT_END_GAP +
                SEQ_LIFELINE_TAIL_PADDING

        // 4. LayoutNodes für die sichtbaren Lifelines — Breite vom SizeProvider,
        //    Höhe von der Vorausberechnung (überschreibt SizeProvider-Höhe).
        val nodes =
            visibleLifelines.map { lifeline ->
                val baseSize = sizeProvider.sizeOf(elementId = lifeline.id, elementKind = "LifelineDefinition")
                LayoutNode(
                    id = NodeId(lifeline.id),
                    intrinsicSize = dev.kuml.layout.Size(width = baseSize.width, height = lifelineHeight),
                )
            }

        // 5. Keine Edges — Nachrichten werden vom Renderer direkt gezeichnet.
        //    Siehe Architektur-Divergenz oben. `visibleIds` ist hier nur als
        //    Sanity-Snapshot der sichtbaren Ids referenziert, wird sonst aber
        //    nicht weiter konsumiert.
        check(visibleIds.size >= visibleLifelineIds.size) { "Unreachable: visibleIds must cover all lifeline ids." }
        return LayoutGraph(nodes = nodes, edges = emptyList())
    }

    /**
     * Default-[SizeProvider] für SEQ-Diagramme (V2.0.11) — gibt die
     * Lifeline-Standardbreite [SEQ_LIFELINE_WIDTH] mit
     * [SEQ_LIFELINE_HEAD_HEIGHT] als Höhe zurück. Die *tatsächliche* Höhe
     * berechnet die Bridge pro Lifeline aus der Nachrichtenanzahl und
     * überschreibt damit den SizeProvider-Wert. Diese Form macht den
     * SizeProvider-Pattern-Aufruf konsistent mit den anderen Diagrammtypen,
     * auch wenn das Height-Feld hier nur als Fallback dient.
     */
    public fun seqDefaultSizeProvider(): SizeProvider =
        SizeProvider { _, kindHint ->
            when (kindHint) {
                "LifelineDefinition" -> dev.kuml.layout.Size(width = SEQ_LIFELINE_WIDTH, height = SEQ_LIFELINE_HEAD_HEIGHT)
                else -> dev.kuml.layout.Size(width = SEQ_LIFELINE_WIDTH, height = SEQ_LIFELINE_HEAD_HEIGHT)
            }
        }

    /**
     * Content-aware [SizeProvider] for SEQ diagrams. [seqDefaultSizeProvider]
     * always returns a fixed [SEQ_LIFELINE_WIDTH] head box regardless of
     * lifeline name length — [renderLifelineHead] (`Sysml2SequenceSvg.kt`,
     * kuml-io-svg) draws the `«lifeline»` stereotype and the name centered
     * with no clamp, so a long part/lifeline name overflows the fixed head
     * box (message *labels* between lifelines are already safely clamped via
     * `drawSeqLabelWithWhiteBackground`'s `maxWidth` — only the header box
     * itself had this gap). Width grows to fit the longer of the stereotype
     * and the name, reusing the same char-width constants as
     * [bddContentAwareSizeProvider]'s width formula. Only the *width* the
     * bridge assigns via [sizeProvider] changes — [toLayoutGraph] always
     * overrides the *height* from the message-count calculation, unaffected
     * by this provider.
     */
    public fun seqContentAwareSizeProvider(model: Sysml2Model): SizeProvider {
        // Diagram-level rule shared with the renderer: when any head wraps, the «lifeline» keyword is dropped
        // everywhere, so it no longer counts towards the width.
        val showKeyword = model.definitions.filterIsInstance<LifelineDefinition>().none { seqHeadLines(name = it.name).size > 1 }
        return SizeProvider { id, kindHint ->
            when (kindHint) {
                "LifelineDefinition" -> {
                    val lifeline = model.definitions.firstOrNull { it.id == id } as? LifelineDefinition
                    val width = seqHeadWidth(name = lifeline?.name.orEmpty(), showKeyword = showKeyword)
                    dev.kuml.layout.Size(width = width, height = SEQ_LIFELINE_HEAD_HEIGHT)
                }
                else -> dev.kuml.layout.Size(width = SEQ_LIFELINE_WIDTH, height = SEQ_LIFELINE_HEAD_HEIGHT)
            }
        }
    }

    /**
     * Default-[SizeProvider] für ACT-Diagramme (V2.0.10) — gibt je nach
     * `kindHint` (Name eines [ActivityNodeKind]-Enum-Werts) die passenden
     * Default-Maße zurück:
     *  - `"Action"` → [ACT_ACTION_WIDTH] × [ACT_ACTION_HEIGHT]
     *  - `"Initial"` / `"Final"` / `"FlowFinal"` → quadratisch
     *    [ACT_PSEUDO_SIZE] × [ACT_PSEUDO_SIZE]
     *  - `"Decision"` / `"Merge"` → [ACT_DIAMOND_WIDTH] × [ACT_DIAMOND_HEIGHT]
     *  - `"Fork"` / `"Join"` → [ACT_BAR_WIDTH] × [ACT_BAR_HEIGHT] (horizontale
     *    Bar; Layout-Engine kann später flippen)
     *
     * Unbekannte Hints fallen auf die Action-Größe zurück.
     */
    public fun actDefaultSizeProvider(): SizeProvider =
        SizeProvider { _, kindHint ->
            when (kindHint) {
                ActivityNodeKind.Action.name ->
                    dev.kuml.layout.Size(width = ACT_ACTION_WIDTH, height = ACT_ACTION_HEIGHT)
                ActivityNodeKind.Initial.name,
                ActivityNodeKind.Final.name,
                ActivityNodeKind.FlowFinal.name,
                ->
                    dev.kuml.layout.Size(width = ACT_PSEUDO_SIZE, height = ACT_PSEUDO_SIZE)
                ActivityNodeKind.Decision.name,
                ActivityNodeKind.Merge.name,
                ->
                    dev.kuml.layout.Size(width = ACT_DIAMOND_WIDTH, height = ACT_DIAMOND_HEIGHT)
                ActivityNodeKind.Fork.name,
                ActivityNodeKind.Join.name,
                ->
                    dev.kuml.layout.Size(width = ACT_BAR_WIDTH, height = ACT_BAR_HEIGHT)
                ActivityNodeKind.SendSignal.name,
                ActivityNodeKind.AcceptEvent.name,
                ->
                    dev.kuml.layout.Size(width = ACT_SIGNAL_WIDTH, height = ACT_SIGNAL_HEIGHT)
                ActivityNodeKind.AcceptTimeEvent.name ->
                    dev.kuml.layout.Size(width = ACT_TIME_EVENT_W, height = ACT_TIME_EVENT_H + ACT_TIME_EVENT_LABEL_HEIGHT)
                ActivityNodeKind.CallBehavior.name ->
                    dev.kuml.layout.Size(width = ACT_ACTION_WIDTH + ACT_CALL_FORK_EXTRA_W, height = ACT_ACTION_HEIGHT)
                else -> dev.kuml.layout.Size(width = ACT_ACTION_WIDTH, height = ACT_ACTION_HEIGHT)
            }
        }

    /**
     * Rendered action-body text is truncated at this many characters by
     * [dev.kuml.io.svg.sysml2.renderRegularAction] (`ACT_BODY_MAX_LEN` in
     * `Sysml2ActivitySvg.kt`, kuml-io-svg). Duplicated here — per the
     * established house convention (`kuml-io-svg` cannot depend on
     * `kuml-layout-bridge`) — solely to cap the body-line contribution to
     * [actContentAwareSizeProvider]'s width estimate at the same length that
     * actually renders; MUST stay numerically identical to the io-svg
     * constant.
     */
    public const val ACT_BODY_MAX_LEN_FOR_SIZING: Int = 30

    /**
     * Content-aware [SizeProvider] for ACT (SysML 2 Activity) action boxes.
     *
     * [actDefaultSizeProvider] always returns a fixed [ACT_ACTION_WIDTH] ×
     * [ACT_ACTION_HEIGHT] box for every `Action` node — [renderRegularAction]
     * draws the action's `name` as a single unclamped, uncentered-overflow
     * `<text>` line with no wrap or truncation, so a long action name
     * overflows the fixed box (unlike the action *body*, which the renderer
     * already truncates at [ACT_BODY_MAX_LEN_FOR_SIZING] chars). This
     * provider grows width to fit the longer of the name and the
     * (truncation-capped) body line — mirrors
     * [bddContentAwareSizeProvider]'s width formula, reusing the same
     * char-width constants.
     *
     * Pseudo-nodes (Initial/Final/FlowFinal), diamonds (Decision/Merge) and
     * bars (Fork/Join) have no variable text inside their shape and keep
     * their fixed [actDefaultSizeProvider] sizes unchanged.
     */
    public fun actContentAwareSizeProvider(model: Sysml2Model): SizeProvider =
        SizeProvider { id, kindHint ->
            val def = model.definitions.firstOrNull { it.id == id } as? ActionDefinition
            val base: dev.kuml.layout.Size =
                when (kindHint) {
                    ActivityNodeKind.Action.name -> {
                        val nameW = (def?.name?.length ?: 0) * IBD_TITLE_CHAR_PX
                        val bodyLen = minOf(def?.action?.length ?: 0, ACT_BODY_MAX_LEN_FOR_SIZING)
                        val bodyW = bodyLen * BDD_BODY_CHAR_PX
                        val contentW = maxOf(nameW, bodyW) + 2 * IBD_H_PAD
                        dev.kuml.layout.Size(width = maxOf(contentW, ACT_ACTION_WIDTH), height = ACT_ACTION_HEIGHT)
                    }
                    ActivityNodeKind.Initial.name,
                    ActivityNodeKind.Final.name,
                    ActivityNodeKind.FlowFinal.name,
                    ->
                        dev.kuml.layout.Size(width = ACT_PSEUDO_SIZE, height = ACT_PSEUDO_SIZE)
                    ActivityNodeKind.Decision.name,
                    ActivityNodeKind.Merge.name,
                    -> {
                        // Wave 2F: from five arrivals on the diamond grows to max(28, n * 10 + 8) px so the arrivals keep
                        // their 8 px apart; capped at 16 arrivals (DoS guard).
                        val arrivals =
                            minOf(
                                ACT_MAX_DIAMOND_ARRIVALS,
                                model.usages.count { it is ControlFlowUsage && it.targetNodeId == id } +
                                    model.usages.count { it is ObjectFlowUsage && it.targetNodeId == id },
                            )
                        val side = if (arrivals >= 5) maxOf(ACT_DIAMOND_WIDTH, arrivals * 10f + 8f) else ACT_DIAMOND_WIDTH
                        dev.kuml.layout.Size(width = side, height = maxOf(ACT_DIAMOND_HEIGHT, side))
                    }
                    ActivityNodeKind.Fork.name,
                    ActivityNodeKind.Join.name,
                    -> {
                        // Wave C2b: the bar grows to (n + 1) * 16 px so arrivals / departures keep 16 px apart.
                        val flowsIn =
                            model.usages.count { it is ControlFlowUsage && it.targetNodeId == id } +
                                model.usages.count { it is ObjectFlowUsage && it.targetNodeId == id }
                        val flowsOut =
                            model.usages.count { it is ControlFlowUsage && it.sourceNodeId == id } +
                                model.usages.count { it is ObjectFlowUsage && it.sourceNodeId == id }
                        val n = maxOf(flowsIn, flowsOut)
                        dev.kuml.layout.Size(width = maxOf(ACT_BAR_WIDTH, (n + 1) * ACT_BAR_ANCHOR_GAP), height = ACT_BAR_HEIGHT)
                    }
                    ActivityNodeKind.SendSignal.name,
                    ActivityNodeKind.AcceptEvent.name,
                    -> {
                        // Wave 2F: the name is wrapped at word boundaries (never truncated); the shape grows with it.
                        val lines = ActTextWrap.signalLines(name = def?.name.orEmpty(), signal = def?.signal)
                        val longest = lines.maxOf { it.length }
                        dev.kuml.layout.Size(
                            width = maxOf(longest * IBD_TITLE_CHAR_PX + 2 * IBD_H_PAD + ACT_SIGNAL_NOTCH, ACT_SIGNAL_WIDTH),
                            height = maxOf(ACT_SIGNAL_HEIGHT, lines.size * ACT_SIGNAL_LINE_PX + 16f),
                        )
                    }
                    ActivityNodeKind.AcceptTimeEvent.name -> {
                        val lines = ActTextWrap.timeEventLines(name = def?.name, time = def?.timeExpression).all
                        val labelW = lines.maxOf { it.length } * ACT_LABEL_CHAR_PX + 8f
                        dev.kuml.layout.Size(
                            width = maxOf(labelW.coerceAtLeast(ACT_TIME_EVENT_W), ACT_TIME_EVENT_W),
                            height = ACT_TIME_EVENT_H + ACT_TIME_EVENT_LABEL_HEIGHT + (lines.size - 1) * ACT_TIME_LINE_PX,
                        )
                    }
                    ActivityNodeKind.CallBehavior.name -> {
                        val nameW = (def?.name?.length ?: 0) * IBD_TITLE_CHAR_PX
                        val contentW = nameW + 2 * IBD_H_PAD + ACT_CALL_FORK_EXTRA_W
                        dev.kuml.layout.Size(width = maxOf(contentW, ACT_ACTION_WIDTH + ACT_CALL_FORK_EXTRA_W), height = ACT_ACTION_HEIGHT)
                    }
                    else -> dev.kuml.layout.Size(width = ACT_ACTION_WIDTH, height = ACT_ACTION_HEIGHT)
                }
            actPinGrowth(base = base, def = def)
        }

    /** Wave 2F — line height (px) of a wrapped signal name. */
    internal const val ACT_SIGNAL_LINE_PX: Float = 15f

    /** Wave 2F — line height (px) of a wrapped time-event label. */
    internal const val ACT_TIME_LINE_PX: Float = 13f

    /** Wave 2F — arrivals counted for the diamond growth. */
    internal const val ACT_MAX_DIAMOND_ARRIVALS: Int = 16

    /** Wave C2 — characters rendered at most in a signal / time-event label. */
    internal const val ACT_LABEL_MAX_CHARS: Int = 40

    /** Wave C2 — approximate width of one 11 px label character. */
    internal const val ACT_LABEL_CHAR_PX: Float = 6.2f

    /** Wave C2 — depth of the notch (accept-event) or tip (send-signal) of the signal shapes. */
    internal const val ACT_SIGNAL_NOTCH: Float = 14f

    /**
     * Wave C2 — enlarges [base] for pins on an *explicit* side ([dev.kuml.sysml2.PinSide] other than
     * `Auto`): top / bottom pins widen the box, left / right pins heighten it, [ACT_PIN_GROWTH] per pin.
     * Boxes without explicit pin sides keep their size exactly (byte-compat for existing diagrams).
     */
    internal fun actPinGrowth(
        base: dev.kuml.layout.Size,
        def: ActionDefinition?,
    ): dev.kuml.layout.Size {
        if (def == null || def.pins.none { it.side != dev.kuml.sysml2.PinSide.Auto }) return base
        val perSide = def.pins.groupingBy { it.effectiveSide }.eachCount()

        fun need(side: dev.kuml.sysml2.PinSide): Float = (perSide[side] ?: 0).coerceAtMost(ACT_MAX_PINS_PER_SIDE) * ACT_PIN_GROWTH + 24f
        val w = maxOf(base.width, need(dev.kuml.sysml2.PinSide.Top), need(dev.kuml.sysml2.PinSide.Bottom))
        val h = maxOf(base.height, need(dev.kuml.sysml2.PinSide.Left), need(dev.kuml.sysml2.PinSide.Right))
        return dev.kuml.layout.Size(width = w, height = h)
    }

    /** Wave C2 — maximum number of pins per side taken into account for sizing (the checker flags more). */
    public const val ACT_MAX_PINS_PER_SIDE: Int = 8

    /**
     * Default-[SizeProvider] für STM-Diagramme (V2.0.9) — gibt je nach
     * `kindHint` (`"InitialPseudoState"` / `"FinalPseudoState"` /
     * `"StateDefinition"`) die passenden Default-Maße zurück. Pseudo-States
     * sind quadratisch ([STM_PSEUDO_SIZE]) damit der Renderer den Kreis bzw.
     * Donut bequem zentrieren kann; reguläre Zustände nutzen die
     * BDD-ähnlichen Box-Maße ([STM_STATE_WIDTH] × [STM_STATE_HEIGHT]).
     */
    public fun stmDefaultSizeProvider(): SizeProvider =
        SizeProvider { _, kindHint ->
            when (kindHint) {
                "InitialPseudoState", "FinalPseudoState" ->
                    dev.kuml.layout.Size(width = STM_PSEUDO_SIZE, height = STM_PSEUDO_SIZE)
                STM_HINT_CHOICE -> dev.kuml.layout.Size(width = STM_CHOICE_SIZE, height = STM_CHOICE_SIZE)
                STM_HINT_JUNCTION -> dev.kuml.layout.Size(width = STM_JUNCTION_SIZE, height = STM_JUNCTION_SIZE)
                STM_HINT_HISTORY -> dev.kuml.layout.Size(width = STM_HISTORY_SIZE, height = STM_HISTORY_SIZE)
                STM_HINT_FORK_JOIN -> stmForkJoinBarSize(transitionCount = 2, layoutDirection = LayoutDirection.TopToBottom)
                "StateDefinition" -> dev.kuml.layout.Size(width = STM_STATE_WIDTH, height = STM_STATE_HEIGHT)
                else -> dev.kuml.layout.Size(width = STM_STATE_WIDTH, height = STM_STATE_HEIGHT)
            }
        }

    /**
     * Default-[SizeProvider] für REQ-Diagramme (V2.0.8) — gibt je nach
     * `kindHint` (`"RequirementDefinition"` vs `"ActorDefinition"` vs
     * `"UseCaseDefinition"` vs `"PartDefinition"`) die passenden Default-
     * Maße zurück. Andere Kinds fallen auf [REQ_DEFAULT_WIDTH] ×
     * [REQ_DEFAULT_HEIGHT] zurück.
     */
    public fun reqDefaultSizeProvider(): SizeProvider =
        SizeProvider { _, kindHint ->
            when (kindHint) {
                "RequirementDefinition" -> dev.kuml.layout.Size(width = REQ_DEFAULT_WIDTH, height = REQ_DEFAULT_HEIGHT)
                "ActorDefinition" -> dev.kuml.layout.Size(width = UC_ACTOR_WIDTH, height = UC_ACTOR_HEIGHT)
                "UseCaseDefinition" -> dev.kuml.layout.Size(width = UC_USECASE_WIDTH, height = UC_USECASE_HEIGHT)
                "PartDefinition" -> dev.kuml.layout.Size(width = DEFAULT_WIDTH, height = DEFAULT_HEIGHT)
                else -> dev.kuml.layout.Size(width = REQ_DEFAULT_WIDTH, height = REQ_DEFAULT_HEIGHT)
            }
        }

    /**
     * Default-[SizeProvider] für UC-Diagramme — gibt je nach `kindHint`
     * (`"ActorDefinition"` vs `"UseCaseDefinition"`) die passenden
     * `UC_*`-Default-Maße zurück. Andere Kinds fallen auf die UseCase-
     * Default-Größe zurück.
     */
    public fun ucDefaultSizeProvider(): SizeProvider =
        SizeProvider { _, kindHint ->
            when (kindHint) {
                "ActorDefinition" -> dev.kuml.layout.Size(width = UC_ACTOR_WIDTH, height = UC_ACTOR_HEIGHT)
                "UseCaseDefinition" -> dev.kuml.layout.Size(width = UC_USECASE_WIDTH, height = UC_USECASE_HEIGHT)
                else -> dev.kuml.layout.Size(width = UC_USECASE_WIDTH, height = UC_USECASE_HEIGHT)
            }
        }

    /**
     * Content-aware [SizeProvider] for UC diagrams. [ucDefaultSizeProvider]
     * always returns fixed [UC_ACTOR_WIDTH]/[UC_USECASE_WIDTH] boxes
     * regardless of name length — [reqContentAwareSizeProvider] already grows
     * `UseCaseDefinition` correctly (a long use-case name like `"Rate
     * Tradesperson (Symmetric, Double-Blind)"` overflowed the fixed ellipse
     * only in a *pure* UC diagram, not inside a REQ diagram, since only REQ
     * had a content-aware path). This gives UC diagrams the identical
     * content-aware sizing, via the shared [ucActorContentSize] /
     * [ucUseCaseContentSize] helpers.
     */
    public fun ucContentAwareSizeProvider(
        model: Sysml2Model,
        extensionPoints: Map<String, List<String>> = emptyMap(),
    ): SizeProvider =
        SizeProvider { id, kindHint ->
            when (kindHint) {
                "ActorDefinition" -> {
                    val actor = model.definitions.firstOrNull { it.id == id } as? ActorDefinition
                    ucActorContentSize(name = actor?.name)
                }
                "UseCaseDefinition" -> {
                    val uc = model.definitions.firstOrNull { it.id == id } as? UseCaseDefinition
                    ucUseCaseContentSize(
                        name = uc?.name,
                        description = uc?.description.orEmpty(),
                        extensionPoints = extensionPoints[id].orEmpty(),
                    )
                }
                else -> {
                    val def = model.definitions.firstOrNull { it.id == id }
                    ucUseCaseContentSize(name = def?.name)
                }
            }
        }

    /**
     * Übersetzt das gegebene PAR-Diagramm (V2.0.12) in einen [LayoutGraph] —
     * die **achte** und **letzte** Übersetzung der SysML-2-Diagramm-Linie.
     *
     * MVP-Scope:
     *  - Jede in [ParDiagram.elementIds] referenzierte [ConstraintDefinition]
     *    wird ein [LayoutNode] mit den `PAR_CONSTRAINT_*`-Default-Maßen.
     *  - Jede in [ParDiagram.elementIds] referenzierte [PartDefinition] wird
     *    ein [LayoutNode] mit den `DEFAULT_*`-Maßen (BDD-Box-Größe). Andere
     *    Definition-Kinds (AttributeDefinition, PortDefinition, ConnectionDefinition,
     *    Actor, UseCase, Requirement, State, Action, Lifeline) sind im PAR-
     *    Diagramm konzeptionell nicht vorgesehen und werden stillschweigend
     *    übersprungen — Konsistenzprüfung ist Validator-Sache.
     *  - Bindings werden aus `model.usages.filterIsInstance<BindingConnectorUsage>()`
     *    gezogen — nicht aus dem Diagramm. Endpunkt-Resolution per
     *    **Longest-Prefix-Match** (gleiche Heuristik wie V2.0.6 IBD-
     *    [ConnectionUsage]): die längste sichtbare Element-ID, die ein Präfix
     *    des Endpunkts ist, gilt als die enthaltende sichtbare Einheit. So
     *    löst `"Vehicle::mass"` zum sichtbaren `Vehicle`-Knoten auf, und
     *    `"NewtonsLaw::m"` zum sichtbaren `NewtonsLaw`-Constraint-Knoten.
     *  - Eine Binding wird genau dann zur [LayoutEdge], wenn *beide* Endpunkte
     *    auf sichtbare Elemente auflösen. Dangling-Bindings (Endpunkt löst
     *    nicht auf) werden stillschweigend übersprungen.
     *
     * **Architektur-Begründung** für "Bindings leben auf dem Modell, nicht
     * auf dem Diagramm" (wie V2.0.6 IBD / V2.0.9 STM / V2.0.10 ACT, anders
     * als V2.0.7 UC / V2.0.8 REQ): Bindings sind ein integraler Teil der
     * Constraint-Topologie (die zukünftige parametrische Solver-Welle braucht
     * sie zur Laufzeit für die Wert-Propagation), während UC-/REQ-Edges
     * reine Diagramm-Aussagen sind. Das PAR-Diagramm ist eine *Projektion*
     * der Knoten; die Edges entstehen automatisch aus dem Modell.
     *
     * Edge-Stilunterscheidung: alle Bindings tragen [EdgeHints.NONE] — der
     * Parameter-Pin-Anchor-Punkt (zeichne die Edge direkt am Pin statt am
     * Knoten-Mittelpunkt) ist V2.x-Polish. Die synthetische `KumlDiagram`-
     * Hülle hat keine `UmlRelationship`-Elemente für `BindingConnectorUsage`s,
     * deshalb fällt der Edge-Renderer auf den Plain-Pfad zurück — gleiche
     * Limitation wie UC / REQ / STM / ACT.
     *
     * **Edge-ID-Konvention**: jede Binding behält ihre `BindingConnectorUsage.id`
     * als Edge-ID. Damit bleibt der Edge-Identifier stabil — auch wenn die
     * gleiche Source/Target-Auflösung durch zwei verschiedene Bindings
     * passiert (z.B. wenn ein Vehicle zwei verschiedene NewtonsLaw-
     * Constraint-Instanzen erfüllt), bleiben die Edges unterscheidbar.
     *
     * @param model Container mit allen Definitionen + Usages (Bindings werden
     *   aus `model.usages` gelesen).
     * @param diagram Das PAR-Diagramm (`elementIds` selektiert die sichtbaren
     *   Constraints + Parts; Bindings kommen vom Modell).
     * @param sizeProvider Liefert die intrinsische Größe pro Definition.
     *   Default nutzt die `PAR_CONSTRAINT_*`-Konstanten für ConstraintDefinitions
     *   und die `DEFAULT_*`-Konstanten (BDD-Default) für PartDefinitions.
     */
    public fun toLayoutGraph(
        model: Sysml2Model,
        diagram: ParDiagram,
        sizeProvider: SizeProvider = parContentAwareSizeProvider(model),
    ): LayoutGraph {
        val nesting = ParNesting.of(model = model, visibleIds = diagram.elementIds)

        fun ownSize(id: String) = sizeProvider.sizeOf(elementId = id, elementKind = "ConstraintDefinition")
        val plan = ParLanePlan.of(model = model, nesting = nesting, visible = diagram.elementIds.toSet())
        val boxMemo = HashMap<String, ParBox>()
        val partAttributes = parPartAttributes(model = model, visibleIds = diagram.elementIds)
        val attributeAnchors =
            parAttributeAnchors(model = model, visibleIds = diagram.elementIds, nesting = nesting, partAttributes = partAttributes)
        val nodes = mutableListOf<LayoutNode>()
        for (id in diagram.elementIds) {
            val def = model.definitions.firstOrNull { it.id == id } ?: continue
            // PAR-Diagramme zeigen Constraints (primär) + Parts (für deren
            // Attribute-Referenzen). Alles andere ist konzeptionell nicht
            // vorgesehen und wird ignoriert.
            if (def !is ConstraintDefinition && def !is PartDefinition) continue
            // Verschachtelte Constraints sind keine Layout-Knoten: sie liegen im Elternknoten (C4).
            if (def is ConstraintDefinition && nesting.isNested(def.id)) continue
            val kind = def::class.simpleName ?: "Sysml2Definition"
            val size =
                if (def is ConstraintDefinition) {
                    ParCompositeLayout
                        .box(
                            id = def.id,
                            model = model,
                            nesting = nesting,
                            plan = plan,
                            ownSize = ::ownSize,
                            memo = boxMemo,
                        ).size
                } else {
                    sizeProvider.sizeOf(elementId = def.id, elementKind = kind)
                }
            val ports =
                if (def is ConstraintDefinition) {
                    val exprLines = ParConstraintGeometry.expressionLinesOf(def).size
                    val bySide = def.parameters.groupBy { ParConstraintGeometry.pinSide(it.direction) }
                    def.parameters.mapIndexed { i, p ->
                        val side = ParConstraintGeometry.pinSide(p.direction)
                        LayoutPort(
                            id = ParConstraintGeometry.portId(constraint = def, parameter = p),
                            side = side,
                            order = bySide.getValue(side).indexOf(p),
                            size = Size(width = ParConstraintGeometry.PIN_SIZE, height = ParConstraintGeometry.PIN_SIZE),
                            offset = ParConstraintGeometry.pinY(expressionLines = exprLines, index = i),
                        )
                    }
                } else {
                    // Part: one anchor port per bound attribute row (never visible, see parAttributePortId).
                    val bySide = attributeAnchors[def.id].orEmpty().entries.groupBy({ it.value }, { it.key })
                    attributeAnchors[def.id].orEmpty().map { (index, side) ->
                        LayoutPort(
                            id = PortId(parAttributePortId(partId = def.id, attributeName = partAttributes.getValue(def.id)[index])),
                            side = side,
                            order = bySide.getValue(side).sorted().indexOf(index),
                            size = Size(width = 0f, height = 0f),
                            offset = BddBoxGeometry.featureRowCentreY(index),
                        )
                    }
                }
            nodes += LayoutNode(id = NodeId(def.id), intrinsicSize = size, ports = ports)
        }
        val visibleNodeIds: Set<String> = diagram.elementIds.toSet()
        val layoutNodeIds: Set<String> = nodes.map { it.id.value }.toSet()

        // Bindings aus dem Modell ziehen (nicht aus dem Diagramm) — siehe
        // KDoc-Begründung oben. Eine Binding wird zur Edge, wenn beide
        // Endpunkte per Longest-Prefix-Match auf sichtbare Knoten auflösen;
        // sonst stillschweigend übersprungen. Seit C4 endet eine Kante an dem
        // Parameter-Pin, den der Endpunkt benennt; Bindings innerhalb eines
        // verschachtelten Constraints (Eltern/Kind, Geschwister) routet
        // [parEnrich] nach dem Layout.
        val edges = mutableListOf<LayoutEdge>()
        for (binding in model.usages.filterIsInstance<BindingConnectorUsage>()) {
            val src =
                parResolveEnd(
                    endpoint = binding.sourceEndId,
                    visibleIds = visibleNodeIds,
                    nesting = nesting,
                    partAttributes = partAttributes,
                )
                    ?: continue
            val tgt =
                parResolveEnd(
                    endpoint = binding.targetEndId,
                    visibleIds = visibleNodeIds,
                    nesting = nesting,
                    partAttributes = partAttributes,
                )
                    ?: continue
            val srcRoot = if (src.nodeId in nesting.constraints) nesting.rootOf(src.nodeId) else src.nodeId
            val tgtRoot = if (tgt.nodeId in nesting.constraints) nesting.rootOf(tgt.nodeId) else tgt.nodeId
            if (srcRoot !in layoutNodeIds || tgtRoot !in layoutNodeIds) continue
            if (src.nodeId in nesting.constraints &&
                tgt.nodeId in nesting.constraints &&
                srcRoot == tgtRoot &&
                src.nodeId != tgt.nodeId
            ) {
                continue
            }

            fun ref(
                end: ParEnd,
                root: String,
            ): EndpointRef {
                val c = nesting.constraints[end.nodeId]
                val port =
                    if (c != null && end.nodeId == root && end.parameterIndex != null) {
                        ParConstraintGeometry.portId(constraint = c, parameter = c.parameters[end.parameterIndex])
                    } else if (c == null &&
                        end.attributeIndex != null &&
                        attributeAnchors[end.nodeId]?.containsKey(end.attributeIndex) == true
                    ) {
                        PortId(
                            parAttributePortId(
                                partId = end.nodeId,
                                attributeName = partAttributes.getValue(end.nodeId)[end.attributeIndex],
                            ),
                        )
                    } else {
                        null
                    }
                return EndpointRef(nodeId = NodeId(root), portId = port)
            }
            edges +=
                LayoutEdge(
                    id = EdgeId(binding.id),
                    source = ref(end = src, root = srcRoot),
                    target = ref(end = tgt, root = tgtRoot),
                    hints = EdgeHints.NONE,
                )
        }

        return LayoutGraph(nodes = nodes, edges = edges).withLayoutSpec(diagram = diagram)
    }

    /** Id of the invisible anchor port of a part attribute row; never drawn, it only fixes where a binding ends. */
    internal fun parAttributePortId(
        partId: String,
        attributeName: String,
    ): String = "$partId::attr::$attributeName"

    /** Feature names of the visible parts in row order; the index of a name is its row in the drawn box. */
    private fun parPartAttributes(
        model: Sysml2Model,
        visibleIds: List<String>,
    ): Map<String, List<String>> =
        model.definitions
            .filterIsInstance<PartDefinition>()
            .filter { it.id in visibleIds }
            .associate { part -> part.id to part.features.map { it.name } }

    /**
     * Anchor ports of the part boxes: for every attribute row that a binding addresses (`Part::attr`) one side
     * (`row index -> side`). The side follows the pin the binding ends at (West pin -> West, East pin -> East); any
     * other binding uses East. Attributes that no binding references get no port, so diagrams without attribute
     * bindings stay unchanged.
     */
    private fun parAttributeAnchors(
        model: Sysml2Model,
        visibleIds: List<String>,
        nesting: ParNesting,
        partAttributes: Map<String, List<String>>,
    ): Map<String, Map<Int, PortSide>> {
        val visible = visibleIds.toSet()
        val out = LinkedHashMap<String, MutableMap<Int, PortSide>>()
        for (b in model.usages.filterIsInstance<BindingConnectorUsage>()) {
            val a =
                parResolveEnd(endpoint = b.sourceEndId, visibleIds = visible, nesting = nesting, partAttributes = partAttributes)
                    ?: continue
            val z =
                parResolveEnd(endpoint = b.targetEndId, visibleIds = visible, nesting = nesting, partAttributes = partAttributes)
                    ?: continue
            for ((own, other) in listOf(a to z, z to a)) {
                val index = own.attributeIndex ?: continue
                val pin = other.parameterIndex?.takeIf { other.nodeId in nesting.constraints && !nesting.isNested(other.nodeId) }
                val side =
                    if (pin != null) {
                        ParConstraintGeometry.pinSide(
                            nesting.constraints
                                .getValue(other.nodeId)
                                .parameters[pin]
                                .direction,
                        )
                    } else {
                        PortSide.East
                    }
                val sides = out.getOrPut(own.nodeId) { LinkedHashMap() }
                if (index !in sides) sides[index] = side
            }
        }
        return out
    }

    /**
     * Default-[SizeProvider] für PAR-Diagramme (V2.0.12) — gibt je nach
     * `kindHint` (`"ConstraintDefinition"` vs `"PartDefinition"`) die passenden
     * Default-Maße zurück:
     *  - `"ConstraintDefinition"` → [PAR_CONSTRAINT_WIDTH] × [PAR_CONSTRAINT_HEIGHT]
     *  - `"PartDefinition"` → [DEFAULT_WIDTH] × [DEFAULT_HEIGHT] (BDD-Default)
     *  - alles andere → fällt auf die Constraint-Größe zurück (defensive
     *    Default, falls künftige Polish-Wellen weitere Kind-Hints einführen).
     */
    public fun parDefaultSizeProvider(): SizeProvider =
        SizeProvider { _, kindHint ->
            when (kindHint) {
                "ConstraintDefinition" -> dev.kuml.layout.Size(width = PAR_CONSTRAINT_WIDTH, height = PAR_CONSTRAINT_HEIGHT)
                "PartDefinition" -> dev.kuml.layout.Size(width = DEFAULT_WIDTH, height = DEFAULT_HEIGHT)
                else -> dev.kuml.layout.Size(width = PAR_CONSTRAINT_WIDTH, height = PAR_CONSTRAINT_HEIGHT)
            }
        }

    /**
     * Completes the engine result of a parametric diagram (wave C4): places the nested constraint boxes inside
     * their owner box, gives them pin positions and routes the bindings that stay inside one composite
     * (owner pin to child pin, sibling to sibling). Top-level constraints and external bindings are untouched; their
     * edges already end at the pins because the layout graph declares them as fixed-position ports.
     *
     * Idempotent, and the identity for diagrams without nesting. The SVG renderer calls it, so the callers of
     * `toSvg(model, diagram, layoutResult)` need no extra step; tests and other consumers can call it directly.
     */
    public fun parEnrich(
        model: Sysml2Model,
        diagram: ParDiagram,
        layoutResult: LayoutResult,
        sizeProvider: SizeProvider = parContentAwareSizeProvider(model),
    ): LayoutResult =
        ParCompositeLayout.enrich(
            model = model,
            visibleIds = diagram.elementIds,
            layout = layoutResult,
            ownSize = { id -> sizeProvider.sizeOf(elementId = id, elementKind = "ConstraintDefinition") },
        )

    /** All pin squares of a parametric diagram, absolute, for the label planner (PAL wave 2C-b3). */
    public fun parPinRects(
        model: Sysml2Model,
        diagram: ParDiagram,
        layout: LayoutResult,
    ): List<dev.kuml.layout.Rect> = Sysml2ParLabelGeometry.pinRects(model = model, diagram = diagram, layout = layout)

    /** Estimated text rectangles (stereotype, name, expression, parameters) of all constraint boxes, absolute. */
    public fun parTextRects(
        model: Sysml2Model,
        diagram: ParDiagram,
        layout: LayoutResult,
    ): List<dev.kuml.layout.Rect> = Sysml2ParLabelGeometry.textRects(model = model, diagram = diagram, layout = layout)

    /** `KUML-LAYOUT-W005` findings for binding labels without a free spot. */
    public fun parLabelWarnings(plan: dev.kuml.layout.bridge.label.Sysml2LabelPlanResult): List<LayoutSpecFinding> =
        Sysml2ParLabelGeometry.warnings(plan)

    /**
     * Avg width of an 11pt monospace char (constraint expression compartment,
     * `font-family="monospace"` in [dev.kuml.io.svg.sysml2.renderConstraintDefinition]).
     * Monospace glyphs at a given point size run wider on average than the
     * proportional [BDD_BODY_CHAR_PX] estimate, since every glyph is sized to
     * fit the widest character in the font.
     */
    @Deprecated("Use ParConstraintGeometry.EXPRESSION_CHAR_PX", ReplaceWith("ParConstraintGeometry.EXPRESSION_CHAR_PX"))
    public const val PAR_EXPRESSION_CHAR_PX: Float = ParConstraintGeometry.EXPRESSION_CHAR_PX

    /**
     * Content-aware [SizeProvider] for PAR diagrams (V2.0.44).
     *
     * ConstraintDefinition height now grows with parameter count instead of
     * being clamped at [PAR_CONSTRAINT_HEIGHT] = 100 px (which truncated any
     * constraint with > 2 parameters — newton-second-law-par showed the third
     * `«in» a : Acceleration` line running outside the box).
     *
     * Width also grows to fit the longest of: the name, the (truncation-
     * capped) expression, and each formatted parameter line
     * (`"«in» accelerationValue : AccelerationMagnitude"`) — previously
     * fixed at [PAR_CONSTRAINT_WIDTH] regardless of content, which could
     * still overflow even though the expression itself is bounded by
     * `EXPRESSION_MAX_CHARS` (30 chars) at render time.
     *
     * Height layout: `«constraint»` stereotype + name + expression compartment +
     * parameter compartment (one line per parameter), each separated by a
     * divider gap.
     *
     * PartDefinitions defer to [bddContentAwareSizeProvider] (already
     * content-aware), so the two PartUsage boxes in newton-second-law-par
     * shrink to their actual feature count.
     */
    public fun parContentAwareSizeProvider(model: Sysml2Model): SizeProvider {
        val bdd = bddContentAwareSizeProvider(model)
        val units = ParConstraintGeometry.unitLookup(model)
        return SizeProvider { id, kindHint ->
            when (kindHint) {
                "ConstraintDefinition" -> {
                    val c = model.definitions.firstOrNull { it.id == id } as? ConstraintDefinition
                    val h = c?.let { ParConstraintGeometry.contentHeight(it) } ?: PAR_CONSTRAINT_HEIGHT
                    val nameW = (c?.name?.length ?: 0) * IBD_TITLE_CHAR_PX
                    val exprW =
                        (c?.let { ParConstraintGeometry.expressionLinesOf(it) }.orEmpty().maxOfOrNull { it.length } ?: 0) *
                            ParConstraintGeometry.EXPRESSION_CHAR_PX
                    val paramMaxW =
                        c?.parameters?.maxOfOrNull { p ->
                            ParConstraintGeometry.parameterLine(p = p, unitOf = units).length * BDD_BODY_CHAR_PX +
                                ParConstraintGeometry.paramTextIndent(model = model, constraint = c)
                        } ?: 0f
                    val contentW = maxOf(nameW, exprW, paramMaxW) + 2 * IBD_H_PAD
                    dev.kuml.layout.Size(width = maxOf(contentW, PAR_CONSTRAINT_WIDTH), height = h)
                }
                "PartDefinition" -> bdd.sizeOf(elementId = id, elementKind = kindHint)
                else -> dev.kuml.layout.Size(width = PAR_CONSTRAINT_WIDTH, height = PAR_CONSTRAINT_HEIGHT)
            }
        }
    }

    /**
     * Enriches a raw [LayoutResult] with port positions derived from the
     * edge-route endpoints of IBD connections.
     *
     * For each [ConnectionUsage] whose id starts with `diagram.ownerId::`,
     * the function:
     *  1. Looks up the edge route `"conn:<connectionId>"` in [layoutResult].
     *  2. Extracts the source/target attachment points.
     *  3. Derives the port name from the endpoint id suffix after the node id
     *     (e.g. `"HybridVehicle::battery::dcOut"` on node `"HybridVehicle::battery"`
     *     → port `"dcOut"` at the source attachment point).
     *  4. Converts the absolute canvas point to **node-local coordinates**
     *     (relative to the node's `bounds.origin` in the raw layout).
     *  5. Stores the result in the corresponding `NodeLayout.ports` entry.
     *
     * Port positions are stored as local coordinates so that [dev.kuml.io.svg.sysml2.renderSysml2Usage]
     * can use them directly inside the `<g transform="translate(x,y)">` context
     * without an extra coordinate conversion.
     *
     * Returns the original [layoutResult] unchanged when there are no connections
     * or no recognisable port endpoints.
     */
    public fun enrichIbdPortPositions(
        model: Sysml2Model,
        diagram: IbdDiagram,
        layoutResult: LayoutResult,
    ): LayoutResult {
        val projection = sysml2IbdProjection(model = model, diagram = diagram)
        val wires =
            projection.connections.map { Triple("conn:${it.usage.id}", it.source, it.target) } +
                projection.ownLineFlows.map { Triple("flow:${it.usage.id}", it.source, it.target) } +
                projection.bindings.map { Triple(it.usage.id, it.source, it.target) }
        if (wires.isEmpty()) return layoutResult

        // portPositions[nodeId][portId] = local Point (relative to node origin), snapped onto the box border
        val portPositions = mutableMapOf<NodeId, MutableMap<PortId, Point>>()

        for ((edgeId, src, tgt) in wires) {
            val route = layoutResult.edges[EdgeId(edgeId)] ?: continue
            for ((end, point) in listOf(src to route.source, tgt to route.target)) {
                val nodeId = end.nodeId ?: continue
                if (end.steps.isEmpty()) continue
                val bounds = layoutResult.nodes[NodeId(nodeId)]?.bounds ?: continue
                val origin = bounds.origin
                val local = snapToBorder(p = Point(x = point.x - origin.x, y = point.y - origin.y), size = bounds.size)
                val key = PortId(end.steps.joinToString("::"))
                portPositions
                    .getOrPut(NodeId(nodeId)) { mutableMapOf() }
                    .let { map -> if (key !in map) map[key] = local }
            }
        }

        if (portPositions.isEmpty()) return layoutResult

        // PAL 2C-b1: ports on one side keep a pitch that fits their labels.
        val partLabels = ibdPartPortLabels(model = model, wires = ibdWires(projection))
        for ((nodeId, ports) in portPositions) {
            val size = layoutResult.nodes[nodeId]?.bounds?.size ?: continue
            val spread =
                ibdSpreadPorts(
                    boxSize = size,
                    ports = ports.mapKeys { it.key.value },
                    labels = partLabels[nodeId] ?: emptyMap(),
                    declarationIndex = ibdPortDeclarationOrder(model = model, nodeId = nodeId.value, keys = ports.keys.map { it.value }),
                )
            for (k in ports.keys.toList()) ports[k] = spread.getValue(k.value)
        }

        val enrichedNodes =
            layoutResult.nodes.mapValues { (nodeId, nodeLayout) ->
                val ports = portPositions[nodeId] ?: return@mapValues nodeLayout
                nodeLayout.copy(ports = ports)
            }

        // PAL 2C (P-IBD-08): edges end on the outer border of the port square, perpendicular to the box side.
        fun attachment(end: dev.kuml.sysml2.projection.ResolvedEnd): PortAttachment? {
            val nodeId = end.nodeId?.let { NodeId(it) } ?: return null
            if (end.steps.isEmpty()) return null
            val bounds = layoutResult.nodes[nodeId]?.bounds ?: return null
            val local = portPositions[nodeId]?.get(PortId(end.steps.joinToString("::"))) ?: return null
            val side = ibdNearestSide(p = local, w = bounds.size.width, h = bounds.size.height)
            return PortAttachment(rect = ibdPortSquare(boxBounds = bounds, local = local), face = ibdFace(side = side))
        }
        val adapter = IbdEdgeAdapter(model = model, diagram = diagram)
        val clampedEdges =
            LinkedHashMap(
                layoutResult.edges.mapValues { (eid, route) ->
                    val w = wires.firstOrNull { it.first == eid.value } ?: return@mapValues route
                    val srcAtt = attachment(w.second)
                    val tgtAtt = attachment(w.third)
                    val (srcArrow, tgtArrow) = Sysml2EdgeArrowEnds.of(adapter.metadataFor(eid.value))
                    val ends = setOfNotNull(w.second.nodeId, w.third.nodeId)
                    val foreign =
                        layoutResult.nodes
                            .filterKeys { it.value !in ends }
                            .values
                            .map { it.bounds }
                    val own =
                        layoutResult.nodes
                            .filterKeys { it.value in ends }
                            .values
                            .map { it.bounds }
                    ibdRouteAndClamp(
                        route = route,
                        source = srcAtt,
                        target = tgtAtt,
                        sourceArrowhead = srcArrow,
                        targetArrowhead = tgtArrow,
                        foreign = foreign,
                        own = own,
                    )
                },
            )
        return layoutResult.copy(nodes = enrichedNodes, edges = clampedEdges)
    }

    /**
     * Routes an edge between two port attachments around the given part bounds ([foreign] parts keep a clearance, [own]
     * are the two connected parts, [extra] are further rectangles such as foreign port labels and squares) and clamps it onto the port borders. Without both attachments the original route is
     * only clamped. After clamping the result is checked against the parts once more; on a hit the edge is re-routed
     * once with the full search.
     */
    private fun ibdRouteAndClamp(
        route: EdgeRoute,
        source: PortAttachment?,
        target: PortAttachment?,
        sourceArrowhead: Boolean,
        targetArrowhead: Boolean,
        foreign: List<Rect>,
        own: List<Rect>,
        extra: List<Rect> = emptyList(),
    ): EdgeRoute {
        if (source == null || target == null) {
            return PortEdgeClamp.clamp(
                route = route,
                source = source,
                target = target,
                sourceArrowhead = sourceArrowhead,
                targetArrowhead = targetArrowhead,
            )
        }

        fun attempt(forceSearch: Boolean): EdgeRoute {
            val routed =
                ibdRouteBetweenPorts(
                    source = source,
                    target = target,
                    sourceArrowhead = sourceArrowhead,
                    targetArrowhead = targetArrowhead,
                    foreign = foreign,
                    own = own,
                    forceSearch = forceSearch,
                    extra = extra,
                )
            return PortEdgeClamp.clamp(
                route = routed,
                source = source,
                target = target,
                sourceArrowhead = sourceArrowhead,
                targetArrowhead = targetArrowhead,
            )
        }

        fun hits(r: EdgeRoute): Boolean {
            val pts = routePoints(r)
            val (from, to) =
                ibdStubPoints(
                    source = source,
                    target = target,
                    sourceArrowhead = sourceArrowhead,
                    targetArrowhead = targetArrowhead,
                )
            val relevant = IbdObstacleRouter.extrasClearOfEnds(extraObstacles = extra, from = from, to = to)
            return IbdObstacleRouter.collisions(path = pts, obstacles = foreign + relevant) > 0
        }
        val first = attempt(forceSearch = false)
        return if (hits(first)) attempt(forceSearch = true) else first
    }

    private const val IBD_CORNER_RADIUS_PX = 4f

    /** Start and end point of the routed part of a port-to-port edge (border point plus stub along the port normal). */
    private fun ibdStubPoints(
        source: PortAttachment,
        target: PortAttachment,
        sourceArrowhead: Boolean,
        targetArrowhead: Boolean,
    ): Pair<Point, Point> {
        val bs = PortEdgeClamp.borderPoint(source)
        val bt = PortEdgeClamp.borderPoint(target)
        val ns = PortEdgeClamp.normal(source.face)
        val nt = PortEdgeClamp.normal(target.face)
        val stubS = if (sourceArrowhead) PortEdgeClamp.arrowStubPx(IBD_CORNER_RADIUS_PX) else IBD_CORNER_RADIUS_PX
        val stubT = if (targetArrowhead) PortEdgeClamp.arrowStubPx(IBD_CORNER_RADIUS_PX) else IBD_CORNER_RADIUS_PX
        return Point(x = bs.x + ns.x * stubS, y = bs.y + ns.y * stubS) to Point(x = bt.x + nt.x * stubT, y = bt.y + nt.y * stubT)
    }

    private fun ibdRouteBetweenPorts(
        source: PortAttachment,
        target: PortAttachment,
        sourceArrowhead: Boolean,
        targetArrowhead: Boolean,
        foreign: List<Rect>,
        own: List<Rect>,
        forceSearch: Boolean,
        extra: List<Rect> = emptyList(),
    ): EdgeRoute {
        val bs = PortEdgeClamp.borderPoint(source)
        val bt = PortEdgeClamp.borderPoint(target)
        val ns = PortEdgeClamp.normal(source.face)
        val nt = PortEdgeClamp.normal(target.face)
        val (from, to) =
            ibdStubPoints(
                source = source,
                target = target,
                sourceArrowhead = sourceArrowhead,
                targetArrowhead = targetArrowhead,
            )
        val inner =
            IbdObstacleRouter.route(
                from = from,
                fromDir = ns,
                to = to,
                toDir = nt,
                obstacles = foreign,
                endObstacles = own,
                forceSearch = forceSearch,
                extraObstacles = extra,
            )
        val all = PortEdgeClamp.simplify(listOf(bs) + inner + listOf(bt))
        var minSeg = Float.MAX_VALUE
        for (i in 0 until all.size - 1) {
            val l = kotlin.math.abs(all[i + 1].x - all[i].x) + kotlin.math.abs(all[i + 1].y - all[i].y)
            if (l > 0.01f) minSeg = minOf(minSeg, l)
        }
        val radius = if (minSeg == Float.MAX_VALUE) 0f else minOf(6f, minSeg / 2f)
        return EdgeRoute.OrthogonalRounded(
            source = all.first(),
            target = all.last(),
            waypoints = all.subList(1, all.size - 1),
            cornerRadiusPx = radius,
        )
    }

    /** Pitch (px) between two neighbouring ports on a top/bottom side for a label of [labelWidth] px. */
    public fun ibdPortPitchHorizontal(labelWidth: Float): Float = maxOf(IBD_PORT_MIN_PITCH_PX, labelWidth + 8f)

    /** Pitch (px) between two neighbouring ports on a left/right side for a label of [lineHeight] px height. */
    public fun ibdPortPitchVertical(lineHeight: Float): Float = maxOf(IBD_PORT_MIN_PITCH_PX, lineHeight + 6f)

    private const val IBD_PORT_MIN_PITCH_PX: Float = 24f

    /** Maximum extra width (px) a part box gains so that its ports keep their label-dependent pitch. */
    private const val IBD_PORT_PITCH_GROWTH_CAP_PX: Float = 112f

    /**
     * [ibdSpreadPorts] for a box with nested frames (PAL wave 2C-b2b): the ports of each frame are spread along that
     * frame's sides; [frameOf] gives the frame (local to the box) of a port key, `null` for the box itself.
     */
    public fun ibdSpreadPortsInFrames(
        boxSize: Size,
        ports: Map<String, Point>,
        labels: Map<String, String>,
        frameOf: (String) -> Rect?,
        declarationIndex: Map<String, Int> = emptyMap(),
    ): Map<String, Point> {
        if (ports.keys.all { frameOf(it) == null }) {
            return ibdSpreadPorts(boxSize = boxSize, ports = ports, labels = labels, declarationIndex = declarationIndex)
        }
        val spread = HashMap<String, Point>()
        val groups = ports.keys.groupBy { frameOf(it) }
        for ((frame, keys) in groups) {
            if (frame == null) {
                spread +=
                    ibdSpreadPorts(
                        boxSize = boxSize,
                        ports = ports.filterKeys { it in keys },
                        labels = labels,
                        declarationIndex = declarationIndex,
                    )
            } else {
                val local =
                    keys.associateWith {
                        Point(
                            x = ports.getValue(it).x - frame.origin.x,
                            y = ports.getValue(it).y - frame.origin.y,
                        )
                    }
                val done = ibdSpreadPorts(boxSize = frame.size, ports = local, labels = labels, declarationIndex = declarationIndex)
                for ((k, p) in done) spread[k] = Point(x = p.x + frame.origin.x, y = p.y + frame.origin.y)
            }
        }
        return ports.keys.associateWith { spread.getValue(it) }
    }

    /** Estimated rectangle of the attribute lines of a part, drawn left-aligned at [x] + 8 below [y]. */
    private fun ibdAttrTextRect(
        x: Float,
        y: Float,
        lines: List<String>,
    ): Rect =
        Rect(
            origin = Point(x = x + 8f, y = y + IBD_ATTR_PAD / 2f),
            size =
                Size(
                    width = (lines.maxOfOrNull { it.length } ?: 0) * IBD_ATTR_CHAR_PX + 8f,
                    height = lines.size * IBD_ATTR_LINE_H,
                ),
        )

    /**
     * Looks for a position of the nested-frame port [port] (local to the visible box of size [boxSize]) whose straight
     * corridor to the border of the box crosses none of [blockers]. The current side is tried first, then the sides
     * beside it, then the opposite one; on a side the positions nearest to the current one come first. `null` when
     * nothing is free.
     */
    private fun ibdFreeCorridor(
        port: Point,
        frame: dev.kuml.layout.IbdNestedFrame,
        boxSize: Size,
        blockers: List<Rect>,
    ): Point? {
        val f = frame.bounds
        val half = IBD_PORT_SIZE_PX / 2f
        val margin = half + 6f
        val cur = ibdNearestSide(p = Point(x = port.x - f.origin.x, y = port.y - f.origin.y), w = f.size.width, h = f.size.height)
        val order =
            when (cur) {
                IbdSide.Top -> listOf(IbdSide.Top, IbdSide.Left, IbdSide.Right, IbdSide.Bottom)
                IbdSide.Bottom -> listOf(IbdSide.Bottom, IbdSide.Left, IbdSide.Right, IbdSide.Top)
                IbdSide.Left -> listOf(IbdSide.Left, IbdSide.Top, IbdSide.Bottom, IbdSide.Right)
                IbdSide.Right -> listOf(IbdSide.Right, IbdSide.Top, IbdSide.Bottom, IbdSide.Left)
            }
        val compartmentH = if (frame.attributeLines.isEmpty()) 0f else frame.attributeLines.size * IBD_ATTR_LINE_H + IBD_ATTR_PAD
        for (side in order) {
            val horizontalSide = side == IbdSide.Top || side == IbdSide.Bottom
            val lo = if (horizontalSide) f.origin.x + margin else f.origin.y + frame.headerHeight + compartmentH + margin
            val hi = if (horizontalSide) f.origin.x + f.size.width - margin else f.origin.y + f.size.height - margin
            if (hi < lo) continue
            val preferred =
                if (!horizontalSide) {
                    // On a side wall the port sits near the top so its label fits below it.
                    lo + 4f
                } else if (side == cur) {
                    port.x
                } else {
                    (lo + hi) / 2f
                }
            val candidates = ArrayList<Float>()
            var v = lo
            while (v <= hi + 0.01f) {
                candidates += v
                v += 4f
            }
            candidates += preferred.coerceIn(lo, hi)
            for (pos in candidates.distinct().sortedWith(compareBy({ kotlin.math.abs(it - preferred) }, { it }))) {
                val corridor =
                    when (side) {
                        IbdSide.Top -> Rect(origin = Point(x = pos - 4f, y = 0f), size = Size(width = 8f, height = f.origin.y))
                        IbdSide.Bottom ->
                            Rect(
                                origin = Point(x = pos - 4f, y = f.origin.y + f.size.height),
                                size = Size(width = 8f, height = boxSize.height - f.origin.y - f.size.height),
                            )
                        IbdSide.Left -> Rect(origin = Point(x = 0f, y = pos - 4f), size = Size(width = f.origin.x, height = 8f))
                        IbdSide.Right ->
                            Rect(
                                origin = Point(x = f.origin.x + f.size.width, y = pos - 4f),
                                size = Size(width = boxSize.width - f.origin.x - f.size.width, height = 8f),
                            )
                    }
                if (corridor.size.width <= 0f || corridor.size.height <= 0f) continue
                if (blockers.none { rectsOverlap(a = it, b = corridor) }) {
                    return when (side) {
                        IbdSide.Top -> Point(x = pos, y = f.origin.y)
                        IbdSide.Bottom -> Point(x = pos, y = f.origin.y + f.size.height)
                        IbdSide.Left -> Point(x = f.origin.x, y = pos)
                        IbdSide.Right -> Point(x = f.origin.x + f.size.width, y = pos)
                    }
                }
            }
        }
        return null
    }

    private fun rectsOverlap(
        a: Rect,
        b: Rect,
    ): Boolean =
        a.origin.x < b.origin.x + b.size.width - 0.5f &&
            b.origin.x < a.origin.x + a.size.width - 0.5f &&
            a.origin.y < b.origin.y + b.size.height - 0.5f &&
            b.origin.y < a.origin.y + a.size.height - 0.5f

    /** Snaps [p] (local to the visible box) onto the border of the nested [frame] side that faces it; keeps clear of the corners and the header. */
    private fun snapToFrame(
        p: Point,
        frame: Rect,
        headerHeight: Float,
    ): Point {
        val half = IBD_PORT_SIZE_PX / 2f
        val cx = frame.origin.x + frame.size.width / 2f
        val cy = frame.origin.y + frame.size.height / 2f
        val dx = (p.x - cx) / maxOf(frame.size.width / 2f, 1f)
        val dy = (p.y - cy) / maxOf(frame.size.height / 2f, 1f)
        val l = frame.origin.x
        val t = frame.origin.y
        val r = l + frame.size.width
        val b = t + frame.size.height
        val x = p.x.coerceIn(minOf(l + half, cx), maxOf(r - half, cx))
        val y = p.y.coerceIn(minOf(t + headerHeight + half, cy), maxOf(b - half, cy))
        return if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) {
            if (dx >= 0f) Point(x = r, y = y) else Point(x = l, y = y)
        } else if (dy >= 0f) {
            Point(x = x, y = b)
        } else {
            Point(x = x, y = t)
        }
    }

    /**
     * Declaration order of the ports of one part (Nacharbeit 48, N-IBD-F d): for every port key (`steps` joined by `::`)
     * the position of its [dev.kuml.sysml2.PortUsage] in `model.usages`. Keys that do not resolve get no entry.
     */
    internal fun ibdPortDeclarationOrder(
        model: Sysml2Model,
        nodeId: String,
        keys: Collection<String>,
    ): Map<String, Int> {
        val result = LinkedHashMap<String, Int>()
        for (key in keys) {
            val port =
                dev.kuml.sysml2.projection.Sysml2PathResolver
                    .resolve(model = model, ownerId = "", endpointId = "$nodeId::$key", visibleNodeIds = setOf(nodeId))
                    ?.terminalPort ?: continue
            val index = model.usages.indexOfFirst { it.id == port.id }
            if (index >= 0) result[key] = index
        }
        return result
    }

    /**
     * Spreads the part ports of one box along their sides so that neighbouring ports keep a pitch of
     * `max(24, labelW + 8)` (top/bottom) or `max(24, lineH + 6)` (left/right). [ports] holds the local port centres
     * (on the box border), [labels] the label texts; [measure] is the text-width function (default: the estimate the
     * renderer's label placement uses). Order along the side decides first (coordinates compared at 1 px); ports at the
     * same coordinate keep their declaration order ([declarationIndex], Nacharbeit 48 N-IBD-F d) and then their keys,
     * never the insertion order of a hash map. If a side is too short for the requested pitch, the ports are distributed evenly over the side.
     */
    public fun ibdSpreadPorts(
        boxSize: Size,
        ports: Map<String, Point>,
        labels: Map<String, String>,
        measure: (String) -> Float = { estimateLabelSize(text = it).width },
        declarationIndex: Map<String, Int> = emptyMap(),
    ): Map<String, Point> {
        val half = IBD_PORT_SIZE_PX / 2f
        val out = LinkedHashMap(ports)
        val sides = ports.mapValues { (_, p) -> ibdNearestSide(p = p, w = boxSize.width, h = boxSize.height) }
        for (side in listOf(IbdSide.Top, IbdSide.Bottom, IbdSide.Left, IbdSide.Right)) {
            val horizontal = side == IbdSide.Top || side == IbdSide.Bottom
            val keys =
                ports.keys
                    .filter { sides[it] == side }
                    .sortedWith(
                        compareBy(
                            { kotlin.math.round(if (horizontal) ports.getValue(it).x else ports.getValue(it).y) },
                            { declarationIndex[it] ?: Int.MAX_VALUE },
                            { it },
                        ),
                    )
            if (keys.size < 2) continue
            val length = if (horizontal) boxSize.width else boxSize.height

            fun pitchAfter(key: String): Float {
                val text = labels[key] ?: key
                return if (horizontal) {
                    ibdPortPitchHorizontal(labelWidth = measure(text))
                } else {
                    ibdPortPitchVertical(lineHeight = estimateLabelSize(text = text).height)
                }
            }
            val lo = minOf(half, length / 2f)
            val hi = maxOf(length - half, length / 2f)
            val pos = FloatArray(keys.size) { i -> if (horizontal) ports.getValue(keys[i]).x else ports.getValue(keys[i]).y }
            for (i in 1 until keys.size) pos[i] = maxOf(pos[i], pos[i - 1] + pitchAfter(keys[i - 1]))
            var limit = hi
            for (i in keys.indices.reversed()) {
                pos[i] = minOf(pos[i], limit)
                if (i > 0) limit = pos[i] - pitchAfter(keys[i - 1])
            }
            if (pos[0] < lo - 0.01f) {
                // Not enough room: distribute evenly over the side.
                val step = (hi - lo) / (keys.size - 1)
                for (i in keys.indices) pos[i] = lo + step * i
            }
            for ((i, k) in keys.withIndex()) {
                val p = ports.getValue(k)
                out[k] = if (horizontal) Point(x = pos[i], y = p.y) else Point(x = p.x, y = pos[i])
            }
        }
        return out
    }

    /** Innenabstand zwischen den äußersten Inhalten und dem IBD-Rahmen. */
    public const val IBD_FRAME_PADDING_PX: Float = 24f

    /** Höhe des Titel-Reiters in der linken oberen Ecke des IBD-Rahmens. */
    public const val IBD_FRAME_TAB_H_PX: Float = 28f

    /** Mindestabstand zwischen Reiter-Unterkante und darunterliegendem Inhalt. */
    public const val IBD_TAB_CONTENT_GAP_PX: Float = 16f

    /** Freizone (px) um ein Port-Quadrat, in die kein Label ragen darf. */
    public const val IBD_PORT_CLEARANCE_PX: Float = 4f

    /** Abstand zwischen Port-Quadrat und seinem Label. */
    public const val IBD_PORT_LABEL_GAP_PX: Float = 6f

    /** Außenrand der Zeichenfläche um alle Diagrammteile. */
    public const val IBD_CANVAS_MARGIN_PX: Float = 16f

    /** Seitlicher Versatz, wenn Part-Port und Owner-Port auf derselben Achse liegen. */
    public const val IBD_COLLINEAR_OFFSET_PX: Float = 40f

    /** Schriftgröße, mit der Label-Größen geschätzt werden. */
    public const val IBD_LABEL_FONT_PX: Float = 12f

    /** Distance of the baselines of a wrapped port label. */
    public const val IBD_LABEL_LINE_H: Float = IBD_LABEL_FONT_PX * 1.3f

    private const val IBD_PORT_MIN_GAP_PX: Float = 56f
    private const val IBD_TAB_TITLE_MAX_CHARS: Int = 40
    private const val IBD_LABEL_LINE_GAP_PX: Float = 3f

    /** Rahmen-Gruppen-Id: `frame:<ownerId>`. */
    public fun ibdFrameGroupId(ownerId: String): GroupId = GroupId("frame:$ownerId")

    /** Seite eines Rahmens oder einer Box, an der ein Port sitzt. */
    public enum class IbdSide { Top, Bottom, Left, Right }

    /** Art eines Port-Labels. */
    public enum class IbdLabelKind { PartPort, OwnerPort }

    /** Platziertes Label-Rechteck samt Text-Ankerpunkt (gleiche Koordinaten wie das übergebene Port-Zentrum). */
    public data class IbdLabelPlacement(
        val rect: Rect,
        val textX: Float,
        val textY: Float,
        val anchorEnd: Boolean,
    )

    /** Label-Rechteck eines Ports in Layout-Koordinaten (für Layout, Renderer und Tests). */
    public data class IbdLabelRect(
        val owner: String,
        val kind: IbdLabelKind,
        val rect: Rect,
    )

    /**
     * Schätzt die Größe eines einzeiligen Textes: `0,6 × Schrift × Zeichen` plus 5 % Reserve, Höhe `1,3 × Schrift`.
     */
    public fun estimateLabelSize(
        text: String,
        fontPx: Float = IBD_LABEL_FONT_PX,
    ): Size {
        // PAL 2C-b2: a text with line breaks (wrapped port labels) is as wide as its widest line and as high as all lines.
        val lines = text.split('\n')
        val widest = lines.maxOf { it.length }
        return Size(width = 0.6f * fontPx * widest * 1.05f, height = 1.3f * fontPx * lines.size)
    }

    /** Reiter-Titel `ibd [part] Owner` (gekürzt). */
    public fun ibdTabTitle(ownerName: String): String {
        val t = "ibd [part] $ownerName"
        return if (t.length <= IBD_TAB_TITLE_MAX_CHARS) t else t.take(IBD_TAB_TITLE_MAX_CHARS - 1) + "…"
    }

    /** Breite des Reiters; Layout und SVG-Renderer verwenden dieselbe Funktion. */
    public fun ibdTabWidth(ownerName: String): Float = estimateLabelSize(text = ibdTabTitle(ownerName)).width + 16f

    /**
     * `name : Type` (`name : ~Type` bei konjugiertem Port). PAL 2C-b2: lange Labels werden auf höchstens zwei Zeilen
     * umgebrochen (Trenner `\n`) und nie gekürzt.
     */
    public fun ibdPortLabelText(
        name: String,
        typeName: String,
        conjugated: Boolean,
    ): String {
        val full = "$name : ${if (conjugated) "~" else ""}$typeName"
        return dev.kuml.layout.bridge.label.Sysml2LabelSizing
            .wrapPortLabel(text = full)
            .joinToString("\n")
    }

    /**
     * Das gezeichnete Quadrat eines Part-Ports: [IBD_PORT_SIZE_PX] groß, zentriert auf dem lokalen Punkt [local]
     * der Box [boxBounds]. Ergebnis in absoluten Koordinaten. Renderer und Notiz-Anker nutzen dieselbe Funktion.
     */
    public fun ibdPortSquare(
        boxBounds: Rect,
        local: Point,
    ): Rect {
        val half = IBD_PORT_SIZE_PX / 2f
        return Rect(
            origin = Point(x = boxBounds.origin.x + local.x - half, y = boxBounds.origin.y + local.y - half),
            size = Size(width = IBD_PORT_SIZE_PX, height = IBD_PORT_SIZE_PX),
        )
    }

    /** Nächstgelegene Seite einer Box der Größe [w] × [h] zum lokalen Punkt [p] (kleinster Abstand gewinnt). */
    public fun ibdNearestSide(
        p: Point,
        w: Float,
        h: Float,
    ): IbdSide =
        listOf(
            IbdSide.Top to kotlin.math.abs(p.y),
            IbdSide.Bottom to kotlin.math.abs(h - p.y),
            IbdSide.Left to kotlin.math.abs(p.x),
            IbdSide.Right to kotlin.math.abs(w - p.x),
        ).minBy { it.second }.first

    /**
     * The nested frame (local to the visible box [nodeId]) a port with the key [key] (`step::step`) sits on, or `null` when
     * it sits on the visible box itself (PAL wave 2C-b2b). A port `cell::drive::power` sits on the frame `<nodeId>::cell::drive`.
     */
    public fun ibdPortFrameRect(
        nodeId: String,
        key: String,
        frames: List<dev.kuml.layout.IbdNestedFrame>,
    ): Rect? = ibdPortFrame(nodeId = nodeId, key = key, frames = frames)?.bounds

    /** Like [ibdPortFrameRect], the whole frame record. */
    public fun ibdPortFrame(
        nodeId: String,
        key: String,
        frames: List<dev.kuml.layout.IbdNestedFrame>,
    ): dev.kuml.layout.IbdNestedFrame? {
        if (frames.isEmpty()) return null
        val steps = key.split("::")
        if (steps.size < 2) return null
        val path = nodeId + "::" + steps.dropLast(1).joinToString("::")
        return frames.firstOrNull { it.path == path && it.nodeId == nodeId }
    }

    /**
     * Adds the nested part frames of the IBD (box in box, PAL wave 2C-b2b) to [layoutResult]: for every visible part box
     * with nested parts the frames are positioned inside it, local to the box. Run it before [addIbdOwnerFrame]. A
     * diagram without nested parts is returned unchanged.
     */
    public fun addIbdNestedFrames(
        model: Sysml2Model,
        diagram: IbdDiagram,
        layoutResult: LayoutResult,
    ): LayoutResult {
        val projection = sysml2IbdProjection(model = model, diagram = diagram)
        val tree = IbdNesting.treeOf(model = model, diagram = diagram, projection = projection)
        if (tree.isEmpty()) return layoutResult
        val frames = ArrayList<dev.kuml.layout.IbdNestedFrame>()
        for ((nodeId, roots) in tree) {
            val nl = layoutResult.nodes[NodeId(nodeId)] ?: continue
            val usage = model.usages.filterIsInstance<PartUsage>().firstOrNull { it.id == nodeId }
            val compartment =
                if (diagram.showAttributes && usage != null) {
                    dev.kuml.sysml2.projection
                        .ibdPartAttributes(model = model, usage = usage)
                } else {
                    null
                }
            val topArea = STEREOTYPE_LINE_H + NAME_LINE_H + BOX_V_PADDING + (compartment?.let { ibdAttributeCompartmentHeight(it) } ?: 0f)
            frames += IbdNesting.frames(nodeId = nodeId, roots = roots, nodeSize = nl.bounds.size, topArea = topArea)
        }
        return layoutResult.copy(ibdNestedFrames = frames)
    }

    /**
     * Estimated rectangles (layout coordinates) of the title texts of the IBD part boxes and nested frames; labels and
     * connectors keep clear of them (PAL wave 2C-b2b). Only boxes that contain nested frames report their title: the
     * space inside such a box is where labels and connectors may otherwise run through the text.
     */
    public fun ibdTitleRects(
        model: Sysml2Model,
        layout: LayoutResult,
    ): List<Rect> {
        if (layout.ibdNestedFrames.isEmpty()) return emptyList()
        val out = ArrayList<Rect>()
        val hosts = layout.ibdNestedFrames.map { it.nodeId }.toSet()
        for (id in hosts.sorted()) {
            val nl = layout.nodes[NodeId(id)] ?: continue
            val usage = model.usages.filterIsInstance<PartUsage>().firstOrNull { it.id == id }
            val title = if (usage != null) "${usage.name} : ${usage.definitionId}" else id
            val w = maxOf(title.length * IBD_TITLE_CHAR_PX, "«part»".length * IBD_STEREO_CHAR_PX) * 1.15f + 8f
            out +=
                Rect(
                    origin = Point(x = nl.bounds.origin.x + (nl.bounds.size.width - w) / 2f, y = nl.bounds.origin.y + 2f),
                    size = Size(width = w, height = STEREOTYPE_LINE_H + NAME_LINE_H),
                )
        }
        for (f in layout.ibdNestedFrames) {
            val nl = layout.nodes[NodeId(f.nodeId)] ?: continue
            val w = f.title.length * IBD_TITLE_CHAR_PX * 1.15f + 8f
            out +=
                Rect(
                    origin =
                        Point(
                            x = nl.bounds.origin.x + f.bounds.origin.x + (f.bounds.size.width - w) / 2f,
                            y = nl.bounds.origin.y + f.bounds.origin.y,
                        ),
                    size = Size(width = w, height = f.headerHeight),
                )
        }
        return out
    }

    /**
     * The text obstacles of an IBD for the label plan (Nacharbeit 43): [ibdTitleRects] plus a full-width title band for
     * every part box that has no nested frame, so a port or connector label never lands on the text of such a box.
     * Boxes with nested frames keep their estimated title rectangle (their band would cover the nested frames too).
     */
    public fun ibdTitleObstacles(
        model: Sysml2Model,
        layout: LayoutResult,
    ): List<Rect> {
        val hosts = layout.ibdNestedFrames.map { it.nodeId }.toSet()
        val partIds =
            model.usages
                .filterIsInstance<PartUsage>()
                .map { it.id }
                .toSet()
        val plain = layout.nodes.keys.filter { it.value in partIds && it.value !in hosts }
        return ibdTitleRects(model = model, layout = layout) +
            partTitleBands(layout = layout, nodeIds = plain, hasStereotype = { true })
    }

    /** Absolute (layout coordinate) rectangle of [frame]. */
    public fun ibdFrameAbs(
        frame: dev.kuml.layout.IbdNestedFrame,
        layout: LayoutResult,
    ): Rect? {
        val node = layout.nodes[NodeId(frame.nodeId)] ?: return null
        return Rect(
            origin = Point(x = node.bounds.origin.x + frame.bounds.origin.x, y = node.bounds.origin.y + frame.bounds.origin.y),
            size = frame.bounds.size,
        )
    }

    /**
     * Platziert das Label eines Ports um das Port-Zentrum ([cx], [cy]) auf der Außenseite von [side].
     *
     * - Oben/unten: linksbündig 6 px neben dem Quadrat ([alternate]: rechtsbündig links davon), senkrecht außerhalb der Kante.
     * - Links/rechts, Part-Port: außerhalb der Box, knapp oberhalb der Verbindungslinie ([alternate]: unterhalb).
     * - Links/rechts, Owner-Port: außerhalb des Rahmens, vertikal zentriert.
     * Das Label liegt nie mittig auf der Kante.
     */
    public fun ibdLabelPlacement(
        side: IbdSide,
        cx: Float,
        cy: Float,
        text: String,
        partPort: Boolean,
        alternate: Boolean = false,
    ): IbdLabelPlacement {
        val s = estimateLabelSize(text = text)
        val half = IBD_PORT_SIZE_PX / 2f
        val gap = IBD_PORT_LABEL_GAP_PX
        val w = s.width
        val h = s.height
        val x: Float
        val y: Float
        when (side) {
            IbdSide.Top -> {
                x = if (alternate) cx - half - gap - w else cx + half + gap
                y = cy - IBD_PORT_CLEARANCE_PX - h
            }
            IbdSide.Bottom -> {
                x = if (alternate) cx - half - gap - w else cx + half + gap
                y = cy + IBD_PORT_CLEARANCE_PX
            }
            IbdSide.Left -> {
                x = cx - half - gap - w
                y =
                    if (!partPort) {
                        cy - h / 2f
                    } else if (alternate) {
                        cy + IBD_LABEL_LINE_GAP_PX
                    } else {
                        cy - IBD_LABEL_LINE_GAP_PX - h
                    }
            }
            IbdSide.Right -> {
                x = cx + half + gap
                y =
                    if (!partPort) {
                        cy - h / 2f
                    } else if (alternate) {
                        cy + IBD_LABEL_LINE_GAP_PX
                    } else {
                        cy - IBD_LABEL_LINE_GAP_PX - h
                    }
            }
        }
        val anchorEnd = side == IbdSide.Left || ((side == IbdSide.Top || side == IbdSide.Bottom) && alternate)
        return IbdLabelPlacement(
            rect = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h)),
            textX = if (anchorEnd) x + w - 2f else x + 2f,
            // First baseline; further lines of a wrapped label follow at IBD_LABEL_LINE_H.
            textY = y + IBD_LABEL_FONT_PX * 1.3f - 4f,
            anchorEnd = anchorEnd,
        )
    }

    /**
     * Platziert die Labels aller Part-Ports einer Box (lokale Koordinaten relativ zur Box-Ecke). Liegen zwei Ports
     * derselben oberen/unteren Seite so nah beieinander, dass sich ihre Labels berühren würden, weicht das Label
     * des linken Ports nach links aus. Layout und Renderer nutzen dieselbe Funktion.
     */
    public fun ibdPartPortPlacements(
        boxSize: Size,
        ports: Map<String, Point>,
        labels: Map<String, String>,
        frameOf: (String) -> Rect? = { null },
    ): Map<String, Pair<IbdSide, IbdLabelPlacement>> {
        val half = IBD_PORT_SIZE_PX / 2f
        val sides =
            ports.mapValues { (k, p) ->
                val f = frameOf(k)
                if (f == null) {
                    ibdNearestSide(p = p, w = boxSize.width, h = boxSize.height)
                } else {
                    ibdNearestSide(p = Point(x = p.x - f.origin.x, y = p.y - f.origin.y), w = f.size.width, h = f.size.height)
                }
            }
        val alt = mutableSetOf<String>()
        for (side in listOf(IbdSide.Top, IbdSide.Bottom)) {
            val onSide = ports.filter { sides[it.key] == side }.entries.sortedBy { it.value.x }
            var prevRight = Float.NEGATIVE_INFINITY
            for ((i, e) in onSide.withIndex()) {
                val w = estimateLabelSize(text = labels[e.key] ?: e.key).width
                val next = onSide.getOrNull(i + 1)
                var useAlt = false
                if (next != null && next.value.x - e.value.x < w + half + IBD_PORT_LABEL_GAP_PX + 8f) {
                    val leftEdge = e.value.x - half - IBD_PORT_LABEL_GAP_PX - w
                    if (leftEdge >= prevRight + 4f) useAlt = true
                }
                if (useAlt) {
                    alt += e.key
                    prevRight = e.value.x + half
                } else {
                    prevRight = e.value.x + half + IBD_PORT_LABEL_GAP_PX + w
                }
            }
        }
        return ports.mapValues { (k, p) ->
            val side = sides.getValue(k)
            side to
                ibdLabelPlacement(
                    side = side,
                    cx = p.x,
                    cy = p.y,
                    text = labels[k] ?: k,
                    partPort = true,
                    alternate = k in alt,
                )
        }
    }

    private data class IbdBox(
        val l: Float,
        val t: Float,
        val r: Float,
        val b: Float,
    ) {
        fun intersects(o: IbdBox): Boolean = l < o.r && o.l < r && t < o.b && o.t < b

        fun inflate(d: Float): IbdBox = IbdBox(l = l - d, t = t - d, r = r + d, b = b + d)

        fun shift(
            dx: Float,
            dy: Float,
        ): IbdBox = IbdBox(l = l + dx, t = t + dy, r = r + dx, b = b + dy)

        fun toRect(): Rect = Rect(origin = Point(x = l, y = t), size = Size(width = r - l, height = b - t))
    }

    private fun Rect.toBox(): IbdBox = IbdBox(l = origin.x, t = origin.y, r = origin.x + size.width, b = origin.y + size.height)

    private fun squareBox(
        c: Point,
        half: Float,
    ): IbdBox = IbdBox(l = c.x - half, t = c.y - half, r = c.x + half, b = c.y + half)

    private data class IbdWireRef(
        val edgeId: String,
        val src: dev.kuml.sysml2.projection.ResolvedEnd,
        val tgt: dev.kuml.sysml2.projection.ResolvedEnd,
    )

    private fun ibdWires(projection: dev.kuml.sysml2.projection.IbdProjection): List<IbdWireRef> =
        projection.connections.map { IbdWireRef(edgeId = "conn:${it.usage.id}", src = it.source, tgt = it.target) } +
            projection.ownLineFlows.map { IbdWireRef(edgeId = "flow:${it.usage.id}", src = it.source, tgt = it.target) } +
            projection.bindings.map { IbdWireRef(edgeId = it.usage.id, src = it.source, tgt = it.target) }

    private fun ibdTypeName(
        model: Sysml2Model,
        port: dev.kuml.sysml2.PortUsage,
    ): String = model.definitions.firstOrNull { it.id == port.definitionId }?.name ?: port.definitionId

    /** Label-Texte der Part-Ports: `nodeId` → (`step::step` → Text). */
    private fun ibdPartPortLabels(
        model: Sysml2Model,
        wires: List<IbdWireRef>,
    ): Map<NodeId, Map<String, String>> {
        val out = linkedMapOf<NodeId, MutableMap<String, String>>()
        for (w in wires) {
            for (end in listOf(w.src, w.tgt)) {
                val nodeId = end.nodeId ?: continue
                if (end.steps.isEmpty()) continue
                val key = end.steps.joinToString("::")
                val port = end.terminalPort
                out.getOrPut(NodeId(nodeId)) { linkedMapOf() }.getOrPut(key) {
                    if (port ==
                        null
                    ) {
                        key
                    } else {
                        ibdPortLabelText(
                            name = end.steps.last(),
                            typeName = ibdTypeName(model = model, port = port),
                            conjugated = port.isConjugated,
                        )
                    }
                }
            }
        }
        return out
    }

    private fun snapToBorder(
        p: Point,
        size: Size,
    ): Point {
        val half = IBD_PORT_SIZE_PX / 2f
        val side = ibdNearestSide(p = p, w = size.width, h = size.height)
        val x = p.x.coerceIn(minOf(half, size.width / 2f), maxOf(size.width - half, size.width / 2f))
        val y = p.y.coerceIn(minOf(half, size.height / 2f), maxOf(size.height - half, size.height / 2f))
        return when (side) {
            IbdSide.Top -> Point(x = x, y = 0f)
            IbdSide.Bottom -> Point(x = x, y = size.height)
            IbdSide.Left -> Point(x = 0f, y = y)
            IbdSide.Right -> Point(x = size.width, y = y)
        }
    }

    /** Face of a port square that looks outwards ([inward] = false) or inwards from the box/frame side [side]. */
    private fun ibdFace(
        side: IbdSide,
        inward: Boolean = false,
    ): PortFace {
        val f =
            when (side) {
                IbdSide.Top -> PortFace.Top
                IbdSide.Bottom -> PortFace.Bottom
                IbdSide.Left -> PortFace.Left
                IbdSide.Right -> PortFace.Right
            }
        return if (inward) PortEdgeClamp.opposite(f) else f
    }

    private fun outwardOf(side: IbdSide): Point =
        when (side) {
            IbdSide.Top -> Point(x = 0f, y = -1f)
            IbdSide.Bottom -> Point(x = 0f, y = 1f)
            IbdSide.Left -> Point(x = -1f, y = 0f)
            IbdSide.Right -> Point(x = 1f, y = 0f)
        }

    private fun inwardOf(side: IbdSide): Point = outwardOf(side).let { Point(x = -it.x, y = -it.y) }

    private fun segmentBox(
        a: Point,
        b: Point,
    ): IbdBox =
        IbdBox(
            l = minOf(a.x, b.x) - 1f,
            t = minOf(a.y, b.y) - 1f,
            r = maxOf(a.x, b.x) + 1f,
            b = maxOf(a.y, b.y) + 1f,
        )

    /** Minimum distance (px) of an owner port centre to a frame corner. */
    private const val IBD_OWNER_PORT_CORNER_GAP_PX: Float = 16f

    /**
     * Frame side an owner port of the given [direction] prefers (Nacharbeit 48, N-IBD-F e): `in` on the side the flow
     * enters from, `out` on the side it leaves to, for the diagram flow [flow] (`null` = down). `inout` and undirected
     * ports have no preference (`null`).
     */
    internal fun ibdPreferredOwnerSide(
        direction: PortDirection?,
        flow: dev.kuml.core.model.layout.LayoutFlow?,
    ): IbdSide? {
        val entering =
            when (flow ?: dev.kuml.core.model.layout.LayoutFlow.Down) {
                dev.kuml.core.model.layout.LayoutFlow.Down -> IbdSide.Top
                dev.kuml.core.model.layout.LayoutFlow.Up -> IbdSide.Bottom
                dev.kuml.core.model.layout.LayoutFlow.Right -> IbdSide.Left
                dev.kuml.core.model.layout.LayoutFlow.Left -> IbdSide.Right
            }
        val leaving =
            when (entering) {
                IbdSide.Top -> IbdSide.Bottom
                IbdSide.Bottom -> IbdSide.Top
                IbdSide.Left -> IbdSide.Right
                IbdSide.Right -> IbdSide.Left
            }
        return when (direction) {
            PortDirection.In -> entering
            PortDirection.Out -> leaving
            PortDirection.InOut, null -> null
        }
    }

    /**
     * Side and position of an owner port on the frame (PAL 2C-b1). The side is the frame side nearest to the box
     * [partBox] of the connected part(s) ([exclude] removes a side that would force the line through the part); ties
     * resolve in the order top, bottom, left, right. The coordinate along that side is the projection of the mean of the
     * [targets] (centres of the connected part ports), kept at least 16 px away from the frame corners. A [prefer]red side
     * (the side of the port direction, see [ibdPreferredOwnerSide]) replaces the nearest side unless it is [exclude]d.
     */
    internal fun placeOwnerPort(
        frame: Rect,
        targets: List<Point>,
        partBox: Rect,
        exclude: IbdSide? = null,
        prefer: IbdSide? = null,
    ): Pair<IbdSide, Float> {
        val l = frame.origin.x
        val t = frame.origin.y
        val r = l + frame.size.width
        val b = t + frame.size.height
        val dists =
            listOf(
                IbdSide.Top to partBox.origin.y - t,
                IbdSide.Bottom to b - (partBox.origin.y + partBox.size.height),
                IbdSide.Left to partBox.origin.x - l,
                IbdSide.Right to r - (partBox.origin.x + partBox.size.width),
            ).filter { it.first != exclude }
        val side = prefer?.takeIf { it != exclude } ?: dists.minWith(compareBy({ kotlin.math.abs(it.second) })).first
        val vertical = side == IbdSide.Top || side == IbdSide.Bottom
        val mean = if (vertical) targets.map { it.x }.average().toFloat() else targets.map { it.y }.average().toFloat()
        val lo = (if (vertical) l else t) + IBD_OWNER_PORT_CORNER_GAP_PX
        val hi = (if (vertical) r else b) - IBD_OWNER_PORT_CORNER_GAP_PX
        return side to mean.coerceIn(lo, maxOf(hi, lo))
    }

    /** Where a connector enters the visible part box to reach a port on a nested frame (PAL wave 2C-b2b). */
    private class IbdGate(
        val gate: Point,
        val portBorder: Point,
        val side: IbdSide,
    )

    private class OwnerPlace(
        val port: dev.kuml.sysml2.PortUsage,
        val id: NodeId,
        var side: IbdSide,
        var pos: Float,
        val cp: Point?,
        val cpSide: IbdSide?,
        val cpNode: NodeId?,
        val label: String,
    )

    private fun routePoints(r: EdgeRoute): List<Point> =
        when (r) {
            is EdgeRoute.Direct -> listOf(r.source, r.target)
            is EdgeRoute.OrthogonalRounded -> listOf(r.source) + r.waypoints + listOf(r.target)
            is EdgeRoute.TreeRounded -> listOf(r.source) + r.waypoints + listOf(r.target)
            is EdgeRoute.Bezier -> listOf(r.source) + r.controlPoints + listOf(r.target)
        }

    /** Orthogonale Route von [a] (Austrittsrichtung [da]) nach [b] (Austrittsrichtung [db]) mit höchstens zwei Knicken. */
    private fun ibdOrthoRoute(
        a: Point,
        da: Point?,
        b: Point,
        db: Point?,
        avoid: List<IbdBox>,
    ): EdgeRoute {
        if (da == null && db != null) {
            return when (val r = ibdOrthoRoute(a = b, da = db, b = a, db = null, avoid = avoid)) {
                is EdgeRoute.OrthogonalRounded ->
                    EdgeRoute.OrthogonalRounded(
                        source = a,
                        target = b,
                        waypoints = r.waypoints.reversed(),
                        cornerRadiusPx = r.cornerRadiusPx,
                    )
                else -> r
            }
        }
        val vA = da != null && da.y != 0f
        val vB = db == null || db.y != 0f
        val pts = mutableListOf<Point>()
        if (da == null) {
            pts += Point(x = (a.x + b.x) / 2f, y = a.y)
            pts += Point(x = (a.x + b.x) / 2f, y = b.y)
        } else if (vA && vB) {
            if (kotlin.math.abs(a.x - b.x) > 0.01f) {
                var y = (a.y + b.y) / 2f
                for (f in listOf(0.5f, 0.3f, 0.7f, 0.2f, 0.8f, 0.1f, 0.9f)) {
                    val cand = a.y + (b.y - a.y) * f
                    val seg = segmentBox(a = Point(x = a.x, y = cand), b = Point(x = b.x, y = cand))
                    if (avoid.none { it.intersects(seg) }) {
                        y = cand
                        break
                    }
                }
                pts += Point(x = a.x, y = y)
                pts += Point(x = b.x, y = y)
            }
        } else if (!vA && db != null && db.x != 0f) {
            if (kotlin.math.abs(a.y - b.y) > 0.01f) {
                var x = (a.x + b.x) / 2f
                for (f in listOf(0.5f, 0.3f, 0.7f, 0.2f, 0.8f, 0.1f, 0.9f)) {
                    val cand = a.x + (b.x - a.x) * f
                    val seg = segmentBox(a = Point(x = cand, y = a.y), b = Point(x = cand, y = b.y))
                    if (avoid.none { it.intersects(seg) }) {
                        x = cand
                        break
                    }
                }
                pts += Point(x = x, y = a.y)
                pts += Point(x = x, y = b.y)
            }
        } else if (vA) {
            // a exits vertically, b horizontally (or unknown): L-shape.
            pts += Point(x = a.x, y = b.y)
        } else {
            pts += Point(x = b.x, y = a.y)
        }
        val wp = pts.filter { it != a && it != b }.distinct()
        val all = listOf(a) + wp + listOf(b)
        var minSeg = Float.MAX_VALUE
        for (i in 0 until all.size - 1) {
            val l = kotlin.math.abs(all[i + 1].x - all[i].x) + kotlin.math.abs(all[i + 1].y - all[i].y)
            if (l > 0.01f) minSeg = minOf(minSeg, l)
        }
        val radius = if (minSeg == Float.MAX_VALUE) 0f else minOf(6f, minSeg / 2f)
        return EdgeRoute.OrthogonalRounded(source = a, target = b, waypoints = wp, cornerRadiusPx = radius)
    }

    /**
     * Legt (nur wenn die [dev.kuml.sysml2.projection.IbdProjection.showFrame]-Regel greift) einen Rahmen um alle
     * Inhaltsknoten des IBD, verteilt die Owner-Ports auf die Rahmenkanten und führt Kanten an Ports orthogonal neu.
     *
     * Der Rahmen ist die Vereinigung aus Part-Boxen, Part-Port-Quadraten samt Freizone und Part-Port-Labels plus
     * [IBD_FRAME_PADDING_PX]. Der Titel-Reiter sitzt innerhalb der oberen linken Rahmenecke; Inhalt unter dem Reiter
     * hält [IBD_TAB_CONTENT_GAP_PX] Abstand. Owner-Port-Labels stehen außerhalb des Rahmens; die Zeichenfläche wird aus
     * dem tatsächlichen Inhalt neu bemessen ([IBD_CANVAS_MARGIN_PX] Außenrand, Minimum bei `(margin, margin)`).
     *
     * Die Part-Port-Positionen (lokale Koordinaten, auf die Box-Kante eingerastet) werden in [NodeLayout.ports] abgelegt.
     */
    public fun addIbdOwnerFrame(
        model: Sysml2Model,
        diagram: IbdDiagram,
        layoutResult: LayoutResult,
    ): LayoutResult {
        val projection = sysml2IbdProjection(model = model, diagram = diagram)
        if (!projection.showFrame) return layoutResult
        val ownerPortIds = projection.boundaryPorts.map { NodeId(it.id) }.toSet()
        val content = layoutResult.nodes.filterKeys { it !in ownerPortIds }
        if (content.isEmpty()) return layoutResult

        val half = IBD_PORT_SIZE_PX / 2f
        val pad = IBD_FRAME_PADDING_PX
        val wires = ibdWires(projection)
        val labelTexts = ibdPartPortLabels(model = model, wires = wires)

        // A. Part-Ports: aus den Kantenenden, auf die Box-Kante (oder die Kante eines geschachtelten Rahmens) eingerastet (lokale Koordinaten).
        val nestedFrames = layoutResult.ibdNestedFrames
        val partPorts = linkedMapOf<NodeId, LinkedHashMap<String, Point>>()
        for (w in wires) {
            val route = layoutResult.edges[EdgeId(w.edgeId)] ?: continue
            for ((end, pt) in listOf(w.src to route.source, w.tgt to route.target)) {
                val nodeId = end.nodeId?.let { NodeId(it) } ?: continue
                if (end.steps.isEmpty()) continue
                val nl = content[nodeId] ?: continue
                val key = end.steps.joinToString("::")
                val m = partPorts.getOrPut(nodeId) { linkedMapOf() }
                if (key in m) continue
                val o = nl.bounds.origin
                val frame = ibdPortFrame(nodeId = nodeId.value, key = key, frames = nestedFrames)
                val local = Point(x = pt.x - o.x, y = pt.y - o.y)
                m[key] =
                    if (frame == null) {
                        snapToBorder(p = local, size = nl.bounds.size)
                    } else {
                        snapToFrame(p = local, frame = frame.bounds, headerHeight = frame.headerHeight)
                    }
            }
        }

        fun frameOfPort(nodeId: NodeId): (String) -> Rect? =
            { key -> ibdPortFrameRect(nodeId = nodeId.value, key = key, frames = nestedFrames) }
        // PAL 2C-b1: ports on one side keep a pitch that fits their labels.
        for ((nodeId, m) in partPorts) {
            val size = content[nodeId]?.bounds?.size ?: continue
            val spread =
                ibdSpreadPortsInFrames(
                    boxSize = size,
                    ports = m,
                    labels = labelTexts[nodeId] ?: emptyMap(),
                    frameOf = frameOfPort(nodeId),
                    declarationIndex = ibdPortDeclarationOrder(model = model, nodeId = nodeId.value, keys = m.keys),
                )
            for (k in m.keys.toList()) m[k] = spread.getValue(k)
        }
        // A'. PAL 2C-b2b: a port on a nested frame needs a free straight corridor from the border of the visible box; it moves
        // along its frame (or to another side) until the corridor crosses no title, attribute text or foreign frame.
        if (nestedFrames.isNotEmpty()) {
            val titlesAbs = ibdTitleRects(model = model, layout = layoutResult)
            for ((nodeId, m) in partPorts) {
                val nl = content[nodeId] ?: continue
                val frames = nestedFrames.filter { it.nodeId == nodeId.value }
                if (frames.isEmpty()) continue
                val o = nl.bounds.origin
                val blockers = ArrayList<Rect>()
                for (t in titlesAbs) {
                    if (t.origin.x >= o.x - 1f &&
                        t.origin.y >= o.y - 1f &&
                        t.origin.x <= o.x + nl.bounds.size.width &&
                        t.origin.y <= o.y + nl.bounds.size.height
                    ) {
                        blockers += Rect(origin = Point(x = t.origin.x - o.x, y = t.origin.y - o.y), size = t.size)
                    }
                }
                val hostUsage = model.usages.filterIsInstance<PartUsage>().firstOrNull { it.id == nodeId.value }
                if (diagram.showAttributes && hostUsage != null) {
                    val c =
                        dev.kuml.sysml2.projection
                            .ibdPartAttributes(model = model, usage = hostUsage)
                    if (!c.isEmpty) {
                        val texts =
                            c.lines.map { it.text } + (if (c.hiddenCount > 0) listOf("\u2026 ${c.hiddenCount} more") else emptyList())
                        blockers +=
                            ibdAttrTextRect(x = 0f, y = STEREOTYPE_LINE_H + NAME_LINE_H + BOX_V_PADDING - IBD_ATTR_PAD / 2f, lines = texts)
                    }
                }
                for (f in frames) {
                    if (f.attributeLines.isNotEmpty()) {
                        blockers += ibdAttrTextRect(x = f.bounds.origin.x, y = f.bounds.origin.y + f.headerHeight, lines = f.attributeLines)
                    }
                }
                for (key in m.keys.toList()) {
                    val f = ibdPortFrame(nodeId = nodeId.value, key = key, frames = nestedFrames) ?: continue
                    val chain = HashSet<String>()
                    var parent = f.parentPath
                    while (parent != null) {
                        chain += parent
                        parent = frames.firstOrNull { it.path == parent }?.parentPath
                    }
                    val siblings = frames.filter { it.path != f.path && it.path !in chain }.map { it.bounds }
                    ibdFreeCorridor(
                        port = m.getValue(key),
                        frame = f,
                        boxSize = nl.bounds.size,
                        blockers = blockers + siblings,
                    )?.let { m[key] = it }
                }
            }
        }
        val placements = mutableMapOf<NodeId, Map<String, Pair<IbdSide, IbdLabelPlacement>>>()
        val boxes = mutableMapOf<NodeId, IbdBox>()
        val frameParts = mutableListOf<IbdBox>()
        val partLabelBoxes = mutableListOf<IbdBox>()
        val partSquares = mutableListOf<IbdBox>()
        val partLabelByPort = linkedMapOf<Pair<NodeId, String>, IbdBox>()
        val partSquareByPort = linkedMapOf<Pair<NodeId, String>, IbdBox>()
        for ((id, nl) in content) {
            val box = nl.bounds.toBox()
            boxes[id] = box
            frameParts += box
            val ports = partPorts[id] ?: continue
            val pl =
                ibdPartPortPlacements(
                    boxSize = nl.bounds.size,
                    ports = ports,
                    labels = labelTexts[id] ?: emptyMap(),
                    frameOf = frameOfPort(id),
                )
            placements[id] = pl
            for ((k, p) in ports) {
                val c = Point(x = box.l + p.x, y = box.t + p.y)
                val sq = squareBox(c = c, half = half + IBD_PORT_CLEARANCE_PX)
                frameParts += sq
                partSquares += sq
                partSquareByPort[id to k] = sq
                val lb =
                    pl
                        .getValue(k)
                        .second.rect
                        .toBox()
                        .shift(dx = box.l, dy = box.t)
                frameParts += lb
                partLabelBoxes += lb
                partLabelByPort[id to k] = lb
            }
        }

        // B/C. Rahmen aus der Vereinigung bestimmen.
        val uL = frameParts.minOf { it.l }
        val uR = frameParts.maxOf { it.r }
        val uB = frameParts.maxOf { it.b }
        val ownerName = model.definitions.firstOrNull { it.id == diagram.ownerId }?.name ?: diagram.ownerId
        val tabW = ibdTabWidth(ownerName)
        val x0 = uL - pad
        var x1 = maxOf(uR + pad, x0 + tabW + 2 * pad)
        val tabRight = x0 + tabW
        val y0 =
            frameParts.minOf {
                it.t - (if (it.r > x0 && it.l < tabRight) IBD_FRAME_TAB_H_PX + IBD_TAB_CONTENT_GAP_PX else pad)
            }
        var y1 = uB + pad

        // D. Owner-Ports: Seite nach dem verbundenen Part-Port, Position entlang der Seite verteilen.
        fun partPortAbs(end: dev.kuml.sysml2.projection.ResolvedEnd): Triple<Point, IbdSide, NodeId>? {
            val nodeId = end.nodeId?.let { NodeId(it) } ?: return null
            if (end.steps.isEmpty()) return null
            val key = end.steps.joinToString("::")
            val local = partPorts[nodeId]?.get(key) ?: return null
            val box = boxes[nodeId] ?: return null
            val side = placements[nodeId]?.get(key)?.first ?: return null
            return Triple(Point(x = box.l + local.x, y = box.t + local.y), side, nodeId)
        }
        val places = mutableListOf<OwnerPlace>()
        // Nacharbeit 48 (N-IBD-F d): declaration order, so that ports at the same coordinate keep it (not the id order).
        val ownerDeclaration = projection.boundaryPorts.associate { p -> p.id to model.usages.indexOfFirst { it.id == p.id } }
        for (port in projection.boundaryPorts.sortedWith(compareBy({ ownerDeclaration.getValue(it.id) }, { it.id }))) {
            val id = NodeId(port.id)
            var cp: Point? = null
            var cpSide: IbdSide? = null
            var cpNode: NodeId? = null
            val targets = mutableListOf<Point>()
            val targetNodes = linkedSetOf<NodeId>()
            for (w in wires) {
                val other =
                    when {
                        w.src.ownerPort?.id == port.id -> w.tgt
                        w.tgt.ownerPort?.id == port.id -> w.src
                        else -> continue
                    }
                val abs = partPortAbs(other) ?: continue
                if (cp == null) {
                    cp = abs.first
                    cpSide = abs.second
                    cpNode = abs.third
                }
                targets += abs.first
                targetNodes += abs.third
            }
            val b = layoutResult.nodes[id]?.bounds
            val ex = (b?.origin?.x ?: x0) + (b?.size?.width ?: 0f) / 2f
            val ey = (b?.origin?.y ?: y0) + (b?.size?.height ?: 0f) / 2f
            // A connector leaves a part port outward; the frame side opposite to that would force the line through
            // the part, so it is excluded.
            val forbidden =
                when (cpSide) {
                    IbdSide.Top -> IbdSide.Bottom
                    IbdSide.Bottom -> IbdSide.Top
                    IbdSide.Left -> IbdSide.Right
                    IbdSide.Right -> IbdSide.Left
                    null -> null
                }
            val frameRect = Rect(origin = Point(x = x0, y = y0), size = Size(width = x1 - x0, height = y1 - y0))
            val partBox =
                if (targetNodes.isEmpty()) {
                    Rect(origin = Point(x = ex, y = ey), size = Size(width = 0f, height = 0f))
                } else {
                    val bs = targetNodes.mapNotNull { boxes[it] }
                    IbdBox(l = bs.minOf { it.l }, t = bs.minOf { it.t }, r = bs.maxOf { it.r }, b = bs.maxOf { it.b }).toRect()
                }
            val (side, pos) =
                placeOwnerPort(
                    frame = frameRect,
                    targets = targets.ifEmpty { listOf(Point(x = ex, y = ey)) },
                    partBox = partBox,
                    exclude = forbidden,
                    prefer = ibdPreferredOwnerSide(direction = port.effectivePortDirection(model), flow = diagram.layout?.direction),
                )
            places +=
                OwnerPlace(
                    port = port,
                    id = id,
                    side = side,
                    pos = pos,
                    cp = cp,
                    cpSide = cpSide,
                    cpNode = cpNode,
                    label =
                        ibdPortLabelText(
                            name = port.name,
                            typeName = ibdTypeName(model = model, port = port),
                            conjugated = port.isConjugated,
                        ),
                )
        }

        fun minStart(side: IbdSide): Float =
            when (side) {
                IbdSide.Top -> tabRight + IBD_TAB_CONTENT_GAP_PX + half
                IbdSide.Bottom -> x0 + pad
                IbdSide.Left -> y0 + IBD_FRAME_TAB_H_PX + IBD_TAB_CONTENT_GAP_PX + half
                IbdSide.Right -> y0 + pad
            }

        fun frameCoord(
            side: IbdSide,
            pos: Float,
        ): Point =
            when (side) {
                IbdSide.Top -> Point(x = pos, y = y0)
                IbdSide.Bottom -> Point(x = pos, y = y1)
                IbdSide.Left -> Point(x = x0, y = pos)
                IbdSide.Right -> Point(x = x1, y = pos)
            }

        // Kollinear-Versatz: gleiche Achse wie der Part-Port -> seitlich versetzen, damit die Kante einen Knick bekommt.
        for (pl in places) {
            val cp = pl.cp ?: continue
            val vertical = pl.side == IbdSide.Top || pl.side == IbdSide.Bottom
            val cpVertical = pl.cpSide == IbdSide.Top || pl.cpSide == IbdSide.Bottom
            if (vertical != cpVertical) continue
            val cpAlong = if (vertical) cp.x else cp.y
            if (kotlin.math.abs(pl.pos - cpAlong) >= 1f) continue
            val lo = minStart(pl.side)
            val hi = (if (vertical) x1 else y1) - pad
            val order =
                if (hi - pl.pos >= pl.pos - lo) {
                    listOf(IBD_COLLINEAR_OFFSET_PX, -IBD_COLLINEAR_OFFSET_PX)
                } else {
                    listOf(-IBD_COLLINEAR_OFFSET_PX, IBD_COLLINEAR_OFFSET_PX)
                }
            val others = (partLabelBoxes + boxes.filterKeys { it != pl.cpNode }.values).map { it.inflate(1f) }
            val inRange = order.map { pl.pos + it }.filter { it in lo..maxOf(hi, lo) }
            val free =
                inRange.firstOrNull { cand ->
                    val from = frameCoord(pl.side, cand)
                    val seg =
                        if (vertical) {
                            segmentBox(
                                a = from,
                                b = Point(x = cand, y = cp.y),
                            )
                        } else {
                            segmentBox(a = from, b = Point(x = cp.x, y = cand))
                        }
                    others.none { it.intersects(seg) }
                }
            pl.pos = free ?: inRange.firstOrNull() ?: (pl.pos + IBD_COLLINEAR_OFFSET_PX)
        }

        // Entlang jeder Seite sortieren und Mindestabstände einhalten.
        for (side in listOf(IbdSide.Top, IbdSide.Bottom, IbdSide.Left, IbdSide.Right)) {
            val onSide = places.filter { it.side == side }.sortedBy { it.pos }
            val vertical = side == IbdSide.Top || side == IbdSide.Bottom
            var last: OwnerPlace? = null
            for (pl in onSide) {
                var pos = maxOf(pl.pos, minStart(side))
                val prev = last
                if (prev != null) {
                    val gap =
                        if (vertical) {
                            maxOf(
                                IBD_PORT_MIN_GAP_PX,
                                estimateLabelSize(text = prev.label).width / 2f + estimateLabelSize(text = pl.label).width / 2f + 12f,
                            )
                        } else {
                            maxOf(IBD_PORT_MIN_GAP_PX * 0.7f, estimateLabelSize(text = pl.label).height + 12f)
                        }
                    if (pos - prev.pos < gap) pos = prev.pos + gap
                }
                pl.pos = pos
                last = pl
            }
            if (last != null) {
                if (vertical) x1 = maxOf(x1, last.pos + pad) else y1 = maxOf(y1, last.pos + pad)
            }
        }

        // E. Owner-Port-Quadrate und -Labels.
        val ownerCenter = mutableMapOf<NodeId, Point>()
        val ownerLabelBoxes = mutableListOf<IbdBox>()
        val ownerSquares = mutableListOf<IbdBox>()
        val ownerLabelById = linkedMapOf<NodeId, IbdBox>()
        val ownerSquareById = linkedMapOf<NodeId, IbdBox>()
        for (pl in places) {
            val c = frameCoord(pl.side, pl.pos)
            ownerCenter[pl.id] = c
            val sq = squareBox(c = c, half = half)
            ownerSquares += sq
            ownerSquareById[pl.id] = sq
            val lb = ibdLabelPlacement(side = pl.side, cx = c.x, cy = c.y, text = pl.label, partPort = false).rect.toBox()
            ownerLabelBoxes += lb
            ownerLabelById[pl.id] = lb
        }
        val ownerByNode = places.associateBy { it.id }

        // F. Kanten.
        val avoidBoxes = (partLabelBoxes + ownerLabelBoxes + boxes.values + partSquares).map { it.inflate(1f) }
        val newEdges = linkedMapOf<EdgeId, EdgeRoute>()
        val adapter = IbdEdgeAdapter(model = model, diagram = diagram)
        val wireById = wires.associateBy { it.edgeId }
        for ((eid, route) in layoutResult.edges) {
            val w = wireById[eid.value]
            val srcOwner = w?.src?.ownerPort?.let { ownerByNode[NodeId(it.id)] }
            val tgtOwner = w?.tgt?.ownerPort?.let { ownerByNode[NodeId(it.id)] }
            if (w == null || (srcOwner == null && tgtOwner == null && partPortAbs(w.src) == null && partPortAbs(w.tgt) == null)) {
                newEdges[eid] = route
                continue
            }

            fun endpoint(
                end: dev.kuml.sysml2.projection.ResolvedEnd,
                owner: OwnerPlace?,
                raw: Point,
            ): Pair<Point, Point?> {
                if (owner != null) {
                    val c = ownerCenter.getValue(owner.id)
                    val dir = inwardOf(owner.side)
                    return Point(x = c.x + dir.x * half, y = c.y + dir.y * half) to dir
                }
                val pp = partPortAbs(end)
                if (pp != null) {
                    val dir = outwardOf(pp.second)
                    return Point(x = pp.first.x + dir.x * half, y = pp.first.y + dir.y * half) to dir
                }
                return raw to null
            }
            val (a, da) = endpoint(w.src, srcOwner, route.source)
            val (b, db) = endpoint(w.tgt, tgtOwner, route.target)

            // PAL 2C (P-IBD-08): central clamping onto the outer port border, perpendicular; stub only with arrowhead.
            fun attach(
                end: dev.kuml.sysml2.projection.ResolvedEnd,
                owner: OwnerPlace?,
            ): PortAttachment? {
                if (owner != null) {
                    return PortAttachment(
                        rect = squareBox(c = ownerCenter.getValue(owner.id), half = half).toRect(),
                        face = ibdFace(side = owner.side, inward = true),
                    )
                }
                val pp = partPortAbs(end) ?: return null
                return PortAttachment(rect = squareBox(c = pp.first, half = half).toRect(), face = ibdFace(side = pp.second))
            }

            // PAL 2C-b2b: an end on a nested frame is reached through a gate on the border of the visible box: the outer part of the
            // connector is routed to the gate like to any port, the inner part is the straight corridor from the gate to the port.
            fun gateOf(end: dev.kuml.sysml2.projection.ResolvedEnd): IbdGate? {
                val nodeId = end.nodeId ?: return null
                if (ibdPortFrame(nodeId = nodeId, key = end.steps.joinToString("::"), frames = nestedFrames) == null) return null
                val pp = partPortAbs(end) ?: return null
                val box = boxes[NodeId(nodeId)] ?: return null
                val gate =
                    when (pp.second) {
                        IbdSide.Top -> Point(x = pp.first.x, y = box.t)
                        IbdSide.Bottom -> Point(x = pp.first.x, y = box.b)
                        IbdSide.Left -> Point(x = box.l, y = pp.first.y)
                        IbdSide.Right -> Point(x = box.r, y = pp.first.y)
                    }
                val dir = outwardOf(pp.second)
                return IbdGate(
                    gate = gate,
                    portBorder = Point(x = pp.first.x + dir.x * half, y = pp.first.y + dir.y * half),
                    side = pp.second,
                )
            }
            val gateS = gateOf(w.src)
            val gateT = gateOf(w.tgt)

            fun gateAttachment(g: IbdGate) =
                PortAttachment(rect = Rect(origin = g.gate, size = Size(width = 0f, height = 0f)), face = ibdFace(side = g.side))
            val srcAtt = gateS?.let { gateAttachment(it) } ?: attach(w.src, srcOwner)
            val tgtAtt = gateT?.let { gateAttachment(it) } ?: attach(w.tgt, tgtOwner)
            val (srcArrow, tgtArrow) = Sysml2EdgeArrowEnds.of(adapter.metadataFor(eid.value))
            val endNodes = setOfNotNull(partPortAbs(w.src)?.third, partPortAbs(w.tgt)?.third)
            // Labels and squares of all ports except the two connected ones are obstacles for the port-to-port router.
            val endPortKeys =
                listOf(w.src, w.tgt).mapNotNull { e -> partPortAbs(e)?.let { it.third to e.steps.joinToString("::") } }.toSet()
            val endOwnerIds = setOfNotNull(srcOwner?.id, tgtOwner?.id)
            val extraObstacles =
                (
                    partLabelByPort.filterKeys { it !in endPortKeys }.values +
                        partSquareByPort.filterKeys { it !in endPortKeys }.values +
                        ownerLabelById.filterKeys { it !in endOwnerIds }.values +
                        ownerSquareById.filterKeys { it !in endOwnerIds }.values
                ).map { it.toRect() }
            val routed =
                if (srcAtt != null && tgtAtt != null) {
                    route
                } else {
                    ibdOrthoRoute(a = a, da = da, b = b, db = db, avoid = avoidBoxes)
                }
            val outer =
                ibdRouteAndClamp(
                    route = routed,
                    source = srcAtt,
                    target = tgtAtt,
                    sourceArrowhead = srcArrow && gateS == null,
                    targetArrowhead = tgtArrow && gateT == null,
                    foreign = boxes.filterKeys { it !in endNodes }.values.map { it.toRect() },
                    own = boxes.filterKeys { it in endNodes }.values.map { it.toRect() },
                    extra = extraObstacles,
                )
            newEdges[eid] =
                if (gateS == null && gateT == null) {
                    outer
                } else {
                    val pts = routePoints(outer).toMutableList()
                    if (gateS != null) pts.add(0, gateS.portBorder)
                    if (gateT != null) pts.add(gateT.portBorder)
                    val all = PortEdgeClamp.simplify(pts)
                    var minSeg = Float.MAX_VALUE
                    for (i in 0 until all.size - 1) {
                        val l = kotlin.math.abs(all[i + 1].x - all[i].x) + kotlin.math.abs(all[i + 1].y - all[i].y)
                        if (l > 0.01f) minSeg = minOf(minSeg, l)
                    }
                    EdgeRoute.OrthogonalRounded(
                        source = all.first(),
                        target = all.last(),
                        waypoints = all.subList(1, all.size - 1),
                        cornerRadiusPx = if (minSeg == Float.MAX_VALUE) 0f else minOf(6f, minSeg / 2f),
                    )
                }
        }

        // G. Zeichenfläche aus dem Inhalt neu bemessen und alles so verschieben, dass das Minimum bei (margin, margin) liegt.
        val frameBox = IbdBox(l = x0, t = y0, r = x1, b = y1)
        val tabBox = IbdBox(l = x0, t = y0, r = x0 + tabW, b = y0 + IBD_FRAME_TAB_H_PX)
        val extents = mutableListOf(frameBox, tabBox)
        extents += ownerLabelBoxes
        extents += ownerSquares
        extents += boxes.values
        extents += partLabelBoxes
        for (g in layoutResult.groups.values) extents += g.bounds.toBox()
        for (r in newEdges.values) {
            val pts = routePoints(r)
            extents += IbdBox(l = pts.minOf { it.x }, t = pts.minOf { it.y }, r = pts.maxOf { it.x }, b = pts.maxOf { it.y })
        }
        val minX = extents.minOf { it.l }
        val minY = extents.minOf { it.t }
        val maxX = extents.maxOf { it.r }
        val maxY = extents.maxOf { it.b }
        val dx = IBD_CANVAS_MARGIN_PX - minX
        val dy = IBD_CANVAS_MARGIN_PX - minY

        val newNodes = linkedMapOf<NodeId, NodeLayout>()
        for ((id, nl) in layoutResult.nodes) {
            if (id in ownerPortIds) continue
            val ports = partPorts[id]?.entries?.associate { (k, p) -> PortId(k) to p } ?: nl.ports
            newNodes[id] =
                nl.copy(
                    bounds = Rect(origin = Point(x = nl.bounds.origin.x + dx, y = nl.bounds.origin.y + dy), size = nl.bounds.size),
                    ports = ports,
                )
        }
        for (pl in places) {
            val c = ownerCenter.getValue(pl.id)
            newNodes[pl.id] =
                NodeLayout(
                    bounds =
                        Rect(
                            origin = Point(x = c.x - half + dx, y = c.y - half + dy),
                            size = Size(width = IBD_PORT_SIZE_PX, height = IBD_PORT_SIZE_PX),
                        ),
                )
        }
        val shiftedEdges = LinkedHashMap<EdgeId, EdgeRoute>()
        for ((eid, r) in newEdges) shiftedEdges[eid] = translateRoute(r = r, dx = dx, dy = dy)
        val newGroups =
            LinkedHashMap(
                layoutResult.groups.mapValues { (_, g) ->
                    GroupLayout(Rect(origin = Point(x = g.bounds.origin.x + dx, y = g.bounds.origin.y + dy), size = g.bounds.size))
                },
            )
        newGroups[ibdFrameGroupId(diagram.ownerId)] =
            GroupLayout(Rect(origin = Point(x = x0 + dx, y = y0 + dy), size = Size(width = x1 - x0, height = y1 - y0)))
        return layoutResult.copy(
            canvas = Size(width = maxX - minX + 2 * IBD_CANVAS_MARGIN_PX, height = maxY - minY + 2 * IBD_CANVAS_MARGIN_PX),
            nodes = newNodes,
            edges = shiftedEdges,
            groups = newGroups,
        )
    }

    /**
     * Label-Rechtecke aller Part- und Owner-Ports eines gerahmten IBD-Layouts (Layout-Koordinaten). Layout, Renderer
     * und Tests nutzen dieselben Platzierungsfunktionen.
     */
    public fun ibdLabelRects(
        model: Sysml2Model,
        diagram: IbdDiagram,
        layout: LayoutResult,
    ): List<IbdLabelRect> {
        val projection = sysml2IbdProjection(model = model, diagram = diagram)
        if (!projection.showFrame) return emptyList()
        val wires = ibdWires(projection)
        val labelTexts = ibdPartPortLabels(model = model, wires = wires)
        val out = mutableListOf<IbdLabelRect>()
        val ownerIds = projection.boundaryPorts.map { NodeId(it.id) }.toSet()
        for ((id, nl) in layout.nodes) {
            if (id in ownerIds || nl.ports.isEmpty()) continue
            val pl =
                ibdPartPortPlacements(
                    boxSize = nl.bounds.size,
                    ports = nl.ports.mapKeys { it.key.value },
                    labels = labelTexts[id] ?: emptyMap(),
                    frameOf = { key -> ibdPortFrameRect(nodeId = id.value, key = key, frames = layout.ibdNestedFrames) },
                )
            for ((k, v) in pl) {
                out +=
                    IbdLabelRect(
                        owner = "${id.value}::$k",
                        kind = IbdLabelKind.PartPort,
                        rect =
                            v.second.rect
                                .toBox()
                                .shift(dx = nl.bounds.origin.x, dy = nl.bounds.origin.y)
                                .toRect(),
                    )
            }
        }
        val frame = layout.groups[ibdFrameGroupId(diagram.ownerId)]?.bounds ?: return out
        val f = frame.toBox()
        for (port in projection.boundaryPorts) {
            val b = layout.nodes[NodeId(port.id)]?.bounds ?: continue
            val cx = b.origin.x + b.size.width / 2f
            val cy = b.origin.y + b.size.height / 2f
            val side =
                listOf(
                    IbdSide.Top to kotlin.math.abs(cy - f.t),
                    IbdSide.Bottom to kotlin.math.abs(cy - f.b),
                    IbdSide.Left to kotlin.math.abs(cx - f.l),
                    IbdSide.Right to kotlin.math.abs(cx - f.r),
                ).minBy { it.second }.first
            val text =
                ibdPortLabelText(name = port.name, typeName = ibdTypeName(model = model, port = port), conjugated = port.isConjugated)
            out +=
                IbdLabelRect(
                    owner = port.id,
                    kind = IbdLabelKind.OwnerPort,
                    rect = ibdLabelPlacement(side = side, cx = cx, cy = cy, text = text, partPort = false).rect,
                )
        }
        return out
    }

    /**
     * The part-port labels of a laid out IBD as label-plan requests (PAL wave 2C-b2b, layout coordinates): one per port
     * square, ranked outside / inside its owner box or nested frame, kept off the side where its connector docks. The
     * result of the plan (`port:<node id>::<port key>`) is what the renderer draws and what notes keep clear of.
     */
    public fun ibdPortLabelSpecs(
        model: Sysml2Model,
        diagram: IbdDiagram,
        layout: LayoutResult,
    ): List<dev.kuml.layout.bridge.label.PortLabelSpec> {
        val projection = sysml2IbdProjection(model = model, diagram = diagram)
        val labelTexts = ibdPartPortLabels(model = model, wires = ibdWires(projection))
        val ownerIds = projection.boundaryPorts.map { NodeId(it.id) }.toSet()
        val out = ArrayList<dev.kuml.layout.bridge.label.PortLabelSpec>()
        for ((id, nl) in layout.nodes.entries.sortedBy { it.key.value }) {
            if (id in ownerIds || nl.ports.isEmpty()) continue
            for ((pid, local) in nl.ports.entries.sortedBy { it.key.value }) {
                val key = pid.value
                val text = labelTexts[id]?.get(key) ?: key
                val frame = ibdPortFrameRect(nodeId = id.value, key = key, frames = layout.ibdNestedFrames)
                val side =
                    if (frame == null) {
                        ibdNearestSide(p = local, w = nl.bounds.size.width, h = nl.bounds.size.height)
                    } else {
                        ibdNearestSide(
                            p = Point(x = local.x - frame.origin.x, y = local.y - frame.origin.y),
                            w = frame.size.width,
                            h = frame.size.height,
                        )
                    }
                val labelSide =
                    when (side) {
                        IbdSide.Top -> dev.kuml.layout.bridge.label.LabelSide.NORTH
                        IbdSide.Bottom -> dev.kuml.layout.bridge.label.LabelSide.SOUTH
                        IbdSide.Left -> dev.kuml.layout.bridge.label.LabelSide.WEST
                        IbdSide.Right -> dev.kuml.layout.bridge.label.LabelSide.EAST
                    }
                out +=
                    dev.kuml.layout.bridge.label.PortLabelSpec(
                        id = ibdPortLabelId(nodeId = id.value, key = key),
                        ownerId = "${id.value}::$key",
                        size = estimateLabelSize(text = text),
                        anchorRect = ibdPortSquare(boxBounds = nl.bounds, local = local),
                        excludedSide = labelSide,
                        outward = labelSide,
                        parentId = id.value,
                    )
            }
        }
        return out
    }

    /** Label-plan id of the label of port [key] of the part box [nodeId]. */
    public fun ibdPortLabelId(
        nodeId: String,
        key: String,
    ): String = "port:$nodeId::$key"

    /**
     * Rechtecke, in die Connector-Labels nicht ragen dürfen (Layout-Koordinaten): Port-Labels, Port-Quadrate samt
     * Freizone und Part-Boxen.
     */
    public fun ibdAvoidRects(
        model: Sysml2Model,
        diagram: IbdDiagram,
        layout: LayoutResult,
    ): List<Rect> {
        val projection = sysml2IbdProjection(model = model, diagram = diagram)
        if (!projection.showFrame) return emptyList()
        val half = IBD_PORT_SIZE_PX / 2f
        val ownerIds = projection.boundaryPorts.map { NodeId(it.id) }.toSet()
        val out = mutableListOf<Rect>()
        out += ibdLabelRects(model = model, diagram = diagram, layout = layout).map { it.rect }
        layout.groups[ibdFrameGroupId(diagram.ownerId)]?.bounds?.let { f ->
            val ownerName = model.definitions.firstOrNull { it.id == diagram.ownerId }?.name ?: diagram.ownerId
            out += Rect(origin = f.origin, size = Size(width = ibdTabWidth(ownerName), height = IBD_FRAME_TAB_H_PX))
        }
        for ((id, nl) in layout.nodes) {
            if (id in ownerIds) {
                out +=
                    nl.bounds
                        .toBox()
                        .inflate(IBD_PORT_CLEARANCE_PX)
                        .toRect()
                continue
            }
            out += nl.bounds
            for (p in nl.ports.values) {
                out +=
                    squareBox(
                        c = Point(x = nl.bounds.origin.x + p.x, y = nl.bounds.origin.y + p.y),
                        half = half + IBD_PORT_CLEARANCE_PX,
                    ).toRect()
            }
        }
        return out
    }

    private fun translateRoute(
        r: EdgeRoute,
        dx: Float,
        dy: Float,
    ): EdgeRoute {
        fun t(p: Point) = Point(x = p.x + dx, y = p.y + dy)
        return when (r) {
            is EdgeRoute.Direct -> r.copy(source = t(r.source), target = t(r.target))
            is EdgeRoute.OrthogonalRounded -> r.copy(source = t(r.source), target = t(r.target), waypoints = r.waypoints.map(::t))
            is EdgeRoute.TreeRounded -> r.copy(source = t(r.source), target = t(r.target), waypoints = r.waypoints.map(::t))
            is EdgeRoute.Bezier -> r.copy(source = t(r.source), target = t(r.target), controlPoints = r.controlPoints.map(::t))
        }
    }

    @Suppress("unused")
    private fun PartDefinition.featureCount(): Int = features.size
}
