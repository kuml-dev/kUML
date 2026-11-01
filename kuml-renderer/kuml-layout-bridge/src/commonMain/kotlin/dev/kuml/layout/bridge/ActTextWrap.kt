package dev.kuml.layout.bridge

/**
 * Word-wrapping for activity-diagram names (PAL wave 2F). Shared by the size provider (bridge) and the SVG renderer so
 * both agree on the number of lines. Pure and deterministic; the estimate uses a fixed character width, never glyph
 * metrics, so the result does not depend on the installed fonts.
 */
public object ActTextWrap {
    /** Approximate width (px) of one character of a 13 px label. */
    public const val CHAR_PX: Float = 7.0f

    /** Hard cap of lines produced when no widening is allowed (DoS guard). */
    public const val MAX_LINES_CAP: Int = 12

    /** The wrapped lines and the width they fit into. */
    public data class Wrapped(
        val lines: List<String>,
        val widthPx: Float,
    )

    /**
     * Breaks [text] at word boundaries into lines of at most [maxWidthPx]. Words wider than a line are split hard.
     * If more than [maxLines] lines are needed the width grows (10 % steps, at most 24 times) instead of truncating;
     * nothing is ever shortened and no ellipsis is added.
     */
    public fun wrap(
        text: String,
        maxWidthPx: Float = 200f,
        maxLines: Int = 4,
        charPx: Float = CHAR_PX,
    ): Wrapped {
        var width = maxWidthPx.coerceAtLeast(charPx * 2)
        var lines = breakLines(text = text, widthPx = width, charPx = charPx)
        var steps = 0
        while (lines.size > maxLines && steps < 24) {
            width *= 1.1f
            lines = breakLines(text = text, widthPx = width, charPx = charPx)
            steps++
        }
        return Wrapped(lines = lines.take(MAX_LINES_CAP.coerceAtLeast(maxLines)), widthPx = width)
    }

    /** Convenience: only the lines. */
    public fun wrapActName(
        text: String,
        maxWidthPx: Float = 200f,
        maxLines: Int = 4,
    ): List<String> = wrap(text = text, maxWidthPx = maxWidthPx, maxLines = maxLines).lines

    /** Height (px) of a swimlane header whose [title] is wrapped to [laneWidthPx] (one line = 28 px, 16 px per extra line). */
    public fun laneHeaderHeight(
        title: String,
        laneWidthPx: Float,
    ): Float = 28f + (wrap(text = title, maxWidthPx = laneWidthPx - 16f, maxLines = 6).lines.size - 1) * 16f

    /** Lines of a signal / accept-event node: the wrapped name, then the wrapped signal if it differs. */
    public fun signalLines(
        name: String,
        signal: String?,
    ): List<String> {
        val sig = signal?.takeIf { it.isNotBlank() && it != name }
        return wrap(text = name, maxWidthPx = 200f, maxLines = 4).lines +
            (sig?.let { wrap(text = it, maxWidthPx = 200f, maxLines = 4).lines } ?: emptyList())
    }

    /** Lines of the label under an accept-time-event hourglass (11 px text). */
    public fun timeLines(label: String): List<String> = wrap(text = label, maxWidthPx = 120f, maxLines = 4, charPx = 6.2f).lines

    private fun breakLines(
        text: String,
        widthPx: Float,
        charPx: Float,
    ): List<String> {
        val perLine = (widthPx / charPx).toInt().coerceAtLeast(2)
        val out = ArrayList<String>()
        var cur = StringBuilder()
        for (word in text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            var w = word
            while (w.length > perLine) {
                if (cur.isNotEmpty()) {
                    out += cur.toString()
                    cur = StringBuilder()
                }
                out += w.take(perLine)
                w = w.drop(perLine)
            }
            if (cur.isEmpty()) {
                cur.append(w)
            } else if (cur.length + 1 + w.length <= perLine) {
                cur.append(' ').append(w)
            } else {
                out += cur.toString()
                cur = StringBuilder(w)
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        if (out.isEmpty()) out += ""
        return out
    }
}
