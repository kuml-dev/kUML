package dev.kuml.core.ocl

import kotlinx.serialization.Serializable

/**
 * A single OCL constraint violation reported by [OclValidator].
 *
 * @property sourcePosition 1-based line/column of the token that caused the
 *   violation, relative to [oclExpression] — `null` when no position could be
 *   determined (V3.2.23). Populated for:
 *   - **Parse errors**: the exact token position where parsing failed.
 *   - **Evaluation errors** (constraint parsed but threw, or evaluated to
 *     non-`true`): the constraint body's start position (`line = 1, col = 1`)
 *     as a best-effort fallback, since [OclEvaluator] does not thread
 *     per-sub-expression positions through evaluation (see [OclEvaluationException]
 *     KDoc).
 *
 * `sourcePosition` is additive on this `@Serializable` type — existing JSON
 * consumers (`kuml validate -o json`, `kuml-mcp`'s `kuml.validate` tool) see
 * a new optional field and are unaffected by its presence.
 */
@Serializable
public data class KumlViolation(
    val constraintId: String,
    val constraintName: String,
    val classifierId: String,
    val classifierName: String,
    val oclExpression: String,
    val message: String,
    val sourcePosition: OclPosition? = null,
)
