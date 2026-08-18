package dev.kuml.core.script.style

import kotlinx.serialization.Serializable

/**
 * A single source-style violation reported by [NamedArgumentStyleCheck] —
 * currently always a `POSITIONAL_ARGUMENT` finding (a `dev.kuml.*` call with
 * more than one value parameter passing an argument positionally instead of
 * named). See CLAUDE.md "Kotlin-Coding-Konventionen / 1. Named Parameters —
 * PFLICHT" and `dev.kuml.detekt.RequireNamedArguments` (`:kuml-detekt-rules`),
 * whose exemption logic this check mirrors.
 *
 * @property id Stable machine-readable finding code (`"POSITIONAL_ARGUMENT"`).
 * @property severity Always `"error"` for a real finding.
 * @property message Human-readable description, matching the detekt rule's wording.
 * @property line 1-based line in the **original** script source.
 * @property column 1-based column in the **original** script source.
 */
@Serializable
public data class StyleFinding(
    val id: String,
    val severity: String,
    val message: String,
    val line: Int,
    val column: Int,
) {
    /** `"line <L>, column <C>"` — the human-readable location string used by CLI text output. */
    public val location: String get() = "line $line, column $column"
}
