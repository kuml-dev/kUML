package dev.kuml.core.model.note

/**
 * One run of note text with a uniform style. [href] is set only for a link that passed
 * [NoteMarkup.isAllowedHref].
 */
data class NoteSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val href: String? = null,
) {
    /** True if the span carries no style and no link. */
    val isPlain: Boolean get() = !bold && !italic && !code && href == null
}

/** One line of a note: a paragraph or a list item. */
sealed interface NoteBlock {
    val spans: List<NoteSpan>
}

/** A normal line (an empty line has no spans). */
data class NoteParagraph(
    override val spans: List<NoteSpan>,
) : NoteBlock

/** A bullet (`- x`) or numbered (`1. x`) list item. [number] is the displayed number of an ordered item. */
data class NoteListItem(
    val ordered: Boolean,
    val number: Int,
    override val spans: List<NoteSpan>,
) : NoteBlock

/**
 * Parsed note text. [isPlain] is true if the text contains no markup at all (no list item,
 * no styled span, no consumed escape); renderers then keep their unchanged plain-text path.
 */
data class NoteDoc(
    val blocks: List<NoteBlock>,
    val isPlain: Boolean,
)

/**
 * Parser for the small inline markup of note text: `**bold**`, `*italic*`, `***both***`,
 * `` `code` ``, `[text](https://url)`, `- ` and `1. ` list items and backslash escapes.
 *
 * The parser never throws and never reads the input beyond [KumlNoteLimits.MAX_PARSE_CHARS].
 * Everything that is not recognised stays plain text, so ordinary prose such as `2*3*4`
 * or `snake_case` is unchanged. Worst-case runtime is O(depth * L^2) for L <= 2000.
 */
object NoteMarkup {
    private const val ESCAPABLE = "*`[]\\"

    /** Parses [text] into blocks of styled spans. */
    fun parse(text: String): NoteDoc = Parser().run(text)

    /**
     * True if [url] may become a link target: 1..[KumlNoteLimits.MAX_URL_LEN] printable ASCII
     * characters (no space, control or bidi character), no `)`, scheme `http://` or `https://`
     * (case-insensitive) followed by at least one character. Also called by the SVG renderer.
     */
    fun isAllowedHref(url: String): Boolean {
        if (url.isEmpty() || url.length > KumlNoteLimits.MAX_URL_LEN) return false
        for (c in url) {
            if (c.code !in 0x21..0x7E || c == ')') return false
        }
        val lower = url.lowercase()
        val prefix =
            when {
                lower.startsWith("https://") -> 8
                lower.startsWith("http://") -> 7
                else -> return false
            }
        return lower.length > prefix
    }

    private class Sink {
        val spans = ArrayList<NoteSpan>()

        fun add(span: NoteSpan) {
            if (span.text.isEmpty()) return
            val last = spans.lastOrNull()
            if (last != null && last.bold == span.bold && last.italic == span.italic && last.code == span.code && last.href == span.href) {
                spans[spans.size - 1] = last.copy(text = last.text + span.text)
            } else {
                spans.add(span)
            }
        }
    }

    private class Parser {
        var formatted = 0
        var usedEscape = false

        private val exhausted: Boolean get() = formatted >= KumlNoteLimits.MAX_SPANS

        fun run(text: String): NoteDoc {
            val blocks = ArrayList<NoteBlock>()
            var offset = 0
            var prevNumber = 0
            for (line in text.split('\n')) {
                val lineStart = offset
                offset += line.length + 1
                val block = parseLine(line = line, lineStart = lineStart, prevNumber = prevNumber)
                prevNumber = if (block is NoteListItem && block.ordered) block.number else 0
                blocks.add(block)
            }
            val plain = !usedEscape && blocks.all { it is NoteParagraph && it.spans.all(NoteSpan::isPlain) }
            return NoteDoc(blocks = blocks, isPlain = plain)
        }

        private fun plainParagraph(line: String): NoteBlock =
            NoteParagraph(spans = if (line.isEmpty()) emptyList() else listOf(NoteSpan(text = line)))

        private fun parseLine(
            line: String,
            lineStart: Int,
            prevNumber: Int,
        ): NoteBlock {
            if (lineStart >= KumlNoteLimits.MAX_PARSE_CHARS || exhausted) return plainParagraph(line)
            val end = minOf(line.length, KumlNoteLimits.MAX_PARSE_CHARS - lineStart)

            var contentStart = 0
            var ordered = false
            var bullet = false
            var number = 0
            if (end >= 2 && line[0] == '-' && line[1] == ' ') {
                bullet = true
                contentStart = 2
            } else {
                var d = 0
                while (d < end && d < 4 && line[d] in '0'..'9') d++
                if (d in 1..3 && d + 1 < end && line[d] == '.' && line[d + 1] == ' ') {
                    ordered = true
                    number = line.substring(0, d).toInt()
                    contentStart = d + 2
                }
            }

            val sink = Sink()
            inline(s = line, from = contentStart, to = end, style = Style(), depth = 0, inLink = false, sink = sink)
            if (end < line.length) sink.add(NoteSpan(text = line.substring(end)))
            return when {
                bullet -> NoteListItem(ordered = false, number = 0, spans = sink.spans)
                ordered -> NoteListItem(ordered = true, number = if (prevNumber > 0) prevNumber + 1 else number, spans = sink.spans)
                else -> NoteParagraph(spans = sink.spans)
            }
        }

