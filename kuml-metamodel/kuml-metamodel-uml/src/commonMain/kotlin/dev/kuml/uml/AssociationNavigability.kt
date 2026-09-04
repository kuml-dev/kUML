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
 * Shared derivation used by both [UmlAssociation.navigability] and
 * [UmlAssociationClass.navigability]. Defensive: fewer than 2 ends treats the
 * missing end(s) as navigable (default `true`), so degenerate fixtures resolve
 * to [UmlNavigability.BOTH] rather than throwing.
 */
private fun navigabilityOf(ends: List<UmlAssociationEnd>): UmlNavigability {
    val sourceNav = ends.getOrNull(0)?.navigable ?: true
    val targetNav = ends.getOrNull(1)?.navigable ?: true
    return when {
        sourceNav && targetNav -> UmlNavigability.BOTH
        sourceNav && !targetNav -> UmlNavigability.SOURCE_ONLY
        !sourceNav && targetNav -> UmlNavigability.TARGET_ONLY
        else -> UmlNavigability.NEITHER
    }
}

/**
 * Derives [UmlNavigability] from `ends[0]` (source) / `ends[1]` (target).
 * Defensive: an association with fewer than 2 ends treats the missing
 * end(s) as navigable (default `true`), so degenerate fixtures resolve to
 * [UmlNavigability.BOTH] rather than throwing.
 */
fun UmlAssociation.navigability(): UmlNavigability = navigabilityOf(ends = ends)

/**
 * Derives [UmlNavigability] from `ends[0]` (source) / `ends[1]` (target) of an
 * association class — identical semantics to [UmlAssociation.navigability],
 * shared via [navigabilityOf] so the two can never drift apart.
 */
fun UmlAssociationClass.navigability(): UmlNavigability = navigabilityOf(ends = ends)
