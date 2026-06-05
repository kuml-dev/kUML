package dev.kuml.cli

import dev.kuml.core.config.ConfigExtractor
import dev.kuml.core.config.KumlConfig
import dev.kuml.core.config.KumlConfigScriptHost
import java.io.File
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * Lädt `kuml.config.kts` aus dem CLI-Kontext.
 *
 * Auflösungsreihenfolge:
 * 1. Wenn `explicitFile` gesetzt → diese Datei laden.
 * 2. Sonst: nach `./kuml.config.kts` im aktuellen Arbeitsverzeichnis suchen.
 * 3. Wenn auch das fehlt → [KumlConfig.DEFAULT].
 */
internal object ConfigLoader {
    private const val DEFAULT_FILE_NAME = "kuml.config.kts"

    internal fun load(explicitFile: File?): KumlConfig {
        val file =
            explicitFile
                ?: File(DEFAULT_FILE_NAME).takeIf { it.exists() && it.isFile }
                ?: return KumlConfig.DEFAULT

        val result = KumlConfigScriptHost.eval(file)
        val errors = result.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
        if (errors.isNotEmpty() || result is ResultWithDiagnostics.Failure) {
            throw ScriptEvaluationException(
                "Config script '${file.path}' failed:\n" +
                    errors.joinToString("\n") { it.message },
            )
        }
        val success =
            result as? ResultWithDiagnostics.Success
                ?: throw ScriptEvaluationException(
                    "Config script '${file.path}' did not produce a value",
                )
        return ConfigExtractor.extract(success.value.returnValue, file)
    }
}
