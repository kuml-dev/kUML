package dev.kuml.uml.dsl

import dev.kuml.uml.UmlConstraint
import dev.kuml.uml.UmlConstraintKind
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
 * @param kind The OCL contextual stereotype (`inv:`/`def:`/`pre:`/`post:`/`body:`,
 *   V3.2.22); defaults to [UmlConstraintKind.Invariant]. Prefer the dedicated
 *   [invariant]/[definition]/[precondition]/[postcondition]/[body] functions
 *   over passing this explicitly — they read better at call sites.
 * @param contextOperation Name of the operation this constraint is scoped to.
 *   Required for [UmlConstraintKind.Precondition], [UmlConstraintKind.Postcondition],
 *   and [UmlConstraintKind.Body]; ignored for [UmlConstraintKind.Invariant] and
 *   [UmlConstraintKind.Definition].
 * @return The built [UmlConstraint].
 */
public fun UmlClassifierScope.constraint(
    name: String,
    body: String,
    kind: UmlConstraintKind = UmlConstraintKind.Invariant,
    contextOperation: String? = null,
): UmlConstraint {
    val id = UmlIds.disambiguate(UmlIds.child(ownerId, name), takenIds)
    takenIds += id
    val c = UmlConstraint(id = id, name = name, body = body, kind = kind, contextOperation = contextOperation)
    addConstraint(c)
    return c
}

/**
 * `inv:` — a classifier-scoped invariant. Equivalent to calling [constraint]
 * with the default [UmlConstraintKind.Invariant] kind; provided for symmetry
 * with [definition]/[precondition]/[postcondition]/[body].
 */
public fun UmlClassifierScope.invariant(
    name: String,
    body: String,
): UmlConstraint = constraint(name, body, kind = UmlConstraintKind.Invariant)

/**
 * `def:` — a reusable named helper (attribute/operation), declared in the
 * classifier scope and referenceable by name from later `inv:`/`pre:`/
 * `post:`/`body:` constraints on the same classifier. Not itself an assertion
 * — `kuml validate` binds its value into the evaluation environment rather
 * than checking it evaluates to `true`.
 *
 * ```kotlin
 * classOf("Order") {
 *     definition("isPaid", "self.status = 'PAID'")
 *     invariant("paidHasTotal", "isPaid implies self.total > 0")
 * }
 * ```
 */
public fun UmlClassifierScope.definition(
    name: String,
    body: String,
): UmlConstraint = constraint(name, body, kind = UmlConstraintKind.Definition)

/**
 * `pre:` — an operation entry condition, evaluated against `self` (the
 * receiver at operation entry). [operation] must name an operation already
 * declared in the enclosing classifier.
 */
public fun UmlClassifierScope.precondition(
    name: String,
    operation: String,
    body: String,
): UmlConstraint = constraint(name, body, kind = UmlConstraintKind.Precondition, contextOperation = operation)

/**
 * `post:` — an operation exit condition. May reference `result` (the
 * operation's return value) and `expr@pre` (the value of `expr` at operation
 * entry). [operation] must name an operation already declared in the
 * enclosing classifier.
 */
public fun UmlClassifierScope.postcondition(
    name: String,
    operation: String,
    body: String,
): UmlConstraint = constraint(name, body, kind = UmlConstraintKind.Postcondition, contextOperation = operation)

/**
 * `body:` — a full operation definition via its return-value expression
 * (referencing `result`). [operation] must name an operation already declared
 * in the enclosing classifier.
 */
public fun UmlClassifierScope.body(
    name: String,
    operation: String,
    body: String,
): UmlConstraint = constraint(name, body, kind = UmlConstraintKind.Body, contextOperation = operation)
