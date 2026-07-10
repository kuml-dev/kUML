package dev.kuml.uml

import dev.kuml.core.model.KumlMetaValue

/** Well-known metadata keys carried on a [UmlTransition]'s [UmlTransition.metadata] map. */
object TransitionMetadataKeys {
    /**
     * Flag key marking a transition as "protected": edits to its guard through an
     * interactive widget require explicit user confirmation. Stored as
     * [KumlMetaValue.Flag] `true`; absent when not protected.
     */
    const val PROTECTED: String = "protected"
}

/**
 * `true` when this transition is marked protected via
 * `metadata["protected"] == KumlMetaValue.Flag(true)`.
 *
 * Derived (not a dedicated metamodel field) so no breaking change to the
 * metamodel, DSL grammar, or codegen. See the DSL `transition { protected = true }`.
 */
val UmlTransition.isProtected: Boolean
    get() = metadata[TransitionMetadataKeys.PROTECTED] == KumlMetaValue.Flag(true)
