package dev.kuml.sysml2

import kotlinx.serialization.Serializable

/**
 * A SysML 2 package (wave 2I-a): a named namespace that owns definitions ([memberIds]) and other packages
 * (those point back through [parentId]; they are not listed in [memberIds]).
 *
 * Packages are deliberately **not** [Sysml2Element]s: every exhaustive `when` over elements and definitions stays
 * untouched, and old `.kuml.json` files stay readable (all new [Sysml2Model] fields default to empty).
 * Ids look like `pkg:<qualifiedName>`, so they never collide with definition ids.
 */
@Serializable
data class Sysml2Package(
    val id: String,
    val name: String,
    /** Id of the owning package, `null` for a top-level package. */
    val parentId: String? = null,
    /** Ids of the definitions owned by this package, in declaration order. */
    val memberIds: List<String> = emptyList(),
)

/** What an `import` pulls in: `P::*`, `P::**` or a single member `P::X`. */
@Serializable
enum class Sysml2ImportKind {
    /** `import P::*;` - the direct members of the namespace. */
    NAMESPACE,

    /** `import P::**;` - the namespace and everything nested in it. */
    NAMESPACE_RECURSIVE,

    /** `import P::X;` - one member. */
    MEMBERSHIP,
}

/** Visibility of an import (`public import` re-exports, a plain `import` is private). */
@Serializable
enum class Sysml2ImportVisibility { PUBLIC, PRIVATE }

/**
 * An import declared inside a package. [targetQualifiedName] is stored **without** the `::*` / `::**` suffix;
 * the suffix is carried by [kind].
 */
@Serializable
data class Sysml2Import(
    val id: String,
    val ownerPackageId: String,
    val targetQualifiedName: String,
    val kind: Sysml2ImportKind,
    val visibility: Sysml2ImportVisibility = Sysml2ImportVisibility.PRIVATE,
) {
    /** The import target as written in the DSL / in the diagram: with `::*` or `::**` where the kind asks for it. */
    val rawTarget: String
        get() =
            when (kind) {
                Sysml2ImportKind.NAMESPACE -> "$targetQualifiedName::*"
                Sysml2ImportKind.NAMESPACE_RECURSIVE -> "$targetQualifiedName::**"
                Sysml2ImportKind.MEMBERSHIP -> targetQualifiedName
            }
}

/**
 * A viewpoint (wave 2I-a): who looks at the model ([stakeholders]) and what they care about ([concerns]).
 * Stakeholders and concerns are plain text in this wave; they are not model elements yet (see ADR-0016).
 */
@Serializable
data class Sysml2Viewpoint(
    val id: String,
    val name: String,
    val stakeholders: List<String> = emptyList(),
    val concerns: List<String> = emptyList(),
)

/**
 * A view (wave 2I-a): a named selection of the model that satisfies one or more viewpoints.
 * [exposes] and [filters] are plain text; they are **not evaluated** in this wave (ADR-0016, option 2).
 */
@Serializable
data class Sysml2View(
    val id: String,
    val name: String,
    val viewpointIds: List<String> = emptyList(),
    val exposes: List<String> = emptyList(),
    val filters: List<String> = emptyList(),
)

/**
 * **Package diagram** (wave 2I-a): packages as nested frames, `import` relationships as dashed arrows, views and
 * viewpoints as cards.
 *
 * [elementIds] lists what the diagram draws: package ids (`pkg:...`, drawn with their whole sub-tree and all members),
 * definition ids (drawn as a card, on root level when their package is not drawn), view ids and viewpoint ids.
 */
@Serializable
data class Sysml2PackageDiagram(
    override val name: String,
    override val elementIds: List<String> = emptyList(),
    override val noteIds: List<String> = emptyList(),
    override val layout: Sysml2LayoutSpec? = null,
) : Sysml2Diagram

/** Resolved target of an import. */
sealed interface Sysml2ImportTarget {
    data class PackageTarget(
        val pkg: Sysml2Package,
    ) : Sysml2ImportTarget

    data class MemberTarget(
        val definition: Sysml2Definition,
    ) : Sysml2ImportTarget
}

/**
 * Lookup helper over the packages of one [Sysml2Model]: qualified names, owners, import resolution.
 * Every traversal is iterative and bounded, so cyclic or hostile `parentId` chains cannot hang it.
 */
