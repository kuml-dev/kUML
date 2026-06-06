package dev.kuml.layout.bridge

import dev.kuml.layout.EdgeHints
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EndpointRef
import dev.kuml.layout.LayoutEdge
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutNode
import dev.kuml.layout.NodeId
import dev.kuml.sysml2.ActorDefinition
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.ConnectionUsage
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.PartDefinition
import dev.kuml.sysml2.PartUsage
import dev.kuml.sysml2.Sysml2Definition
import dev.kuml.sysml2.Sysml2Model
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
        sizeProvider: SizeProvider = SizeProvider.constant(width = DEFAULT_WIDTH, height = DEFAULT_HEIGHT),
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
        sizeProvider: SizeProvider = SizeProvider.constant(width = IBD_DEFAULT_WIDTH, height = IBD_DEFAULT_HEIGHT),
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
