package dev.kuml.uml

/**
 * Derived navigability of a [UmlAssociation]'s two ends, per standard UML
 * notation convention:
 * - [BOTH] (both ends navigable, the default) and [NEITHER] (neither end
 *   navigable) draw no arrowhead — an arrowhead expresses a *restriction*,
 *   and neither case restricts navigation to one direction.
 * - [SOURCE_ONLY] / [TARGET_ONLY] draw an open arrowhead at the one
 *   navigable end.
 */
enum class UmlNavigability { BOTH, SOURCE_ONLY, TARGET_ONLY, NEITHER }

/**
 * Derives [UmlNavigability] from `ends[0]` (source) / `ends[1]` (target).
 * Defensive: an association with fewer than 2 ends treats the missing
 * end(s) as navigable (default `true`), so degenerate fixtures resolve to
 * [BOTH] rather than throwing.
 */
fun UmlAssociation.navigability(): UmlNavigability {
    val sourceNav = ends.getOrNull(0)?.navigable ?: true
    val targetNav = ends.getOrNull(1)?.navigable ?: true
    return when {
        sourceNav && targetNav -> UmlNavigability.BOTH
        sourceNav && !targetNav -> UmlNavigability.SOURCE_ONLY
        !sourceNav && targetNav -> UmlNavigability.TARGET_ONLY
        else -> UmlNavigability.NEITHER
    }
}
