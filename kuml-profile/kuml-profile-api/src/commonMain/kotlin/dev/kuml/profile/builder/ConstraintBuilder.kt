package dev.kuml.profile.builder

import dev.kuml.profile.OclConstraint

/** Builder for [OclConstraint] instances within the profile DSL. */
@KumlProfileDsl
public class ConstraintBuilder internal constructor(
    public val name: String,
) {
    private var bodyText: String? = null

    /** Set the OCL expression body for this constraint. */
    public fun ocl(expression: String) {
        bodyText = expression
    }

    internal fun build(): OclConstraint {
        val body =
            bodyText ?: error(
                "Constraint '$name' must call ocl(\"...\") with the expression",
            )
        return OclConstraint(name, body)
    }
}
