package dev.kuml.layout.bridge

/** Maximum characters of one lifeline head line before it wraps. */
public const val SEQ_HEAD_MAX_CHARS_PER_LINE: Int = 24

/** Upper bound for a lifeline head box width, in px. */
public const val SEQ_HEAD_MAX_WIDTH: Float = 240f

private fun normalizeParts(name: String): Pair<String, String?> {
    val unified = name.replace("\r\n", "\n")
    val firstBreak = unified.indexOf('\n')

    fun clean(s: String): String =
        s
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace('\t', ' ')
            .replace(Regex(" {2,}"), " ")
            .trim()
    return if (firstBreak < 0) {
        clean(unified) to null
    } else {
        clean(unified.substring(0, firstBreak)) to clean(unified.substring(firstBreak + 1))
    }
}

/** Splits [text] into wrap pieces: after a space (dropped), "-" or "/" (kept). */
private fun wrapPieces(text: String): List<String> {
    val pieces = mutableListOf<String>()
    val cur = StringBuilder()
    for (c in text) {
        if (c == ' ') {
            if (cur.isNotEmpty()) pieces += cur.toString()
            cur.clear()
        } else {
            cur.append(c)
            if (c == '-' || c == '/') {
                pieces += cur.toString()
                cur.clear()
            }
        }
    }
    if (cur.isNotEmpty()) pieces += cur.toString()
    return pieces
}

private fun joinPieces(pieces: List<String>): String {
    val sb = StringBuilder()
    for (p in pieces) {
        if (sb.isNotEmpty() && !sb.endsWith("-") && !sb.endsWith("/")) sb.append(' ')
        sb.append(p)
    }
    return sb.toString()
}

private fun wrapOne(
    text: String,
    maxChars: Int,
): List<String> {
    if (text.length <= maxChars) return listOf(text)
    val pieces = wrapPieces(text)
    var take = 0
    while (take < pieces.size && joinPieces(pieces.subList(0, take + 1)).length <= maxChars) take++
    if (take == 0) take = 1
    if (take >= pieces.size) return listOf(text)
    return listOf(joinPieces(pieces.subList(0, take)), joinPieces(pieces.subList(take, pieces.size)))
}

/**
 * Lines of a sequence-diagram lifeline head name. The first `"\n"` is a hard break; names longer than
 * [maxCharsPerLine] wrap at a space or after `-` / `/` (no camel-case splitting, a single long word stays whole).
 * At most [maxLines] lines; surplus text is cut to `maxCharsPerLine - 1` characters plus an ellipsis.
 * Pure and deterministic: the width provider and the renderer share it so they agree on the head shape.
 */
public fun seqHeadLines(
    name: String,
    maxLines: Int = 2,
    maxCharsPerLine: Int = SEQ_HEAD_MAX_CHARS_PER_LINE,
): List<String> {
    val (first, second) = normalizeParts(name)
    val lines =
        if (second == null || second.isEmpty() || first.isEmpty()) {
            wrapOne(text = if (second == null) first else (first + " " + second).trim(), maxChars = maxCharsPerLine)
        } else {
            wrapOne(text = first, maxChars = maxCharsPerLine) + wrapOne(text = second, maxChars = maxCharsPerLine)
        }
    if (lines.size == 1) return lines
    // A single line may stay long (an unbreakable word is never cut); a second line that overflows is cut.
    val head = lines.subList(0, maxLines - 1)
    val tail = lines.subList(maxLines - 1, lines.size).joinToString(" ")
    val cut = if (tail.length > maxCharsPerLine) tail.take(maxCharsPerLine - 1) + "…" else tail
    return head + cut
}

/** Normalized single-line full name, used for the `<title>` tooltip of a changed head. */
public fun seqHeadFullName(name: String): String {
    val (first, second) = normalizeParts(name)
    return if (second == null) first else (first + " " + second).trim()
}

/** True when the head of [name] is shown differently from the raw name (wrapped, hard break or cut). */
public fun seqHeadIsChanged(name: String): Boolean {
    val lines = seqHeadLines(name = name)
    return lines.size > 1 || lines[0] != name
}

/** Width of a lifeline head for [name]; the keyword counts only when [showKeyword] is true. */
public fun seqHeadWidth(
    name: String,
    showKeyword: Boolean,
    keyword: String = "«lifeline»",
    titleCharPx: Float = Sysml2LayoutBridge.IBD_TITLE_CHAR_PX,
    stereoCharPx: Float = Sysml2LayoutBridge.IBD_STEREO_CHAR_PX,
    hPad: Float = Sysml2LayoutBridge.IBD_H_PAD,
): Float {
    val nameW = seqHeadLines(name = name).maxOf { it.length } * titleCharPx
    val stereoW = if (showKeyword) keyword.length * stereoCharPx else 0f
    val w = maxOf(nameW, stereoW) + 2 * hPad
    return minOf(maxOf(w, Sysml2LayoutBridge.SEQ_LIFELINE_WIDTH), SEQ_HEAD_MAX_WIDTH)
}
