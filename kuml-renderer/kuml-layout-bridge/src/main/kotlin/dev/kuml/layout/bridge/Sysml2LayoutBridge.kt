package dev.kuml.layout.bridge

import dev.kuml.layout.EdgeHints
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EndpointRef
import dev.kuml.layout.GroupId
import dev.kuml.layout.LayoutEdge
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutGroup
import dev.kuml.layout.LayoutNode
import dev.kuml.layout.NodeId
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
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.Sysml2Definition
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.TransitionUsage
import dev.kuml.sysml2.UcDiagram
import dev.kuml.sysml2.UseCaseDefinition

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
        sizeProvider: SizeProvider = bddContentAwareSizeProvider(model),
    ): LayoutGraph {
        // Snapshot der sichtbaren Definitionen — ein Set für O(1)-Edge-Filter weiter unten.
        val visibleIds: Set<String> = diagram.elementIds.toSet()

        val nodes = mutableListOf<LayoutNode>()
        for (id in diagram.elementIds) {
            val def = model.definitions.firstOrNull { it.id == id } ?: continue
            nodes +=
                LayoutNode(
                    id = NodeId(def.id),
                    intrinsicSize = sizeProvider.sizeOf(def.id, def::class.simpleName ?: "Sysml2Definition"),
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

        return LayoutGraph(nodes = nodes, edges = edges)
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

    /** Vertical padding (top + bottom) inside a node box. */
    public const val BOX_V_PADDING: Float = 16f

    /** Vertical gap before the feature/action compartment divider. */
    public const val DIVIDER_GAP: Float = 6f

    /** Height per word-wrapped text line in a REQ box. */
    public const val WRAP_LINE_H: Float = 13f

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
     */
    public const val ACT_DIAMOND_WIDTH: Float = 50f

    /** Default-Höhe einer Decision-/Merge-Raute (V2.0.10). */
    public const val ACT_DIAMOND_HEIGHT: Float = 50f

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
     * Content-aware [SizeProvider] for BDD nodes. Computes node height from
     * the number of features in the definition.
     */
    public fun bddContentAwareSizeProvider(model: Sysml2Model): SizeProvider =
        SizeProvider { id, _ ->
            val def = model.definitions.firstOrNull { it.id == id }
            val featureCount = def?.features?.size ?: 0
            val h =
                STEREOTYPE_LINE_H + NAME_LINE_H +
                    (if (featureCount > 0) DIVIDER_GAP + featureCount * FEATURE_LINE_H else 0f) +
                    BOX_V_PADDING
            dev.kuml.layout.Size(DEFAULT_WIDTH, maxOf(h, 70f))
        }

    /**
     * Content-aware [SizeProvider] for IBD nodes. Uses a fixed height based
     * on the two-line IBD box (stereotype + name).
     */
    public fun ibdContentAwareSizeProvider(): SizeProvider =
        SizeProvider { _, _ ->
            val h = STEREOTYPE_LINE_H + NAME_LINE_H + BOX_V_PADDING
            dev.kuml.layout.Size(IBD_DEFAULT_WIDTH, maxOf(h, 50f))
        }

    /**
     * Content-aware [SizeProvider] for STM nodes. Pseudo-states keep the
     * fixed square size; regular states grow with their action count.
     */
    public fun stmContentAwareSizeProvider(model: Sysml2Model): SizeProvider =
        SizeProvider { id, kindHint ->
            when {
                kindHint?.contains("Pseudo") == true ||
                    kindHint?.contains("Initial") == true ||
                    kindHint?.contains("Final") == true ->
                    dev.kuml.layout.Size(STM_PSEUDO_SIZE, STM_PSEUDO_SIZE)
                else -> {
                    val def = model.definitions.firstOrNull { it.id == id } as? StateDefinition
                    val actionCount =
                        listOfNotNull(def?.entryAction, def?.exitAction, def?.doAction)
                            .count { it.isNotBlank() }
                    val h =
                        NAME_LINE_H +
                            (if (actionCount > 0) DIVIDER_GAP + actionCount * ACTION_LINE_H else 0f) +
                            BOX_V_PADDING
                    dev.kuml.layout.Size(STM_STATE_WIDTH, maxOf(h, 44f))
                }
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
                    val reqText = req?.text?.takeIf { it.isNotEmpty() }
                    val textLineCount = reqText?.let { (it.length + 24) / 25 } ?: 0
                    val h =
                        STEREOTYPE_LINE_H + NAME_LINE_H +
                            (if (textLineCount > 0) DIVIDER_GAP + textLineCount * WRAP_LINE_H else 0f) +
                            BOX_V_PADDING
                    dev.kuml.layout.Size(REQ_DEFAULT_WIDTH, maxOf(h, 70f))
                }
                "ActorDefinition" -> dev.kuml.layout.Size(UC_ACTOR_WIDTH, UC_ACTOR_HEIGHT)
                "UseCaseDefinition" -> dev.kuml.layout.Size(UC_USECASE_WIDTH, UC_USECASE_HEIGHT)
                else -> dev.kuml.layout.Size(REQ_DEFAULT_WIDTH, REQ_DEFAULT_HEIGHT)
            }
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
        sizeProvider: SizeProvider = ibdContentAwareSizeProvider(),
    ): LayoutGraph {
        // 1. Owner auflösen — fehlt der Owner, ist der Graph leer (analog BDD).
        val owner =
            model.definitions
                .filterIsInstance<PartDefinition>()
                .firstOrNull { it.id == diagram.ownerId }
                ?: return LayoutGraph(nodes = emptyList(), edges = emptyList())

        // 2. Definitionen indexieren, um "Feature ist ein Part-Usage" zu erkennen.
        //    Part-Usages sind Features, deren typeId auf eine PartDefinition zeigt.
        //    Attribute- und Port-Features zeigen auf Attribute- bzw. PortDefinition
        //    und sind im IBD-MVP nicht sichtbar (boundary ports → V2.x).
        val partDefIds: Set<String> =
            model.definitions
                .filterIsInstance<PartDefinition>()
                .map { it.id }
                .toSet()

        // 3. Owner-Part-Usages bestimmen (KermlFeature-Sicht).
        //    Feature-ID = `<ownerId>::<partUsageName>` (DSL-Konvention).
        val ownerPartUsageFeatures =
            owner.features.filter { feature ->
                feature.typeId != null && feature.typeId in partDefIds
            }

        // 4. Optional auf elementIds filtern. Leere Liste = "alle Part-Usages".
        val filterIds: Set<String>? = diagram.elementIds.takeIf { it.isNotEmpty() }?.toSet()
        val visibleFeatures =
            if (filterIds == null) {
                ownerPartUsageFeatures
            } else {
                ownerPartUsageFeatures.filter { it.id in filterIds }
            }

        // 5. LayoutNodes für die sichtbaren Part-Usages — Key = Feature-ID.
        val nodes =
            visibleFeatures.map { feature ->
                LayoutNode(
                    id = NodeId(feature.id),
                    intrinsicSize = sizeProvider.sizeOf(feature.id, "PartUsage"),
                )
            }
        val visibleNodeIds: Set<String> = nodes.map { it.id.value }.toSet()

        // 6. Edges aus Owner-eigenen Connection-Usages bauen.
        //    Owner-eigen = qualifiedName beginnt mit `"<ownerId>::"` (DSL-Konvention).
        val ownerPrefix = "${owner.id}::"
        val ownerConnections =
            model.usages
                .filterIsInstance<ConnectionUsage>()
                .filter { it.id.startsWith(ownerPrefix) }

        val edges = mutableListOf<LayoutEdge>()
        for (connection in ownerConnections) {
            val srcNode = longestPrefixNodeId(connection.sourceEndId, visibleNodeIds) ?: continue
            val tgtNode = longestPrefixNodeId(connection.targetEndId, visibleNodeIds) ?: continue
            edges +=
                LayoutEdge(
                    id = EdgeId("conn:${connection.id}"),
                    source = EndpointRef(nodeId = NodeId(srcNode)),
                    target = EndpointRef(nodeId = NodeId(tgtNode)),
                    hints = EdgeHints.NONE,
                )
        }

        return LayoutGraph(nodes = nodes, edges = edges)
    }

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
        sizeProvider: SizeProvider = ucDefaultSizeProvider(),
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
                    intrinsicSize = sizeProvider.sizeOf(def.id, kind),
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

        return LayoutGraph(nodes = nodes, edges = edges)
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
                    intrinsicSize = sizeProvider.sizeOf(def.id, kind),
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

        return LayoutGraph(nodes = nodes, edges = edges)
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
        sizeProvider: SizeProvider = stmContentAwareSizeProvider(model),
    ): LayoutGraph {
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
                    intrinsicSize = sizeProvider.sizeOf(def.id, kindHint),
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

        return LayoutGraph(nodes = nodes, edges = edges)
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
        sizeProvider: SizeProvider = actDefaultSizeProvider(),
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
                    intrinsicSize = sizeProvider.sizeOf(def.id, kindHint),
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
        val groups: List<LayoutGroup> =
            model.definitions
                .filterIsInstance<ActivityPartitionDefinition>()
                .filter { it.id in visiblePartitionIds }
                .map { LayoutGroup(id = GroupId(it.id), parent = null) }

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

        return LayoutGraph(nodes = nodes, edges = edges, groups = groups)
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
        sizeProvider: SizeProvider = seqDefaultSizeProvider(),
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
        val lifelineHeight: Float =
            SEQ_LIFELINE_HEAD_HEIGHT +
                (rowCount + 1) * SEQ_MESSAGE_ROW_HEIGHT +
                SEQ_LIFELINE_TAIL_PADDING

        // 4. LayoutNodes für die sichtbaren Lifelines — Breite vom SizeProvider,
        //    Höhe von der Vorausberechnung (überschreibt SizeProvider-Höhe).
        val nodes =
            visibleLifelines.map { lifeline ->
                val baseSize = sizeProvider.sizeOf(lifeline.id, "LifelineDefinition")
                LayoutNode(
                    id = NodeId(lifeline.id),
                    intrinsicSize = dev.kuml.layout.Size(baseSize.width, lifelineHeight),
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
                "LifelineDefinition" -> dev.kuml.layout.Size(SEQ_LIFELINE_WIDTH, SEQ_LIFELINE_HEAD_HEIGHT)
                else -> dev.kuml.layout.Size(SEQ_LIFELINE_WIDTH, SEQ_LIFELINE_HEAD_HEIGHT)
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
                    dev.kuml.layout.Size(ACT_ACTION_WIDTH, ACT_ACTION_HEIGHT)
                ActivityNodeKind.Initial.name,
                ActivityNodeKind.Final.name,
                ActivityNodeKind.FlowFinal.name,
                ->
                    dev.kuml.layout.Size(ACT_PSEUDO_SIZE, ACT_PSEUDO_SIZE)
                ActivityNodeKind.Decision.name,
                ActivityNodeKind.Merge.name,
                ->
                    dev.kuml.layout.Size(ACT_DIAMOND_WIDTH, ACT_DIAMOND_HEIGHT)
                ActivityNodeKind.Fork.name,
                ActivityNodeKind.Join.name,
                ->
                    dev.kuml.layout.Size(ACT_BAR_WIDTH, ACT_BAR_HEIGHT)
                else -> dev.kuml.layout.Size(ACT_ACTION_WIDTH, ACT_ACTION_HEIGHT)
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
                    dev.kuml.layout.Size(STM_PSEUDO_SIZE, STM_PSEUDO_SIZE)
                "StateDefinition" -> dev.kuml.layout.Size(STM_STATE_WIDTH, STM_STATE_HEIGHT)
                else -> dev.kuml.layout.Size(STM_STATE_WIDTH, STM_STATE_HEIGHT)
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
                "RequirementDefinition" -> dev.kuml.layout.Size(REQ_DEFAULT_WIDTH, REQ_DEFAULT_HEIGHT)
                "ActorDefinition" -> dev.kuml.layout.Size(UC_ACTOR_WIDTH, UC_ACTOR_HEIGHT)
                "UseCaseDefinition" -> dev.kuml.layout.Size(UC_USECASE_WIDTH, UC_USECASE_HEIGHT)
                "PartDefinition" -> dev.kuml.layout.Size(DEFAULT_WIDTH, DEFAULT_HEIGHT)
                else -> dev.kuml.layout.Size(REQ_DEFAULT_WIDTH, REQ_DEFAULT_HEIGHT)
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
                "ActorDefinition" -> dev.kuml.layout.Size(UC_ACTOR_WIDTH, UC_ACTOR_HEIGHT)
                "UseCaseDefinition" -> dev.kuml.layout.Size(UC_USECASE_WIDTH, UC_USECASE_HEIGHT)
                else -> dev.kuml.layout.Size(UC_USECASE_WIDTH, UC_USECASE_HEIGHT)
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
        sizeProvider: SizeProvider = parDefaultSizeProvider(),
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
                    intrinsicSize = sizeProvider.sizeOf(def.id, kind),
                )
        }
        val visibleNodeIds: Set<String> = nodes.map { it.id.value }.toSet()

        // Bindings aus dem Modell ziehen (nicht aus dem Diagramm) — siehe
        // KDoc-Begründung oben. Eine Binding wird zur Edge, wenn beide
        // Endpunkte per Longest-Prefix-Match auf sichtbare Knoten auflösen;
        // sonst stillschweigend übersprungen.
        val edges = mutableListOf<LayoutEdge>()
        for (binding in model.usages.filterIsInstance<BindingConnectorUsage>()) {
            val srcNode = longestPrefixNodeId(binding.sourceEndId, visibleNodeIds) ?: continue
            val tgtNode = longestPrefixNodeId(binding.targetEndId, visibleNodeIds) ?: continue
            edges +=
                LayoutEdge(
                    id = EdgeId(binding.id),
                    source = EndpointRef(nodeId = NodeId(srcNode)),
                    target = EndpointRef(nodeId = NodeId(tgtNode)),
                    hints = EdgeHints.NONE,
                )
        }

        return LayoutGraph(nodes = nodes, edges = edges)
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
                "ConstraintDefinition" -> dev.kuml.layout.Size(PAR_CONSTRAINT_WIDTH, PAR_CONSTRAINT_HEIGHT)
                "PartDefinition" -> dev.kuml.layout.Size(DEFAULT_WIDTH, DEFAULT_HEIGHT)
                else -> dev.kuml.layout.Size(PAR_CONSTRAINT_WIDTH, PAR_CONSTRAINT_HEIGHT)
            }
        }

    /**
     * Content-aware [SizeProvider] for PAR diagrams (V2.0.44).
     *
     * ConstraintDefinition height now grows with parameter count instead of
     * being clamped at [PAR_CONSTRAINT_HEIGHT] = 100 px (which truncated any
     * constraint with > 2 parameters — newton-second-law-par showed the third
     * `«in» a : Acceleration` line running outside the box).
     *
     * Height layout: `«constraint»` stereotype + name + expression compartment +
     * parameter compartment (one line per parameter), each separated by a
     * divider gap. Width keeps the default — character-level measurement would
     * require a font-metric backed estimator and is left for V2.x polish.
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
                    dev.kuml.layout.Size(PAR_CONSTRAINT_WIDTH, maxOf(h, 70f))
                }
                "PartDefinition" -> bdd.sizeOf(id, kindHint)
                else -> dev.kuml.layout.Size(PAR_CONSTRAINT_WIDTH, PAR_CONSTRAINT_HEIGHT)
            }
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
