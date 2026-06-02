package dev.kuml.core.ocl

internal class OclEvaluationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
