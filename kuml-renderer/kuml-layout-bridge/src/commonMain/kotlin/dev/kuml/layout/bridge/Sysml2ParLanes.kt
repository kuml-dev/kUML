@file:Suppress("RequireNamedArguments")

package dev.kuml.layout.bridge

import dev.kuml.layout.PortSide
import dev.kuml.sysml2.BindingConnectorUsage
import dev.kuml.sysml2.Sysml2Model

/** How a binding inside one composite constraint is routed. */
internal enum class ParRouteKind {
    /** Owner pin to a pin of a direct child (or the reverse): owner corridor, channel lane, approach column. */
    OwnerToChild,

    /** Pins of two order-adjacent children that face each other: one vertical column in the gap between them. */
    SiblingShort,

    /** Any other pair of sibling pins: approach column, channel lane, approach column. */
    SiblingLong,
}

/** A binding the composite router draws itself (both pins known, both ends inside one composite). */
internal data class ParRouted(
    val id: String,
    val kind: ParRouteKind,
    /** The composite constraint that contains the routed binding (owner of the children). */
    val owner: String,
    /** Source end of the binding. */
    val a: ParEnd,
    /** Target end of the binding. */
    val z: ParEnd,
    val aSide: PortSide,
    val zSide: PortSide,
    /** [ParRouteKind.OwnerToChild] only: the source end is the owner pin (otherwise the target end is). */
    val aIsOwner: Boolean,
)

/**
 * Coordinate-free planning pass for the bindings inside nested parametric constraints (Nacharbeit 41).
 *
 * The plan depends on the model and the nesting only, never on box positions, so the box sizing ([ParCompositeLayout.box])
 * and the router ask the same questions and cannot drift apart. It assigns, per composite:
 *  - a channel lane to every binding that crosses the channel below the owner's own text,
 *  - a corridor column to every owner pin that binds inwards,
 *  - an approach column to every child pin that is entered from the channel,
 *  - a column in the gap to every short sibling binding,
 *  - a small shift to bindings that share a pin.
 *
 * Every ordering ends in the binding id, never in the model order, so the result is independent of the order in
 * which the bindings were declared.
 */
