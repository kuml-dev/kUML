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

    /** Maximum number of parameters of one constraint definition. */
    const val CONSTRAINT_PARAMETERS: Int = 64

    /** Maximum number of layout hint groups and ids per group. */
    const val LAYOUT_GROUPS: Int = 32
    const val LAYOUT_IDS_PER_GROUP: Int = 64

    /** Maximum number of packages per model (wave 2I-a). */
    const val PACKAGES: Int = 128

    /** Maximum nesting depth of packages the DSL accepts (wave 2I-a). */
    const val PACKAGE_DEPTH: Int = 8

    /** Packages nested deeper than this are not drawn as frames but as collapsed cards (wave 2I-a). */
    const val PACKAGE_DEPTH_FRAMES: Int = 3

    /** Maximum number of imports per model (wave 2I-a). */
    const val IMPORTS: Int = 256

    /** Maximum number of stakeholders, concerns, exposes or filters of one view / viewpoint (wave 2I-a). */
    const val VIEW_ENTRIES: Int = 32

    /** Maximum number of viewpoints and of views per model (wave 2I-a). */
    const val VIEWS: Int = 128
}
