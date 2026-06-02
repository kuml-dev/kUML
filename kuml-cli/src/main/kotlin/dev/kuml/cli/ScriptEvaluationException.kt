package dev.kuml.cli

/**
 * CLI-local typealias. The canonical type lives in [dev.kuml.core.script.ScriptEvaluationException].
 * Kept so that existing catch-clauses in the CLI commands compile without changes.
 */
internal typealias ScriptEvaluationException = dev.kuml.core.script.ScriptEvaluationException