class Sysml2PackageIndex(
    val model: Sysml2Model,
) {
    private val byId: Map<String, Sysml2Package> = firstWins(items = model.packages) { it.id }
    private val qualifiedNames: Map<String, String> = byId.mapValues { (_, p) -> computeQualifiedName(p) }
    private val byQualifiedName: Map<String, Sysml2Package> = firstWins(items = byId.values) { qualifiedNames.getValue(it.id) }
    private val ownerOfDefinition: Map<String, Sysml2Package> =
        LinkedHashMap<String, Sysml2Package>().also { m ->
            byId.values.forEach { p -> p.memberIds.forEach { if (it !in m) m[it] = p } }
        }
    private val definitionsById: Map<String, Sysml2Definition> = firstWins(items = model.definitions) { it.id }
    private val membersByQualifiedName: Map<String, Sysml2Definition> by lazy {
        firstWins(items = model.definitions) { memberQualifiedName(it) }
    }
    private val childrenOf: Map<String?, List<Sysml2Package>> = byId.values.groupBy { it.parentId?.takeIf { pid -> pid in byId } }

    fun packageById(id: String): Sysml2Package? = byId[id]

    fun packageByQualifiedName(qualifiedName: String): Sysml2Package? = byQualifiedName[qualifiedName]

    fun definitionById(id: String): Sysml2Definition? = definitionsById[id]

    /** Direct sub-packages of [parentId] (`null` = top level; a package with an unknown parent counts as top level). */
    fun children(parentId: String?): List<Sysml2Package> = childrenOf[parentId].orEmpty()

    /** `Outer::Inner` for a package. Cycles stop at the first repeated package. */
    fun qualifiedNameOf(pkg: Sysml2Package): String = qualifiedNames[pkg.id] ?: computeQualifiedName(pkg)

    /** The package that lists the definition as a member (the first one if several do). */
    fun ownerOf(definitionId: String): Sysml2Package? = ownerOfDefinition[definitionId]

    /** `Package::Name` for a definition owned by a package, otherwise the plain name. */
    fun memberQualifiedName(def: Sysml2Definition): String {
        val owner = ownerOf(def.id) ?: return def.name
        return "${qualifiedNameOf(owner)}::${def.name}"
    }

    /** Depth of a package: 1 for a top-level package. A cycle counts as [Int.MAX_VALUE]. */
    fun depthOf(pkg: Sysml2Package): Int {
        var depth = 1
        var cur = pkg
        val seen = mutableSetOf(pkg.id)
        while (true) {
            val parent = cur.parentId?.let { byId[it] } ?: return depth
            if (!seen.add(parent.id)) return Int.MAX_VALUE
            depth++
            cur = parent
        }
    }

    /** Ancestor chain of [pkg] without [pkg] itself, nearest first. Stops at a cycle. */
    fun ancestorsOf(pkg: Sysml2Package): List<Sysml2Package> {
        val out = mutableListOf<Sysml2Package>()
        val seen = mutableSetOf(pkg.id)
        var cur = pkg
        while (true) {
            val parent = cur.parentId?.let { byId[it] } ?: return out
            if (!seen.add(parent.id)) return out
            out += parent
            cur = parent
        }
    }

    /** [pkg] and every package below it, parents before children, in declaration order. */
    fun subtreeOf(pkg: Sysml2Package): List<Sysml2Package> {
        val out = mutableListOf<Sysml2Package>()
        val seen = mutableSetOf<String>()
        val stack = ArrayDeque<Sysml2Package>()
        stack.addLast(pkg)
        while (stack.isNotEmpty() && out.size <= Sysml2Limits.PACKAGES) {
            val p = stack.removeLast()
            if (!seen.add(p.id)) continue
            out += p
            children(p.id).asReversed().forEach { stack.addLast(it) }
        }
        return out
    }

    /** Resolves an import target: a package with that qualified name first, then a member definition. */
    fun resolveImportTarget(imp: Sysml2Import): Sysml2ImportTarget? {
        byQualifiedName[imp.targetQualifiedName]?.let { return Sysml2ImportTarget.PackageTarget(it) }
        return membersByQualifiedName[imp.targetQualifiedName]?.let { Sysml2ImportTarget.MemberTarget(it) }
    }

    private fun computeQualifiedName(pkg: Sysml2Package): String {
        val names = ArrayList<String>()
        val seen = mutableSetOf<String>()
        var cur: Sysml2Package? = pkg
        while (cur != null && seen.add(cur.id) && names.size <= Sysml2Limits.PACKAGE_DEPTH * 4) {
            names += cur.name
            cur = cur.parentId?.let { byId[it] }
        }
        return names.asReversed().joinToString("::")
    }
}

/** Map keyed by [key] that keeps the first value for a repeated key and the insertion order (`putIfAbsent` is JVM-only). */
private fun <T> firstWins(
    items: Iterable<T>,
    key: (T) -> String,
): Map<String, T> {
    val m = LinkedHashMap<String, T>()
    for (item in items) {
        val k = key(item)
        if (k !in m) m[k] = item
    }
    return m
}
