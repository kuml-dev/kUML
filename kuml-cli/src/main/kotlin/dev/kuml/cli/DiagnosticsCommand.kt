package dev.kuml.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.types.file
import dev.kuml.core.script.KumlScriptHost
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * The `diagnostics` subcommand — emits raw script compile/eval diagnostics for
 * IDE/editor integration (e.g. the JetBrains plugin's annotator).
 *
 * Unlike `validate` (which focuses on OCL/structural model violations and prints
 * script errors as a plain, location-less message), this command preserves the
 * **source location** (line/column) of every diagnostic so editors can place
 * inline squiggles precisely.
 *
 * ## Output format
 *
 * One diagnostic per line, tab-separated, on stdout:
 *
 * ```
 * <severity>\t<startLine>\t<startCol>\t<endLine>\t<endCol>\t<message>
 * ```
 *
 * - `severity` is the [ScriptDiagnostic.Severity] name (`WARNING`/`ERROR`/`FATAL`).
 * - Location fields are empty strings when the diagnostic carries no location.
 * - `message` is the last field and has any tab/newline replaced by a space, so a
 *   simple `line.split('\t')` with limit 6 is always safe.
 *
 * A tab-separated line format (rather than JSON) keeps consumers dependency-free —
 * no JSON parser required to read it. The command always exits 0; the presence of
 * errors is conveyed by the emitted lines, not the exit code, so callers can parse
 * the result regardless of script validity.
 */
internal class DiagnosticsCommand : CliktCommand(name = "diagnostics") {
    private val input by argument(help = "Path to *.kuml.kts script")
        .file(mustExist = true, canBeDir = false)

    override fun help(context: Context): String = "Emit script compile/eval diagnostics (with line/column) as TSV for IDE integration."

    override fun run() {
        val result = KumlScriptHost.eval(input)
        result.reports
            .filter { it.severity >= ScriptDiagnostic.Severity.WARNING }
            .forEach { d ->
                val loc = d.location
                val fields =
                    listOf(
                        d.severity.name,
                        loc?.start?.line?.toString() ?: "",
                        loc?.start?.col?.toString() ?: "",
                        loc?.end?.line?.toString() ?: "",
                        loc?.end?.col?.toString() ?: "",
                        d.message
                            .replace('\t', ' ')
                            .replace('\n', ' ')
                            .replace('\r', ' '),
                    )
                echo(fields.joinToString("\t"))
            }
    }
}
