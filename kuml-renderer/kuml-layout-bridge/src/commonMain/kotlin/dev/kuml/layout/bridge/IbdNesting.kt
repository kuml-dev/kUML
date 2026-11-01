package dev.kuml.layout.bridge

import dev.kuml.layout.IbdNestedFrame
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.PartDefinition
import dev.kuml.sysml2.PartUsage
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.projection.IbdAttributeCompartment
import dev.kuml.sysml2.projection.IbdProjection
import dev.kuml.sysml2.projection.ibdPartAttributes
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Box-in-box layout of an IBD (PAL wave 2C-b2b): the parts nested inside a visible part box are drawn as frames
 * inside it, recursively. The sizes are computed from the inside out (children, [PAD] inner distance on all sides,
 * header on top), the children sit in a small grid inside their parent. The visible part box is level 1 and stays a
 * layout node (ELK sees it with its final size); the nested frames are positioned afterwards, local to that box.
 */
internal object IbdNesting {
    /** Inner distance between a frame and the frames inside it. */
    const val PAD: Float = 16f

    /** Height of the single header line of a nested frame (`name : Type`). */
    const val HEADER_H: Float = 22f

    /** Distance between two frames of one grid. */
    const val GAP: Float = 16f

    /** Extra room between a header (or the box's own header area) and the frames below it, for port labels on their top sides. */
    const val TOP_EXTRA: Float = 8f

    private const val MIN_LEAF_W: Float = 160f
    private const val MIN_LEAF_BODY_H: Float = 56f
    private const val MAX_COLUMNS_FIXED: Int = 3

    /** One nested part of the tree below a visible part box. */
    class Node(
        val path: String,
        val title: String,
        val level: Int,
        val compartment: IbdAttributeCompartment?,
        val children: List<Node>,
    ) {
        var w: Float = 0f
        var h: Float = 0f
        val compartmentHeight: Float get() = compartment?.let { Sysml2LayoutBridge.ibdAttributeCompartmentHeight(it) } ?: 0f
    }

    /** The nested tree per visible node id (nodes without nested parts have no entry). */
    fun treeOf(
        model: Sysml2Model,
        diagram: IbdDiagram,
        projection: IbdProjection,
    ): Map<String, List<Node>> {
        if (projection.nestedPartPaths.isEmpty()) return emptyMap()
        val known = projection.nestedPartPaths.toSet()
        val partDefs = model.definitions.filterIsInstance<PartDefinition>().associateBy { it.id }
        val partUsages = model.usages.filterIsInstance<PartUsage>().associateBy { it.id }

        fun childrenOf(
            path: String,
            defId: String,
            level: Int,
        ): List<Node> {
            val def = partDefs[defId] ?: return emptyList()
            val out = ArrayList<Node>()
            for (f in def.features) {
                val typeId = f.typeId ?: continue
                if (typeId !in partDefs) continue
                val usage = partUsages[f.id] ?: continue
                val childPath = "$path::${f.id.substringAfterLast("::")}"
                if (childPath !in known) continue
                out +=
                    Node(
                        path = childPath,
                        title = "${usage.name} : ${usage.definitionId}",
                        level = level,
                        compartment =
                            if (diagram.showAttributes) ibdPartAttributes(model = model, usage = usage).takeUnless { it.isEmpty } else null,
                        children = childrenOf(path = childPath, defId = typeId, level = level + 1),
                    )
            }
            return out
        }
        val out = LinkedHashMap<String, List<Node>>()
        for (n in projection.partNodes) {
            val typeId = n.typeId ?: continue
            val kids = childrenOf(path = n.id, defId = typeId, level = 2)
            if (kids.isNotEmpty()) out[n.id] = kids
        }
        return out
    }

    private class Grid(
        val cols: Int,
        val colW: List<Float>,
        val rowH: List<Float>,
    ) {
        val w: Float get() = colW.sum() + GAP * (colW.size - 1).coerceAtLeast(0)
        val h: Float get() = rowH.sum() + GAP * (rowH.size - 1).coerceAtLeast(0)
    }

