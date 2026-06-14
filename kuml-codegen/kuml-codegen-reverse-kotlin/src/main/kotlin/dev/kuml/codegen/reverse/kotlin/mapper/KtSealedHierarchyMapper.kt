package dev.kuml.codegen.reverse.kotlin.mapper

import dev.kuml.uml.UmlGeneralization

/**
 * Placeholder: sealed hierarchy relationships are covered by [KtGeneralizationMapper].
 *
 * Returns an empty list intentionally — sealed subclasses appear in the pool
 * and their `superTypeListEntries` are handled in the generalization pass.
 */
internal object KtSealedHierarchyMapper {
    fun map(): List<UmlGeneralization> = emptyList()
}
