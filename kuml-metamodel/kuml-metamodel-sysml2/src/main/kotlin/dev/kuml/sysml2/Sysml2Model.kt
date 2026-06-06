package dev.kuml.sysml2

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

/**
 * Top-level container for a SysML 2 model.
 *
 * Holds all definitions ([definitions]) and standalone usages ([usages]) that
 * the model declares, plus any diagrams ([diagrams]) the author has tagged
 * for rendering.
 *
 * For V2.0.3, "diagram" started with a Block Definition Diagram ([BdDiagram])
 * that selects which definitions to show; V2.0.6 adds [IbdDiagram] (Internal
 * Block Diagram) for the wiring view of a single [PartDefinition]. The
 * remaining SysML 2 diagram kinds (REQ, PAR, ACT, SEQ, STM, UC) land in
 * follow-up waves. The model itself is diagram-agnostic — a single model
 * can drive many diagrams.
 */
@Serializable
data class Sysml2Model(
    /** Human-readable model name, e.g. `"HybridVehicle"`. */
    val name: String,
    /** All definitions in the model — parts, attributes, ports, connections. */
    val definitions: List<Sysml2Definition> = emptyList(),
    /** Top-level usages (rare; usages are usually nested in definitions). */
    val usages: List<Sysml2Usage> = emptyList(),
    /** Diagrams tagged for rendering. */
    val diagrams: List<Sysml2Diagram> = emptyList(),
    val metadata: Map<String, KumlMetaValue> = emptyMap(),
) {
    /** Indexed lookup by element id. Computed lazily; rebuilt per instance. */
    fun elementById(id: String): Sysml2Element? {
        // Search definitions, their owned features (re-cast through Usages), and
        // top-level usages. Returns the first match — ids are expected unique.
        definitions.firstOrNull { it.id == id }?.let { return it }
        usages.firstOrNull { it.id == id }?.let { return it }
        return null
    }
}

/**
 * Sealed root for every SysML 2 diagram kind. V2.0.3 implements [BdDiagram],
 * V2.0.6 adds [IbdDiagram] — the rest (REQ, PAR, ACT, SEQ, STM, UC) follow
 * as separate `data class` subtypes in later waves.
 */
@Serializable
sealed interface Sysml2Diagram {
    /** Human-readable diagram title. */
    val name: String

    /** Ids of the SysML 2 elements the diagram is allowed to display. */
    val elementIds: List<String>
}

/**
 * **Block Definition Diagram** — the SysML 2 analogue of the UML class
 * diagram and the natural starting point for any structural modelling.
 * Shows [PartDefinition]s, their attributes, their ports, and their
 * specialisations.
 *
 * The diagram is a *projection*: the model owns the truth, the BDD just
 * names which definitions to render and how. Other diagram kinds will
 * share this projection pattern.
 */
@Serializable
data class BdDiagram(
    override val name: String,
    override val elementIds: List<String> = emptyList(),
) : Sysml2Diagram

/**
 * **Internal Block Diagram** (IBD) — the structural sibling of [BdDiagram]
 * that zooms *inside* a single [PartDefinition] (`ownerId`) and shows its
 * internal wiring:
 *
 *  - every owned `PartUsage` becomes a nested box,
 *  - every owned `PortUsage` surfaces as a compartment entry (V2.0.6 MVP —
 *    boundary port markers on the IBD frame are deferred to V2.x),
 *  - every owned `ConnectionUsage` becomes an edge between the two part-usage
 *    boxes its endpoints fall under.
 *
 * Whereas a BDD shows *types* (Vehicle, Engine, Battery), an IBD shows *the
 * wiring of one type* (inside Vehicle: `engine : Engine`, `battery : Battery`,
 * and a `PowerLine` connection between them).
 *
 * V2.0.6 MVP scope (per the wave plan):
 *  - One IBD per [PartDefinition]; nested IBDs (drilling further into a
 *    contained part) land in V2.x.
 *  - Boundary ports on the IBD frame: V2.x — needs port-position layout hints.
 *  - Typed connection styles per `ConnectionDefinition`: V2.x.
 *  - PNG export: V2.x (same as BDD).
 */
@Serializable
data class IbdDiagram(
    override val name: String,
    /** Id of the [PartDefinition] whose internals are projected. */
    val ownerId: String,
    /**
     * Optional filter — if empty, *all* of the owner's part-usages render.
     * If non-empty, only the listed usage ids survive the bridge's selection.
     */
    override val elementIds: List<String> = emptyList(),
) : Sysml2Diagram
