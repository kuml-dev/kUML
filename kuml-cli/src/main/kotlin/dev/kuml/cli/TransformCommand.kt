package dev.kuml.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import dev.kuml.bpmn.model.BpmnProcess
import dev.kuml.codegen.m2m.GeneratedFile
import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.codegen.m2m.TransformerRegistry
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.KumlScriptHost
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * The `transform` subcommand.
 *
 * Evaluates a `*.kuml.kts` script, extracts the UML diagram, and applies the
 * selected M2M/M2T transformer to produce output files (or a transformed model).
 *
 * Usage:
 * ```
 * kuml transform <script> --transformer uml-to-jpa --output build/jpa-gen/
 * kuml transform --list-transformers
 * ```
 *
 * V2.0.21 — first production release. Opens the M2M transformation track.
 */
internal class TransformCommand : CliktCommand(name = "transform") {
    private val input by argument("script", help = "Path to *.kuml.kts script")
        .file(mustExist = true, canBeDir = false)

    private val transformerOption by option("--transformer", "-t")
        .help("Transformer id (e.g. uml-to-jpa). Required unless --list-transformers is set.")

    private val outputDir by option("--output", "-o")
        .help("Output directory for generated files")

    private val fromFormat by option("--from")
        .help("Source format shorthand: 'bpmn' or 'uml-activity'. Sugar for --transformer.")

    private val toFormat by option("--to")
        .help("Target format shorthand: 'uml-activity' or 'bpmn'. Sugar for --transformer.")

    private val listTransformers by option("--list-transformers")
        .flag()
        .help("List all available transformers and exit")

    private val traceOut by option("--trace-out")
        .help("Write the transform trace as JSON to this file path")

    private val packageName by option("--package")
        .help("Override the output package name (transformer-specific)")

    override fun help(context: Context): String = "Apply an M2M or M2T transformer to a kUML script."

    override fun run() {
        // ── List transformers ─────────────────────────────────────────────────
        if (listTransformers) {
            TransformerRegistry.loadFromClasspath()
            if (TransformerRegistry.ids().isEmpty()) {
                echo("No transformers found on the classpath.")
                return
            }
            TransformerRegistry.ids().forEach { id ->
                echo("  $id: ${TransformerRegistry.descriptions()[id]}")
            }
            return
        }

        // ── Resolve transformer id from --from/--to sugar ────────────────────
        val resolvedTransformerId: String? =
            when {
                fromFormat != null && toFormat != null -> resolveFromTo(fromFormat!!, toFormat!!)
                transformerOption != null -> transformerOption
                else -> null
            }

        // ── Validate required options ─────────────────────────────────────────
        val transformerId =
            resolvedTransformerId ?: throw UsageError(
                "Missing required option --transformer (or --from/--to). " +
                    "Use --list-transformers to see available transformers.",
            )
        val outDir =
            outputDir ?: throw UsageError(
                "Missing required option --output.",
            )

        // ── Load transformers ─────────────────────────────────────────────────
        TransformerRegistry.loadFromClasspath()

        // ── Evaluate the script ───────────────────────────────────────────────
        val evalResult = KumlScriptHost.eval(input)
        val scriptErrors = evalResult.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
        if (scriptErrors.isNotEmpty() || evalResult is ResultWithDiagnostics.Failure) {
            val msg = scriptErrors.joinToString("\n") { it.message }
            System.err.println("Script error: $msg")
            throw ProgramResult(ExitCodes.SCRIPT_ERROR)
        }
        val success =
            evalResult as? ResultWithDiagnostics.Success
                ?: run {
                    System.err.println("Script evaluation produced no result")
                    throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                }

        val ctx =
            TransformContext(
                options =
                    buildMap {
                        packageName?.let { put("package", it) }
                    },
            )

        // ── Dispatch: BPMN source requires extractAny → ExtractedDiagram.Bpmn ─
        val isBpmnSource =
            transformerId == "bpmn-to-uml-activity" ||
                (fromFormat == "bpmn" && toFormat != null)

        val transformResult: TransformResult<List<GeneratedFile>> =
            if (isBpmnSource) {
                val extracted =
                    try {
                        DiagramExtractor.extractAny(success.value.returnValue, input)
                    } catch (e: ScriptEvaluationException) {
                        System.err.println(e.message)
                        throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                    }
                val bpmnExtracted =
                    extracted as? ExtractedDiagram.Bpmn
                        ?: run {
                            System.err.println(
                                "Transformer '$transformerId' requires a BPMN script. " +
                                    "The script did not produce a BpmnModel.",
                            )
                            throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                        }
                val bpmnProcess =
                    bpmnExtracted.model.processes.firstOrNull()
                        ?: run {
                            System.err.println("BPMN model contains no processes.")
                            throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                        }
                val t =
                    TransformerRegistry.get<BpmnProcess, List<GeneratedFile>>(transformerId)
                        ?: throw UsageError(
                            "Unknown transformer: '$transformerId'. " +
                                "Use --list-transformers to see available transformers.",
                        )
                t.transform(bpmnProcess, ctx)
            } else {
                // Standard UML path
                val t =
                    TransformerRegistry.get<KumlDiagram, List<GeneratedFile>>(transformerId)
                        ?: throw UsageError(
                            "Unknown transformer: '$transformerId'. " +
                                "Use --list-transformers to see available transformers.",
                        )
                val diagram =
                    try {
                        DiagramExtractor.extract(success.value.returnValue, input)
                    } catch (e: ScriptEvaluationException) {
                        System.err.println(e.message)
                        throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                    }
                t.transform(diagram, ctx)
            }

        // ── Transform ─────────────────────────────────────────────────────────
        when (val result = transformResult) {
            is TransformResult.Success -> {
                val dir = File(outDir).also { it.mkdirs() }
                for (file in result.output) {
                    val out = File(dir, file.relativePath)
                    out.parentFile?.mkdirs()
                    out.writeText(file.content)
                }
                echo("Generated ${result.output.size} file(s) in $outDir")
                traceOut?.let { path ->
                    val traceJson = Json.encodeToString(TraceJson.from(result.trace))
                    File(path).writeText(traceJson)
                    echo("Trace written to $path")
                }
            }
            is TransformResult.Failure -> {
                for (e in result.errors) echo("Error: ${e.message}", err = true)
                throw ProgramResult(ExitCodes.SCRIPT_ERROR)
            }
        }
    }

    // ── --from/--to sugar resolution ─────────────────────────────────────────

    private fun resolveFromTo(
        from: String,
        to: String,
    ): String =
        when {
            from == "bpmn" && to == "uml-activity" -> "bpmn-to-uml-activity"
            from == "uml-activity" && to == "bpmn" -> "uml-activity-to-bpmn"
            else -> throw UsageError(
                "Unsupported --from/$from --to/$to combination. " +
                    "Supported: (bpmn → uml-activity) and (uml-activity → bpmn).",
            )
        }

    // ── Serialization shim for trace JSON output ──────────────────────────────

    @Serializable
    private data class TraceJson(
        val links: List<TraceabilityLinkJson>,
    ) {
        companion object {
            fun from(trace: dev.kuml.codegen.m2m.TransformTrace): TraceJson =
                TraceJson(
                    trace.links.map {
                        TraceabilityLinkJson(it.sourceElementId, it.targetArtifactId, it.ruleId)
                    },
                )
        }
    }

    @Serializable
    private data class TraceabilityLinkJson(
        val sourceElementId: String,
        val targetArtifactId: String,
        val ruleId: String,
    )
}
