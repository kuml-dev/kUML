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

    /** Approximate width (px) of one character of a 14 px bold action title (mirrors the size provider constant). */
    public const val TITLE_CHAR_PX: Float = 8.4f

    /**
     * Lines of an action name: forced breaks (`\n`) are kept, each paragraph is wrapped at about 24 characters, and
     * at most 4 lines are drawn before the width grows (10 % steps) instead of truncating.
     */
    public fun actionNameLines(name: String): List<String> =
        wrap(text = name, maxWidthPx = ACTION_NAME_WIDTH_PX, maxLines = 4, charPx = TITLE_CHAR_PX).lines

    private const val ACTION_NAME_WIDTH_PX: Float = 208f

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

    /** Lines of an accept-time-event label: the [name] block first, then the [time] block. */
    public data class TimeEventLines(
        val name: List<String>,
        val time: List<String>,
    ) {
        /** Name lines followed by time lines. */
        val all: List<String> get() = name + time
    }

    /**
     * Name (at most 2 lines) and time expression of an accept-time-event. A name equal (after trim) to the time
     * expression is shown once, as time. Never empty: with neither, the old single empty line is kept.
     */
    public fun timeEventLines(
        name: String?,
        time: String?,
    ): TimeEventLines {
        val n = name?.trim().orEmpty()
        val t = time?.trim().orEmpty()
        val nameLines =
            if (n.isEmpty() || n == t) emptyList() else wrap(text = n, maxWidthPx = 120f, maxLines = 2, charPx = 6.2f).lines
        val timeLines = if (t.isEmpty() && nameLines.isNotEmpty()) emptyList() else timeLines(t)
        return TimeEventLines(name = nameLines, time = timeLines)
    }

    private fun breakLines(
        text: String,
        widthPx: Float,
        charPx: Float,
    ): List<String> {
        val perLine = (widthPx / charPx).toInt().coerceAtLeast(2)
        val out = ArrayList<String>()
        var cur = StringBuilder()
        val paragraphs =
            text
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .split('\n')
                .filter { it.isNotBlank() }
        for (paragraph in paragraphs) {
            for (word in paragraph.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
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
            // A forced break ends the paragraph: the next one starts on a fresh line.
            if (cur.isNotEmpty()) out += cur.toString()
            cur = StringBuilder()
        }
        if (out.isEmpty()) out += ""
        return out
    }
}
