package dev.kuml.cli

import com.github.ajalt.clikt.core.ProgramResult
import dev.kuml.core.model.DiagramType
import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.transform.umlerm.UmlModelSource
import java.io.File
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

/** Loads several `*.kuml.kts` scripts as UML class diagrams for merging (multi-script mode). */
internal object MultiScriptLoader {
    /** Upper bound for the number of scripts in one invocation (DoS guard). */
    const val MAX_SCRIPTS: Int = 1000

    /**
     * Evaluates every file and returns one [UmlModelSource] per script, keyed by the canonical path.
     *
     * @throws ProgramResult with [ExitCodes.USAGE] for a duplicate file or too many scripts.
     * @throws ProgramResult with [ExitCodes.SCRIPT_ERROR] for script errors and non-class diagrams.
     */
    fun loadUmlClassSources(
        files: List<File>,
        echoErr: (String) -> Unit,
    ): List<UmlModelSource> {
        if (files.size > MAX_SCRIPTS) {
            echoErr("Too many scripts (${files.size}); at most $MAX_SCRIPTS are supported.")
            throw ProgramResult(ExitCodes.USAGE)
        }
        val origins = files.map { it.canonicalFile.invariantSeparatorsPath }
        val duplicate =
            origins
                .groupingBy { it }
                .eachCount()
                .entries
                .firstOrNull { it.value > 1 }
                ?.key
        if (duplicate != null) {
            echoErr("Script given more than once: $duplicate")
            throw ProgramResult(ExitCodes.USAGE)
        }

        return files.zip(origins).map { (file, origin) ->
            val evalResult = KumlScriptHost.eval(file = file)
            val errors = evalResult.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
            if (errors.isNotEmpty() || evalResult is ResultWithDiagnostics.Failure) {
                echoErr("Script error in '${file.path}': ${errors.joinToString("\n") { it.message }}")
                throw ProgramResult(ExitCodes.SCRIPT_ERROR)
            }
            val success =
                evalResult as? ResultWithDiagnostics.Success
                    ?: run {
                        echoErr("Script evaluation of '${file.path}' produced no result")
                        throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                    }
            val extracted =
                try {
                    DiagramExtractor.extractAny(returnValue = success.value.returnValue, input = file)
                } catch (e: ScriptEvaluationException) {
                    echoErr(e.message.orEmpty())
                    throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                }
            val diagram = (extracted as? ExtractedDiagram.Uml)?.diagram?.takeIf { it.type == DiagramType.CLASS }
            if (extracted is ExtractedDiagram.Erm) {
                echoErr("'${file.path}' is an ERM model; merging ERM models is not supported")
                throw ProgramResult(ExitCodes.SCRIPT_ERROR)
            }
            if (diagram == null) {
                val kind =
                    when (extracted) {
                        is ExtractedDiagram.Uml -> "UML ${extracted.diagram.type} diagram"
                        else -> extracted::class.simpleName.orEmpty() + " model"
                    }
                echoErr("'${file.path}' is a $kind; multi-script mode merges UML class diagrams only")
                throw ProgramResult(ExitCodes.SCRIPT_ERROR)
            }
            UmlModelSource(origin = origin, diagram = diagram)
        }
    }
}
