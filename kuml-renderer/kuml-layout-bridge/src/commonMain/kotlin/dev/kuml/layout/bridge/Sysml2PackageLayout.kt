package dev.kuml.layout.bridge

import dev.kuml.core.model.KumlElement
import dev.kuml.core.model.KumlMetaValue
import dev.kuml.layout.EdgeHints
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EndpointRef
import dev.kuml.layout.GroupId
import dev.kuml.layout.Insets
import dev.kuml.layout.LayoutEdge
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutGroup
import dev.kuml.layout.LayoutNode
import dev.kuml.layout.NodeHints
import dev.kuml.layout.NodeId
import dev.kuml.layout.Size
import dev.kuml.sysml2.ActionDefinition
import dev.kuml.sysml2.ActivityPartitionDefinition
import dev.kuml.sysml2.ActorDefinition
import dev.kuml.sysml2.AttributeDefinition
import dev.kuml.sysml2.ConnectionDefinition
import dev.kuml.sysml2.ConstraintDefinition
import dev.kuml.sysml2.EnumerationDefinition
import dev.kuml.sysml2.InterruptibleRegionDefinition
import dev.kuml.sysml2.LifelineDefinition
import dev.kuml.sysml2.PartDefinition
import dev.kuml.sysml2.PortDefinition
import dev.kuml.sysml2.RequirementDefinition
import dev.kuml.sysml2.StateDefinition
import dev.kuml.sysml2.Sysml2Definition
import dev.kuml.sysml2.Sysml2Import
import dev.kuml.sysml2.Sysml2ImportTarget
import dev.kuml.sysml2.Sysml2ImportVisibility
import dev.kuml.sysml2.Sysml2Limits
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.Sysml2Package
import dev.kuml.sysml2.Sysml2PackageDiagram
import dev.kuml.sysml2.Sysml2PackageIndex
import dev.kuml.sysml2.TestCaseDefinition
import dev.kuml.sysml2.UseCaseDefinition
import dev.kuml.sysml2.edge.PackageEdgeAdapter

/** What a [PkgCardElement] stands for. */
public enum class PkgCardKind { Definition, View, Viewpoint, CollapsedPackage }

/** One text line of a card. [italic] marks `import ...` lines. */
public data class PkgCardLine(
    val text: String,
    val italic: Boolean = false,
)

/** A block of a card below a divider: an optional [heading] (such as `expose`) and its [lines]. */
public data class PkgCardSection(
    val heading: String?,
    val lines: List<PkgCardLine>,
)

/**
 * The box of a definition, view, viewpoint or collapsed package in a package diagram (wave 2I-a). Not serialised:
 * it only carries what the SVG renderer needs, computed by [Sysml2PackageLayout] so the layout size and the drawing agree.
 *
 * For [PkgCardKind.CollapsedPackage], [title] is the qualified name relative to the level-3 package (`C::D`), drawn as a tab.
 */