internal class ParLanePlan private constructor(
    private val routed: Map<String, ParRouted>,
    private val channelLanes: Map<String, Int>,
    private val channelCounts: Map<String, Int>,
    private val corridorRanks: Map<Triple<String, PortSide, Int>, Int>,
    private val corridorCounts: Map<Pair<String, PortSide>, Int>,
    private val approachRanks: Map<Triple<String, PortSide, Int>, Int>,
    private val approachCounts: Map<Pair<String, PortSide>, Int>,
    private val shortRanks: Map<String, Int>,
    private val shortCounts: Map<Pair<String, Int>, Int>,
    private val shares: Map<Pair<String, Int>, Float>,
) {
    fun route(bindingId: String): ParRouted? = routed[bindingId]

    fun routedIds(): Set<String> = routed.keys

    /** Channel lane `0 until channelCount(owner)` of a binding; 0 for bindings that do not use the channel. */
    fun channelLane(bindingId: String): Int = channelLanes[bindingId] ?: 0

    fun channelCount(owner: String): Int = channelCounts[owner] ?: 0

    /** Rank of the corridor column of an owner pin (0 = next to the border); null when the pin binds nowhere inwards. */
    fun corridorRank(
        owner: String,
        side: PortSide,
        paramIndex: Int,
    ): Int? = corridorRanks[Triple(owner, side, paramIndex)]

    fun corridorCount(
        owner: String,
        side: PortSide,
    ): Int = corridorCounts[Pair(owner, side)] ?: 0

    /** Rank of the approach column of a child pin (0 = next to the pin). */
    fun approachRank(
        child: String,
        side: PortSide,
        paramIndex: Int,
    ): Int = approachRanks[Triple(child, side, paramIndex)] ?: 0

    fun approachCount(
        child: String,
        side: PortSide,
    ): Int = approachCounts[Pair(child, side)] ?: 0

    /** Rank of a short sibling binding inside its gap (0 = next to the left child's approach columns). */
    fun shortRank(bindingId: String): Int = shortRanks[bindingId] ?: 0

    /** Number of short sibling bindings in the gap right of the child with order index [gapIndex]. */
    fun shortCount(
        owner: String,
        gapIndex: Int,
    ): Int = shortCounts[Pair(owner, gapIndex)] ?: 0

    /** Shift (px) of an end of a binding that shares its pin with others; [end] is 0 for the source, 1 for the target. */
    fun share(
        bindingId: String,
        end: Int,
    ): Float = shares[Pair(bindingId, end)] ?: 0f

    companion object {
        private const val SHARE_SPACING_PX = 2f
        private const val SHARE_MAX_FULL_SPACING = 5
        private const val SHARE_SPAN_PX = 8f
        private const val KEY_STRIDE = 5000L
        private const val KEY_MID = 2500L

        fun of(
            model: Sysml2Model,
            nesting: ParNesting,
            visible: Set<String>,
        ): ParLanePlan {
            val orderIdx = HashMap<String, Map<String, Int>>()

            fun idxOf(
                owner: String,
                child: String,
            ): Int =
                orderIdx
                    .getOrPut(owner) {
                        ParCompositeLayout.orderChildren(model = model, nesting = nesting, owner = owner).withIndex().associate {
                            it.value to it.index
                        }
                    }[child] ?: 0

            val list = ArrayList<ParRouted>()
            for (b in model.usages.filterIsInstance<BindingConnectorUsage>()) {
                val a = parResolveEnd(endpoint = b.sourceEndId, visibleIds = visible, nesting = nesting) ?: continue
                val z = parResolveEnd(endpoint = b.targetEndId, visibleIds = visible, nesting = nesting) ?: continue
                val ca = nesting.constraints[a.nodeId] ?: continue
                val cz = nesting.constraints[z.nodeId] ?: continue
                if (nesting.rootOf(a.nodeId) != nesting.rootOf(z.nodeId) || a.nodeId == z.nodeId) continue
                val ia = a.parameterIndex ?: continue
                val iz = z.parameterIndex ?: continue
                val sa = ParConstraintGeometry.pinSide(ca.parameters[ia].direction)
                val sz = ParConstraintGeometry.pinSide(cz.parameters[iz].direction)
                val ownerA = nesting.ownerOf(a.nodeId)
                val ownerZ = nesting.ownerOf(z.nodeId)
                when {
                    ownerZ == a.nodeId ->
                        list += ParRouted(b.id, ParRouteKind.OwnerToChild, a.nodeId, a, z, sa, sz, aIsOwner = true)
                    ownerA == z.nodeId ->
                        list += ParRouted(b.id, ParRouteKind.OwnerToChild, z.nodeId, a, z, sa, sz, aIsOwner = false)
                    ownerA != null && ownerA == ownerZ -> {
                        val na = idxOf(ownerA, a.nodeId)
                        val nz = idxOf(ownerA, z.nodeId)
                        val leftToRight = na < nz
                        val facing =
                            if (leftToRight) {
                                sa == PortSide.East && sz == PortSide.West
                            } else {
                                sa == PortSide.West &&
                                    sz == PortSide.East
                            }
                        val kind = if (facing && kotlin.math.abs(na - nz) == 1) ParRouteKind.SiblingShort else ParRouteKind.SiblingLong
                        list += ParRouted(b.id, kind, ownerA, a, z, sa, sz, aIsOwner = false)
                    }
                }
            }
            val all = list.distinctBy { it.id }.sortedBy { it.id }

            // Approach columns: child pins entered from the channel.
            val approachPins = HashMap<Pair<String, PortSide>, MutableSet<Int>>()

            fun addApproach(
                node: String,
                side: PortSide,
                idx: Int,
            ) {
                approachPins.getOrPut(Pair(node, side)) { mutableSetOf() } += idx
            }
            for (r in all) {
                when (r.kind) {
                    ParRouteKind.OwnerToChild -> {
                        val child = if (r.aIsOwner) r.z else r.a
                        val side = if (r.aIsOwner) r.zSide else r.aSide
                        addApproach(child.nodeId, side, child.parameterIndex!!)
                    }
                    ParRouteKind.SiblingLong -> {
                        addApproach(r.a.nodeId, r.aSide, r.a.parameterIndex!!)
                        addApproach(r.z.nodeId, r.zSide, r.z.parameterIndex!!)
                    }
                    ParRouteKind.SiblingShort -> Unit
                }
            }
            val approachRanks = HashMap<Triple<String, PortSide, Int>, Int>()
            val approachCounts = HashMap<Pair<String, PortSide>, Int>()
            for ((k, pins) in approachPins) {
                approachCounts[k] = pins.size
                pins.sorted().forEachIndexed { rank, idx -> approachRanks[Triple(k.first, k.second, idx)] = rank }
            }

            // Corridors: owner pins that bind inwards; the topmost pin takes the innermost column.
            val corridorPins = HashMap<Pair<String, PortSide>, MutableSet<Int>>()
            for (r in all) {
                if (r.kind != ParRouteKind.OwnerToChild) continue
                val end = if (r.aIsOwner) r.a else r.z
                val side = if (r.aIsOwner) r.aSide else r.zSide
                corridorPins.getOrPut(Pair(end.nodeId, side)) { mutableSetOf() } += end.parameterIndex!!
            }
            val corridorRanks = HashMap<Triple<String, PortSide, Int>, Int>()
            val corridorCounts = HashMap<Pair<String, PortSide>, Int>()
            for ((k, pins) in corridorPins) {
                corridorCounts[k] = pins.size
                pins.sorted().forEachIndexed { pos, idx -> corridorRanks[Triple(k.first, k.second, idx)] = pins.size - 1 - pos }
            }

            // Position key along x of an approach column, coordinate-free (children are placed in order).
            fun key(
                owner: String,
                node: String,
                side: PortSide,
                idx: Int,
            ): Long {
                val rank = (approachRanks[Triple(node, side, idx)] ?: 0).toLong()
                val col = if (side == PortSide.West) KEY_MID - 1 - rank else KEY_MID + rank
                return idxOf(owner, node) * KEY_STRIDE + col
            }

            fun childKey(r: ParRouted): Long {
                val child = if (r.aIsOwner) r.z else r.a
                val side = if (r.aIsOwner) r.zSide else r.aSide
                return key(r.owner, child.nodeId, side, child.parameterIndex!!)
            }

            fun childPin(r: ParRouted): Int = (if (r.aIsOwner) r.z else r.a).parameterIndex!!

            fun ownerSide(r: ParRouted): PortSide = if (r.aIsOwner) r.aSide else r.zSide

            // Channel lanes per owner: west corridors (farthest target on top), east corridors (farthest on top), siblings.
            val channelLanes = HashMap<String, Int>()
            val channelCounts = HashMap<String, Int>()
            for ((owner, group) in all.filter { it.kind != ParRouteKind.SiblingShort }.groupBy { it.owner }) {
                val west =
                    group
                        .filter { it.kind == ParRouteKind.OwnerToChild && ownerSide(it) == PortSide.West }
                        .sortedWith(compareBy<ParRouted>({ -childKey(it) }, { childPin(it) }, { it.id }))
                val east =
                    group
                        .filter { it.kind == ParRouteKind.OwnerToChild && ownerSide(it) == PortSide.East }
                        .sortedWith(compareBy<ParRouted>({ childKey(it) }, { childPin(it) }, { it.id }))

                fun sibKey(r: ParRouted) =
                    listOf(
                        key(owner, r.a.nodeId, r.aSide, r.a.parameterIndex!!),
                        key(owner, r.z.nodeId, r.zSide, r.z.parameterIndex!!),
                    )
                val sib =
                    group
                        .filter { it.kind == ParRouteKind.SiblingLong }
                        .sortedWith(compareBy<ParRouted>({ sibKey(it).min() }, { sibKey(it).max() }, { it.id }))
                (west + east + sib).forEachIndexed { lane, r -> channelLanes[r.id] = lane }
                channelCounts[owner] = group.size
            }

            // Short sibling columns per gap.
            val shortRanks = HashMap<String, Int>()
            val shortCounts = HashMap<Pair<String, Int>, Int>()
            val shorts =
                all
                    .filter { it.kind == ParRouteKind.SiblingShort }
                    .groupBy { Pair(it.owner, minOf(idxOf(it.owner, it.a.nodeId), idxOf(it.owner, it.z.nodeId))) }
            for ((gap, group) in shorts) {
                fun leftPin(r: ParRouted) = (if (idxOf(r.owner, r.a.nodeId) < idxOf(r.owner, r.z.nodeId)) r.a else r.z).parameterIndex!!

                fun rightPin(r: ParRouted) = (if (idxOf(r.owner, r.a.nodeId) < idxOf(r.owner, r.z.nodeId)) r.z else r.a).parameterIndex!!
                group
                    .sortedWith(compareBy<ParRouted>({ leftPin(it) }, { rightPin(it) }, { it.id }))
                    .forEachIndexed { q, r -> shortRanks[r.id] = q }
                shortCounts[gap] = group.size
            }

            // Pin sharing: bindings ending on the same pin are shifted a few px against each other.
            val ends = HashMap<Pair<String, Int>, MutableList<Pair<String, Int>>>()
            for (r in all) {
                ends.getOrPut(Pair(r.a.nodeId, r.a.parameterIndex!!)) { mutableListOf() } += Pair(r.id, 0)
                ends.getOrPut(Pair(r.z.nodeId, r.z.parameterIndex!!)) { mutableListOf() } += Pair(r.id, 1)
            }
            val shares = HashMap<Pair<String, Int>, Float>()
            for (group in ends.values) {
                val n = group.size
                if (n < 2) continue
                val spacing = if (n <= SHARE_MAX_FULL_SPACING) SHARE_SPACING_PX else SHARE_SPAN_PX / (n - 1)
                group.sortedWith(compareBy({ it.first }, { it.second })).forEachIndexed { k, e ->
                    shares[e] = (k - (n - 1) / 2f) * spacing
                }
            }
            return ParLanePlan(
                routed = all.associateBy { it.id },
                channelLanes = channelLanes,
                channelCounts = channelCounts,
                corridorRanks = corridorRanks,
                corridorCounts = corridorCounts,
                approachRanks = approachRanks,
                approachCounts = approachCounts,
                shortRanks = shortRanks,
                shortCounts = shortCounts,
                shares = shares,
            )
        }
    }
}
