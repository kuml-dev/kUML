package dev.kuml.layout.bridge

import dev.kuml.c4.model.C4Component
import dev.kuml.c4.model.C4Container
import dev.kuml.c4.model.C4DeploymentNode
import dev.kuml.c4.model.C4Element
import dev.kuml.c4.model.C4Model
import dev.kuml.c4.model.C4Person
import dev.kuml.c4.model.C4Relationship
import dev.kuml.c4.model.C4SoftwareSystem
import dev.kuml.layout.LayoutDirection
import dev.kuml.layout.Size
import dev.kuml.layout.TextWrap

/**
 * Content-aware [SizeProvider] for C4 diagrams (System Landscape, System
 * Context, Container, Component).
 *
 * The previous default [SizeProvider.constant] returned `160 × 80` regardless
 * of the element's content. C4 nodes carry three text rows by default:
 * stereotype header (e.g. `[Software System]`), name (bold) and optional
 * description. Long descriptions like
 * `"Manages loan applications and disbursements"` ran past the 160-px box edge
 * and visually overlapped the boxes below — exactly what was observed in the
 * "Enterprise Banking Landscape" sample.
 *
 * This provider walks the [C4Model] once and pre-computes an
 * `elementId → Size` map. For each element it measures:
 *
 * - **Width**: max over header, name and (wrapped) description, capped at
 *   [DESC_WRAP_MAX_W] so descriptions break to multiple lines instead of
 *   stretching the box horizontally.
 * - **Height**: header + name + N description lines + padding, matching the
 *   y-offsets used in the SVG renderers (`y=18`, `y=36`, `y=52` plus a 13-px
 *   line increment for `kuml-small`).
 *
 * Wrapping uses [C4TextWrap.wrapToWidth] with identical constants to the SVG
 * renderers in `kuml-io-svg/c4/`, so the bridge's height prediction and the
 * renderer's actual `<tspan>` output stay in sync.
 *
 * As in [UmlContentSizeProvider], a per-element "connection puffer" is added
 * to the side where ELK is expected to dock edges — wider for vertical layout
 * flows, taller for horizontal ones. Hub nodes with many relationships get a
 * little more room so edge endpoints don't pile up on the same pixel.
 */
