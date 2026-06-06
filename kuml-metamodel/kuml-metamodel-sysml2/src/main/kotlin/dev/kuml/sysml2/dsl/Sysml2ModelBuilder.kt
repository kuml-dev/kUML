package dev.kuml.sysml2.dsl

import dev.kuml.kerml.KermlMultiplicity
import dev.kuml.kerml.KermlSpecialization
import dev.kuml.sysml2.ActorDefinition
import dev.kuml.sysml2.AttributeDefinition
import dev.kuml.sysml2.AttributeUsage
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.ConnectionDefinition
import dev.kuml.sysml2.ConnectionUsage
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.PartDefinition
import dev.kuml.sysml2.PartUsage
import dev.kuml.sysml2.PortDefinition
import dev.kuml.sysml2.PortUsage
import dev.kuml.sysml2.Sysml2Definition
import dev.kuml.sysml2.Sysml2Diagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.Sysml2Usage
import dev.kuml.sysml2.UcAssociation
import dev.kuml.sysml2.UcDiagram
import dev.kuml.sysml2.UcExtend
import dev.kuml.sysml2.UcInclude
import dev.kuml.sysml2.UseCaseDefinition
import dev.kuml.sysml2.units.UnitValue

/**
 * Top-level entry — `sysml2Model("HybridVehicle") { … }`.
 *
 * Mirrors the existing `umlModel(...)` / `c4Model(...)` shape so SysML 2
 * scripts look at-home next to existing kUML scripts. The DSL produces a
 * fully-built [Sysml2Model] — no half-states, no resolver phase, no
 * cross-module dependency on `kuml-core-dsl`.
 */
fun sysml2Model(
    name: String,
    block: Sysml2ModelBuilder.() -> Unit = {},
): Sysml2Model = Sysml2ModelBuilder(name).apply(block).build()

/**
 * Builder for [Sysml2Model]. Collects definitions, top-level usages, and
 * diagrams as the user populates the model.
 *
 * Definitions accumulate in registration order — the layout/renderer
 * traverses them in that order, so the user's source ordering controls
 * deterministic output. (Same convention the UML builder uses.)
 */
