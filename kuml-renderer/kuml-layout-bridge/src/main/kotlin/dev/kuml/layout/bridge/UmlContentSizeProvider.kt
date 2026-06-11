package dev.kuml.layout.bridge

import dev.kuml.core.model.KumlDiagram
import dev.kuml.layout.Size
import dev.kuml.uml.AppliedStereotype
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlPackage
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.Visibility

/**
 * Content-aware [SizeProvider] for UML class diagrams (V2.0.44).
 *
 * The default [SizeProvider.constant] returns `160 × 80` which is too small
 * for any classifier with more than one attribute or operation — the SVG
 * renderer happily writes the feature lines below the box bottom or runs the
 * trailing characters past the box right edge.
 *
 * This provider walks the diagram once and pre-computes an
 * `elementId → Size` map. For each [UmlClass], [UmlInterface] or
 * [UmlComponent] it measures:
 *
 * - **Width**: max over the title, the `«stereo»` header, and every feature
 *   line (attributes + operations), using fixed per-character width estimates
 *   (no real font metrics — they live in the renderer). For components with
 *   ports an extra horizontal reserve is added so port labels don't collide
 *   with the title.
 * - **Height**: stereotype header + name line + divider + feature lines +
 *   bottom padding, matching the cumulative `cy` increments in
 *   [dev.kuml.io.svg.uml.renderUmlClass] etc.
 *
 * All other element kinds fall back to the constant size — keeps state-machine
 * vertices, use-case nodes, packages and partitions unchanged for V2.0.44.
 *
 * Char-width estimates are intentionally generous (~10 % padding) to absorb
 * proportional-font variance across themes (Plain, kUML, Elegant, Playful).
 *
 * Usage:
 * ```kotlin
 * val sizes = UmlContentSizeProvider(diagram)
 * val graph = UmlLayoutBridge.toLayoutGraph(diagram, sizes)
 * ```
 */
