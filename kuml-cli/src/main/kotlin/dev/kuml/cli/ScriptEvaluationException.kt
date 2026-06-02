package dev.kuml.cli

/**
 * Thrown when a kUML script fails to compile or evaluate.
 *
 * Maps to exit code [ExitCodes.SCRIPT_ERROR] (2).
 */
internal class ScriptEvaluationException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
