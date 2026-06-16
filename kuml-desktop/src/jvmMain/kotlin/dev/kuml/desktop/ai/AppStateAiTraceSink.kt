package dev.kuml.desktop.ai

import dev.kuml.ai.tools.patch.aitrace.AiTraceSink
import dev.kuml.runtime.AiTraceEntry

/**
 * [AiTraceSink] implementation for the kUML Desktop UI.
 *
 * Routes AI patch lifecycle events to console output.
 * Applied and Rejected entries are logged explicitly; all other entries are
 * logged at trace level (simple println).
 *
 * V3.0.26 will route these to the OTLP exporter.
 */
class AppStateAiTraceSink : AiTraceSink {
    override suspend fun emit(entry: AiTraceEntry) {
        println("[AI Trace] ${entry::class.simpleName}: $entry")
    }
}
