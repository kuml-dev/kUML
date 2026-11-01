package dev.kuml.layout.bridge

import dev.kuml.layout.Size

/**
 * Single source of truth for the action compartment of a state box (PAL wave 2D-b): the lines shown below the
 * state name, their order and their size. The layout bridges size the box with it, the SVG renderers draw
 * exactly the same lines, so the text can never run out of the box.
 *
 * Order is fixed: `[invariant]`, `entry / ...`, `do / ...`, `exit / ...`. Blank texts produce no line.
 * The UML and the SysML 2 renderer stay separate; they only share this measuring and line-building helper.
 */
public object StateActionCompartment {
    /** Height of one compartment line (px). */
    public const val LINE_H: Float = 13f

    /** Gap between the name line and the first compartment line, including the separator (px). */
    public const val DIVIDER_GAP: Float = 6f

    /** Avg width of an 11pt `kuml-body` character (px). */
    public const val CHAR_PX: Float = 6.6f

    /** Horizontal padding left + right of the longest line (px). */
    public const val H_PAD: Float = 16f

    /** The compartment lines of a state, in display order. Blank texts are skipped. */
    public fun lines(
        invariant: String?,
        entry: String?,
        doActivity: String?,
        exit: String?,
    ): List<String> =
        listOfNotNull(
            invariant?.takeIf { it.isNotBlank() }?.let { "[$it]" },
            entry?.takeIf { it.isNotBlank() }?.let { "entry / $it" },
            doActivity?.takeIf { it.isNotBlank() }?.let { "do / $it" },
            exit?.takeIf { it.isNotBlank() }?.let { "exit / $it" },
        )

    /** Height the compartment adds below the name line; `0` for an empty compartment. */
    public fun height(lines: List<String>): Float = if (lines.isEmpty()) 0f else DIVIDER_GAP + lines.size * LINE_H

    /** Width needed by the longest line including padding; `0` for an empty compartment. */
    public fun width(lines: List<String>): Float = (lines.maxOfOrNull { it.length } ?: return 0f) * CHAR_PX + H_PAD

    /** Size the compartment needs (width of the longest line, [height]). */
    public fun measure(lines: List<String>): Size = Size(width = width(lines), height = height(lines))
}
