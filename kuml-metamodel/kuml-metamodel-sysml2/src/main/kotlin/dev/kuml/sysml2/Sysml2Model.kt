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
 * V2.0.6 adds [IbdDiagram], V2.0.7 adds [UcDiagram] — the rest (REQ, PAR,
 * ACT, SEQ, STM) follow as separate `data class` subtypes in later waves.
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

/**
 * **Use Case Diagram** (UC) — the SysML 2 / UML capability view.
 *
 * Shows three primary kinds of elements (V2.0.7 MVP):
 *  - [ActorDefinition]s as stick-figure nodes — the external entities that
 *    interact with the system,
 *  - [UseCaseDefinition]s as ellipse nodes — the capabilities the system
 *    offers,
 *  - three kinds of edges between them:
 *    - [UcAssociation] — an actor participates in a use case
 *      (the canonical "stick figure connected to ellipse" line),
 *    - [UcInclude] — `«include»` from one use case to another (always
 *      executed as part of the source),
 *    - [UcExtend] — `«extend»` from one use case to another (optional
 *      extension of the target's behaviour).
 *
 * The diagram captures the edges directly (instead of deriving them from
 * usages) because UC-edge semantics are diagram-specific: an actor-to-
 * use-case association is not a structural feature of either side, and an
 * `«include»`/`«extend»` is a diagram-level relationship between two
 * use-case definitions. A richer [IncludeUsage]/[ExtendUsage] metamodel
 * lives in [Usages.kt] for future polish waves, but V2.0.7 reads the
 * edges off the diagram itself.
 *
 * V2.0.7 MVP scope (per the wave plan):
 *  - Flat graph: no system-boundary frame around use cases.
 *  - All three edge kinds render with the same plain solid line in SVG +
 *    TikZ. The dashed-line styling and the `«include»`/`«extend»` stereotype
 *    labels are deferred to V2.x.
 *  - No actor specialisation arrows, no use-case generalisation.
 *  - PNG export: V2.x (same as BDD + IBD).
 */
@Serializable
data class UcDiagram(
    override val name: String,
    /**
     * Ids of the [ActorDefinition]s + [UseCaseDefinition]s the diagram is
     * allowed to display. Order is preserved so layout / serialisation /
     * diff stay deterministic.
     */
    override val elementIds: List<String> = emptyList(),
    /** Actor ↔ UseCase associations (the classical UML "participates in" line). */
    val associations: List<UcAssociation> = emptyList(),
    /** `«include»` relationships between two use-case definitions. */
    val includes: List<UcInclude> = emptyList(),
    /** `«extend»` relationships between two use-case definitions. */
    val extends: List<UcExtend> = emptyList(),
) : Sysml2Diagram

/**
 * Actor ↔ use-case association edge in a [UcDiagram].
 *
 * Endpoints reference SysML 2 element ids by string so the diagram can be
 * authored before the referenced definitions exist (forward refs are fine).
 * The bridge silently drops associations whose endpoints aren't both in
 * the diagram's visible node set — validator's job to flag dangling refs.
 *
 * Edge id convention (set by the DSL): `assoc:<actorId>::<useCaseId>` —
 * deterministic, readable, collision-free for unique actor/use-case pairs.
 */
@Serializable
data class UcAssociation(
    val id: String,
    /** Id of the [ActorDefinition] that participates in the use case. */
    val actorId: String,
    /** Id of the [UseCaseDefinition] the actor participates in. */
    val useCaseId: String,
)

/**
 * `«include»` relationship between two use-case definitions in a
 * [UcDiagram].
 *
 * Semantics: the source use case *always* executes the target as part of
 * its own behaviour (`BorrowBook` includes `Authenticate` → every borrow
 * authenticates first).
 *
 * Edge id convention (set by the DSL): `include:<source>::<target>`.
 */
@Serializable
data class UcInclude(
    val id: String,
    /** Id of the *including* (source) [UseCaseDefinition]. */
    val sourceUseCaseId: String,
    /** Id of the *included* (target) [UseCaseDefinition]. */
    val targetUseCaseId: String,
)

/**
 * `«extend»` relationship between two use-case definitions in a
 * [UcDiagram].
 *
 * Semantics: the source use case *optionally* extends the target's
 * behaviour (`PayLateFee` extends `ReturnBook` only when there is a fee).
 *
 * Edge id convention (set by the DSL): `extend:<source>::<target>`.
 */
@Serializable
data class UcExtend(
    val id: String,
    /** Id of the *extending* (source) [UseCaseDefinition]. */
    val sourceUseCaseId: String,
    /** Id of the *extended* (target) [UseCaseDefinition]. */
    val targetUseCaseId: String,
)
