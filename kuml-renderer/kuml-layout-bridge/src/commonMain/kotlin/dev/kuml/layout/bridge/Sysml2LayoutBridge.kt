package dev.kuml.layout.bridge

import dev.kuml.layout.EdgeHints
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.EndpointRef
import dev.kuml.layout.GroupId
import dev.kuml.layout.GroupLayout
import dev.kuml.layout.Insets
import dev.kuml.layout.LayoutDirection
import dev.kuml.layout.LayoutEdge
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutGroup
import dev.kuml.layout.LayoutNode
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.PortId
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
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
import dev.kuml.sysml2.ExecutionSpecificationUsage
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.LifelineDefinition
import dev.kuml.sysml2.MessageUsage
import dev.kuml.sysml2.ObjectFlowUsage
import dev.kuml.sysml2.ParDiagram
import dev.kuml.sysml2.PartDefinition
import dev.kuml.sysml2.PartUsage
import dev.kuml.sysml2.ReqDiagram
import dev.kuml.sysml2.RequirementDefinition
import dev.kuml.sysml2.SeqDiagram
import dev.kuml.sysml2.StateDefinition
import dev.kuml.sysml2.StateKind
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.Sysml2Definition
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.TestCaseDefinition
import dev.kuml.sysml2.TransitionUsage
import dev.kuml.sysml2.UcDiagram
import dev.kuml.sysml2.UseCaseDefinition
import dev.kuml.sysml2.constraint.Sysml2StmChecker
import dev.kuml.sysml2.edge.ReqEdgeAdapter
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
        for (e in sysml2BddProjection(model = model, diagram = diagram).edges) {
            if (e.sourceId !in visibleIds || e.targetId !in visibleIds) continue
            edges +=
                LayoutEdge(
                    id = EdgeId(e.id),
                    source = EndpointRef(nodeId = NodeId(e.sourceId)),
                    target = EndpointRef(nodeId = NodeId(e.targetId)),
                    hints = EdgeHints.NONE,
                )
        }

        return LayoutGraph(nodes = nodes, edges = edges).withSysml2Notes(model = model, diagram = diagram)
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

    /** Default-Höhe einer Fork-/Join-Synchronisations-Bar (V2.0.10). */
    public const val ACT_BAR_HEIGHT: Float = 10f

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
            val stereoW = "«part def»".length * IBD_STEREO_CHAR_PX
            val nameW = (def?.name?.length ?: 0) * IBD_TITLE_CHAR_PX
            val bodyMax =
                ((content?.widthProbe ?: emptyList()) + actions + flows).maxOfOrNull { it.length * BDD_BODY_CHAR_PX } ?: 0f
            val contentW = maxOf(stereoW, nameW, bodyMax) + 2 * IBD_H_PAD
            Size(width = maxOf(contentW, DEFAULT_WIDTH), height = maxOf(h, 70f))
        }
    }

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

        return SizeProvider { id, _ ->
            val h = STEREOTYPE_LINE_H + NAME_LINE_H + BOX_V_PADDING
            val w =
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
                    val contentW = maxOf(stereoW, titleW) + 2 * IBD_H_PAD
                    maxOf(contentW, IBD_DEFAULT_WIDTH)
                }
            val (wExtra, hExtra) =
                stmConnectionPuffer(transitionCount = connectionsById[id] ?: 0, layoutDirection = layoutDirection)
            dev.kuml.layout.Size(width = w + wExtra, height = maxOf(h, 50f) + hExtra)
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
                    val actionCount =
                        listOfNotNull(def?.entryAction, def?.exitAction, def?.doAction)
                            .count { it.isNotBlank() }
                    val baseH =
                        NAME_LINE_H +
                            (if (actionCount > 0) DIVIDER_GAP + actionCount * ACTION_LINE_H else 0f) +
                            BOX_V_PADDING
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
                    val actionMaxW =
                        listOfNotNull(
                            def?.entryAction?.takeIf { it.isNotBlank() }?.let { "entry / $it" },
                            def?.doAction?.takeIf { it.isNotBlank() }?.let { "do / $it" },
                            def?.exitAction?.takeIf { it.isNotBlank() }?.let { "exit / $it" },
                        ).maxOfOrNull { it.length * BDD_BODY_CHAR_PX } ?: 0f
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
                        val card = Sysml2ReqCard.forRequirement(req)
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
                else -> dev.kuml.layout.Size(width = REQ_DEFAULT_WIDTH, height = REQ_DEFAULT_HEIGHT)
            }
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
    ): dev.kuml.layout.Size {
        val nameW = (name?.length ?: 0) * BDD_BODY_CHAR_PX
        val contentW = nameW + 2 * UC_ELLIPSE_H_PAD
        if (description.isBlank()) {
            return dev.kuml.layout.Size(width = maxOf(contentW, UC_USECASE_WIDTH), height = UC_USECASE_HEIGHT)
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
        val height = maxOf(UC_USECASE_HEIGHT, (24f + n * Sysml2ReqCard.UC_DESCRIPTION_LINE_H) / 0.62f)
        return dev.kuml.layout.Size(width = width, height = height)
    }

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

        return LayoutGraph(nodes = nodes, edges = edges).withSysml2Notes(model = model, diagram = diagram)
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
        sizeProvider: SizeProvider = ucContentAwareSizeProvider(model),
    ): LayoutGraph {
        val visibleIds: Set<String> = diagram.elementIds.toSet()

        val nodes = mutableListOf<LayoutNode>()
        for (id in diagram.elementIds) {
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
            .withSysml2Notes(model = model, diagram = diagram, extraVisibleIds = groups.map { it.id.value }.toSet())
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
            nodes +=
                LayoutNode(
                    id = NodeId(def.id),
                    intrinsicSize = sizeProvider.sizeOf(elementId = def.id, elementKind = kind),
                )
        }
        val visibleNodeIds: Set<String> = nodes.map { it.id.value }.toSet()

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
        for (id in diagram.elementIds) {
            val req = model.definitions.firstOrNull { it.id == id } as? RequirementDefinition ?: continue
            val ref = req.subjectRef ?: continue
            if (id !in visibleNodeIds || ref !in visibleNodeIds || ref == id) continue
            edges += reqEdge(id = "${ReqEdgeAdapter.SUBJECT_EDGE_PREFIX}$id::$ref", source = id, target = ref)
        }

        val groups = c1RequirementGroups(diagram = diagram, visibleNodeIds = visibleNodeIds)
        val groupOfNode = mutableMapOf<String, GroupId>()
        for (g in diagram.groups.take(C1_MAX_GROUPS)) {
            val gid = GroupId(g.id)
            if (groups.none { it.id == gid }) continue
            for (m in g.memberIds) if (m in visibleNodeIds && m !in groupOfNode) groupOfNode[m] = gid
        }
        val finalNodes = if (groupOfNode.isEmpty()) nodes else nodes.map { n -> groupOfNode[n.id.value]?.let { n.copy(groupId = it) } ?: n }

        return LayoutGraph(nodes = finalNodes, edges = edges, groups = groups)
            .withSysml2Notes(model = model, diagram = diagram, extraVisibleIds = groups.map { it.id.value }.toSet())
    }

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
        sizeProvider: SizeProvider = stmContentAwareSizeProvider(model = model, diagram = diagram),
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

        return LayoutGraph(nodes = nodes, edges = edges).withSysml2Notes(model = model, diagram = diagram)
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
            val actionLines = listOfNotNull(s.entryAction, s.exitAction, s.doAction).count { it.isNotBlank() }
            groups +=
                LayoutGroup(
                    id = GroupId(s.id),
                    parent = containerOf(s),
                    padding = STM_COMPOSITE_PADDING.copy(top = STM_COMPOSITE_PADDING.top + actionLines * STM_COMPOSITE_ACTION_LINE_H),
                    layoutAsCompound = true,
                    minSize = STM_COMPOSITE_MIN_SIZE,
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
            .withSysml2Notes(model = model, diagram = diagram, extraVisibleIds = groupIds)
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
        val partitionPadding = Insets(top = 0f, right = 80f, bottom = 0f, left = 80f)
        val groups: List<LayoutGroup> =
            model.definitions
                .filterIsInstance<ActivityPartitionDefinition>()
                .filter { it.id in visiblePartitionIds }
                .map { LayoutGroup(id = GroupId(it.id), parent = null, padding = partitionPadding) }

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

        return LayoutGraph(nodes = nodes, edges = edges, groups = groups).withSysml2Notes(model = model, diagram = diagram)
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
        val lifelineHeight: Float =
            SEQ_LIFELINE_HEAD_HEIGHT +
                (rowCount + 1) * SEQ_MESSAGE_ROW_HEIGHT +
                operandBandCount * SEQ_FRAGMENT_HEADER_BAND +
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
    public fun seqContentAwareSizeProvider(model: Sysml2Model): SizeProvider =
        SizeProvider { id, kindHint ->
            when (kindHint) {
                "LifelineDefinition" -> {
                    val lifeline = model.definitions.firstOrNull { it.id == id } as? LifelineDefinition
                    val stereoW = "«lifeline»".length * IBD_STEREO_CHAR_PX
                    val nameW = (lifeline?.name?.length ?: 0) * IBD_TITLE_CHAR_PX
                    val contentW = maxOf(stereoW, nameW) + 2 * IBD_H_PAD
                    dev.kuml.layout.Size(width = maxOf(contentW, SEQ_LIFELINE_WIDTH), height = SEQ_LIFELINE_HEAD_HEIGHT)
                }
                else -> dev.kuml.layout.Size(width = SEQ_LIFELINE_WIDTH, height = SEQ_LIFELINE_HEAD_HEIGHT)
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
            when (kindHint) {
                ActivityNodeKind.Action.name -> {
                    val def = model.definitions.firstOrNull { it.id == id } as? ActionDefinition
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
                ->
                    dev.kuml.layout.Size(width = ACT_DIAMOND_WIDTH, height = ACT_DIAMOND_HEIGHT)
                ActivityNodeKind.Fork.name,
                ActivityNodeKind.Join.name,
                ->
                    dev.kuml.layout.Size(width = ACT_BAR_WIDTH, height = ACT_BAR_HEIGHT)
                else -> dev.kuml.layout.Size(width = ACT_ACTION_WIDTH, height = ACT_ACTION_HEIGHT)
            }
        }

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
    public fun ucContentAwareSizeProvider(model: Sysml2Model): SizeProvider =
        SizeProvider { id, kindHint ->
            when (kindHint) {
                "ActorDefinition" -> {
                    val actor = model.definitions.firstOrNull { it.id == id } as? ActorDefinition
                    ucActorContentSize(name = actor?.name)
                }
                "UseCaseDefinition" -> {
                    val uc = model.definitions.firstOrNull { it.id == id } as? UseCaseDefinition
                    ucUseCaseContentSize(name = uc?.name, description = uc?.description.orEmpty())
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
        val nodes = mutableListOf<LayoutNode>()
        for (id in diagram.elementIds) {
            val def = model.definitions.firstOrNull { it.id == id } ?: continue
            // PAR-Diagramme zeigen Constraints (primär) + Parts (für deren
            // Attribute-Referenzen). Alles andere ist konzeptionell nicht
            // vorgesehen und wird ignoriert.
            if (def !is ConstraintDefinition && def !is PartDefinition) continue
            val kind = def::class.simpleName ?: "Sysml2Definition"
            nodes +=
                LayoutNode(
                    id = NodeId(def.id),
                    intrinsicSize = sizeProvider.sizeOf(elementId = def.id, elementKind = kind),
                )
        }
        val visibleNodeIds: Set<String> = nodes.map { it.id.value }.toSet()

        // Bindings aus dem Modell ziehen (nicht aus dem Diagramm) — siehe
        // KDoc-Begründung oben. Eine Binding wird zur Edge, wenn beide
        // Endpunkte per Longest-Prefix-Match auf sichtbare Knoten auflösen;
        // sonst stillschweigend übersprungen.
        val edges = mutableListOf<LayoutEdge>()
        for (binding in model.usages.filterIsInstance<BindingConnectorUsage>()) {
            val srcNode = longestPrefixNodeId(endpointId = binding.sourceEndId, visibleNodeIds = visibleNodeIds) ?: continue
            val tgtNode = longestPrefixNodeId(endpointId = binding.targetEndId, visibleNodeIds = visibleNodeIds) ?: continue
            edges +=
                LayoutEdge(
                    id = EdgeId(binding.id),
                    source = EndpointRef(nodeId = NodeId(srcNode)),
                    target = EndpointRef(nodeId = NodeId(tgtNode)),
                    hints = EdgeHints.NONE,
                )
        }

        return LayoutGraph(nodes = nodes, edges = edges).withSysml2Notes(model = model, diagram = diagram)
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
     * Avg width of an 11pt monospace char (constraint expression compartment,
     * `font-family="monospace"` in [dev.kuml.io.svg.sysml2.renderConstraintDefinition]).
     * Monospace glyphs at a given point size run wider on average than the
     * proportional [BDD_BODY_CHAR_PX] estimate, since every glyph is sized to
     * fit the widest character in the font.
     */
    public const val PAR_EXPRESSION_CHAR_PX: Float = 7.2f

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
        return SizeProvider { id, kindHint ->
            when (kindHint) {
                "ConstraintDefinition" -> {
                    val c = model.definitions.firstOrNull { it.id == id } as? ConstraintDefinition
                    val paramCount = c?.parameters?.size ?: 0
                    val hasExpression = c?.expression?.isNotBlank() == true
                    val h =
                        STEREOTYPE_LINE_H + NAME_LINE_H +
                            (if (hasExpression) DIVIDER_GAP + FEATURE_LINE_H else 0f) +
                            (if (paramCount > 0) DIVIDER_GAP + paramCount * FEATURE_LINE_H else 0f) +
                            BOX_V_PADDING
                    val nameW = (c?.name?.length ?: 0) * IBD_TITLE_CHAR_PX
                    // EXPRESSION_MAX_CHARS in Sysml2ConstraintSvg.kt (kuml-io-svg) —
                    // duplicated per the house convention (io-svg can't be depended
                    // on from layout-bridge); MUST stay numerically identical.
                    val exprLen = minOf(c?.expression?.length ?: 0, PAR_EXPRESSION_MAX_CHARS_FOR_SIZING)
                    val exprW = exprLen * PAR_EXPRESSION_CHAR_PX
                    val paramMaxW =
                        c?.parameters?.maxOfOrNull { p ->
                            val stereo =
                                when (p.direction) {
                                    dev.kuml.sysml2.ConstraintParameterDirection.In -> "«in»"
                                    dev.kuml.sysml2.ConstraintParameterDirection.Out -> "«out»"
                                    dev.kuml.sysml2.ConstraintParameterDirection.Inout -> "«inout»"
                                }
                            val typeSuffix = p.typeId?.let { " : $it" } ?: ""
                            "$stereo ${p.name}$typeSuffix".length * BDD_BODY_CHAR_PX
                        } ?: 0f
                    val contentW = maxOf(nameW, exprW, paramMaxW) + 2 * IBD_H_PAD
                    dev.kuml.layout.Size(width = maxOf(contentW, PAR_CONSTRAINT_WIDTH), height = maxOf(h, 70f))
                }
                "PartDefinition" -> bdd.sizeOf(elementId = id, elementKind = kindHint)
                else -> dev.kuml.layout.Size(width = PAR_CONSTRAINT_WIDTH, height = PAR_CONSTRAINT_HEIGHT)
            }
        }
    }

    /**
     * Rendered constraint-expression text is truncated at this many
     * characters by [dev.kuml.io.svg.sysml2.renderConstraintDefinition]
     * (`EXPRESSION_MAX_CHARS` in `Sysml2ConstraintSvg.kt`, kuml-io-svg).
     * Duplicated here solely to cap the expression-line contribution to
     * [parContentAwareSizeProvider]'s width estimate at the same length that
     * actually renders; MUST stay numerically identical to the io-svg
     * constant.
     */
    public const val PAR_EXPRESSION_MAX_CHARS_FOR_SIZING: Int = 30

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

        // portPositions[nodeId][portId] = local Point (relative to node origin)
        val portPositions = mutableMapOf<NodeId, MutableMap<PortId, Point>>()

        for ((edgeId, src, tgt) in wires) {
            val route = layoutResult.edges[EdgeId(edgeId)] ?: continue
            for ((end, point) in listOf(src to route.source, tgt to route.target)) {
                val nodeId = end.nodeId ?: continue
                if (end.steps.isEmpty()) continue
                val origin = layoutResult.nodes[NodeId(nodeId)]?.bounds?.origin ?: continue
                val local = Point(x = point.x - origin.x, y = point.y - origin.y)
                val key = PortId(end.steps.joinToString("::"))
                portPositions
                    .getOrPut(NodeId(nodeId)) { mutableMapOf() }
                    .let { map -> if (key !in map) map[key] = local }
            }
        }

        if (portPositions.isEmpty()) return layoutResult

        val enrichedNodes =
            layoutResult.nodes.mapValues { (nodeId, nodeLayout) ->
                val ports = portPositions[nodeId] ?: return@mapValues nodeLayout
                nodeLayout.copy(ports = ports)
            }
        return layoutResult.copy(nodes = enrichedNodes)
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

    /** Längster Port-Label-Text (danach Auslassungszeichen). */
    public const val IBD_PORT_LABEL_MAX_CHARS: Int = 32

    /** Schriftgröße, mit der Label-Größen geschätzt werden. */
    public const val IBD_LABEL_FONT_PX: Float = 12f

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
    ): Size = Size(width = 0.6f * fontPx * text.length * 1.05f, height = 1.3f * fontPx)

    /** Reiter-Titel `ibd [part] Owner` (gekürzt). */
    public fun ibdTabTitle(ownerName: String): String {
        val t = "ibd [part] $ownerName"
        return if (t.length <= IBD_TAB_TITLE_MAX_CHARS) t else t.take(IBD_TAB_TITLE_MAX_CHARS - 1) + "…"
    }

    /** Breite des Reiters; Layout und SVG-Renderer verwenden dieselbe Funktion. */
    public fun ibdTabWidth(ownerName: String): Float = estimateLabelSize(text = ibdTabTitle(ownerName)).width + 16f

    /** `name : Type` (`name : ~Type` bei konjugiertem Port), gekürzt auf [IBD_PORT_LABEL_MAX_CHARS]. */
    public fun ibdPortLabelText(
        name: String,
        typeName: String,
        conjugated: Boolean,
    ): String {
        val full = "$name : ${if (conjugated) "~" else ""}$typeName"
        return if (full.length <= IBD_PORT_LABEL_MAX_CHARS) full else full.take(IBD_PORT_LABEL_MAX_CHARS - 1) + "…"
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
            textY = y + h - 4f,
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
    ): Map<String, Pair<IbdSide, IbdLabelPlacement>> {
        val half = IBD_PORT_SIZE_PX / 2f
        val sides = ports.mapValues { (_, p) -> ibdNearestSide(p = p, w = boxSize.width, h = boxSize.height) }
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

        // A. Part-Ports: aus den Kantenenden, auf die Box-Kante eingerastet (lokale Koordinaten).
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
                m[key] = snapToBorder(p = Point(x = pt.x - o.x, y = pt.y - o.y), size = nl.bounds.size)
            }
        }
        val placements = mutableMapOf<NodeId, Map<String, Pair<IbdSide, IbdLabelPlacement>>>()
        val boxes = mutableMapOf<NodeId, IbdBox>()
        val frameParts = mutableListOf<IbdBox>()
        val partLabelBoxes = mutableListOf<IbdBox>()
        val partSquares = mutableListOf<IbdBox>()
        for ((id, nl) in content) {
            val box = nl.bounds.toBox()
            boxes[id] = box
            frameParts += box
            val ports = partPorts[id] ?: continue
            val pl = ibdPartPortPlacements(boxSize = nl.bounds.size, ports = ports, labels = labelTexts[id] ?: emptyMap())
            placements[id] = pl
            for ((k, p) in ports) {
                val c = Point(x = box.l + p.x, y = box.t + p.y)
                val sq = squareBox(c = c, half = half + IBD_PORT_CLEARANCE_PX)
                frameParts += sq
                partSquares += sq
                val lb =
                    pl
                        .getValue(k)
                        .second.rect
                        .toBox()
                        .shift(dx = box.l, dy = box.t)
                frameParts += lb
                partLabelBoxes += lb
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
        for (port in projection.boundaryPorts) {
            val id = NodeId(port.id)
            var cp: Point? = null
            var cpSide: IbdSide? = null
            var cpNode: NodeId? = null
            for (w in wires) {
                val other =
                    when {
                        w.src.ownerPort?.id == port.id -> w.tgt
                        w.tgt.ownerPort?.id == port.id -> w.src
                        else -> continue
                    }
                val abs = partPortAbs(other) ?: continue
                cp = abs.first
                cpSide = abs.second
                cpNode = abs.third
                break
            }
            val b = layoutResult.nodes[id]?.bounds
            val ex = (b?.origin?.x ?: x0) + (b?.size?.width ?: 0f) / 2f
            val ey = (b?.origin?.y ?: y0) + (b?.size?.height ?: 0f) / 2f
            val ref = cp ?: Point(x = ex, y = ey)
            // A connector leaves a part port outward; the frame side opposite to that would force the line through
            // the part, so it is excluded. Among the rest the nearest side wins.
            val forbidden =
                when (cpSide) {
                    IbdSide.Top -> IbdSide.Bottom
                    IbdSide.Bottom -> IbdSide.Top
                    IbdSide.Left -> IbdSide.Right
                    IbdSide.Right -> IbdSide.Left
                    null -> null
                }
            val side =
                listOf(
                    IbdSide.Top to kotlin.math.abs(ref.y - y0),
                    IbdSide.Bottom to kotlin.math.abs(y1 - ref.y),
                    IbdSide.Left to kotlin.math.abs(ref.x - x0),
                    IbdSide.Right to kotlin.math.abs(x1 - ref.x),
                ).filter { it.first != forbidden }.minBy { it.second }.first
            val sideVertical = side == IbdSide.Top || side == IbdSide.Bottom
            val cpVerticalSide = cpSide == IbdSide.Top || cpSide == IbdSide.Bottom
            // Perpendicular case (owner on a left/right side, port on a top/bottom side, or vice versa): aim at the
            // free space just beyond the port square so the elbow does not run along the part border.
            val lead = half + 24f
            val pos =
                if (cp != null && cpSide != null && sideVertical != cpVerticalSide) {
                    val out = outwardOf(cpSide)
                    if (sideVertical) cp.x + out.x * lead else cp.y + out.y * lead
                } else if (sideVertical) {
                    ref.x
                } else {
                    ref.y
                }
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
        for (pl in places) {
            val c = frameCoord(pl.side, pl.pos)
            ownerCenter[pl.id] = c
            ownerSquares += squareBox(c = c, half = half)
            ownerLabelBoxes +=
                ibdLabelPlacement(side = pl.side, cx = c.x, cy = c.y, text = pl.label, partPort = false).rect.toBox()
        }
        val ownerByNode = places.associateBy { it.id }

        // F. Kanten.
        val avoidBoxes = (partLabelBoxes + ownerLabelBoxes + boxes.values + partSquares).map { it.inflate(1f) }
        val newEdges = linkedMapOf<EdgeId, EdgeRoute>()
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
            newEdges[eid] = ibdOrthoRoute(a = a, da = da, b = b, db = db, avoid = avoidBoxes)
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

    /**
     * Findet die längste sichtbare Part-Usage-ID, die ein Präfix des
     * Endpunkts ist. SysML-2-Endpunkte folgen der Form
     * `Owner::partUsage::portUsage`; das längste Präfix entspricht der
     * enthaltenden Part-Usage. Gibt `null` zurück, wenn der Endpunkt in
     * keiner sichtbaren Part-Usage liegt — die Connection wird dann verworfen.
     */
    private fun longestPrefixNodeId(
        endpointId: String,
        visibleNodeIds: Set<String>,
    ): String? =
        visibleNodeIds
            .filter { endpointId == it || endpointId.startsWith("$it::") }
            .maxByOrNull { it.length }

    @Suppress("unused")
    private fun PartDefinition.featureCount(): Int = features.size
}