    private fun gridOf(children: List<Node>): Grid {
        val cols =
            when {
                children.size <= MAX_COLUMNS_FIXED -> children.size
                else -> ceil(sqrt(children.size.toFloat())).toInt()
            }.coerceAtLeast(1)
        val colW = FloatArray(cols)
        val rows = (children.size + cols - 1) / cols
        val rowH = FloatArray(rows)
        children.forEachIndexed { i, c ->
            colW[i % cols] = maxOf(colW[i % cols], c.w)
            rowH[i / cols] = maxOf(rowH[i / cols], c.h)
        }
        return Grid(cols = cols, colW = colW.toList(), rowH = rowH.toList())
    }

    /** Computes [Node.w] / [Node.h] of [node] and everything below it. */
    fun measure(node: Node) {
        for (c in node.children) measure(c)
        val titleW = node.title.length * Sysml2LayoutBridge.IBD_TITLE_CHAR_PX + 16f
        val attrW = (node.compartment?.lines?.maxOfOrNull { it.text.length * Sysml2LayoutBridge.IBD_ATTR_CHAR_PX } ?: 0f) + 16f
        val head = HEADER_H + node.compartmentHeight
        if (node.children.isEmpty()) {
            node.w = maxOf(MIN_LEAF_W, titleW, attrW)
            node.h = head + MIN_LEAF_BODY_H
        } else {
            val g = gridOf(node.children)
            node.w = maxOf(MIN_LEAF_W, titleW, attrW, g.w + 2f * PAD)
            node.h = head + PAD + TOP_EXTRA + g.h + PAD
        }
    }

    /** Size of the children area of a visible box: width [PAD] on both sides, height from [topArea] down plus [PAD]. */
    fun areaSize(
        roots: List<Node>,
        topArea: Float,
    ): Size {
        roots.forEach { measure(it) }
        val g = gridOf(roots)
        return Size(width = g.w + 2f * PAD, height = topArea + TOP_EXTRA + g.h + PAD)
    }

    /**
     * The frames of [roots] inside the visible box [nodeId] of size [nodeSize]; the children area starts at [topArea]
     * (below the box's own header and attribute compartment) and is centred horizontally. Parents come before children.
     */
    fun frames(
        nodeId: String,
        roots: List<Node>,
        nodeSize: Size,
        topArea: Float,
    ): List<IbdNestedFrame> {
        roots.forEach { measure(it) }
        val out = ArrayList<IbdNestedFrame>()

        fun place(
            children: List<Node>,
            originX: Float,
            originY: Float,
            parentPath: String?,
        ) {
            val g = gridOf(children)
            var y = originY
            children.chunked(g.cols).forEachIndexed { r, row ->
                var x = originX
                row.forEachIndexed { c, n ->
                    val bounds = Rect(origin = Point(x = x, y = y), size = Size(width = n.w, height = n.h))
                    out +=
                        IbdNestedFrame(
                            path = n.path,
                            level = n.level,
                            bounds = bounds,
                            headerHeight = HEADER_H,
                            parentPath = parentPath,
                            nodeId = nodeId,
                            title = n.title,
                            attributeLines =
                                n.compartment?.let { comp ->
                                    comp.lines.map { it.text } +
                                        (if (comp.hiddenCount > 0) listOf("… ${comp.hiddenCount} more") else emptyList())
                                } ?: emptyList(),
                        )
                    if (n.children.isNotEmpty()) {
                        val inner = gridOf(n.children)
                        place(
                            children = n.children,
                            originX = x + (n.w - inner.w) / 2f,
                            originY = y + HEADER_H + n.compartmentHeight + PAD + TOP_EXTRA,
                            parentPath = n.path,
                        )
                    }
                    x += g.colW[c] + GAP
                }
                y += g.rowH[r] + GAP
            }
        }
        val g = gridOf(roots)
        place(children = roots, originX = (nodeSize.width - g.w) / 2f, originY = topArea + TOP_EXTRA, parentPath = null)
        return out
    }
}
