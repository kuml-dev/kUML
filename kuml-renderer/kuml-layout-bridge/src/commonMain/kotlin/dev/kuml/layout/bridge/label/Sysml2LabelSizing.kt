package dev.kuml.layout.bridge.label

import dev.kuml.layout.Size

/**
 * Font independent size estimate of a SysML 2 edge label. One function for the label plan, the note placement and the
 * renderer, so what is planned is what is drawn (PAL wave 2B-c).
 */
public object Sysml2LabelSizing {
    /** Estimated width of one label character. */
    public const val CHAR_PX: Float = 6.6f

    /** Height of one label line. */
    public const val LINE_PX: Float = 15f
    private const val PAD_PX: Float = 8f

    /** Size of a label drawn as [lines] (one entry per drawn line). */
    public fun sizeOf(lines: List<String>): Size {
        val widest = lines.maxOfOrNull { it.length } ?: 0
        return Size(width = widest * CHAR_PX + PAD_PX, height = lines.size * LINE_PX + PAD_PX / 2f)
    }

    /** Width limit of a port label line in characters. */
    public const val PORT_WRAP_CHARS: Int = 24

    /** Longest port label (characters) the wrap handles; the rest is dropped by the model limit, never by the wrap. */
    private const val PORT_LABEL_MAX_CHARS: Int = 512

    /**
     * Wraps a port label to at most 2 lines of [maxChars] characters (PAL wave 2C-b2). Breaks after `:` or `.` or at a
     * space, whichever lies closest to (and not beyond) the limit; never shortens the text. A label that fits stays one
     * line; a second line longer than [maxChars] is kept whole (the label grows wider instead of losing characters).
     */
    public fun wrapPortLabel(
        text: String,
        maxChars: Int = PORT_WRAP_CHARS,
    ): List<String> {
        val t = if (text.length > PORT_LABEL_MAX_CHARS) text.take(PORT_LABEL_MAX_CHARS) else text
        if (t.length <= maxChars) return listOf(t)
        var cut = -1
        for (i in minOf(maxChars, t.length - 1) downTo 1) {
            val c = t[i - 1]
            if (c == ':' || c == '.') {
                cut = i
                break
            }
            if (t[i] == ' ') {
                cut = i
                break
            }
        }
        if (cut <= 0) return listOf(t)
        val first = t.substring(0, cut).trimEnd()
        val second = t.substring(cut).trimStart()
        return if (second.isEmpty()) listOf(first) else listOf(first, second)
    }
}
