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
}
