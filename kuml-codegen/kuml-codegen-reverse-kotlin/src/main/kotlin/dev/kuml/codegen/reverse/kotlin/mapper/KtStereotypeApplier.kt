package dev.kuml.codegen.reverse.kotlin.mapper

/**
 * Placeholder for annotation-based stereotype application.
 *
 * In V3.0.8 annotations are not mapped to stereotypes — this is reserved for a later version.
 */
internal object KtStereotypeApplier {
    /**
     * Maps annotation names to additional stereotypes.
     *
     * @param annotationNames fully-qualified or simple annotation names
     * @return additional stereotype strings (empty in V3.0.8)
     */
    fun fromAnnotations(annotationNames: List<String>): List<String> = emptyList()
}
