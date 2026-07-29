package dev.kuml.codegen.reverse.sql

import dev.kuml.codegen.reverse.ReverseDiagnostic
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.Statement
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * One top-level parsed SQL [Statement], plus the file it came from, plus —
 * for a `CREATE [UNIQUE] INDEX` statement whose trailing `WHERE` predicate had
 * to be stripped before JSqlParser could parse the rest (see class KDoc) —
 * that predicate's raw text. `partialIndexPredicate` is non-null *only* in
 * that case; every other statement (including a non-partial `CREATE INDEX`)
 * carries `null`.
 */
internal data class ParsedSqlStatement(
    val statement: Statement,
    val fileName: String,
    val partialIndexPredicate: String? = null,
)

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
 * ### Partial/conditional `CREATE INDEX ... WHERE ...` (V3.4.11)
 *
 * JSqlParser 5.3's `CreateIndex` grammar does **not** route its trailing
 * `WHERE` clause through the general SQL `Expression()` grammar — it uses a
 * hand-rolled flat-token accumulator with a hardcoded keyword whitelist that
 * excludes `IS`, `>`, `<`, `!=`, `AND`, `OR`. A statement like
 * `CREATE UNIQUE INDEX ... WHERE consumed_at IS NULL` therefore fails to parse
 * at *both* the whole-file fast path and the per-statement fallback (known,
 * still-open upstream limitation, JSqlParser issue #187) — the entire index
 * would otherwise be silently dropped, not just its predicate.
 *
 * [collect] works around this with a preprocessing step, entirely in front of
 * JSqlParser: [extractPartialIndexPredicate] recognizes a `CREATE [UNIQUE]
 * INDEX` statement, locates the top-level (not inside quotes/comments/parens)
 * `WHERE` keyword via [findTopLevelWhere] — Postgres's grammar guarantees
 * `WHERE` is always the last clause and always at paren-depth 0, so this is
 * unambiguous even for functional indexes (`(lower(name))`) or a preceding
 * `INCLUDE (...)`/`WITH (...)` clause — strips the predicate off, and hands
 * JSqlParser the now WHERE-less, already-well-formed remainder (the shape it
 * already parses fine today). The stripped predicate travels alongside the
 * parsed [Statement] in [ParsedSqlStatement.partialIndexPredicate].
 *
 * A cheap whole-file regex pre-check ([PARTIAL_INDEX_HINT_REGEX]) decides
 * whether a file needs this treatment at all: when it does not match, the
 * existing `tryParseWhole`/`splitStatements` path runs completely unchanged —
 * this is what guarantees zero behavior change for every file without a
 * partial index. When it does match, the whole-file fast path is skipped
 * unconditionally for that file (not just for the matching statement), so
 * every statement in it goes through [splitStatements] +
 * [extractPartialIndexPredicate] instead — [extractPartialIndexPredicate] is a
 * no-op for every non-`CREATE INDEX` statement and every non-partial `CREATE
 * INDEX`, so this costs nothing beyond a few extra scans.
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
    ): List<ParsedSqlStatement> {
        val executor: ExecutorService =
            Executors.newSingleThreadExecutor { r -> Thread(r, "kuml-reverse-sql-parser").apply { isDaemon = true } }
        try {
            val result = mutableListOf<ParsedSqlStatement>()
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

                // A file that might contain a partial CREATE INDEX ... WHERE ... skips the
                // whole-file fast path entirely — see class KDoc — so every statement in it goes
                // through the WHERE-stripping per-statement path below.
                val mightHavePartialIndex = PARTIAL_INDEX_HINT_REGEX.containsMatchIn(text)
                if (!mightHavePartialIndex) {
                    val whole = tryParseWhole(text, executor)
                    if (whole != null) {
                        whole.forEach { result += ParsedSqlStatement(it, fileName) }
                        continue
                    }
                }

                for (chunk in splitStatements(text)) {
                    val trimmed = chunk.trim()
                    if (trimmed.isEmpty()) continue
                    val (toParse, predicate) = extractPartialIndexPredicate(trimmed)
                    try {
                        result += ParsedSqlStatement(CCJSqlParserUtil.parse(toParse, executor, null), fileName, predicate)
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

    /**
     * Cheap whole-file negative pre-check: does [text] contain anything that could plausibly be a
     * partial/conditional `CREATE INDEX ... WHERE ...`? Deliberately loose (no quote/comment
     * awareness) — a false positive only costs a few extra, harmless scans in [collect] (see class
     * KDoc); a false negative would silently reintroduce the dropped-index bug this whole
     * mechanism exists to fix, so this must never under-match.
     */
    private val PARTIAL_INDEX_HINT_REGEX =
        Regex("""CREATE\s+(UNIQUE\s+)?INDEX\b[\s\S]*?\bWHERE\b""", RegexOption.IGNORE_CASE)

    /** `CREATE INDEX` / `CREATE UNIQUE INDEX` statement prefix — anchors [extractPartialIndexPredicate]. */
    private val CREATE_INDEX_PREFIX_REGEX = Regex("""^\s*CREATE\s+(UNIQUE\s+)?INDEX\b""", RegexOption.IGNORE_CASE)

    /**
     * If [stmt] is a `CREATE [UNIQUE] INDEX` statement carrying a top-level `WHERE` clause (see
     * class KDoc for the JSqlParser limitation this works around), splits it into
     * `(strippedStatementText, predicate)` — [strippedStatementText] is the WHERE-less remainder
     * (already the well-formed shape JSqlParser's grammar parses fine), [predicate] is the raw
     * predicate text with the trailing `;`/whitespace trimmed. Otherwise (not a `CREATE INDEX`, or
     * no top-level `WHERE` found — e.g. a `WHERE` that only appears inside a string/comment/paren
     * group) returns `(stmt, null)` unchanged.
     *
     * The "is this a CREATE INDEX statement" check skips leading whitespace *and* `--`/`/* */`
     * comments first (via [firstSignificantIndex]) — [splitStatements] keeps a comment that
     * precedes a statement glued to the front of that statement's chunk (comments are not
     * stripped, only tracked so a `;` inside one doesn't split), so a real-world commented
     * `CREATE UNIQUE INDEX ... WHERE ...` (like the fixture this feature was built against) would
     * otherwise silently fail this anchor check and fall straight back to the unstripped,
     * JSqlParser-unparseable text.
     */
    internal fun extractPartialIndexPredicate(stmt: String): Pair<String, String?> {
        val significantStart = firstSignificantIndex(stmt)
        if (!CREATE_INDEX_PREFIX_REGEX.containsMatchIn(stmt.substring(significantStart))) return stmt to null
        val whereIdx = findTopLevelWhere(stmt) ?: return stmt to null
        val predicate =
            stmt
                .substring(whereIdx + WHERE_KEYWORD.length)
                .trim()
                .trimEnd(';')
                .trim()
        val stripped = stmt.substring(0, whereIdx).trimEnd()
        return stripped to predicate
    }

    /**
     * Index of the first character in [text] that is neither whitespace nor part of a
     * `--`/`/* */` comment — i.e. where the actual SQL statement text begins. Returns
     * `text.length` if [text] is entirely whitespace/comments.
     */
    private fun firstSignificantIndex(text: String): Int {
        var i = 0
        var inLineComment = false
        var inBlockComment = false
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                inLineComment -> {
                    i++
                    if (c == '\n') inLineComment = false
                }
                inBlockComment -> {
                    if (c == '*' && i + 1 < n && text[i + 1] == '/') {
                        i += 2
                        inBlockComment = false
                    } else {
                        i++
                    }
                }
                c.isWhitespace() -> i++
                c == '-' && i + 1 < n && text[i + 1] == '-' -> {
                    inLineComment = true
                    i += 2
                }
                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    inBlockComment = true
                    i += 2
                }
                else -> return i
            }
        }
        return i
    }

    /**
     * Locates the start index of a top-level (not inside a quoted literal/identifier, `--`/`/* */`
     * comment, or parenthesized group) whole-word `WHERE` keyword in [text], or `null` if none
     * exists. Paren-depth tracking is what makes this correct for a functional index
     * (`CREATE INDEX ... ON t (lower(name)) WHERE ...`) or a preceding `INCLUDE (...)`/`WITH (...)`
     * clause — the `WHERE` inside those parens (there isn't one, by grammar, but a nested
     * expression could otherwise confuse a naive scan) never matches; only paren-depth-0 does.
     */
    private fun findTopLevelWhere(text: String): Int? {
        var i = 0
        var parenDepth = 0
        var inSingleQuote = false
        var inDoubleQuote = false
        var inLineComment = false
        var inBlockComment = false
        val n = text.length

        fun isIdentChar(c: Char) = c.isLetterOrDigit() || c == '_'

        while (i < n) {
            val c = text[i]
            when {
                inLineComment -> {
                    i++
                    if (c == '\n') inLineComment = false
                }
                inBlockComment -> {
                    if (c == '*' && i + 1 < n && text[i + 1] == '/') {
                        i += 2
                        inBlockComment = false
                    } else {
                        i++
                    }
                }
                inSingleQuote -> {
                    if (c == '\'') {
                        if (i + 1 < n && text[i + 1] == '\'') {
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
                    if (c == '"') {
                        if (i + 1 < n && text[i + 1] == '"') {
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
                    inLineComment = true
                    i += 2
                }
                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    inBlockComment = true
                    i += 2
                }
                c == '\'' -> {
                    inSingleQuote = true
                    i++
                }
                c == '"' -> {
                    inDoubleQuote = true
                    i++
                }
                c == '(' -> {
                    parenDepth++
                    i++
                }
                c == ')' -> {
                    parenDepth--
                    i++
                }
                parenDepth == 0 &&
                    text.regionMatches(i, WHERE_KEYWORD, 0, WHERE_KEYWORD.length, ignoreCase = true) &&
                    (i == 0 || !isIdentChar(text[i - 1])) &&
                    (i + WHERE_KEYWORD.length >= n || !isIdentChar(text[i + WHERE_KEYWORD.length])) -> {
                    return i
                }
                else -> i++
            }
        }
        return null
    }

    private const val WHERE_KEYWORD = "WHERE"
}
