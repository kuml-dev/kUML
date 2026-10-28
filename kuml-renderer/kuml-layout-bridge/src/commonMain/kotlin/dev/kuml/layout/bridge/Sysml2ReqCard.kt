package dev.kuml.layout.bridge

import dev.kuml.sysml2.RequirementDefinition
import dev.kuml.sysml2.TestCaseDefinition
import dev.kuml.sysml2.UseCaseDefinition

/**
 * Single source of truth for the geometry and line-wrapping of requirement / test-case cards
 * and use-case descriptions (wave C1). The layout bridge sizes the node with exactly the same
 * lines that the SVG renderer later draws — no mirrored constants that can drift apart.
 *
 * Wrapping is based on a **character count per line** derived from the box width (no font
 * measurement), which keeps golden tests independent of fonts and platforms.
 */
public object Sysml2ReqCard {
    public const val MIN_WIDTH: Float = 160f
    public const val MAX_WIDTH: Float = 240f

    public const val TITLE_MAX_CHARS: Int = 200
    public const val TEXT_MAX_CHARS: Int = 2000
    public const val SOURCE_MAX_CHARS: Int = 200
    public const val MAX_BODY_LINES: Int = 60

    public const val H_PAD: Float = 8f
    public const val TITLE_CHAR_PX: Float = 8f
    public const val BODY_CHAR_PX: Float = 6f
    public const val STEREOTYPE_BASELINE: Float = 16f
    public const val TITLE_FIRST_BASELINE: Float = 31f
    public const val TITLE_LINE_H: Float = 14f
    public const val BODY_LINE_H: Float = 13f

    /** Geometry + text lines of one card. */
    public data class Card(
        val width: Float,
        val height: Float,
        val stereotype: String,
        val titleLines: List<String>,
        val bodyLines: List<String>,
        /** y of the divider line, or `null` when the card has no body. */
        val dividerY: Float?,
        val bodyFirstBaseline: Float,
        val isAssumption: Boolean,
    )

    /** Layout of a [RequirementDefinition] card. */
    public fun forRequirement(req: RequirementDefinition): Card {
        val body = mutableListOf<Pair<String, Boolean>>() // text, isWrappable
        val rawTitle = if (req.reqId.isNotEmpty()) "${req.reqId} :: ${req.name}" else req.name
        val attrs = mutableListOf<String>()
        req.priority?.let { attrs += "priority: ${it.name.lowercase().replaceFirstChar(Char::uppercase)}" }
        req.status?.let { attrs += "status: ${it.name.lowercase().replaceFirstChar(Char::uppercase)}" }
        if (req.source.isNotEmpty()) attrs += "source: ${clip(s = req.source, max = SOURCE_MAX_CHARS)}"
        (req.subjectRef ?: req.subject)?.takeIf { it.isNotEmpty() }?.let { attrs += "subject: ${clip(s = it, max = SOURCE_MAX_CHARS)}" }
        body += clip(s = req.text, max = TEXT_MAX_CHARS) to true
        attrs.forEach { body += it to true }
        return build(
            stereotype = if (req.isAssumption) "«assumption»" else "«requirement»",
            rawTitle = rawTitle,
            blocks = body.filter { it.first.isNotEmpty() }.map { it.first },
            assumption = req.isAssumption,
        )
    }

    /** Layout of a [TestCaseDefinition] card. */
    public fun forTestCase(tc: TestCaseDefinition): Card =
        build(
            stereotype = "«testCase»",
            rawTitle = tc.name,
            blocks = listOf(clip(s = tc.text, max = TEXT_MAX_CHARS)).filter { it.isNotEmpty() },
            assumption = false,
        )

