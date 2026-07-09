package dev.kuml.codegen.reverse.sql

import dev.kuml.codegen.reverse.ReverseDiagnostic
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.Statement
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Reads and parses `.sql` source files into a flat, file-order list of
 * top-level [Statement]s (V3.4.9).
 *
 * Tries the fast path first — [CCJSqlParserUtil.parseStatements] on the whole
 * file text — which succeeds for the overwhelming majority of pure-DDL files
 * (hand-written schemas, Flyway migrations). Real-world `pg_dump` output and
 * some migrations interleave DDL with `CREATE FUNCTION`/`CREATE TRIGGER`/`DO`
 * blocks that JSqlParser's grammar does not parse (PL/pgSQL bodies), which
 * would otherwise abort parsing of an *entire* file over one unsupported
 * statement. When the whole-file parse fails, [collect] falls back to
 * [splitStatements] — a dollar-quote-aware, string/quoted-identifier-aware,
 * comment-aware top-level `;` splitter — and parses each candidate statement
 * individually, so the DDL statements around an unsupported function body are
 * still recovered; the unsupported ones surface as `REV-SQL-002` WARN
 * diagnostics rather than failing the whole file.
 *
 * **Executor lifecycle**: `CCJSqlParserUtil`'s convenience overloads
 * (`parseStatements(String)`, `parse(String)`) each spin up their own
 * single-thread `ExecutorService` internally and only `shutdown()` it on the
 * *success* path — when parsing throws, that internal executor's non-daemon
 * worker thread is leaked and keeps the JVM alive indefinitely (verified: a
 * bare `main()` that catches the exception and returns still hangs). [collect]
 * therefore always drives parsing through the `(String, ExecutorService,
 * Consumer)` overloads with **its own** single-thread, **daemon**-backed
 * executor that it owns and shuts down in a `finally` block — daemon threads
 * never block JVM exit even in the worst case, and the explicit `finally`
 * avoids leaking a thread pool across repeated `collect()` calls in a
 * long-lived process (`kuml serve`, the MCP server, or a test suite that
 * reuses one JVM across many cases).
 */
internal object SqlStatementCollector {
    fun collect(
        files: List<Path>,
        diagnostics: MutableList<ReverseDiagnostic>,
    ): List<Pair<Statement, String>> {
        val executor: ExecutorService =
            Executors.newSingleThreadExecutor { r -> Thread(r, "kuml-reverse-sql-parser").apply { isDaemon = true } }
        try {
            val result = mutableListOf<Pair<Statement, String>>()
            for (file in files) {
                val fileName = file.fileName.toString()
                val text =
                    try {
                        Files.readString(file)
                    } catch (e: Exception) {
                        diagnostics +=
                            ReverseDiagnostic(
                                ReverseDiagnostic.Severity.WARN,
                                "REV-SQL-002",
                                "Failed to read file: ${e.message ?: e.javaClass.simpleName}",
                                file = fileName,
                            )
                        continue
                    }

                val whole = tryParseWhole(text, executor)
                if (whole != null) {
                    whole.forEach { result += it to fileName }
                    continue
                }

                for (chunk in splitStatements(text)) {
                    val trimmed = chunk.trim()
                    if (trimmed.isEmpty()) continue
                    try {
                        result += CCJSqlParserUtil.parse(trimmed, executor, null) to fileName
                    } catch (e: Exception) {
                        diagnostics +=
                            ReverseDiagnostic(
                                ReverseDiagnostic.Severity.WARN,
                                "REV-SQL-002",
                                "Failed to parse statement: ${firstLine(e.message) ?: e.javaClass.simpleName}",
                                file = fileName,
                            )
                    }
                }
            }
            return result
        } finally {
            executor.shutdownNow()
        }
    }

    private fun firstLine(message: String?): String? = message?.lineSequence()?.firstOrNull()

    private fun tryParseWhole(
        text: String,
        executor: ExecutorService,
    ): List<Statement>? =
        try {
            // Statements extends ArrayList<Statement> — use it directly rather than the
            // deprecated Statements.getStatements() accessor.
            CCJSqlParserUtil.parseStatements(text, executor, null)
        } catch (_: Exception) {
            null
        }

    /**
     * Splits SQL text into top-level statements on unquoted `;`. Tracks single-
     * and double-quoted literals, `--`/`/* */` comments, and Postgres
     * dollar-quoted bodies (`$$ ... $$` / `$tag$ ... $tag$`) so a `;` inside a
     * string, comment, or function body never splits a statement in half.
     */
    internal fun splitStatements(text: String): List<String> {
        val statements = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        var inSingleQuote = false
        var inDoubleQuote = false
        var inLineComment = false
        var inBlockComment = false
        var dollarTag: String? = null
        val n = text.length

        while (i < n) {
            val c = text[i]
            when {
                inLineComment -> {
                    current.append(c)
                    i++
                    if (c == '\n') inLineComment = false
                }
                inBlockComment -> {
                    if (c == '*' && i + 1 < n && text[i + 1] == '/') {
                        current.append("*/")
                        i += 2
                        inBlockComment = false
                    } else {
                        current.append(c)
                        i++
                    }
                }
                dollarTag != null -> {
                    val tag = dollarTag
                    if (c == '$' && text.startsWith(tag, i)) {
                        current.append(tag)
                        i += tag.length
                        dollarTag = null
                    } else {
                        current.append(c)
                        i++
                    }
                }
                inSingleQuote -> {
                    current.append(c)
                    if (c == '\'') {
                        if (i + 1 < n && text[i + 1] == '\'') {
                            current.append('\'')
                            i += 2
                        } else {
                            inSingleQuote = false
                            i++
                        }
                    } else {
                        i++
                    }
                }
                inDoubleQuote -> {
                    current.append(c)
                    if (c == '"') {
                        if (i + 1 < n && text[i + 1] == '"') {
                            current.append('"')
                            i += 2
                        } else {
                            inDoubleQuote = false
                            i++
                        }
                    } else {
                        i++
                    }
                }
                c == '-' && i + 1 < n && text[i + 1] == '-' -> {
                    current.append("--")
                    i += 2
                    inLineComment = true
                }
                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    current.append("/*")
                    i += 2
                    inBlockComment = true
                }
                c == '\'' -> {
                    current.append(c)
                    inSingleQuote = true
                    i++
                }
                c == '"' -> {
                    current.append(c)
                    inDoubleQuote = true
                    i++
                }
                c == '$' -> {
                    val end = text.indexOf('$', i + 1)
                    val isTag = end >= 0 && (end == i + 1 || text.substring(i + 1, end).all { it.isLetterOrDigit() || it == '_' })
                    if (isTag) {
                        val tag = text.substring(i, end + 1)
                        current.append(tag)
                        i = end + 1
                        dollarTag = tag
                    } else {
                        current.append(c)
                        i++
                    }
                }
                c == ';' -> {
                    statements += current.toString()
                    current.clear()
                    i++
                }
                else -> {
                    current.append(c)
                    i++
                }
            }
        }
        if (current.toString().isNotBlank()) statements += current.toString()
        return statements
    }
}
