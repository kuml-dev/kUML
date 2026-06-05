package dev.kuml.cli

import dev.kuml.core.model.KumlDiagram
import java.io.File
import kotlin.script.experimental.api.ResultValue

/**
 * CLI-local alias delegating to [dev.kuml.core.script.DiagramExtractor].
 * Kept so that [RenderPipeline] and [ValidateCommand] require no import changes.
 */
internal object DiagramExtractor {
    internal fun extract(
        returnValue: ResultValue,
        input: File,
    ): KumlDiagram =
        dev.kuml.core.script.DiagramExtractor
            .extract(returnValue, input)
}
