package dev.kuml.core.ocl

import kotlinx.serialization.Serializable

/**
 * 1-based source position of a token within the constraint body string that
 * was tokenized (V3.2.23 — `sourcePosition` on [KumlViolation]).
 *
 * Relative to the constraint body text handed to [OclLexer], not the
 * surrounding `.kuml.kts` script — [OclLexer] has no notion of the script's
 * line offset, only of the expression string it received. Multi-line
 * constraint bodies (rare but legal — an OCL expression may contain literal
 * newlines inside e.g. a `let ... in` chain) are tracked correctly relative
 * to the body's own first character.
 *
 * Public (and `@Serializable`) since [KumlViolation.sourcePosition] exposes
 * it through `kuml validate -o json` and the `kuml-mcp` `kuml.validate` tool.
 *
 * @property line 1-based line number within the tokenized string.
 * @property col 1-based column number within [line].
 */
@Serializable
public data class OclPosition(
    val line: Int,
    val col: Int,
)
