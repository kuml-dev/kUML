package dev.kuml.cli

import dev.kuml.core.model.KumlDiagram
import java.io.File
import kotlin.script.experimental.api.ResultValue

/**
 * Extracts a [KumlDiagram] from a Kotlin Scripting evaluation result.
 * Shared by [RenderPipeline] and [ValidateCommand].
 */
internal object DiagramExtractor {
    /**
     * Extracts a [KumlDiagram] from the script's [ResultValue].
     *
     * The kUML DSL's `diagram(...)` top-level function returns a [KumlDiagram].
     * When used as the **last expression** in a script, the result is
     * [ResultValue.Value] with [ResultValue.Value.value] holding the diagram.
     *
     * When the last statement is an assignment (`val d = diagram(...)`), the
     * result is [ResultValue.Unit]. In that case we look for a `KumlDiagram`
     * property on the script instance via reflection.
     *
     * V1 always takes the **first** diagram found.
     *
     * @param returnValue The `ResultValue` from `EvaluationResult.returnValue`.
     * @param input The script file (used in error messages).
     * @return The extracted [KumlDiagram].
     * @throws ScriptEvaluationException if no diagram can be found.
     */
    internal fun extract(
        returnValue: ResultValue,
        input: File,
    ): KumlDiagram {
        // Case 1: the script's last expression is the diagram itself
        if (returnValue is ResultValue.Value) {
            val value = returnValue.value
            if (value is KumlDiagram) return value
        }

        // Case 2: script ends with a statement — scan the script instance for a KumlDiagram property
        val instance = returnValue.scriptInstance
        if (instance != null) {
            val diagramProp =
                instance::class
                    .members
                    .filterIsInstance<kotlin.reflect.KProperty1<Any, *>>()
                    .firstOrNull { prop ->
                        try {
                            prop.get(instance) is KumlDiagram
                        } catch (_: Exception) {
                            false
                        }
                    }
            if (diagramProp != null) {
                @Suppress("UNCHECKED_CAST")
                return diagramProp.get(instance) as KumlDiagram
            }
        }

        throw ScriptEvaluationException(
            "Script '${input.name}' did not produce a KumlDiagram. " +
                "Ensure the script ends with a `diagram { }` expression.",
        )
    }
}
