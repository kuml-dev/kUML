package dev.kuml.core.ocl

/**
 * Geworfen, wenn die Auswertung eines OCL-Ausdrucks scheitert
 * (z.B. Typfehler, fehlende Property, Division durch null).
 *
 * Public seit V1.1.5 (für [OclExpressions]-Konsumenten).
 */
public class OclEvaluationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
