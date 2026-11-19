package dev.kuml.layout.bridge

import dev.kuml.core.model.layout.LayoutOption
import dev.kuml.core.model.layout.LayoutSpec
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.blockingEdgeInSubtreeDetailed

/**
 * Nacharbeit 107: per compound group with members, its member ids and its first blocking edge (absent = self-contained)
 * and whether it contains nested groups. Nacharbeit 119: an edge blocks when it crosses the border of the group or of
 * any group nested in it, so layer options act inside a group when its whole subtree is self-contained (see
 * [LayoutEffectMatrix.honouredInSelfContainedCompound]). Group order is declaration order.
 */
public data class CompoundLayerScope(
    val members: Map<String, Set<String>>,
    val crossing: Map<String, String>,
    val nested: Set<String>,
    /** Per group with a blocking edge: the id of the (own or nested) group whose border that edge crosses. */
    val crossingGroup: Map<String, String> = emptyMap(),
    /** Ids of all nodes of the graph; ids outside it are unknown and ignored when scopes are compared. */
    val nodeIds: Set<String> = emptySet(),
) {
    public companion object {
        /** The scope of [graph]: every compound group that has at least one direct member node. */
        public fun of(graph: LayoutGraph): CompoundLayerScope {
            val members = linkedMapOf<String, Set<String>>()
            val crossing = linkedMapOf<String, String>()
            val crossingGroup = linkedMapOf<String, String>()
            val nested = linkedSetOf<String>()
            val parents = graph.groups.mapNotNull { it.parent?.value }.toSet()
            for (group in graph.groups) {
                if (!group.layoutAsCompound) continue
                val direct =
                    graph.nodes
                        .filter { it.groupId == group.id }
                        .map { it.id.value }
                        .toSet()
                if (direct.isEmpty()) continue
                members[group.id.value] = direct
                graph.blockingEdgeInSubtreeDetailed(group)?.let {
                    crossing[group.id.value] = it.edge.value
                    crossingGroup[group.id.value] = it.group.value
                }
                if (group.id.value in parents) nested += group.id.value
            }
            return CompoundLayerScope(
                members = members,
                crossing = crossing,
                nested = nested,
                crossingGroup = crossingGroup,
                nodeIds = graph.nodes.map { it.id.value }.toSet(),
            )
        }
    }

    /** True when the layer hints of the members of [group] can be honoured. */
    internal fun honours(group: String): Boolean = group in members && group !in crossing

    private fun blockedReason(group: String): String =
        crossing[group]?.let { edge ->
            val border = crossingGroup[group]
            if (border == null || border == group) {
                "relationship '$edge' crosses its border"
            } else {
                "relationship '$edge' crosses the border of nested group '$border'"
            }
        } ?: "it is not a self-contained group"

    /**
     * The `KUML-LAYOUT-W001` text for [option] when it has no effect inside the compound groups of [kindName], or `null`
     * when it acts. Only `wrapColumns` and `sameLayer` are ever reported.
     */
    internal fun noEffectMessage(
        option: LayoutOption,
        spec: LayoutSpec,
        diagramName: String?,
        kindName: String,
    ): String? {
        val where = if (diagramName != null) "diagram '$diagramName' ($kindName)" else "this diagram ($kindName)"
        val rule = "layer options act inside a package only when no relationship crosses its border or the border of a group nested in it"
        return when (option) {
            LayoutOption.WRAP_COLUMNS -> {
                val blocked = members.keys.firstOrNull { !honours(it) } ?: return null
                "layout option 'wrap_columns' has no effect on the elements inside package '$blocked' of $where: " +
                    "${blockedReason(blocked)}; $rule."
            }
            LayoutOption.SAME_LAYER -> sameLayerMessage(spec = spec, where = where, rule = rule)
            else -> null
        }
    }

    private fun sameLayerMessage(
        spec: LayoutSpec,
        where: String,
        rule: String,
    ): String? {
        val scopeOf = HashMap<String, String>()
        for ((group, ids) in members) for (id in ids) scopeOf[id] = group
        for (ids in spec.sameLayer) {
            // Unknown ids are reported by the checker; they must not make the group look like a mixed-scope one.
            val scopes = ids.filter { it in nodeIds }.map { scopeOf[it] ?: "" }.toSet()
            if (scopes.size > 1) {
                return "layout option 'same_layer' has no effect on $where: a sameLayer group mixes elements of different " +
                    "packages or of a package and the top level; such a group cannot share one layer."
            }
            val only = scopes.singleOrNull() ?: continue
            if (only.isNotEmpty() && !honours(only)) {
                return "layout option 'same_layer' has no effect on the elements inside package '$only' of $where: " +
                    "${blockedReason(only)}; $rule."
            }
        }
        return null
    }
}

/**
 * Nacharbeit 107: flags every compound group whose members' layer hints should be honoured. Returns `this` (the same
 * instance) when [spec] has none of wrapColumns / sameLayer / first / last. `wrapColumns` affects every self-contained
 * group; `sameLayer` / `first` / `last` only the groups that contain a listed id (a sameLayer group that mixes scopes
 * flags nothing). Based on the user's spec only, so the aspect iteration alone never switches honouring on.
 */
internal fun LayoutGraph.withMemberLayerHints(spec: LayoutSpec?): LayoutGraph {
    if (spec == null) return this
    val wrap = spec.wrapColumns != null
    if (!wrap && spec.sameLayer.isEmpty() && spec.first.isEmpty() && spec.last.isEmpty()) return this
    val scope = CompoundLayerScope.of(this)
    if (scope.members.isEmpty()) return this
    val scopeOf = HashMap<String, String>()
    for ((group, ids) in scope.members) for (id in ids) scopeOf[id] = group
    val listed = mutableSetOf<String>()
    listed += spec.first
    listed += spec.last
    for (ids in spec.sameLayer) {
        if (ids
                .filter { it in scope.nodeIds }
                .map { scopeOf[it] ?: "" }
                .toSet()
                .size <= 1
        ) {
            listed += ids
        }
    }
    var changed = false
    val flagged =
        groups.map { g ->
            val name = g.id.value
            if (scope.honours(name) && !g.memberLayerHints && (wrap || scope.members.getValue(name).any { it in listed })) {
                changed = true
                g.copy(memberLayerHints = true)
            } else {
                g
            }
        }
    return if (changed) copy(groups = flagged) else this
}