@Sysml2Dsl
class Sysml2ModelBuilder(
    private val name: String,
) {
    private val definitions = mutableListOf<Sysml2Definition>()
    private val usages = mutableListOf<Sysml2Usage>()
    private val diagrams = mutableListOf<Sysml2Diagram>()

    // ── Definitions ──────────────────────────────────────────────────────

    /**
     * `part def Vehicle { … }` — declare a [PartDefinition].
     *
     * The optional [specializesId] gives the parent definition for the
     * `Type :> Type` relationship; the lambda gets a [DefinitionBuilder]
     * so the body can declare attributes, ports, sub-parts.
     */
    fun partDef(
        name: String,
        id: String = name,
        isAbstract: Boolean = false,
        specializesId: String? = null,
        block: DefinitionBuilder.() -> Unit = {},
    ): PartDefinition {
        val builder = DefinitionBuilder(parentId = id, modelBuilder = this).apply(block)
        val def =
            PartDefinition(
                id = id,
                name = name,
                qualifiedName = name,
                isAbstract = isAbstract,
                features = builder.features(),
                specializations = specializesId?.let { listOf(KermlSpecialization(id, it)) }.orEmpty(),
            )
        definitions += def
        return def
    }

    /** `attribute def Mass { … }` — a value-typed definition. */
    fun attributeDef(
        name: String,
        id: String = name,
        block: DefinitionBuilder.() -> Unit = {},
    ): AttributeDefinition {
        val builder = DefinitionBuilder(parentId = id, modelBuilder = this).apply(block)
        val def =
            AttributeDefinition(
                id = id,
                name = name,
                features = builder.features(),
            )
        definitions += def
        return def
    }

    /** `port def Inlet { … }` — a port-typed definition. */
    fun portDef(
        name: String,
        id: String = name,
        block: DefinitionBuilder.() -> Unit = {},
    ): PortDefinition {
        val builder = DefinitionBuilder(parentId = id, modelBuilder = this).apply(block)
        val def =
            PortDefinition(
                id = id,
                name = name,
                features = builder.features(),
            )
        definitions += def
        return def
    }

    /** `connection def PowerLine { … }` — a connection-typed definition. */
    fun connectionDef(
        name: String,
        id: String = name,
        block: DefinitionBuilder.() -> Unit = {},
    ): ConnectionDefinition {
        val builder = DefinitionBuilder(parentId = id, modelBuilder = this).apply(block)
        val def =
            ConnectionDefinition(
                id = id,
                name = name,
                features = builder.features(),
            )
        definitions += def
        return def
    }

    /**
     * `actor def Reader { … }` — V2.0.7 actor-typed definition.
     *
     * Mirrors [partDef]. The optional body lets future polish waves attach
     * attribute usages (e.g. an actor with a `role : Role` attribute); the
     * V2.0.7 MVP uses actors as flat leaf nodes in [UcDiagram]s.
     */
    fun actorDef(
        name: String,
        id: String = name,
        isAbstract: Boolean = false,
        block: DefinitionBuilder.() -> Unit = {},
    ): ActorDefinition {
        val builder = DefinitionBuilder(parentId = id, modelBuilder = this).apply(block)
        val def =
            ActorDefinition(
                id = id,
                name = name,
                isAbstract = isAbstract,
                features = builder.features(),
            )
        definitions += def
        return def
    }

    /**
     * `use case def BorrowBook { … }` — V2.0.7 use-case-typed definition.
     *
     * Mirrors [partDef]. Use cases are nodes in a [UcDiagram] and the
     * endpoints of [UcAssociation] / [UcInclude] / [UcExtend] edges.
     */
    fun useCaseDef(
        name: String,
        id: String = name,
        isAbstract: Boolean = false,
        block: DefinitionBuilder.() -> Unit = {},
    ): UseCaseDefinition {
        val builder = DefinitionBuilder(parentId = id, modelBuilder = this).apply(block)
        val def =
            UseCaseDefinition(
                id = id,
                name = name,
                isAbstract = isAbstract,
                features = builder.features(),
            )
        definitions += def
        return def
    }

    /**
     * Register a typed [Sysml2Usage] so the model's `usages` list carries the
     * full typed view alongside the KerML `features`. V2.0.6 added this so the
     * IBD bridge can read `model.usages.filterIsInstance<ConnectionUsage>()`
     * directly — `KermlFeature` loses the `sourceEndId`/`targetEndId` of a
     * `ConnectionUsage`, which the IBD wiring projection actually needs.
     *
     * Called from [DefinitionBuilder] for every `part(...) / attribute(...) /
     * port(...) / connect(...)` invocation. Stays `internal` so it's part of
     * the module's contract but not the public DSL surface.
     */
    internal fun registerUsage(usage: Sysml2Usage) {
        usages += usage
    }

    // ── Diagrams ─────────────────────────────────────────────────────────

    /**
     * `bdd("Structural overview") { include(Vehicle); include(Engine) }` — a
     * Block Definition Diagram projecting a subset of the model.
     */
    fun bdd(
        name: String,
        block: BdDiagramBuilder.() -> Unit = {},
    ): BdDiagram {
        val builder = BdDiagramBuilder().apply(block)
        val diagram = BdDiagram(name = name, elementIds = builder.ids())
        diagrams += diagram
        return diagram
    }

    /**
     * `ibd("HybridVehicle wiring", owner = hybrid) { … }` — an Internal Block
     * Diagram projecting the internal structure of [owner].
     *
     * Without an `include`-block the bridge renders **all** part-usages of the
     * owner (the empty-`elementIds` short-hand). With one or more `include(...)`
     * calls the bridge restricts the visible part-usages to that subset —
     * mirrors the BDD's semantic where empty means "no restriction".
     */
    fun ibd(
        name: String,
        owner: PartDefinition,
        block: IbdDiagramBuilder.() -> Unit = {},
    ): IbdDiagram {
        val builder = IbdDiagramBuilder().apply(block)
        val diagram =
            IbdDiagram(
                name = name,
                ownerId = owner.id,
                elementIds = builder.ids(),
            )
        diagrams += diagram
        return diagram
    }

    /**
     * `ucDiagram("Library — top-level use cases") { … }` — V2.0.7 Use Case
     * Diagram.
     *
     * The block declares which actors + use cases participate
     * (`include(...)`) and the associations / include / extend edges between
     * them (`association(...)`, `include(uc1, uc2)`, `extend(uc1, uc2)`).
     * IDs for edges are deterministic so layout + serialisation + diff stay
     * stable across runs.
     */
    fun ucDiagram(
        name: String,
        block: UcDiagramBuilder.() -> Unit = {},
    ): UcDiagram {
        val builder = UcDiagramBuilder().apply(block)
        val diagram =
            UcDiagram(
                name = name,
                elementIds = builder.ids(),
                associations = builder.associations(),
                includes = builder.includes(),
                extends = builder.extends(),
            )
        diagrams += diagram
        return diagram
    }

    fun build(): Sysml2Model =
        Sysml2Model(
            name = name,
            definitions = definitions.toList(),
            usages = usages.toList(),
            diagrams = diagrams.toList(),
        )
}

