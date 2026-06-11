package dev.kuml.widget.compose

/** Defines what the user is allowed to edit in a [BehaviourWidget]. */
public sealed class EditPolicy {
    /** No editing allowed — display only. */
    public object None : EditPolicy()

    /** Only transition guards may be edited. */
    public object GuardsOnly : EditPolicy()

    /** Full structural editing (add/remove states and transitions). */
    public object FullStructural : EditPolicy()

    /** `true` when guard editing is permitted. */
    public val allowsGuardEdit: Boolean get() = this is GuardsOnly || this is FullStructural

    /** `true` when structural editing is permitted. */
    public val allowsStructuralEdit: Boolean get() = this is FullStructural
}
