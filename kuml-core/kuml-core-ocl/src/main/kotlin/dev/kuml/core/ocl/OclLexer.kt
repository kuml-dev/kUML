package dev.kuml.core.ocl

/**
 * One scanned OCL lexeme: its [token], its 0-based char span `[start, end)`
 * within the source string, its 1-based [position], and whether it represents
 * a scan error ([isError]).
 *
 * Introduced (Wave 1 — `OclSyntax`) so [dev.kuml.core.ocl.OclSyntax.highlight]
 * can report exact highlight spans without re-scanning the source. [token] is
 * a placeholder ([OclToken.Eof]) when [isError] is `true` — error lexemes
 * carry no meaningful token, only a span to highlight.
 */
internal data class OclLexeme(
    val token: OclToken,
    val start: Int,
    val end: Int,
    val position: OclPosition,
    val isError: Boolean = false,
)

internal object OclLexer {
    /**
     * Tokenizes [input] and returns the token list only, discarding source
     * positions. Retained for callers that only need the parsed AST (the vast
     * majority — [OclParser] does not require positions to build the tree).
     */
    internal fun tokenize(input: String): List<OclToken> = scan(input).map { it.token }

    /**
     * Tokenizes [input] and returns both the token list and a parallel list of
     * 1-based [OclPosition]s — `positions[i]` is the source position of
     * `tokens[i]` (V3.2.23 — `sourcePosition` on [KumlViolation]).
     *
     * A parallel array (rather than embedding position fields directly on
     * [OclToken]) keeps [OclToken] a plain `data object`/`data class`
     * hierarchy whose `==` comparisons in [OclParser] (`peek() == OclToken.Dot`
     * etc.) stay position-independent — embedding `line`/`col` on every token
     * subtype would turn singleton tokens into per-occurrence instances and
     * break every such comparison across the parser.
     */
    internal fun tokenizeWithPositions(input: String): Pair<List<OclToken>, List<OclPosition>> {
        val lexemes = scan(input)
        return lexemes.map { it.token } to lexemes.map { it.position }
    }

