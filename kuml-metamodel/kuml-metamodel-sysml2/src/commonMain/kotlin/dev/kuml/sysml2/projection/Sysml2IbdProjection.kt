package dev.kuml.sysml2.projection

import dev.kuml.kerml.KermlFeature
import dev.kuml.sysml2.BindingConnectorUsage
import dev.kuml.sysml2.ConnectionUsage
import dev.kuml.sysml2.FlowUsage
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.PartDefinition
import dev.kuml.sysml2.PartUsage
import dev.kuml.sysml2.PortUsage
import dev.kuml.sysml2.Sysml2Model

/** A connection end that could not be resolved; the wire is omitted from the diagram. */
data class IbdUnresolvedEnd(
    val usageId: String,
    val endpointId: String,
)

/** A connection-like edge of an IBD with both ends resolved. */
data class IbdWire<T>(
    val usage: T,
    val source: ResolvedEnd,
    val target: ResolvedEnd,
)

/**
 * What an IBD of one owner shows. Single source of truth for layout bridge,
 * edge adapter and SVG renderer.
 *
 * @property showFrame `true` when some end sits on an owner port or is nested
 *   deeper than one path step below a visible part; otherwise the IBD renders
 *   exactly as before (no frame, no boundary ports).
 */
data class IbdProjection(
    val partNodes: List<KermlFeature>,
    val boundaryPorts: List<PortUsage>,
    val connections: List<IbdWire<ConnectionUsage>>,
    val flows: List<IbdWire<FlowUsage>>,
    val bindings: List<IbdWire<BindingConnectorUsage>>,
    val showFrame: Boolean,
    val unresolvedEnds: List<IbdUnresolvedEnd> = emptyList(),
    /** Parts nested inside the visible parts, as path ids (`Owner::a::b`), parents before children (PAL wave 2C-b2). */
    val nestedPartPaths: List<String> = emptyList(),
    /** Parent path (or visible node id) of every nested part path. */
    val parentOf: Map<String, String> = emptyMap(),
) {
    /** Flows carried by a visible connection (drawn as a mid-arrow on it). */
    val carriedFlows: List<IbdWire<FlowUsage>>
        get() {
            val ids = connections.map { it.usage.id }.toSet()
            return flows.filter { it.usage.viaConnectionId != null && it.usage.viaConnectionId in ids }
        }

    /** Flows without a visible connector: drawn as an own line. */
    val ownLineFlows: List<IbdWire<FlowUsage>> get() = flows - carriedFlows.toSet()
}

