package dev.kuml.uml.dsl

import dev.kuml.uml.UmlConstraint
import dev.kuml.uml.ids.UmlIds

/**
 * Attaches an OCL constraint to the enclosing class or interface.
 *
 * The OCL expression [body] is stored verbatim in [UmlConstraint.body] and
 * evaluated lazily by `kuml validate` (`kuml-core-ocl`).
 * This DSL function has **no dependency** on `kuml-core-ocl`.
 *
 * ```kotlin
 * classOf("Order") {
 *     constraint("hasId",  "self.attributes->size() > 0")
 *     constraint("hasOps", "self.operations->notEmpty()")
 *     attribute("id", "UUID")
 * }
 * ```
 *
 * @param name Short constraint name.
 * @param body OCL expression string (evaluated by `kuml-core-ocl` at validate time).
 * @return The built [UmlConstraint].
 */
public fun UmlClassifierScope.constraint(
    name: String,
    body: String,
): UmlConstraint {
    val id = UmlIds.disambiguate(UmlIds.child(ownerId, name), takenIds)
    takenIds += id
    val c = UmlConstraint(id = id, name = name, body = body)
    addConstraint(c)
    return c
}