public class PkgCardElement(
    override val id: String,
    public val kind: PkgCardKind,
    /** `«part def»`, `«view»`, `«viewpoint»`, or empty for a collapsed package. */
    public val keyword: String,
    public val title: String,
    public val sections: List<PkgCardSection>,
    public val width: Float,
    public val height: Float,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : KumlElement

/**
 * Layout of SysML 2 package diagrams (wave 2I-a): packages become nested compound frames (up to
 * [Sysml2Limits.PACKAGE_DEPTH_FRAMES] levels), everything deeper is a flat [PkgCardKind.CollapsedPackage] card inside the
 * level-3 frame. Definitions, views and viewpoints are [PkgCardElement] nodes. `import` and `satisfy` become edges;
 * an import that cannot be an arrow (target not drawn, unknown, or the own package / an ancestor / a descendant) is a
 * text line in the owner frame.
 *
 * [plan] is the single source of truth: the layout graph and the SVG renderer both read it, so they always agree.
 * Everything is deterministic: only lists and insertion-ordered maps are iterated.
 */
public object Sysml2PackageLayout {
    internal const val FRAME_PADDING_TOP: Float = 36f
    internal const val FRAME_PADDING_SIDE: Float = 16f
    internal const val FRAME_TEXT_LINE_H: Float = 14f
    internal const val FRAME_MIN_W: Float = 140f
    internal const val FRAME_MIN_H: Float = 60f
    internal const val TAB_H: Float = 20f

    private const val TITLE_CHAR_PX: Float = 7.8f
    private const val BODY_CHAR_PX: Float = 6.6f
    private const val CARD_MIN_W: Float = 120f
    private const val CARD_MAX_W: Float = 280f
    private const val CARD_PAD: Float = 16f
    private const val DEF_CARD_H: Float = 44f
    private const val LINE_H: Float = 14f
    private const val SECTION_GAP: Float = 6f
    private const val MAX_ENTRIES: Int = 8
    private const val MAX_TEXT_CHARS: Int = 40

    /** A drawn package frame. */
    public data class FramePlan(
        val pkg: Sysml2Package,
        val qualifiedName: String,
        /** Depth inside the diagram: 1 for a package included directly. */
        val depth: Int,
        val parentId: String?,
        /** Imports that are drawn as text lines (`import X::*`), top-down. */
        val textLines: List<String>,
    ) {
        val id: String get() = pkg.id
    }

    /** A drawn card and the frame it sits in (`null` = root level). */
    public data class CardPlan(
        val card: PkgCardElement,
        val groupId: String?,
    )

    /** An edge between two endpoints (a frame id or a card id). */
    public data class EdgePlan(
        val id: String,
        val source: String,
        val target: String,
    )

    public data class PkgPlan(
        val frames: List<FramePlan>,
        val cards: List<CardPlan>,
        val edges: List<EdgePlan>,
    )

    /** Computes what the diagram draws. */
    public fun plan(
        model: Sysml2Model,
        diagram: Sysml2PackageDiagram,
    ): PkgPlan {
        val index = model.packageIndex()
        val builder = PlanBuilder(model = model, diagram = diagram, index = index)
        return builder.build()
    }

    /** The layout graph for [diagram]; see the class comment. */
    public fun toLayoutGraph(
        model: Sysml2Model,
        diagram: Sysml2PackageDiagram,
    ): LayoutGraph {
        val plan = plan(model = model, diagram = diagram)
        val groups =
            plan.frames.map { f ->
                LayoutGroup(
                    id = GroupId(f.id),
                    parent = f.parentId?.let { GroupId(it) },
                    padding =
                        Insets(
                            top = FRAME_PADDING_TOP + FRAME_TEXT_LINE_H * f.textLines.size,
                            right = FRAME_PADDING_SIDE,
                            bottom = FRAME_PADDING_SIDE,
                            left = FRAME_PADDING_SIDE,
                        ),
                    layoutAsCompound = true,
                    minSize = Size(width = FRAME_MIN_W, height = FRAME_MIN_H),
                )
            }
        val nodes =
            plan.cards.map { c ->
                LayoutNode(
                    id = NodeId(c.card.id),
                    intrinsicSize = Size(width = c.card.width, height = c.card.height),
                    hints = NodeHints.NONE,
                    groupId = c.groupId?.let { GroupId(it) },
                )
            }
        val edges =
            plan.edges.map { e ->
                LayoutEdge(
                    id = EdgeId(e.id),
                    source = EndpointRef(nodeId = NodeId(e.source)),
                    target = EndpointRef(nodeId = NodeId(e.target)),
                    hints = EdgeHints.NONE,
                )
            }
        return LayoutGraph(nodes = nodes, edges = edges, groups = groups).withLayoutSpec(diagram = diagram)
    }

    // ── plan construction ──────────────────────────────────────────────────

    private class PlanBuilder(
        val model: Sysml2Model,
        val diagram: Sysml2PackageDiagram,
        val index: Sysml2PackageIndex,
    ) {
        /** Package id -> depth inside the diagram (1 = directly included). */
        val relDepth = LinkedHashMap<String, Int>()

        /** Package id -> id of its frame ancestor of the diagram depth 3 (or itself). */
        val frameOwner = LinkedHashMap<String, String>()
        val parentOf = LinkedHashMap<String, String?>()
        val order = ArrayList<Sysml2Package>()

        fun isFrame(pkgId: String): Boolean = (relDepth[pkgId] ?: Int.MAX_VALUE) <= Sysml2Limits.PACKAGE_DEPTH_FRAMES

        fun build(): PkgPlan {
            val ids = diagram.elementIds
            val requested = ids.mapNotNull { index.packageById(it) }.distinctBy { it.id }
            val requestedIds = requested.map { it.id }.toSet()
            val roots = requested.filter { p -> index.ancestorsOf(p).none { it.id in requestedIds } }
            for (r in roots) {
                for (p in index.subtreeOf(r)) {
                    if (p.id in relDepth) continue
                    if (p.id == r.id) {
                        relDepth[p.id] = 1
                        parentOf[p.id] = null
                    } else {
                        val parentDepth = relDepth[p.parentId] ?: continue
                        relDepth[p.id] = parentDepth + 1
                        parentOf[p.id] = p.parentId
                    }
                    order += p
                }
            }
            // A package deeper than the frame limit belongs to its ancestor at the limit.
            for (p in order) {
                var cur = p.id
                var guard = 0
                while (!isFrame(cur) && guard++ < Sysml2Limits.PACKAGE_DEPTH + 2) cur = parentOf[cur] ?: break
                frameOwner[p.id] = cur
            }

            // Members of drawn packages (frames or collapsed cards) are never drawn a second time as standalone cards.
            val drawnDefinitionIds = order.flatMap { it.memberIds }.toSet()
            val cards = ArrayList<CardPlan>()
            val emittedCards = HashSet<String>()
            val textLines = linkedMapOf<String, MutableList<String>>()
            val collapsedLines = linkedMapOf<String, MutableList<String>>()

            // Cards in declaration order: elementIds first, a package brings its sub-tree.
            val emittedPackages = HashSet<String>()

            fun emitPackage(p: Sysml2Package) {
                for (sub in index.subtreeOf(p)) {
                    if (sub.id !in relDepth || !emittedPackages.add(sub.id)) continue
                    if (isFrame(sub.id)) {
                        for (m in sub.memberIds) {
                            val def = index.definitionById(m) ?: continue
                            if (emittedCards.add(def.id)) cards += CardPlan(card = definitionCard(def), groupId = sub.id)
                        }
                    } else {
                        val owner = frameOwner.getValue(sub.id)
                        cards += CardPlan(card = collapsedCard(sub = sub, frameOwnerId = owner), groupId = owner)
                        emittedCards += sub.id
                    }
                }
            }
            for (id in ids) {
                val p = index.packageById(id)
                when {
                    p != null -> if (p.id in relDepth) emitPackage(p)
                    else -> {
                        val def = index.definitionById(id)
                        val view = model.views.firstOrNull { it.id == id }
                        val vp = model.viewpoints.firstOrNull { it.id == id }
                        when {
                            def != null ->
                                if (def.id !in drawnDefinitionIds && emittedCards.add(def.id)) {
                                    cards += CardPlan(card = definitionCard(def), groupId = null)
                                }
                            view != null -> if (emittedCards.add(view.id)) cards += CardPlan(card = viewCard(view), groupId = null)
                            vp != null -> if (emittedCards.add(vp.id)) cards += CardPlan(card = viewpointCard(vp), groupId = null)
                        }
                    }
                }
            }

            // Imports.
            val cardIds = cards.map { it.card.id }.toSet()
            val importEdges = ArrayList<EdgePlan>()
            for (imp in model.imports) {
                val owner = index.packageById(imp.ownerPackageId) ?: continue
                if (owner.id !in relDepth) continue
                val ownerEnd = owner.id
                val targetEnd = resolveEnd(imp = imp, owner = owner, cardIds = cardIds)
                if (targetEnd == null || targetEnd == ownerEnd) {
                    val line = (if (imp.visibility == Sysml2ImportVisibility.PUBLIC) "public " else "") + "import " + imp.rawTarget
                    if (isFrame(owner.id)) {
                        textLines.getOrPut(owner.id) { ArrayList() } += line
                    } else {
                        collapsedLines.getOrPut(owner.id) { ArrayList() } += line
                    }
                } else {
                    importEdges += EdgePlan(id = imp.id, source = ownerEnd, target = targetEnd)
                }
            }

            // Collapsed cards pick up their import text lines (the card was sized before the lines were known).
            val finalCards =
                cards.map { c ->
                    val extra = collapsedLines[c.card.id]
                    val sub = index.packageById(c.card.id)
                    val owner = c.groupId
                    if (extra.isNullOrEmpty() ||
                        sub == null ||
                        owner == null
                    ) {
                        c
                    } else {
                        c.copy(card = collapsedCard(sub = sub, frameOwnerId = owner, extra = extra))
                    }
                }

            val satisfy = ArrayList<EdgePlan>()
            val viewpointCards = finalCards.filter { it.card.kind == PkgCardKind.Viewpoint }.map { it.card.id }.toSet()
            for (c in finalCards) {
                if (c.card.kind != PkgCardKind.View) continue
                val v = model.views.firstOrNull { it.id == c.card.id } ?: continue
                for (vpId in v.viewpointIds) {
                    if (vpId in viewpointCards) {
                        satisfy +=
                            EdgePlan(id = PackageEdgeAdapter.satisfyEdgeId(viewId = v.id, viewpointId = vpId), source = v.id, target = vpId)
                    }
                }
            }
            val edges =
                importEdges.sortedWith(compareBy({ it.source }, { it.target }, { it.id })) +
                    satisfy.sortedWith(compareBy({ it.source }, { it.target }, { it.id }))

            val frames =
                order
                    .filter { isFrame(it.id) }
                    .map { p ->
                        FramePlan(
                            pkg = p,
                            qualifiedName = index.qualifiedNameOf(p),
                            depth = relDepth.getValue(p.id),
                            parentId = parentOf[p.id],
                            textLines = textLines[p.id].orEmpty().toList(),
                        )
                    }
            return PkgPlan(frames = frames, cards = finalCards, edges = edges)
        }

        /** The endpoint (frame id or card id) the import points at, or `null` when it can only be a text line. */
        fun resolveEnd(
            imp: Sysml2Import,
            owner: Sysml2Package,
            cardIds: Set<String>,
        ): String? {
            val target = index.resolveImportTarget(imp) ?: return null
            val ownerSubtree = index.subtreeOf(owner).map { it.id }.toSet()
            val ownerAncestors = index.ancestorsOf(owner).map { it.id }.toSet()
            return when (target) {
                is Sysml2ImportTarget.PackageTarget -> {
                    val t = target.pkg
                    if (t.id in ownerSubtree || t.id in ownerAncestors) return null
                    if (t.id !in relDepth) return null
                    t.id
                }
                is Sysml2ImportTarget.MemberTarget -> {
                    val def = target.definition
                    val defOwner = index.ownerOf(def.id)
                    if (defOwner != null && defOwner.id in ownerSubtree) return null
                    if (def.id in cardIds) return def.id
                    // The member lives in a collapsed package: the arrow goes to that package's card.
                    if (defOwner != null && defOwner.id in relDepth && !isFrame(defOwner.id)) return defOwner.id
                    null
                }
            }
        }

        // ── card construction ──────────────────────────────────────────────

        fun definitionCard(def: Sysml2Definition): PkgCardElement {
            val keyword = "«${keywordOf(def)}»"
            val width = widthFor(titleLen = def.name.length, keywordLen = keyword.length, entryLen = 0)
            val title = clip(s = def.name, max = ((width - CARD_PAD) / TITLE_CHAR_PX).toInt())
            return PkgCardElement(
                id = def.id,
                kind = PkgCardKind.Definition,
                keyword = keyword,
                title = title,
                sections = emptyList(),
                width = width,
                height = DEF_CARD_H,
            )
        }

        fun viewCard(v: dev.kuml.sysml2.Sysml2View): PkgCardElement =
            fieldsCard(
                id = v.id,
                kind = PkgCardKind.View,
                keyword = "«view»",
                name = v.name,
                fields = listOf("expose" to v.exposes, "filter" to v.filters),
            )

        fun viewpointCard(vp: dev.kuml.sysml2.Sysml2Viewpoint): PkgCardElement =
            fieldsCard(
                id = vp.id,
                kind = PkgCardKind.Viewpoint,
                keyword = "«viewpoint»",
                name = vp.name,
                fields = listOf("stakeholders" to vp.stakeholders, "concerns" to vp.concerns),
            )

        fun fieldsCard(
            id: String,
            kind: PkgCardKind,
            keyword: String,
            name: String,
            fields: List<Pair<String, List<String>>>,
        ): PkgCardElement {
            val shown =
                fields.filter { it.second.isNotEmpty() }.map { (heading, entries) ->
                    val visible = entries.take(MAX_ENTRIES).map { PkgCardLine(text = clip(s = it, max = MAX_TEXT_CHARS)) }
                    val more = entries.size - visible.size
                    PkgCardSection(
                        heading = heading,
                        lines =
                            if (more >
                                0
                            ) {
                                visible + PkgCardLine(text = "+$more more", italic = true)
                            } else {
                                visible
                            },
                    )
                }
            val longest = shown.flatMap { it.lines }.maxOfOrNull { it.text.length } ?: 0
            val width = widthFor(titleLen = name.length, keywordLen = keyword.length, entryLen = longest + 2)
            val title = clip(s = name, max = ((width - CARD_PAD) / TITLE_CHAR_PX).toInt())
            var height = DEF_CARD_H
            for (s in shown) height += SECTION_GAP + LINE_H * (1 + s.lines.size)
            return PkgCardElement(id = id, kind = kind, keyword = keyword, title = title, sections = shown, width = width, height = height)
        }

        fun collapsedCard(
            sub: Sysml2Package,
            frameOwnerId: String,
            extra: List<String> = emptyList(),
        ): PkgCardElement {
            val ownerPkg = index.packageById(frameOwnerId)
            val rel =
                if (ownerPkg == null) {
                    sub.name
                } else {
                    val full = index.qualifiedNameOf(sub)
                    val prefix = index.qualifiedNameOf(ownerPkg)
                    prefix.substringAfterLast("::") + full.removePrefix(prefix)
                }
            val members = sub.memberIds.mapNotNull { index.definitionById(it)?.name }
            val shown = members.take(MAX_ENTRIES).map { PkgCardLine(text = clip(s = it, max = MAX_TEXT_CHARS)) }
            val more = members.size - shown.size
            val lines =
                shown + (if (more > 0) listOf(PkgCardLine(text = "+$more more", italic = true)) else emptyList()) +
                    extra.map { PkgCardLine(text = clip(s = it, max = MAX_TEXT_CHARS), italic = true) }
            val longest = lines.maxOfOrNull { it.text.length } ?: 0
            val width = widthFor(titleLen = rel.length, keywordLen = 0, entryLen = longest)
            val tab = clip(s = rel, max = ((width - CARD_PAD) / TITLE_CHAR_PX).toInt())
            val height = TAB_H + maxOf(26f, LINE_H * lines.size + 12f)
            return PkgCardElement(
                id = sub.id,
                kind = PkgCardKind.CollapsedPackage,
                keyword = "",
                title = tab,
                sections = if (lines.isEmpty()) emptyList() else listOf(PkgCardSection(heading = null, lines = lines)),
                width = width,
                height = height,
            )
        }

        fun widthFor(
            titleLen: Int,
            keywordLen: Int,
            entryLen: Int,
        ): Float {
            val need = maxOf(titleLen * TITLE_CHAR_PX, keywordLen * BODY_CHAR_PX, entryLen * BODY_CHAR_PX) + CARD_PAD
            return need.coerceIn(CARD_MIN_W, CARD_MAX_W)
        }

        fun clip(
            s: String,
            max: Int,
        ): String {
            val m = max.coerceAtLeast(4)
            return if (s.length <= m) s else s.substring(0, m - 1) + "…"
        }
    }

    internal fun keywordOf(def: Sysml2Definition): String =
        when (def) {
            is PartDefinition -> "part def"
            is AttributeDefinition -> "attribute def"
            is EnumerationDefinition -> "enumeration def"
            is PortDefinition -> "port def"
            is ConnectionDefinition -> "connection def"
            is ActorDefinition -> "actor def"
            is UseCaseDefinition -> "use case def"
            is RequirementDefinition -> "requirement def"
            is TestCaseDefinition -> "test case def"
            is ActionDefinition -> "action def"
            is ActivityPartitionDefinition -> "partition def"
            is InterruptibleRegionDefinition -> "region def"
            is LifelineDefinition -> "lifeline def"
            is StateDefinition -> "state def"
            is ConstraintDefinition -> "constraint def"
        }
}
