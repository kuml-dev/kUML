package dev.kuml.sysml2

/** Maximum nesting depth of composite states (A1 limit, checker code 120). */
const val STM_MAX_DEPTH: Int = 16

/** Maximum number of regions per composite state (checker code 121). */
const val STM_MAX_REGIONS: Int = 16

/** Maximum number of states per state machine (checker code 122). */
const val STM_MAX_STATES: Int = 1000

/** Maximum length of state/region names (checker code 123). */
const val STM_MAX_NAME_LEN: Int = 256

/** All [StateDefinition]s of the model keyed by id (first definition wins on duplicates). */
fun Sysml2Model.stateIndex(): Map<String, StateDefinition> {
    val out = LinkedHashMap<String, StateDefinition>()
    for (d in definitions) {
        if (d is StateDefinition && d.id !in out) out[d.id] = d
    }
    return out
}

/**
 * Direct children of [parentId] (or the top-level states when `null`),
 * optionally restricted to one region. Declaration order is preserved.
 */
fun Sysml2Model.childrenOf(
    parentId: String?,
    regionId: String? = null,
): List<StateDefinition> =
    definitions
        .filterIsInstance<StateDefinition>()
        .filter { it.parentStateId == parentId && (regionId == null || it.regionId == regionId) }

/**
 * Ancestors of [id], nearest first. Iterative, cycle-safe and bounded by
 * [maxDepth]: a cycle or an overlong chain simply ends the walk.
 */
fun Sysml2Model.ancestorsOf(
    id: String,
    maxDepth: Int = STM_MAX_DEPTH,
    index: Map<String, StateDefinition> = stateIndex(),
): List<String> {
    val out = ArrayList<String>()
    val seen = HashSet<String>()
    seen += id
    var current = index[id]?.parentStateId
    while (current != null && out.size < maxDepth && seen.add(current)) {
        out += current
        current = index[current]?.parentStateId
    }
    return out
}

/** Nesting depth of [id] (0 = top-level), bounded by [STM_MAX_DEPTH]. */
fun Sysml2Model.depthOf(
    id: String,
    index: Map<String, StateDefinition> = stateIndex(),
): Int = ancestorsOf(id = id, maxDepth = STM_MAX_DEPTH, index = index).size

/**
 * All transitive descendants of [id] in depth-first declaration order.
 * Iterative with a visited set; stops after [limit] results.
 */
fun Sysml2Model.descendantsOf(
    id: String,
    limit: Int = STM_MAX_STATES,
): List<String> {
    val byParent = HashMap<String, MutableList<String>>()
    for (d in definitions) {
        if (d is StateDefinition && d.parentStateId != null) {
            byParent.getOrPut(d.parentStateId) { ArrayList() } += d.id
        }
    }
    val out = ArrayList<String>()
    val seen = HashSet<String>()
    seen += id
    val stack = ArrayDeque<String>()
    byParent[id]?.asReversed()?.forEach { stack.addLast(it) }
    while (stack.isNotEmpty() && out.size < limit) {
        val next = stack.removeLast()
        if (!seen.add(next)) continue
        out += next
        byParent[next]?.asReversed()?.forEach { stack.addLast(it) }
    }
    return out
}

/**
 * The state ids a [StmDiagram] actually shows: the explicitly included ids,
 * plus all descendants of included composites, plus the ancestors of every
 * visible state (so a child never floats outside its composite frame).
 * Deterministic order, bounded by [STM_MAX_STATES].
 */
fun StmDiagram.visibleStateIds(model: Sysml2Model): Set<String> {
    val out = LinkedHashSet<String>()
    for (id in elementIds) {
        if (out.size >= STM_MAX_STATES) return out
        out += id
    }
    val index = model.stateIndex()
    if (index.values.none { it.parentStateId != null }) return out
    for (id in elementIds) {
        for (d in model.descendantsOf(id = id)) {
            if (out.size >= STM_MAX_STATES) return out
            out += d
        }
    }
    for (id in out.toList()) {
        for (a in model.ancestorsOf(id = id, maxDepth = STM_MAX_DEPTH, index = index)) {
            if (out.size >= STM_MAX_STATES) return out
            if (a in index) out += a
        }
    }
    return out
}

/** `true` when any of the [visible] states uses wave-A1 structure. */
fun Sysml2Model.usesStmStructure(visible: Collection<String>): Boolean {
    val index = stateIndex()
    return visible.any { index[it]?.usesStructure == true }
}