fun sysml2IbdProjection(
    model: Sysml2Model,
    diagram: IbdDiagram,
): IbdProjection {
    val owner =
        model.definitions.filterIsInstance<PartDefinition>().firstOrNull { it.id == diagram.ownerId }
            ?: return IbdProjection(
                partNodes = emptyList(),
                boundaryPorts = emptyList(),
                connections = emptyList(),
                flows = emptyList(),
                bindings = emptyList(),
                showFrame = false,
            )
    val partDefIds =
        model.definitions
            .filterIsInstance<PartDefinition>()
            .map { it.id }
            .toSet()
    val ownerParts =
        owner.features.filter { f ->
            f.typeId != null &&
                f.typeId in partDefIds &&
                model.usages.firstOrNull { it.id == f.id }.let { it == null || it is dev.kuml.sysml2.PartUsage }
        }
    val filter = diagram.elementIds.takeIf { it.isNotEmpty() }?.toSet()
    val nodes = if (filter == null) ownerParts else ownerParts.filter { it.id in filter }
    val nodeIds = nodes.map { it.id }.toSet()
    val prefix = "${owner.id}::"

    fun resolve(id: String) = Sysml2PathResolver.resolve(model = model, ownerId = owner.id, endpointId = id, visibleNodeIds = nodeIds)

    val connections =
        model.usages.filterIsInstance<ConnectionUsage>().filter { it.id.startsWith(prefix) }.mapNotNull {
            val s = resolve(it.sourceEndId) ?: return@mapNotNull null
            val t = resolve(it.targetEndId) ?: return@mapNotNull null
            IbdWire(usage = it, source = s, target = t)
        }
    val flows =
        model.usages.filterIsInstance<FlowUsage>().filter { it.id.startsWith(prefix) }.mapNotNull {
            val s = resolve(it.sourceEndId) ?: return@mapNotNull null
            val t = resolve(it.targetEndId) ?: return@mapNotNull null
            IbdWire(usage = it, source = s, target = t)
        }
    // Bindings of a PAR model are not IBD content: both ends must resolve here.
    val bindings =
        model.usages.filterIsInstance<BindingConnectorUsage>().mapNotNull {
            if (!it.sourceEndId.startsWith(prefix) || !it.targetEndId.startsWith(prefix)) return@mapNotNull null
            val s = resolve(it.sourceEndId) ?: return@mapNotNull null
            val t = resolve(it.targetEndId) ?: return@mapNotNull null
            IbdWire(usage = it, source = s, target = t)
        }
    // Ends that only fail because the diagram filters their part out (elementIds) are deliberately hidden, not unresolved.
    val allPartIds = ownerParts.map { it.id }.toSet()

    fun resolveUnfiltered(id: String) =
        Sysml2PathResolver.resolve(model = model, ownerId = owner.id, endpointId = id, visibleNodeIds = allPartIds)
    val unresolved = mutableListOf<IbdUnresolvedEnd>()
    for (u in model.usages.filter { it.id.startsWith(prefix) }) {
        val ends =
            when (u) {
                is ConnectionUsage -> listOf(u.sourceEndId, u.targetEndId)
                is FlowUsage -> listOf(u.sourceEndId, u.targetEndId)
                else -> continue
            }
        for (e in ends) {
            if (resolve(e) == null && resolveUnfiltered(e) == null) unresolved += IbdUnresolvedEnd(usageId = u.id, endpointId = e)
        }
    }
    val ends = (
        connections.flatMap { listOf(it.source, it.target) } +
            flows.flatMap { listOf(it.source, it.target) } +
            bindings.flatMap { listOf(it.source, it.target) }
    )
    val boundary = ends.mapNotNull { it.ownerPort }.distinctBy { it.id }
    val showFrame = ends.any { it.ownerPort != null || it.steps.size > 1 }
    val (nestedPaths, parents) = nestedPartPaths(model = model, nodes = nodes, partDefIds = partDefIds)
    return IbdProjection(
        nestedPartPaths = nestedPaths,
        parentOf = parents,
        partNodes = nodes,
        boundaryPorts = boundary,
        connections = connections,
        flows = flows,
        bindings = bindings,
        showFrame = showFrame,
        unresolvedEnds = unresolved,
    )
}

/** Upper bound for the number of nested part paths of one IBD (denial-of-service guard against huge or recursive types). */
internal const val MAX_NESTED_PARTS: Int = 256

/**
 * Nested part paths below the visible [nodes]: for every part usage of a node's definition, `node::sub`, recursively up
 * to [Sysml2PathResolver.MAX_SEGMENTS] levels and [MAX_NESTED_PARTS] paths in total. Breadth first, ordered by the
 * definition order, so the result is deterministic; recursive types end at the depth limit.
 */
internal fun nestedPartPaths(
    model: Sysml2Model,
    nodes: List<KermlFeature>,
    partDefIds: Set<String>,
): Pair<List<String>, Map<String, String>> {
    val paths = ArrayList<String>()
    val parents = LinkedHashMap<String, String>()
    var level = nodes.mapNotNull { n -> n.typeId?.let { n.id to it } }
    var depth = 1
    while (level.isNotEmpty() && depth < Sysml2PathResolver.MAX_SEGMENTS && paths.size < MAX_NESTED_PARTS) {
        val next = ArrayList<Pair<String, String>>()
        for ((path, defId) in level) {
            val def = model.definitions.filterIsInstance<PartDefinition>().firstOrNull { it.id == defId } ?: continue
            for (f in def.features) {
                if (paths.size >= MAX_NESTED_PARTS) break
                val typeId = f.typeId ?: continue
                if (typeId !in partDefIds) continue
                if (model.usages.firstOrNull { it.id == f.id } !is PartUsage) continue
                val childPath = "$path::${f.id.substringAfterLast("::")}"
                paths += childPath
                parents[childPath] = path
                next += childPath to typeId
            }
        }
        level = next
        depth++
    }
    return paths to parents
}