    private fun build(
        stereotype: String,
        rawTitle: String,
        blocks: List<String>,
        assumption: Boolean,
    ): Card {
        val title = clip(s = rawTitle, max = TITLE_MAX_CHARS)
        val longestBlock = blocks.maxOfOrNull { b -> b.split(Regex("\\s+")).maxOfOrNull { it.length } ?: 0 } ?: 0
        val titleWanted = minOf(title.length, 32) * TITLE_CHAR_PX + 2 * H_PAD
        val bodyWanted = minOf(blocks.maxOfOrNull { it.length } ?: 0, 36) * BODY_CHAR_PX + 2 * H_PAD
        val longestWordW = minOf(longestBlock, 36) * BODY_CHAR_PX + 2 * H_PAD
        val width = maxOf(titleWanted, bodyWanted, longestWordW).coerceIn(MIN_WIDTH, MAX_WIDTH)
        val titleChars = ((width - 2 * H_PAD) / TITLE_CHAR_PX).toInt().coerceAtLeast(8)
        val bodyChars = ((width - 2 * H_PAD) / BODY_CHAR_PX).toInt().coerceAtLeast(8)
        val titleLines = wrap(text = title, maxChars = titleChars).ifEmpty { listOf("") }
        val bodyLines = blocks.flatMap { wrap(text = it, maxChars = bodyChars) }.take(MAX_BODY_LINES)
        val lastTitleBaseline = TITLE_FIRST_BASELINE + (titleLines.size - 1) * TITLE_LINE_H
        val dividerY = if (bodyLines.isEmpty()) null else lastTitleBaseline + 8f
        val bodyFirst = (dividerY ?: lastTitleBaseline) + 15f
        val height =
            if (bodyLines.isEmpty()) {
                maxOf(lastTitleBaseline + 12f, 50f)
            } else {
                bodyFirst + (bodyLines.size - 1) * BODY_LINE_H + 10f
            }
        return Card(
            width = width,
            height = height,
            stereotype = stereotype,
            titleLines = titleLines,
            bodyLines = bodyLines,
            dividerY = dividerY,
            bodyFirstBaseline = bodyFirst,
            isAssumption = assumption,
        )
    }

    /** Use-case description layout (wave C1). */
    public data class UcDescription(
        val lines: List<String>,
        /** `true` when the description had to be cut ("…" appended, full text goes to `<title>`). */
        val truncated: Boolean,
    )

    public const val UC_DESCRIPTION_MAX_CHARS: Int = 1000
    public const val UC_DESCRIPTION_MAX_LINES: Int = 4
    public const val UC_DESCRIPTION_LINE_H: Float = 12f
    public const val UC_DESCRIPTION_CHAR_PX: Float = 5.4f

    /** Characters per description line inside a use-case ellipse of [ellipseWidth]. */
    public fun ucDescriptionChars(ellipseWidth: Float): Int = (ellipseWidth * 0.68f / UC_DESCRIPTION_CHAR_PX).toInt().coerceAtLeast(10)

    /** Wraps the description of [uc] for an ellipse of [ellipseWidth]; max. 4 lines. */
    public fun ucDescription(
        uc: UseCaseDefinition,
        ellipseWidth: Float,
    ): UcDescription {
        val clipped = clip(s = uc.description, max = UC_DESCRIPTION_MAX_CHARS)
        val all = wrap(text = clipped, maxChars = ucDescriptionChars(ellipseWidth))
        if (all.size <= UC_DESCRIPTION_MAX_LINES) return UcDescription(lines = all, truncated = clipped.length < uc.description.length)
        val cut = all.take(UC_DESCRIPTION_MAX_LINES).toMutableList()
        cut[cut.lastIndex] = cut.last().let { if (it.length >= 2) it.dropLast(1).trimEnd() + "…" else "$it…" }
        return UcDescription(lines = cut, truncated = true)
    }

    /** Clips [s] to [max] characters, appending an ellipsis. */
    public fun clip(
        s: String,
        max: Int,
    ): String = if (s.length <= max) s else s.substring(0, max - 1) + "…"

    /**
     * Wraps [text] at word boundaries to at most [maxChars] characters per line. A single word
     * longer than [maxChars] is split hard at [maxChars]. Never loses characters.
     */
    public fun wrap(
        text: String,
        maxChars: Int,
    ): List<String> {
        if (text.isBlank() || maxChars < 1) return emptyList()
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (rawWord in text.trim().split(Regex("\\s+"))) {
            var word = rawWord
            while (word.length > maxChars) {
                if (current.isNotEmpty()) {
                    lines += current.toString()
                    current = StringBuilder()
                }
                lines += word.substring(0, maxChars)
                word = word.substring(maxChars)
            }
            when {
                current.isEmpty() -> current.append(word)
                current.length + 1 + word.length <= maxChars -> current.append(' ').append(word)
                else -> {
                    lines += current.toString()
                    current = StringBuilder(word)
                }
            }
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines
    }
}