public class C4ContentSizeProvider
    @JvmOverloads
    constructor(
        model: C4Model,
        private val layoutDirection: LayoutDirection = LayoutDirection.TopToBottom,
    ) : SizeProvider {
        private val connectionsById: Map<String, Int> =
            run {
                val out = mutableMapOf<String, Int>()
                countConnections(model.relationships, out)
                out
            }

        private val byId: Map<String, Size> =
            run {
                val out = mutableMapOf<String, Size>()
                collect(model.elements, out)
                out
            }

        override fun sizeOf(
            elementId: String,
            elementKind: String,
        ): Size = byId[elementId] ?: Size(DEFAULT_W, DEFAULT_H)

        private fun collect(
            elements: Iterable<C4Element>,
            out: MutableMap<String, Size>,
        ) {
            for (e in elements) {
                when (e) {
                    is C4Person -> out[e.id] = personSize(e)
                    is C4SoftwareSystem -> out[e.id] = boxSize(e.name, "[Software System]", e.description, e.id)
                    is C4Container -> {
                        val tech = e.technology?.let { " $it" } ?: ""
                        out[e.id] = boxSize(e.name, "[Container:$tech]", e.description, e.id)
                    }
                    is C4Component -> {
                        val tech = e.technology?.let { " $it" } ?: ""
                        out[e.id] = boxSize(e.name, "[Component:$tech]", e.description, e.id)
                    }
                    is C4DeploymentNode -> {
                        // Same shape as a Container box: stereotype header + bold name
                        // + optional wrapped description. The SVG renderer in
                        // kuml-io-svg/c4/C4DeploymentNodeSvg.kt mirrors this layout.
                        val tech = e.technology?.let { ":$it" } ?: ""
                        out[e.id] = boxSize(e.name, "[Deployment Node$tech]", e.description, e.id)
                    }
                    else -> {} // C4Model itself and unknown subtypes — fall back to default.
                }
            }
        }

        private fun countConnections(
            relationships: List<C4Relationship>,
            out: MutableMap<String, Int>,
        ) {
            fun bump(id: String) {
                out[id] = (out[id] ?: 0) + 1
            }
            for (r in relationships) {
                bump(r.source)
                bump(r.target)
            }
        }

        /**
         * Connection puffer in px for [nodeId]. Direction follows the expected
         * edge-docking side, same logic as [UmlContentSizeProvider].
         */
        private fun connectionPuffer(nodeId: String): Pair<Float, Float> {
            val n = connectionsById[nodeId] ?: 0
            if (n == 0) return 0f to 0f
            val raw = (n * CONNECTION_PUFFER_PX).coerceAtMost(CONNECTION_PUFFER_MAX_PX)
            return when (layoutDirection) {
                LayoutDirection.TopToBottom,
                LayoutDirection.BottomToTop,
                -> raw to 0f
                LayoutDirection.LeftToRight,
                LayoutDirection.RightToLeft,
                -> 0f to raw
            }
        }

        // ── Size computation ─────────────────────────────────────────────────────

        /**
         * Computes width + height for a System / Container / Component box
         * (stereotype header + bold name + optional wrapped description).
         */
        private fun boxSize(
            name: String,
            header: String,
            description: String?,
            id: String,
        ): Size {
            // Step 1 — preliminary width: max over header, name, single-line desc.
            val headerW = estimateWidth(header, STEREO_CHAR_PX)
            val nameW = estimateWidth(name, TITLE_CHAR_PX)
            val descSingleW = description?.let { estimateWidth(it, DESC_CHAR_PX) } ?: 0f
            val descCappedW = descSingleW.coerceAtMost(DESC_WRAP_MAX_W - 2 * H_PAD)

            val contentW = maxOf(headerW, nameW, descCappedW)
            var boxW = maxOf(DEFAULT_W, contentW + 2 * H_PAD)

            // Step 2 — wrap description to that inner width; recompute height.
            val descLines =
                if (description != null) {
                    TextWrap.wrapToWidth(
                        text = description,
                        maxWidthPx = boxW - 2 * H_PAD,
                        charPx = DESC_CHAR_PX,
                    )
                } else {
                    emptyList()
                }
            // Step 3 — let the box widen to fit the longest wrapped line in case
            // a single very long word forced a wider line than the cap.
            val longestLinePx = descLines.maxOfOrNull { estimateWidth(it, DESC_CHAR_PX) } ?: 0f
            if (longestLinePx + 2 * H_PAD > boxW) {
                boxW = longestLinePx + 2 * H_PAD
            }

            // Height — matches the y-offsets in the SVG renderers:
            // stereotype at y=18, name at y=36, first desc line at y=52,
            // subsequent desc lines at +13 each, then bottom padding.
            val boxH =
                if (descLines.isEmpty()) {
                    // header + name only — leave room below the name baseline.
                    NAME_BASELINE_Y + BOX_BOTTOM_PAD_NO_DESC
                } else {
                    DESC_FIRST_BASELINE_Y +
                        (descLines.size - 1) * DESC_LINE_H +
                        BOX_BOTTOM_PAD_WITH_DESC
                }

            val (wExtra, hExtra) = connectionPuffer(id)
            return Size(boxW + wExtra, boxH + hExtra)
        }

        /**
         * Person nodes are a stick figure stacked above a label box. Total
         * height = stick-figure area (58 px) + label box height which grows
         * with the wrapped description, mirroring [renderC4Person] in
         * `kuml-io-svg`.
         */
        private fun personSize(p: C4Person): Size {
            val nameW = estimateWidth(p.name, TITLE_CHAR_PX)
            val descSingleW = p.description?.let { estimateWidth(it, DESC_CHAR_PX) } ?: 0f
            val descCappedW = descSingleW.coerceAtMost(DESC_WRAP_MAX_W - 2 * H_PAD)
            val contentW = maxOf(nameW, descCappedW)
            var boxW = maxOf(DEFAULT_W, contentW + 2 * H_PAD)

            val descLines =
                if (p.description != null) {
                    TextWrap.wrapToWidth(
                        text = p.description!!,
                        maxWidthPx = boxW - 2 * H_PAD - 8f, // label box inset (rect x=4, width=w-8)
                        charPx = DESC_CHAR_PX,
                    )
                } else {
                    emptyList()
                }
            val longestLinePx = descLines.maxOfOrNull { estimateWidth(it, DESC_CHAR_PX) } ?: 0f
            if (longestLinePx + 2 * H_PAD + 8f > boxW) {
                boxW = longestLinePx + 2 * H_PAD + 8f
            }

            // Label box: name baseline at boxY+18; each desc line +13 below; +10 bottom.
            val labelBoxH =
                if (descLines.isEmpty()) {
                    PERSON_LABEL_BOX_H_NO_DESC
                } else {
                    PERSON_NAME_BASELINE_OFFSET +
                        PERSON_NAME_TO_DESC_GAP +
                        (descLines.size - 1) * DESC_LINE_H +
                        PERSON_LABEL_BOX_BOTTOM_PAD
                }
            val totalH = PERSON_STICK_AREA_H + labelBoxH

            val (wExtra, hExtra) = connectionPuffer(p.id)
            return Size(boxW + wExtra, totalH + hExtra)
        }

        private fun estimateWidth(
            text: String,
            charPx: Float,
        ): Float = text.length * charPx

        public companion object {
            // ── Defaults (match the previous SizeProvider.constant fallback) ───
            public const val DEFAULT_W: Float = 160f
            public const val DEFAULT_H: Float = 80f

            // ── Horizontal char-width estimates (mirrors UmlContentSizeProvider) ──

            /** Avg width of a 14pt bold sans-serif char (incl. ~10% slack). */
            public const val TITLE_CHAR_PX: Float = 8.4f

            /** Avg width of a 10pt italic sans-serif char (stereotype label). */
            public const val STEREO_CHAR_PX: Float = 5.6f

            /** Avg width of a 9pt sans-serif char (description — `kuml-small`). */
            public const val DESC_CHAR_PX: Float = 5.4f

            /** Horizontal padding (left + right) inside a C4 box. */
            public const val H_PAD: Float = 12f

            /**
             * Maximum box width before we force the description to wrap into
             * multiple lines. Keeps Landscape diagrams compact instead of
             * stretching one box across the whole canvas.
             */
            public const val DESC_WRAP_MAX_W: Float = 240f

            // ── Vertical y-offsets — match the absolute coordinates in the
            //    C4 SVG renderers (renderC4SoftwareSystem/Container/Component) ──

            /** y-coordinate of the bold name text inside the box (renderer puts it here). */
            public const val NAME_BASELINE_Y: Float = 36f

            /** Bottom padding when only header + name are shown (no description). */
            public const val BOX_BOTTOM_PAD_NO_DESC: Float = 16f

            /** y-coordinate of the first description line (`<text y="52">`). */
            public const val DESC_FIRST_BASELINE_Y: Float = 52f

            /** Bottom padding after the last description line. */
            public const val BOX_BOTTOM_PAD_WITH_DESC: Float = 10f

            /** Line height for `kuml-small` (description) text. */
            public const val DESC_LINE_H: Float = 13f

            // ── Person-specific (mirrors renderC4Person) ───────────────────────

            /** Stick-figure area before the label box starts (head + body + legs + gap). */
            public const val PERSON_STICK_AREA_H: Float = 58f

            /** Name baseline inside the person label box (boxY + 18). */
            public const val PERSON_NAME_BASELINE_OFFSET: Float = 18f

            /** Gap from name baseline to first description line inside the person label box. */
            public const val PERSON_NAME_TO_DESC_GAP: Float = 16f

            /** Bottom padding inside the person label box. */
            public const val PERSON_LABEL_BOX_BOTTOM_PAD: Float = 10f

            /** Label box height when no description is present (matches the old `boxH = 28`). */
            public const val PERSON_LABEL_BOX_H_NO_DESC: Float = 28f

            // ── Connection-aware sizing (mirrors UmlContentSizeProvider) ───────
            public const val CONNECTION_PUFFER_PX: Float = 14f
            public const val CONNECTION_PUFFER_MAX_PX: Float = 200f
        }
    }