        private class Style(
            val bold: Boolean = false,
            val italic: Boolean = false,
            val href: String? = null,
        )

        private fun emit(
            sink: Sink,
            text: String,
            style: Style,
            code: Boolean = false,
        ) {
            if (text.isEmpty()) return
            val span = NoteSpan(text = text, bold = style.bold, italic = style.italic, code = code, href = style.href)
            if (!span.isPlain) formatted++
            sink.add(span)
        }

        @Suppress("LongParameterList", "CyclomaticComplexMethod", "LoopWithTooManyJumpStatements", "ReturnCount")
        private fun inline(
            s: String,
            from: Int,
            to: Int,
            style: Style,
            depth: Int,
            inLink: Boolean,
            sink: Sink,
        ) {
            val buf = StringBuilder()

            fun flush() {
                emit(sink = sink, text = buf.toString(), style = style)
                buf.setLength(0)
            }
            var i = from
            while (i < to) {
                if (exhausted) {
                    buf.append(s.substring(i, to))
                    break
                }
                val c = s[i]
                when {
                    c == '\\' -> {
                        if (i + 1 < to && ESCAPABLE.indexOf(s[i + 1]) >= 0) {
                            buf.append(s[i + 1])
                            usedEscape = true
                            i += 2
                        } else {
                            buf.append(c)
                            i++
                        }
                    }
                    c == '`' && !inLink -> {
                        val k = s.indexOf('`', i + 1)
                        if (k in (i + 2) until to) {
                            flush()
                            emit(sink = sink, text = s.substring(i + 1, k), style = style, code = true)
                            i = k + 1
                        } else {
                            buf.append(c)
                            i++
                        }
                    }
                    c == '[' && !inLink -> {
                        val j = findLinkTextEnd(s = s, from = i + 1, to = to)
                        val k = if (j > 0 && j + 1 < to && s[j + 1] == '(') s.indexOf(')', j + 2) else -1
                        if (k < 0 || k >= to) {
                            buf.append(c)
                            i++
                        } else {
                            val url = s.substring(j + 2, k)
                            if (j == i + 1 || !isAllowedHref(url) || depth + 1 > KumlNoteLimits.MAX_NESTING) {
                                buf.append(s.substring(i, k + 1))
                            } else {
                                flush()
                                inline(
                                    s = s,
                                    from = i + 1,
                                    to = j,
                                    style = Style(bold = style.bold, italic = style.italic, href = url),
                                    depth = depth + 1,
                                    inLink = true,
                                    sink = sink,
                                )
                            }
                            i = k + 1
                        }
                    }
                    c == '*' -> {
                        var n = 1
                        while (i + n < to && s[i + n] == '*') n++
                        val cost = if (n == 3) 2 else 1
                        val closer =
                            if (n <= 3 &&
                                depth + cost <= KumlNoteLimits.MAX_NESTING &&
                                canOpen(s = s, i = i, n = n, to = to)
                            ) {
                                findCloser(s = s, from = i + n, to = to, n = n)
                            } else {
                                -1
                            }
                        if (closer < 0) {
                            buf.append(s.substring(i, i + n))
                            i += n
                        } else {
                            flush()
                            val inner = Style(bold = style.bold || n >= 2, italic = style.italic || n == 1 || n == 3, href = style.href)
                            inline(s = s, from = i + n, to = closer, style = inner, depth = depth + cost, inLink = inLink, sink = sink)
                            i = closer + n
                        }
                    }
                    else -> {
                        buf.append(c)
                        i++
                    }
                }
            }
            flush()
        }

        private fun canOpen(
            s: String,
            i: Int,
            n: Int,
            to: Int,
        ): Boolean {
            if (i > 0 && s[i - 1].isLetterOrDigit()) return false
            return i + n < to && s[i + n] != ' '
        }

        /** Index of the first unescaped `]` in [from, to), or -1. */
        private fun findLinkTextEnd(
            s: String,
            from: Int,
            to: Int,
        ): Int {
            var j = from
            while (j < to) {
                val c = s[j]
                if (c == '\\' && j + 1 < to && ESCAPABLE.indexOf(s[j + 1]) >= 0) {
                    j += 2
                } else if (c == ']') {
                    return j
                } else {
                    j++
                }
            }
            return -1
        }

        /** Start of the first closing run of exactly [n] stars in [from, to), or -1. */
        private fun findCloser(
            s: String,
            from: Int,
            to: Int,
            n: Int,
        ): Int {
            var j = from
            while (j < to) {
                val c = s[j]
                when {
                    c == '\\' && j + 1 < to && ESCAPABLE.indexOf(s[j + 1]) >= 0 -> j += 2
                    c == '`' -> {
                        val k = s.indexOf('`', j + 1)
                        j = if (k in (j + 2) until to) k + 1 else j + 1
                    }
                    c == '*' -> {
                        var m = 1
                        while (j + m < to && s[j + m] == '*') m++
                        val closes =
                            m == n && j > from && s[j - 1] != ' ' && (j + m >= to || !s[j + m].isLetterOrDigit())
                        if (closes) return j
                        j += m
                    }
                    else -> j++
                }
            }
            return -1
        }
    }
}
