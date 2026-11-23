package dev.kuml.layout.bridge

import dev.kuml.sysml2.Sysml2Limits

/** PAL Welle D2 (12e): width (px) an execution-specification label may take before it wraps at a space. */
public const val SEQ_EXEC_LABEL_WRAP_PX: Float = 130f

/** PAL Welle D2 (12e): most lines an execution-specification label wraps into. */
public const val SEQ_EXEC_LABEL_MAX_LINES: Int = 3

/** Estimated glyph width (px) of the label font, the same estimate the renderer uses. */
private const val SEQ_LABEL_CHAR_WIDTH: Float = 6.5f

/**
 * PAL Welle D2 (12e): wraps an execution-specification name for display. Never shortens the text (no ellipsis) and
 * never drops it. The break happens only at spaces, greedily within [maxWidthPx]; at most [SEQ_EXEC_LABEL_MAX_LINES]
 * lines, the last one takes everything that is left even when it is wider than [maxWidthPx] (the layout then reserves
 * the room, see [seqExecLabelWidth]). A single word wider than [maxWidthPx] stays whole on its line.
 */
public fun wrapExecLabel(
    name: String,
    maxWidthPx: Float = SEQ_EXEC_LABEL_WRAP_PX,
): List<String> {
    // Hostile-text guard: normal names are never shortened, but the input is capped so a multi-MB name cannot blow up the canvas width.
    val capped = name.take(Sysml2Limits.TEXT).trim()
    val words = capped.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    val maxChars = (maxWidthPx / SEQ_LABEL_CHAR_WIDTH).toInt().coerceAtLeast(1)
    val lines = mutableListOf<String>()
    var current = StringBuilder()
    for ((i, word) in words.withIndex()) {
        if (lines.size == SEQ_EXEC_LABEL_MAX_LINES - 1) {
            // last allowed line: take the rest unshortened
            val rest = (listOf(current.toString()).filter { it.isNotEmpty() } + words.subList(i, words.size)).joinToString(" ")
            lines += rest
            return lines
        }
        if (current.isEmpty()) {
            current.append(word)
        } else if (current.length + 1 + word.length <= maxChars) {
            current.append(' ').append(word)
        } else {
            lines += current.toString()
            current = StringBuilder(word)
        }
    }
    if (current.isNotEmpty()) lines += current.toString()
    return lines
}

/** Width (px) of the widest line of [wrapExecLabel]; the layout reserves this much room right of the bar. */
public fun seqExecLabelWidth(name: String): Float = (wrapExecLabel(name = name).maxOfOrNull { it.length } ?: 0) * SEQ_LABEL_CHAR_WIDTH
