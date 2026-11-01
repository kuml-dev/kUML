package dev.kuml.layout.bridge

import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.sysml2.ACT_MAX_REGION_DEPTH
import dev.kuml.sysml2.InterruptibleRegionDefinition

/** An interruptible region with its frame rectangle (layout coordinates, padding applied). */
public data class ActRegionFrame(
    val regionId: String,
    val parentRegionId: String?,
    val showLabel: Boolean,
    val rect: Rect,
)

/**
 * Single source of the interruptible-region frame geometry (PAL wave 2F): the layout pass (region displacement) and the
 * SVG renderer both call [compute], so the frame the pass keeps clear is exactly the frame that gets drawn.
 */
public object ActRegionFrames {
    /** Padding of a frame around its members (px). */
    public const val PADDING: Float = 12f

    /** Extra top padding when the region name is shown (px). */
    public const val TAB_TOP: Float = 28f

    /** Minimum indent of a nested frame inside its parent (px). */
    public const val NEST_INDENT: Float = 8f

    /**
     * Frame of every non-empty region: bounding box of its members ([members] maps node id to region id) and of nested
     * regions plus [PADDING] ([TAB_TOP] on top if the name is shown). Depth beyond [ACT_MAX_REGION_DEPTH] and parent
     * cycles are cut off, so the call always terminates. Result ordered outermost (largest) first.
     */
    public fun compute(
        regions: List<InterruptibleRegionDefinition>,
        members: Map<String, String>,
        nodeBounds: Map<String, Rect>,
    ): List<ActRegionFrame> {
        if (regions.isEmpty()) return emptyList()
        val byId = LinkedHashMap<String, InterruptibleRegionDefinition>()
        regions.forEach { if (it.id !in byId) byId[it.id] = it }
        val children = byId.values.groupBy { it.parentRegionId }
        val membersOf = HashMap<String, MutableList<String>>()
        for ((nodeId, regionId) in members) membersOf.getOrPut(regionId) { ArrayList() } += nodeId
        val memo = HashMap<String, Rect?>()

        fun frameOf(
            id: String,
            depth: Int,
            path: Set<String>,
        ): Rect? {
            if (id in memo) return memo[id]
            if (depth > ACT_MAX_REGION_DEPTH || id in path) return null
            val region = byId[id] ?: return null
            val rects = ArrayList<Rect>()
            membersOf[id]?.sorted()?.forEach { n -> nodeBounds[n]?.let { rects += it } }
            children[id]?.forEach { c -> frameOf(c.id, depth + 1, path + id)?.let { rects += it } }
            val result =
                if (rects.isEmpty()) {
                    null
                } else {
                    val showLabel = region.showLabel && region.name.isNotBlank()
                    val minX = rects.minOf { it.origin.x } - PADDING
                    val minY = rects.minOf { it.origin.y } - if (showLabel) TAB_TOP else PADDING
                    val maxX = rects.maxOf { it.origin.x + it.size.width } + PADDING
                    val maxY = rects.maxOf { it.origin.y + it.size.height } + PADDING
                    Rect(origin = Point(x = minX, y = minY), size = Size(width = maxX - minX, height = maxY - minY))
                }
            memo[id] = result
            return result
        }

        return byId.values
            .mapNotNull { r ->
                frameOf(r.id, 1, emptySet())?.let {
                    ActRegionFrame(
                        regionId = r.id,
                        parentRegionId = r.parentRegionId,
                        showLabel = r.showLabel && r.name.isNotBlank(),
                        rect = it,
                    )
                }
            }.sortedByDescending { it.rect.size.width * it.rect.size.height }
    }

    /** Ids of [regionId] and every region nested (directly or not) in it; bounded by [ACT_MAX_REGION_DEPTH]. */
    public fun closure(
        regionId: String,
        regions: List<InterruptibleRegionDefinition>,
    ): Set<String> {
        val out = LinkedHashSet<String>()
        out += regionId
        var frontier = listOf(regionId)
        var depth = 0
        while (frontier.isNotEmpty() && depth <= ACT_MAX_REGION_DEPTH) {
            val next = regions.filter { it.parentRegionId in frontier && it.id !in out }.map { it.id }
            out += next
            frontier = next
            depth++
        }
        return out
    }
}