/**
 * Scope inside a definition body — collects nested usages.
 *
 * Holds a [parentId] so generated ids are unambiguous: `Vehicle::engine`
 * for a part-usage `engine` inside `Vehicle`. The parent-qualified id is
 * what the model layer uses to disambiguate when two definitions both have
 * a feature with the same simple name.
 */
@Sysml2Dsl
class DefinitionBuilder internal constructor(
    private val parentId: String,
    private val modelBuilder: Sysml2ModelBuilder,
) {
    private val collected = mutableListOf<dev.kuml.kerml.KermlFeature>()

    /**
     * Declare an attribute-usage: `mass : Mass = 1500[kg]`.
     *
     * The [typeId] points at an [AttributeDefinition] declared elsewhere
     * in the model. We don't resolve right now — the build pass is later.
     */
    fun attribute(
        name: String,
        typeId: String,
        multiplicity: KermlMultiplicity = KermlMultiplicity.EXACTLY_ONE,
        default: UnitValue? = null,
    ): AttributeUsage {
        val qn = "$parentId::$name"
        val usage =
            AttributeUsage(
                id = qn,
                name = name,
                qualifiedName = qn,
                definitionId = typeId,
                multiplicity = multiplicity,
                defaultExpression = default?.toSpecForm(),
            )
        collected += toFeature(usage, typeId)
        modelBuilder.registerUsage(usage)
        return usage
    }

    /** Declare a part-usage: `engine : Engine`. */
    fun part(
        name: String,
        typeId: String,
        multiplicity: KermlMultiplicity = KermlMultiplicity.EXACTLY_ONE,
    ): PartUsage {
        val qn = "$parentId::$name"
        val usage =
            PartUsage(
                id = qn,
                name = name,
                qualifiedName = qn,
                definitionId = typeId,
                multiplicity = multiplicity,
            )
        collected += toFeature(usage, typeId)
        modelBuilder.registerUsage(usage)
        return usage
    }

    /** Declare a port-usage: `port inlet : Inlet`. */
    fun port(
        name: String,
        typeId: String,
        multiplicity: KermlMultiplicity = KermlMultiplicity.EXACTLY_ONE,
    ): PortUsage {
        val qn = "$parentId::$name"
        val usage =
            PortUsage(
                id = qn,
                name = name,
                qualifiedName = qn,
                definitionId = typeId,
                multiplicity = multiplicity,
            )
        collected += toFeature(usage, typeId)
        modelBuilder.registerUsage(usage)
        return usage
    }

    /** Declare a connection-usage: `connect engine.inlet to tank.outlet`. */
    fun connect(
        name: String,
        typeId: String,
        sourceEndId: String,
        targetEndId: String,
        multiplicity: KermlMultiplicity = KermlMultiplicity.EXACTLY_ONE,
    ): ConnectionUsage {
        val qn = "$parentId::$name"
        val usage =
            ConnectionUsage(
                id = qn,
                name = name,
                qualifiedName = qn,
                definitionId = typeId,
                multiplicity = multiplicity,
                sourceEndId = sourceEndId,
                targetEndId = targetEndId,
            )
        collected += toFeature(usage, typeId)
        modelBuilder.registerUsage(usage)
        return usage
    }

    internal fun features(): List<dev.kuml.kerml.KermlFeature> = collected.toList()

    /**
     * Shadow SysML 2 usages onto the KerML feature layer so consumers that
     * walk a definition's `features` list — like a future serialiser or a
     * KerML-level diff — see a consistent view.
     */
    private fun toFeature(
        usage: Sysml2Usage,
        typeId: String,
    ): dev.kuml.kerml.KermlFeature {
        val defaultExpr = (usage as? AttributeUsage)?.defaultExpression
        return dev.kuml.kerml.KermlFeature(
            id = usage.id,
            name = usage.name,
            qualifiedName = usage.qualifiedName,
            typeId = typeId,
            definitionId = usage.definitionId,
            multiplicity = usage.multiplicity,
            defaultExpression = defaultExpr,
        )
    }
}

/** Scope for `bdd("…") { include(Vehicle); include(Engine) }`. */
@Sysml2Dsl
class BdDiagramBuilder internal constructor() {
    private val ids = mutableListOf<String>()

    /** Add a SysML 2 element (by reference) to the diagram. */
    fun include(definition: Sysml2Definition) {
        ids += definition.id
    }

    /** Add a SysML 2 element by raw id — for forward refs / id-only setups. */
    fun includeById(id: String) {
        ids += id
    }

    internal fun ids(): List<String> = ids.toList()
}

/**
 * Scope for `ibd("…", owner = Vehicle) { include(engine); include(battery) }`.
 *
 * Mirrors [BdDiagramBuilder] but selects [Sysml2Usage]s (typically
 * [PartUsage]s) instead of definitions. An empty include-block means
 * "show all of the owner's part-usages" — the bridge enforces that
 * empty-list semantics.
 */
