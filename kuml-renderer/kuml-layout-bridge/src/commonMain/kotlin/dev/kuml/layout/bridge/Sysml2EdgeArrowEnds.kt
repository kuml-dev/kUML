package dev.kuml.layout.bridge

import dev.kuml.sysml2.edge.Sysml2ArrowHead
import dev.kuml.sysml2.edge.Sysml2EdgeMetadata

/**
 * Which ends of a SysML 2 edge carry an end arrowhead (PAL wave 2C-b1). Port-edge clamping only requires a stub of
 * arrowhead length at an end that really draws one. Mid-line item-flow arrows (`midArrows`) are not end arrowheads.
 */
internal object Sysml2EdgeArrowEnds {
    /** `(source, target)`; unknown metadata means no arrowhead at either end. */
    fun of(meta: Sysml2EdgeMetadata?): Pair<Boolean, Boolean> {
        if (meta == null || meta.arrowHead == Sysml2ArrowHead.None) return false to false
        return if (meta.headAtSource) true to false else false to true
    }
}