    /**
     * Scans [input] into [OclLexeme]s, one per token plus a trailing
     * [OclToken.Eof]. This is the single char-scanner the rest of the module
     * is built on; [tokenize] and [tokenizeWithPositions] are thin
     * projections of its result.
     *
     * In the default (non-[tolerant]) mode this behaves exactly like the
     * original inline scanner it replaces: it throws [OclEvaluationException]
     * on an unterminated string literal or an unexpected character.
     * [OclParser] relies on this strict mode via [tokenize] /
     * [tokenizeWithPositions] — nothing about parsing changes.
     *
     * In [tolerant] mode (used only by
     * [dev.kuml.core.ocl.OclSyntax.highlight]), scan errors never throw —
     * they are recorded as [OclLexeme.isError] entries so the rest of the
     * (possibly still-valid) source keeps highlighting:
     *  - an unexpected character becomes a single-char error lexeme; the scan
     *    resumes right after it, so good tokens after the bad char still
     *    highlight.
     *  - an unterminated string literal becomes an error lexeme spanning from
     *    the opening quote to the end of input; since there is no closing
     *    quote to resume from, the scan stops there (the rest of the input is
     *    presumed to be inside the unterminated string).
     */
    internal fun scan(
        input: String,
        tolerant: Boolean = false,
    ): List<OclLexeme> {
        val lexemes = mutableListOf<OclLexeme>()
        var i = 0
        var line = 1
        var lineStart = 0 // index into `input` of the first character of the current line

        fun posAt(index: Int) = OclPosition(line = line, col = index - lineStart + 1)

        fun push(
            token: OclToken,
            start: Int,
            end: Int,
            isError: Boolean = false,
        ) {
            lexemes += OclLexeme(token, start, end, posAt(start), isError)
        }

        while (i < input.length) {
            val tokenStart = i
            when {
                input[i] == '\n' -> {
                    i++
                    line++
                    lineStart = i
                }
                input[i].isWhitespace() -> i++
                // Two-char operators first
                i + 1 < input.length && input.substring(i, i + 2) == "->" -> {
                    push(OclToken.Arrow, tokenStart, tokenStart + 2)
                    i += 2
                }
                i + 1 < input.length && input.substring(i, i + 2) == "<>" -> {
                    push(OclToken.Op("<>"), tokenStart, tokenStart + 2)
                    i += 2
                }
                i + 1 < input.length && input.substring(i, i + 2) == "<=" -> {
                    push(OclToken.Op("<="), tokenStart, tokenStart + 2)
                    i += 2
                }
                i + 1 < input.length && input.substring(i, i + 2) == ">=" -> {
                    push(OclToken.Op(">="), tokenStart, tokenStart + 2)
                    i += 2
                }
                // "@pre" — post-condition pre-state snapshot marker (OCL 2.x `expr@pre`).
                i + 3 < input.length &&
                    input.substring(i, i + 4) == "@pre" &&
                    (i + 4 >= input.length || !(input[i + 4].isLetterOrDigit() || input[i + 4] == '_')) -> {
                    push(OclToken.AtPre, tokenStart, tokenStart + 4)
                    i += 4
                }
                // Single-char
                input[i] == '.' -> {
                    push(OclToken.Dot, tokenStart, tokenStart + 1)
                    i++
                }
                input[i] == '(' -> {
                    push(OclToken.LParen, tokenStart, tokenStart + 1)
                    i++
                }
                input[i] == ')' -> {
                    push(OclToken.RParen, tokenStart, tokenStart + 1)
                    i++
                }
                input[i] == '|' -> {
                    push(OclToken.Pipe, tokenStart, tokenStart + 1)
                    i++
                }
                input[i] == ',' -> {
                    push(OclToken.Comma, tokenStart, tokenStart + 1)
                    i++
                }
                input[i] == ';' -> {
                    push(OclToken.Semicolon, tokenStart, tokenStart + 1)
                    i++
                }
                input[i] == '=' -> {
                    push(OclToken.Op("="), tokenStart, tokenStart + 1)
                    i++
                }
                input[i] == '<' -> {
                    push(OclToken.Op("<"), tokenStart, tokenStart + 1)
                    i++
                }
                input[i] == '>' -> {
                    push(OclToken.Op(">"), tokenStart, tokenStart + 1)
                    i++
                }
                input[i] == '+' -> {
                    push(OclToken.Op("+"), tokenStart, tokenStart + 1)
                    i++
                }
                input[i] == '-' -> {
                    push(OclToken.Op("-"), tokenStart, tokenStart + 1)
                    i++
                }
                input[i] == '*' -> {
                    push(OclToken.Op("*"), tokenStart, tokenStart + 1)
                    i++
                }
                input[i] == '/' -> {
                    push(OclToken.Op("/"), tokenStart, tokenStart + 1)
                    i++
                }
                // String literal (single-quoted)
                input[i] == '\'' -> {
                    val end = input.indexOf('\'', i + 1)
                    if (end < 0) {
                        if (tolerant) {
                            push(OclToken.Eof, tokenStart, input.length, isError = true)
                            i = input.length
                        } else {
                            throw OclEvaluationException("Unterminated string literal")
                        }
                    } else {
                        push(OclToken.StrLit(input.substring(i + 1, end)), tokenStart, end + 1)
                        i = end + 1
                    }
                }
                // Integer or Real literal (e.g. "3.14"). A '.' is only consumed as
                // part of the number if followed by another digit — otherwise it is
                // the navigation operator (e.g. "self.attributes" must not eat the dot).
                input[i].isDigit() -> {
                    val start = i
                    while (i < input.length && input[i].isDigit()) i++
                    val isReal =
                        i + 1 < input.length && input[i] == '.' && input[i + 1].isDigit()
                    if (isReal) {
                        i++ // consume '.'
                        while (i < input.length && input[i].isDigit()) i++
                        push(OclToken.RealLit(input.substring(start, i).toDouble()), tokenStart, i)
                    } else {
                        push(OclToken.IntLit(input.substring(start, i).toInt()), tokenStart, i)
                    }
                }
                // Identifiers and keywords
                input[i].isLetter() || input[i] == '_' -> {
                    val start = i
                    while (i < input.length && (input[i].isLetterOrDigit() || input[i] == '_')) i++
                    val token =
                        when (val word = input.substring(start, i)) {
                            "true" -> OclToken.TrueLit
                            "false" -> OclToken.FalseLit
                            "null" -> OclToken.NullLit
                            "self" -> OclToken.Self
                            else -> OclToken.Ident(word)
                        }
                    push(token, tokenStart, i)
                }
                else -> {
                    if (tolerant) {
                        push(OclToken.Eof, tokenStart, tokenStart + 1, isError = true)
                        i++
                    } else {
                        throw OclEvaluationException("Unexpected character: '${input[i]}' at position $i")
                    }
                }
            }
        }
        lexemes += OclLexeme(OclToken.Eof, input.length, input.length, posAt(i))
        return lexemes
    }
}