@Sysml2Dsl
class IbdDiagramBuilder internal constructor() {
    private val ids = mutableListOf<String>()

    /** Add a SysML 2 usage (by reference) to the IBD's visible set. */
    fun include(usage: Sysml2Usage) {
        ids += usage.id
    }

    /** Add a SysML 2 usage by raw id — for forward refs / id-only setups. */
    fun includeById(id: String) {
        ids += id
    }

    internal fun ids(): List<String> = ids.toList()
}

/**
 * Scope for `ucDiagram("…") { include(reader); include(borrowBook);
 * association(reader, borrowBook); include(borrowBook, authenticate);
 * extend(payLateFee, returnBook) }` — V2.0.7.
 *
 * Two distinct `include`-flavours live on this builder; they are disambig-
 * uated by Kotlin overload resolution on parameter types:
 *  - [include]`(Sysml2Definition)` *adds a node to the diagram* (actor or
 *    use case).
 *  - [include]`(UseCaseDefinition, UseCaseDefinition)` *creates an
 *    `«include»` relationship edge* between two use cases.
 *
 * The compiler picks the right overload based on whether one or two
 * `UseCaseDefinition`s are passed in. Don't try to call the relationship
 * form with positional arguments that would collapse into the
 * single-argument form — the type signature carries the intent.
 *
 * Edge id conventions:
 *  - association: `assoc:<actorId>::<useCaseId>`
 *  - include relationship: `include:<sourceUcId>::<targetUcId>`
 *  - extend relationship:  `extend:<sourceUcId>::<targetUcId>`
 */
@Sysml2Dsl
class UcDiagramBuilder internal constructor() {
    private val ids = mutableListOf<String>()
    private val associations = mutableListOf<UcAssociation>()
    private val includes = mutableListOf<UcInclude>()
    private val extends = mutableListOf<UcExtend>()

    /**
     * Add a SysML 2 definition (actor or use case) as a visible node in
     * the UC diagram. See class KDoc for the overload-resolution rule that
     * distinguishes this from the include-relationship form.
     */
    fun include(definition: Sysml2Definition) {
        ids += definition.id
    }

    /** Add a node by raw id — forward refs / id-only setups. */
    fun includeById(id: String) {
        ids += id
    }

    /**
     * Create an actor-to-use-case association edge. Returns the resulting
     * [UcAssociation] so callers can hold on to it for further reference.
     */
    fun association(
        actor: ActorDefinition,
        useCase: UseCaseDefinition,
    ): UcAssociation {
        val assoc = associationById(actor.id, useCase.id)
        return assoc
    }

    /** Id-only variant of [association] — for forward refs. */
    fun associationById(
        actorId: String,
        useCaseId: String,
    ): UcAssociation {
        val assoc = UcAssociation(id = "assoc:$actorId::$useCaseId", actorId = actorId, useCaseId = useCaseId)
        associations += assoc
        return assoc
    }

    /**
     * Create an `«include»` relationship between two use cases —
     * `source` always executes `target` as part of its own behaviour.
     *
     * Distinct overload from [include]`(Sysml2Definition)`: the two-argument
     * shape with two [UseCaseDefinition]s creates the relationship; the
     * one-argument shape with a [Sysml2Definition] adds a node.
     */
    fun include(
        source: UseCaseDefinition,
        target: UseCaseDefinition,
    ): UcInclude = includeById(source.id, target.id)

    /** Id-only variant of the include-relationship form — for forward refs. */
    fun includeById(
        sourceId: String,
        targetId: String,
    ): UcInclude {
        val inc = UcInclude(id = "include:$sourceId::$targetId", sourceUseCaseId = sourceId, targetUseCaseId = targetId)
        includes += inc
        return inc
    }

    /**
     * Create an `«extend»` relationship between two use cases —
     * `source` optionally extends `target`'s behaviour.
     */
    fun extend(
        source: UseCaseDefinition,
        target: UseCaseDefinition,
    ): UcExtend = extendById(source.id, target.id)

    /** Id-only variant of the extend-relationship form — for forward refs. */
    fun extendById(
        sourceId: String,
        targetId: String,
    ): UcExtend {
        val ext = UcExtend(id = "extend:$sourceId::$targetId", sourceUseCaseId = sourceId, targetUseCaseId = targetId)
        extends += ext
        return ext
    }

    internal fun ids(): List<String> = ids.toList()

    internal fun associations(): List<UcAssociation> = associations.toList()

    internal fun includes(): List<UcInclude> = includes.toList()

    internal fun extends(): List<UcExtend> = extends.toList()
}
