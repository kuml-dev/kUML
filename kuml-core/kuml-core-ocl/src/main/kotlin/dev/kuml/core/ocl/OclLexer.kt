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
                // Integer literal
                input[i].isDigit() -> {
                    val start = i
                    while (i < input.length && input[i].isDigit()) i++
                    tokens += OclToken.IntLit(input.substring(start, i).toInt())
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
