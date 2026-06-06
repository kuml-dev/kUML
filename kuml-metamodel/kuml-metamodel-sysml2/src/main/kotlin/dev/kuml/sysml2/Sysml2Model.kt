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
 * For V2.0.3, "diagram" means a Block Definition Diagram ([BdDiagram]) that
 * selects which definitions to show; the other SysML 2 diagram kinds (IBD,
 * REQ, PAR, ACT, SEQ, STM, UC) land in follow-up waves. The model itself
 * is diagram-agnostic — a single model can drive many diagrams.
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
 * Sealed root for every SysML 2 diagram kind. V2.0.3 implements [BdDiagram]
 * only — the rest follow as separate `data class` subtypes in later waves
 * (IBD, REQ, PAR, ACT, SEQ, STM, UC).
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
