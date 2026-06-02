package dev.kuml.cli

import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.io.png.KumlPngRenderer
import dev.kuml.io.png.PngRenderOptions
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.bridge.UmlLayoutBridge
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.renderer.theme.core.PlainTheme
import java.io.File
import java.io.IOException
import java.nio.file.Path
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * Orchestrates the kUML render pipeline: Script → Layout → Renderer → File.
 *
 * This object is the internal glue between all pipeline stages. It is not part
 * of the public API; all user-facing interaction goes through [RenderCommand].
 */
internal object RenderPipeline {
    private val layoutEngine = ElkLayoutEngine()

    /**
     * Runs the full render pipeline for the given input script.
     *
     * @param input Path to the `*.kuml.kts` script file.
     * @param output Destination path for the rendered output file.
     * @param format Output format: `"svg"` or `"png"`.
     * @param width Width in pixels (used only for PNG output).
     * @param themeName Theme name; currently only `"plain"` is supported.
     * @throws ScriptEvaluationException if the script fails to compile or evaluate.
     * @throws IOException if the output file cannot be written.
     */
    internal fun run(
        input: File,
        output: Path,
        format: String,
        width: Int,
        @Suppress("UNUSED_PARAMETER") themeName: String,
    ) {
        // 1. Evaluate script
        val evalResult = KumlScriptHost.eval(input)

        // Check for compilation/evaluation errors
        val errors = evalResult.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
        if (errors.isNotEmpty() || evalResult is ResultWithDiagnostics.Failure) {
            val message = errors.joinToString("\n") { it.message }
            throw ScriptEvaluationException("Script evaluation failed:\n$message")
        }

        // 2. Extract KumlDiagram from the evaluation result
        val successResult =
            evalResult as? ResultWithDiagnostics.Success
                ?: throw ScriptEvaluationException("Script evaluation did not produce a result")

        val diagram = extractDiagram(successResult.value.returnValue, input)

        // 3. Build layout graph (V1: UML path only; C4 requires a C4Model, not in scope for
        //    scripts that use the diagram() DSL)
        val layoutGraph = UmlLayoutBridge.toLayoutGraph(diagram)

        // 4. Compute layout
        val layoutResult = layoutEngine.layout(layoutGraph, LayoutHints.DEFAULT)

        // 5. Theme
        val theme = PlainTheme()

        // 6. Render and write output
        try {
            when (format) {
                "svg" -> KumlSvgRenderer.toSvgFile(diagram, layoutResult, output, theme)
                "png" -> {
                    val options = PngRenderOptions(widthPx = width)
                    val pngBytes = KumlPngRenderer.toPng(diagram, layoutResult, theme, options)
                    val file = output.toFile()
                    file.parentFile?.mkdirs()
                    file.writeBytes(pngBytes)
                }
                else -> throw ScriptEvaluationException("Unsupported format: $format")
            }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            if (e is ScriptEvaluationException) throw e
            throw IOException("Failed to write output file: ${e.message}", e)
        }
    }

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
    private fun extractDiagram(
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
