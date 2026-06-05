package dev.kuml.core.script

/**
 * Thrown when a kUML script fails to compile or evaluate.
 *
 * Caught by the CLI (exit code 2) and by the MCP server (tool error response).
 */
class ScriptEvaluationException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
