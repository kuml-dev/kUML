package dev.kuml.core.script.diagnostics

import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

/** Coarse class of a script problem, for stable codes in tooling output. */
public enum class ScriptProblemCode { DSL_ERROR, TYPE_MISMATCH, UNKNOWN_ANCHOR, RECEIVER_SCOPE, NO_DIAGRAM }

/**
 * One problem of a kUML script with its position.
 *
 * @property line 1-based line inside the script source, or `null` if unknown
 * @property column 1-based column, or `null` if unknown
 * @property function name of the call enclosing the position (e.g. `sameLayer`), if it could be determined
 */
public data class ScriptProblem(
    val code: ScriptProblemCode,
    val line: Int?,
    val column: Int?,
    val function: String?,
    val message: String,
    val hint: String? = null,
)

/**
 * Turns script-host diagnostics (and runtime exceptions hidden in a *successful* evaluation) into
 * [ScriptProblem]s with positions. Messages never contain absolute file-system paths.
 */
public object ScriptProblems {
    private const val MAX_PROBLEMS = 20
    private const val MAX_MESSAGE = 300

    /** Problems of a failed or erroneous evaluation; empty if the evaluation succeeded cleanly. */
    public fun describe(
        result: ResultWithDiagnostics<EvaluationResult>,
        source: String,
        scriptFileName: String,
    ): List<ScriptProblem> {
        val out = mutableListOf<ScriptProblem>()
        for (d in result.reports) {
            if (d.severity < ScriptDiagnostic.Severity.ERROR) continue
            val line = d.location?.start?.line
            val col = d.location?.start?.col
            val msg = clean(text = d.message, scriptFileName = scriptFileName)
            val fn = if (line != null && col != null) enclosingCall(source = source, line = line, column = col) else null
            val code = classify(msg)
            out +=
                ScriptProblem(
                    code = code,
                    line = line,
                    column = col,
                    function = fn,
                    message = decorate(msg = msg, code = code, fn = fn, line = line, col = col),
                    hint = hintFor(code = code, fn = fn),
                )
            if (out.size >= MAX_PROBLEMS) return out
        }
        if (result is ResultWithDiagnostics.Success) {
            val rv = result.value.returnValue
            if (rv is ResultValue.Error) {
                val t = rv.error
                val frame = t.stackTrace.firstOrNull { it.fileName == scriptFileName }
                val raw = "${t.javaClass.simpleName}: ${t.message ?: ""}"
                val msg = clean(text = raw, scriptFileName = scriptFileName)
                val code = if (msg.contains("anchor", ignoreCase = true)) ScriptProblemCode.UNKNOWN_ANCHOR else ScriptProblemCode.DSL_ERROR
                out +=
                    ScriptProblem(code = code, line = frame?.lineNumber?.takeIf { it > 0 }, column = null, function = null, message = msg)
            }
        }
        return out
    }

    /** Problem for a script that ran but produced no renderable diagram. */
    public fun noDiagram(): ScriptProblem =
        ScriptProblem(
            code = ScriptProblemCode.NO_DIAGRAM,
            line = null,
            column = null,
            function = null,
            message = "The block evaluates but produces no renderable diagram.",
            hint = "End the block with a complete diagram or model expression, not a bare sub-diagram.",
        )

    internal fun classify(message: String): ScriptProblemCode =
        when {
            message.contains("cannot be called in this context with an implicit receiver") -> ScriptProblemCode.RECEIVER_SCOPE
            message.contains("type mismatch", ignoreCase = true) ||
                message.contains("actual type is") -> ScriptProblemCode.TYPE_MISMATCH
            message.contains(
                "anchor",
                ignoreCase = true,
            ) &&
                message.contains("unknown", ignoreCase = true) -> ScriptProblemCode.UNKNOWN_ANCHOR
            else -> ScriptProblemCode.DSL_ERROR
        }

    private fun decorate(
        msg: String,
        code: ScriptProblemCode,
        fn: String?,
        line: Int?,
        col: Int?,
    ): String {
        if (code != ScriptProblemCode.TYPE_MISMATCH || fn == null) return msg
        val where = if (line != null) " at line $line" + (col?.let { ", column $it" } ?: "") else ""
        return "$msg (in $fn(...)$where)"
    }

    private fun hintFor(
        code: ScriptProblemCode,
        fn: String?,
    ): String? =
        when {
            code == ScriptProblemCode.RECEIVER_SCOPE && fn == "relationship" ->
                "Use an explicit receiver, e.g. this@c4Model.relationship(...)."
            else -> null
        }

    /** Strips file-system paths from [text] and bounds its length. */
    internal fun clean(
        text: String,
        scriptFileName: String,
    ): String {
        val withName = text.replace(Regex("(/[^\\s:'\"]+)+/" + Regex.escape(scriptFileName)), scriptFileName)
        val noPaths = withName.replace(Regex("(?:/[A-Za-z0-9._ -]+){2,}"), "<path>")
        return noPaths.lines().first().take(MAX_MESSAGE)
    }

    /**
     * Name of the innermost call `ident(` / `ident {` that encloses the 1-based position, found by a
     * lexical backwards scan with parenthesis counting (string literals and comments are skipped).
     */
    internal fun enclosingCall(
        source: String,
        line: Int,
        column: Int,
    ): String? {
        val lines = source.lines()
        if (line < 1 || line > lines.size) return null
        var offset = 0
        for (i in 0 until line - 1) offset += lines[i].length + 1
        val pos = (offset + column - 1).coerceIn(0, source.length)
        // Mask strings and comments so their brackets are ignored.
        val masked = mask(source)
        var depth = 0
        var i = pos
        while (i > 0) {
            i--
            when (masked[i]) {
                ')' -> depth++
                '(' -> {
                    if (depth == 0) {
                        var j = i
                        while (j > 0 && (masked[j - 1].isLetterOrDigit() || masked[j - 1] == '_')) j--
                        return masked.substring(j, i).takeIf { it.isNotEmpty() }
                    }
                    depth--
                }
            }
        }
        return null
    }

    private fun mask(s: String): String {
        val sb = StringBuilder(s)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '"') {
                var j = i + 1
                while (j < s.length && s[j] != '"' && s[j] != '\n') {
                    if (s[j] == '\\') j++
                    j++
                }
                for (k in i..minOf(j, s.length - 1)) sb[k] = ' '
                i = j + 1
            } else if (c == '/' && i + 1 < s.length && s[i + 1] == '/') {
                var j = i
                while (j < s.length && s[j] != '\n') {
                    sb[j] = ' '
                    j++
                }
                i = j
            } else {
                i++
            }
        }
        return sb.toString()
    }
}
