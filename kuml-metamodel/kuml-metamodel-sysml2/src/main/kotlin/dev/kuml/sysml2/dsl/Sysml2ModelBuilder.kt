package dev.kuml.sysml2.dsl

import dev.kuml.kerml.KermlMultiplicity
import dev.kuml.kerml.KermlSpecialization
import dev.kuml.sysml2.AttributeDefinition
import dev.kuml.sysml2.AttributeUsage
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.ConnectionDefinition
import dev.kuml.sysml2.ConnectionUsage
import dev.kuml.sysml2.PartDefinition
import dev.kuml.sysml2.PartUsage
import dev.kuml.sysml2.PortDefinition
import dev.kuml.sysml2.PortUsage
import dev.kuml.sysml2.Sysml2Definition
import dev.kuml.sysml2.Sysml2Diagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.Sysml2Usage
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
        val builder = DefinitionBuilder(parentId = id).apply(block)
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
        val builder = DefinitionBuilder(parentId = id).apply(block)
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
        val builder = DefinitionBuilder(parentId = id).apply(block)
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
        val builder = DefinitionBuilder(parentId = id).apply(block)
        val def =
            ConnectionDefinition(
                id = id,
                name = name,
                features = builder.features(),
            )
        definitions += def
        return def
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