public class UmlContentSizeProvider(
    diagram: KumlDiagram,
) : SizeProvider {
    private val byId: Map<String, Size> = run {
        val out = mutableMapOf<String, Size>()
        collect(diagram.elements, out)
        out
    }

    override fun sizeOf(
        elementId: String,
        elementKind: String,
    ): Size = byId[elementId] ?: Size(DEFAULT_W, DEFAULT_H)

    private fun collect(
        elements: Iterable<*>,
        out: MutableMap<String, Size>,
    ) {
        for (e in elements) {
            when (e) {
                is UmlClass -> out[e.id] = classSize(e)
                is UmlInterface -> out[e.id] = interfaceSize(e)
                is UmlComponent -> {
                    out[e.id] = componentSize(e)
                    collect(e.nestedComponents, out)
                }
                is UmlPackage -> collect(e.members, out)
                else -> {} // ignore — fall back to default
            }
        }
    }

    // ── Size computations ─────────────────────────────────────────────────────

    private fun classSize(c: UmlClass): Size {
        val nameLine = c.name
        val stereoLine = stereoLabel(c.appliedStereotypes) ?: if (c.isAbstract) "«abstract»" else ""

        val attrLines = c.attributes.map { it.toFormattedLine() }
        val opLines = c.operations.map { it.toFormattedLine() }

        val w = boxWidth(nameLine, stereoLine, attrLines + opLines)
        val attrs = c.attributes.size
        val ops = c.operations.size
        val h = boxHeight(hasStereo = stereoLine.isNotEmpty(), attrs = attrs, ops = ops)
        return Size(w, h)
    }

    private fun interfaceSize(i: UmlInterface): Size {
        val nameLine = i.name
        val stereoLine = stereoLabel(i.appliedStereotypes) ?: "«interface»"

        val opLines = i.operations.map { it.toFormattedLine() }
        val attrLines = i.attributes.map { it.toFormattedLine() }

        val w = boxWidth(nameLine, stereoLine, attrLines + opLines)
        val h = boxHeight(hasStereo = true, attrs = i.attributes.size, ops = i.operations.size)
        return Size(w, h)
    }

    private fun componentSize(c: UmlComponent): Size {
        val nameLine = c.name
        val stereoLine = stereoLabel(c.appliedStereotypes) ?: ""

        val attrLines = c.attributes.map { it.toFormattedLine() }
        val opLines = c.operations.map { it.toFormattedLine() }

        var w = boxWidth(nameLine, stereoLine, attrLines + opLines)
        // Reserve horizontal space for port labels (port square 12px + label gap)
        if (c.ports.isNotEmpty()) {
            val maxPortLabel = c.ports.maxOf { estimateLabelWidth(it.name) }
            w = maxOf(w, w + maxPortLabel.toFloat() + PORT_RESERVE)
        }

        val hasFeatures = c.attributes.isNotEmpty() || c.operations.isNotEmpty()
        // Component always has «component» line + optional applied stereo line.
        val h =
            COMP_HEADER_H +
                (if (stereoLine.isNotEmpty()) STEREO_LINE_H else 0f) +
                NAME_LINE_H +
                (if (hasFeatures) DIVIDER_GAP + (c.attributes.size + c.operations.size) * FEATURE_LINE_H + DIVIDER_GAP else 0f) +
                BOX_BOTTOM_PAD
        return Size(w, maxOf(h, DEFAULT_H))
    }

    // ── Formatting helpers (mirrors UmlFormatHelpers.kt, kept local to bridge) ──

    private fun UmlProperty.toFormattedLine(): String {
        val vis =
            when (visibility) {
                Visibility.PUBLIC -> "+"
                Visibility.PRIVATE -> "-"
                Visibility.PROTECTED -> "#"
                Visibility.PACKAGE -> "~"
            }
        val stereoPrefix =
            if (appliedStereotypes.isNotEmpty()) {
                "«${appliedStereotypes.joinToString(", ") { it.stereotypeName }}» "
            } else {
                ""
            }
        return "$stereoPrefix$vis $name: ${type.name}"
    }

    private fun UmlOperation.toFormattedLine(): String {
        val vis =
            when (visibility) {
                Visibility.PUBLIC -> "+"
                Visibility.PRIVATE -> "-"
                Visibility.PROTECTED -> "#"
                Visibility.PACKAGE -> "~"
            }
        val params =
            parameters.joinToString(", ") { p -> "${p.name}: ${p.type.name}" }
        val ret = returnType?.name?.let { ": $it" } ?: ""
        val stereoPrefix =
            if (appliedStereotypes.isNotEmpty()) {
                "«${appliedStereotypes.joinToString(", ") { it.stereotypeName }}» "
            } else {
                ""
            }
        return "$stereoPrefix$vis $name($params)$ret"
    }

    private fun stereoLabel(applied: List<AppliedStereotype>): String? =
        if (applied.isEmpty()) {
            null
        } else {
            "«" + applied.joinToString(", ") { it.stereotypeName } + "»"
        }

    private fun boxWidth(
        nameLine: String,
        stereoLine: String,
        bodyLines: List<String>,
    ): Float {
        val titleW = estimateLabelWidth(nameLine, charPx = TITLE_CHAR_PX)
        val stereoW = if (stereoLine.isEmpty()) 0 else estimateLabelWidth(stereoLine, charPx = STEREO_CHAR_PX)
        val bodyMax = bodyLines.maxOfOrNull { estimateLabelWidth(it, charPx = BODY_CHAR_PX) } ?: 0
        val raw = maxOf(titleW, stereoW, bodyMax)
        return maxOf(DEFAULT_W, raw.toFloat() + BOX_H_PADDING)
    }

    private fun boxHeight(
        hasStereo: Boolean,
        attrs: Int,
        ops: Int,
    ): Float {
        val hasCompartments = attrs > 0 || ops > 0
        val attrCompartment = if (attrs > 0) DIVIDER_GAP + attrs * FEATURE_LINE_H else 0f
        val opsCompartment = if (ops > 0) (if (attrs > 0) DIVIDER_GAP else DIVIDER_GAP) + ops * FEATURE_LINE_H else 0f
        val h =
            (if (hasStereo) STEREO_LINE_H else 0f) +
                NAME_LINE_H +
                (if (hasCompartments) 0f else 0f) +
                attrCompartment + opsCompartment +
                BOX_BOTTOM_PAD
        return maxOf(h, DEFAULT_H)
    }

    private fun estimateLabelWidth(
        text: String,
        charPx: Float = BODY_CHAR_PX,
    ): Int = (text.length * charPx).toInt()

    public companion object {
        // ── Defaults (match UmlLayoutBridge fallback) ─────────────────────────
        public const val DEFAULT_W: Float = 160f
        public const val DEFAULT_H: Float = 80f

        // ── Vertical line metrics — match cy increments in the SVG renderers ─
        public const val STEREO_LINE_H: Float = 18f
        public const val NAME_LINE_H: Float = 20f
        public const val FEATURE_LINE_H: Float = 13f
        public const val DIVIDER_GAP: Float = 14f
        public const val BOX_BOTTOM_PAD: Float = 12f

        /** Component-glyph header reserve (two stacked 6px rects + top padding). */
        public const val COMP_HEADER_H: Float = 4f

        // ── Horizontal char-width estimates ───────────────────────────────────
        /** Avg width of a 14pt bold sans-serif char (incl. ~10% slack). */
        public const val TITLE_CHAR_PX: Float = 8.4f

        /** Avg width of an 11pt regular sans-serif char. */
        public const val BODY_CHAR_PX: Float = 6.6f

        /** Avg width of a 10pt italic sans-serif char (stereotype label). */
        public const val STEREO_CHAR_PX: Float = 5.6f

        /** Avg width of a 9pt sans-serif char (small / port label). */
        public const val SMALL_CHAR_PX: Float = 5.4f

        /** Horizontal padding (left + right) inside a box. */
        public const val BOX_H_PADDING: Float = 24f

        /** Extra reserve so port labels can sit inside without overlapping the title. */
        public const val PORT_RESERVE: Float = 12f
    }
}

