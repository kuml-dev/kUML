package dev.kuml.llm.bench

import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

internal object BenchmarkValidator {
    /** Validate a kUML script response. Returns (valid, message). */
    internal fun validateKuml(response: String): Pair<Boolean, String> {
        val script = extractCode(response)
        val tmpFile =
            java.nio.file.Files
                .createTempFile("kuml-bench-", ".kuml.kts")
                .toFile()
        tmpFile.writeText(script)
        return try {
            val result =
                dev.kuml.core.script.KumlScriptHost
                    .eval(tmpFile)
            val errors =
                result.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
            if (errors.isEmpty() && result !is ResultWithDiagnostics.Failure) {
                Pair(true, "OK")
            } else {
                Pair(false, errors.joinToString("; ") { it.message })
            }
        } catch (e: Exception) {
            Pair(false, e.message ?: "Unknown error")
        } finally {
            tmpFile.delete()
        }
    }

    /** Validate a PlantUML response. */
    internal fun validatePlantUml(response: String): Pair<Boolean, String> {
        val code = extractCode(response)
        return if (code.contains("@startuml") && code.contains("@enduml")) {
            Pair(true, "OK")
        } else {
            Pair(false, "Missing @startuml/@enduml markers")
        }
    }

    /** Validate a Mermaid response. */
    internal fun validateMermaid(response: String): Pair<Boolean, String> {
        val code = extractCode(response)
        return if (
            code.trimStart().let {
                it.startsWith("classDiagram") ||
                    it.startsWith("C4Context") ||
                    it.startsWith("graph") ||
                    it.startsWith("sequenceDiagram")
            }
        ) {
            Pair(true, "OK")
        } else {
            Pair(false, "Missing Mermaid diagram type declaration")
        }
    }

    internal fun extractCode(response: String): String {
        val fencePattern = Regex("```[a-zA-Z]*\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
        val match = fencePattern.find(response)
        return match?.groupValues?.get(1) ?: response
    }
}
