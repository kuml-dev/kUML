package dev.kuml.layout.bridge

import dev.kuml.layout.EdgeHints
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EndpointRef
import dev.kuml.layout.LayoutEdge
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutNode
import dev.kuml.layout.NodeId
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.PartDefinition
import dev.kuml.sysml2.Sysml2Definition
import dev.kuml.sysml2.Sysml2Model

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

    /** Diagnose-Helfer: extrahiert die in [diagram] referenzierten Definitionen aus [model]. */
    public fun resolveVisibleDefinitions(
        model: Sysml2Model,
        diagram: BdDiagram,
    ): List<Sysml2Definition> = diagram.elementIds.mapNotNull { id -> model.definitions.firstOrNull { it.id == id } }

    @Suppress("unused")
    private fun PartDefinition.featureCount(): Int = features.size
}
