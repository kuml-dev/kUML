package dev.kuml.layout.bridge.note

import dev.kuml.layout.EdgeId
import dev.kuml.layout.GroupId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.polylinePoints

/** A side of a rectangle; for a port the side it sticks out on. */
public enum class Side { E, W, S, N }

/** Where an anchor target is in the layout. */
public sealed interface AnchorGeometry {
    /** A point (port, lifeline stem position). */
    public data class At(
        val p: Point,
    ) : AnchorGeometry

    /**
     * A drawn port square: the line ends at the middle of the square's outer side ([outward]) and may touch the
     * square nowhere else.
     */
    public data class Port(
        val box: Rect,
        val outward: Side,
    ) : AnchorGeometry

    /**
     * The stem of a lifeline: the line ends on the vertical `x` at a height between [top] and [bottom] that the
     * placer picks (the note's own height), so the anchor is one short horizontal line.
     */
    public data class Stem(
        val x: Float,
        val top: Float,
        val bottom: Float,
    ) : AnchorGeometry

    /** A polyline (edge, transition): the line attaches at its midpoint. */
    public data class Along(
        val pts: List<Point>,
    ) : AnchorGeometry

    /** A rectangle (node, group, frame, partition): the line attaches at the nearest border point. */
    public data class Around(
        val r: Rect,
    ) : AnchorGeometry
}

/**
 * Resolves an anchor id to a geometry. Lookup order: [extras] (ports, owner frames, partitions and other
 * things only the renderer knows), node, edge, group; then the id with the prefixes the
 * bridges give to synthetic ids (`conn:`, `flow:` for IBD wires, `frame:` for the IBD owner frame).
 */
public class AnchorGeometryResolver(
    private val layout: LayoutResult,
    private val extras: Map<String, AnchorGeometry> = emptyMap(),
) {
    /** The geometry of [targetId] or `null` if the layout does not show it. */
    public fun resolve(targetId: String): AnchorGeometry? {
        for (candidate in listOf(targetId) + ALIAS_PREFIXES.map { it + targetId }) {
            lookup(candidate)?.let { return it }
        }
        return null
    }

    /** True if [targetId] can be resolved. */
    public fun canResolve(targetId: String): Boolean = resolve(targetId) != null

    private fun lookup(id: String): AnchorGeometry? {
        extras[id]?.let { return it }
        layout.nodes[NodeId(id)]?.let { return AnchorGeometry.Around(it.bounds) }
        layout.edges[EdgeId(id)]?.let { return AnchorGeometry.Along(it.polylinePoints()) }
        layout.groups[GroupId(id)]?.let { return AnchorGeometry.Around(it.bounds) }
        return null
    }

    public companion object {
        private val ALIAS_PREFIXES: List<String> = listOf("conn:", "flow:", "frame:")

        /** [targetId] plus the ids the layout bridges give it as a synthetic edge or frame. */
        public fun aliasesOf(targetId: String): Set<String> = (listOf(targetId) + ALIAS_PREFIXES.map { it + targetId }).toSet()
    }
}
