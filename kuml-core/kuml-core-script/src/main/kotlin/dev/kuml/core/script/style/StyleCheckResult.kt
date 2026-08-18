package dev.kuml.core.script.style

/**
 * Outcome of [NamedArgumentStyleCheck.check].
 *
 * [Unavailable] is deliberately a **separate** case from "zero findings" —
 * it means the check itself could not run (worker library missing, the
 * worker process failed to start, timed out, or returned garbage), as
 * opposed to [Ok] with an empty [Ok.findings] list, which means the check
 * ran successfully and found nothing. Callers must treat [Unavailable] as a
 * WARNING, never as a validation ERROR (a missing `lib/style/` is an
 * installation defect, not a defect in the user's script) — see
 * `ValidateCommand`'s `STYLE_CHECK_UNAVAILABLE` handling.
 */
public sealed interface StyleCheckResult {
    public data class Ok(
        val findings: List<StyleFinding>,
    ) : StyleCheckResult

    public data class Unavailable(
        val reason: String,
    ) : StyleCheckResult
}
