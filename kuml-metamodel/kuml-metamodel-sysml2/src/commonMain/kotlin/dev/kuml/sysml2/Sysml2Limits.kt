package dev.kuml.sysml2

/**
 * Upper bounds for the C3 diagram constructs. They protect renderers and layout code against
 * pathological or hostile models (deep nesting, huge fan-out, huge text). The checkers report
 * violations as warnings/errors; bridge and renderers additionally clamp defensively.
 */
object Sysml2Limits {
    /** Maximum nesting depth of combined fragments; deeper fragments are drawn flat. */
    const val FRAGMENT_DEPTH: Int = 8

    /** Maximum number of combined fragments per model. */
    const val FRAGMENTS: Int = 256

    /** Maximum number of operands per fragment. */
    const val OPERANDS: Int = 16

    /** Maximum text length (guards, `refersTo`, constraint expressions) before it is shortened with an ellipsis. */
    const val TEXT: Int = 200

    /** Maximum length of a time constraint / observation expression. */
    const val TIME_EXPR: Int = 64

    /** Maximum nesting depth of constraint usages in a parametric diagram. */
    const val CONSTRAINT_DEPTH: Int = 4

    /** Maximum number of layout hint groups and ids per group. */
    const val LAYOUT_GROUPS: Int = 32
    const val LAYOUT_IDS_PER_GROUP: Int = 64
}
