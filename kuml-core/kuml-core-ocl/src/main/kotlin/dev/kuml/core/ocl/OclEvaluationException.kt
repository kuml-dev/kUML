package dev.kuml.core.ocl

/**
 * Geworfen, wenn die Auswertung eines OCL-Ausdrucks scheitert
 * (z.B. Typfehler, fehlende Property, Division durch null).
 *
 * Public seit V1.1.5 (für [OclExpressions]-Konsumenten).
 *
 * @property position 1-based source position of the token that triggered the
 *   failure, relative to the constraint body string (V3.2.23). `null` when
 *   the exception originates outside [OclParser] (e.g. deep in
 *   [OclEvaluator], where individual sub-expressions are not
 *   position-tagged) — callers fall back to the constraint body's start
 *   position (`OclPosition(1, 1)`) in that case.
 */
public class OclEvaluationException(
    message: String,
    cause: Throwable? = null,
    internal val position: OclPosition? = null,
) : RuntimeException(message, cause)
