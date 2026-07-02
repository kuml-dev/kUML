package dev.kuml.core.ocl

internal object OclLexer {
    internal fun tokenize(input: String): List<OclToken> {
        val tokens = mutableListOf<OclToken>()
        var i = 0
        while (i < input.length) {
            when {
                input[i].isWhitespace() -> i++
                // Two-char operators first
                i + 1 < input.length && input.substring(i, i + 2) == "->" -> {
                    tokens += OclToken.Arrow
                    i += 2
                }
                i + 1 < input.length && input.substring(i, i + 2) == "<>" -> {
                    tokens += OclToken.Op("<>")
                    i += 2
                }
                i + 1 < input.length && input.substring(i, i + 2) == "<=" -> {
                    tokens += OclToken.Op("<=")
                    i += 2
                }
                i + 1 < input.length && input.substring(i, i + 2) == ">=" -> {
                    tokens += OclToken.Op(">=")
                    i += 2
                }
                // "@pre" — post-condition pre-state snapshot marker (OCL 2.x `expr@pre`).
                i + 3 < input.length &&
                    input.substring(i, i + 4) == "@pre" &&
                    (i + 4 >= input.length || !(input[i + 4].isLetterOrDigit() || input[i + 4] == '_')) -> {
                    tokens += OclToken.AtPre
                    i += 4
                }
                // Single-char
                input[i] == '.' -> {
                    tokens += OclToken.Dot
                    i++
                }
                input[i] == '(' -> {
                    tokens += OclToken.LParen
                    i++
                }
                input[i] == ')' -> {
                    tokens += OclToken.RParen
                    i++
                }
                input[i] == '|' -> {
                    tokens += OclToken.Pipe
                    i++
                }
                input[i] == ',' -> {
                    tokens += OclToken.Comma
                    i++
                }
                input[i] == ';' -> {
                    tokens += OclToken.Semicolon
                    i++
                }
                input[i] == '=' -> {
                    tokens += OclToken.Op("=")
                    i++
                }
                input[i] == '<' -> {
                    tokens += OclToken.Op("<")
                    i++
                }
                input[i] == '>' -> {
                    tokens += OclToken.Op(">")
                    i++
                }
                input[i] == '+' -> {
                    tokens += OclToken.Op("+")
                    i++
                }
                input[i] == '-' -> {
                    tokens += OclToken.Op("-")
                    i++
                }
                input[i] == '*' -> {
                    tokens += OclToken.Op("*")
                    i++
                }
                input[i] == '/' -> {
                    tokens += OclToken.Op("/")
                    i++
                }
                // String literal (single-quoted)
                input[i] == '\'' -> {
                    val end = input.indexOf('\'', i + 1)
                    if (end < 0) throw OclEvaluationException("Unterminated string literal")
                    tokens += OclToken.StrLit(input.substring(i + 1, end))
                    i = end + 1
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
                        tokens += OclToken.RealLit(input.substring(start, i).toDouble())
                    } else {
                        tokens += OclToken.IntLit(input.substring(start, i).toInt())
                    }
                }
                // Identifiers and keywords
                input[i].isLetter() || input[i] == '_' -> {
                    val start = i
                    while (i < input.length && (input[i].isLetterOrDigit() || input[i] == '_')) i++
                    tokens +=
                        when (val word = input.substring(start, i)) {
                            "true" -> OclToken.TrueLit
                            "false" -> OclToken.FalseLit
                            "null" -> OclToken.NullLit
                            "self" -> OclToken.Self
                            else -> OclToken.Ident(word)
                        }
                }
                else -> throw OclEvaluationException("Unexpected character: '${input[i]}' at position $i")
            }
        }
        tokens += OclToken.Eof
        return tokens
    }
}
