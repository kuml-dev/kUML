package dev.kuml.io.svg

/**
 * Text shown for a combined-fragment operand guard in a sequence diagram, or `null` when nothing
 * should be drawn.
 *
 * - `null` or blank input yields `null`.
 * - A bracketed guard (`[x]`, ` [x] `) is kept as `[x]` (idempotent, never `[[x]]`).
 * - A bracketed guard whose inner text is blank (`[]`, `[ ]`) yields `null`: an empty guard is not drawn.
 * - Any other text is wrapped in brackets.
 *
 * Shared by the UML and SysML 2 sequence renderers and by the SysML left-padding computation so the
 * reserved width and the drawn text always agree.
 */
internal fun seqGuardDisplay(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
        val inner = trimmed.removeSurrounding(prefix = "[", suffix = "]").trim()
        return if (inner.isEmpty()) null else "[$inner]"
    }
    return "[$trimmed]"
}
