package dev.kuml.layout.bridge.erm

import dev.kuml.erm.model.ErmAttribute
import dev.kuml.erm.model.ErmDiagram
import dev.kuml.erm.model.ErmEntity
import dev.kuml.erm.model.ErmModel
import dev.kuml.erm.model.ErmView
import dev.kuml.layout.LayoutDirection
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.SizeProvider

/**
 * Content-aware [SizeProvider] for ERM/Martin diagrams (V3.4.2).
 *
 * Mirrors [dev.kuml.layout.bridge.UmlContentSizeProvider] structurally: walks
 * the model once, pre-computes an `elementId → Size` map, and adds a
 * per-relationship "connection buffer" so edges docking on a hub entity
 * (many foreign keys) don't all crowd onto the same few pixels.
 *
 * **Size-sync invariant**: the vertical row metrics used here
 * (`TITLE_ROW_H`, `ROW_H`, `DIVIDER_GAP`, `BOX_BOTTOM_PAD`) and the
 * character-width estimates (`TITLE_CHAR_PX`, `BODY_CHAR_PX`,
 * `SMALL_CHAR_PX`) MUST stay numerically identical to
 * `dev.kuml.io.svg.erm.ErmSizing` in module `kuml-io-svg` — that module
 * cannot depend on this one (house convention, see `ErmSizing`'s KDoc), so
 * the constants are duplicated rather than shared. Changing one side without
 * the other reproduces the classic overflow bug documented for UML boxes.
 *
 * Usage:
 * ```kotlin
 * val sizes = ErmContentSizeProvider(model, diagram)
 * val graph = ErmLayoutBridge.toLayoutGraph(model, diagram, sizes)
 * ```
 */
public class ErmContentSizeProvider
    constructor(
        model: ErmModel,
        diagram: ErmDiagram,
        private val layoutDirection: LayoutDirection = LayoutDirection.TopToBottom,
    ) : SizeProvider {
        /**
         * Number of relationship ends docking on each entity id. Self-references
         * count twice (both ends land on the same box).
         */
        private val connectionsById: Map<String, Int> =
            buildMap {
                fun bump(id: String) {
                    put(id, (get(id) ?: 0) + 1)
                }
                for (rel in model.relationships) {
                    bump(rel.sourceEntityId)
                    bump(rel.targetEntityId)
                }
            }

        private val byId: Map<String, Size> =
            buildMap {
                for (entity in model.entities) {
                    put(entity.id, entitySize(entity, diagram))
                }
                if (diagram.showViews) {
                    for (view in model.views) {
                        put(view.id, viewSize(view))
                    }
                }
            }

        override fun sizeOf(
            elementId: String,
            elementKind: String,
        ): Size = byId[elementId] ?: Size(DEFAULT_W, DEFAULT_H)

        // ── Size computations ─────────────────────────────────────────────────

        private fun entitySize(
            entity: ErmEntity,
            diagram: ErmDiagram,
        ): Size {
            val pkAttrs = entity.primaryKey
            val nonPkAttrs = entity.attributes.filterNot { it.primaryKey }
            val showIdxAndChecks = diagram.showIndexes && (entity.indexes.isNotEmpty() || entity.checks.isNotEmpty())

            val bodyLines = mutableListOf<String>()
            for (attr in pkAttrs) bodyLines.add(attributeLine(attr))
            for (attr in nonPkAttrs) bodyLines.add(attributeLine(attr))
            for (idx in entity.indexes) {
                bodyLines.add("«idx» ${idx.name ?: idx.id} (${idx.attributeIds.joinToString(", ")})")
            }
            for (check in entity.checks) bodyLines.add("«check» ${check.expression}")

            val titleW = estimateWidth(entity.name ?: entity.id, TITLE_CHAR_PX)
            val bodyMaxW = bodyLines.maxOfOrNull { estimateWidth(it, BODY_CHAR_PX) } ?: 0f
            val w = maxOf(DEFAULT_W, maxOf(titleW, bodyMaxW) + MARKER_COL_W + 2 * PAD_X)

            var h = TITLE_ROW_H
            if (pkAttrs.isNotEmpty()) h += DIVIDER_GAP + pkAttrs.size * ROW_H
            if (nonPkAttrs.isNotEmpty()) h += DIVIDER_GAP + nonPkAttrs.size * ROW_H
            if (showIdxAndChecks) h += DIVIDER_GAP + (entity.indexes.size + entity.checks.size) * ROW_H
            h += BOX_BOTTOM_PAD
            h = maxOf(h, DEFAULT_H)

            val (wExtra, hExtra) = connectionPuffer(entity.id)
            return Size(w + wExtra, h + hExtra)
        }

        private fun attributeLine(attr: ErmAttribute): String {
            val suffix = if (!attr.nullable) " NN" else ""
            val defaultSuffix = attr.default?.let { " = $it" } ?: ""
            return "${attr.name ?: attr.id} : ${attr.type.render()}$suffix$defaultSuffix"
        }

        private fun viewSize(view: ErmView): Size {
            val titleW = estimateWidth("«view» ${view.name ?: view.id}", TITLE_CHAR_PX)
            val previewW = estimateWidth(view.query.replace("\n", " ").trim().take(60), SMALL_CHAR_PX)
            val w = maxOf(DEFAULT_W, maxOf(titleW, previewW) + 2 * PAD_X)
            val h = maxOf(DEFAULT_H, TITLE_ROW_H + DIVIDER_GAP + ROW_H + BOX_BOTTOM_PAD)
            val (wExtra, hExtra) = connectionPuffer(view.id)
            return Size(w + wExtra, h + hExtra)
        }

        /**
         * Connection buffer in px for [nodeId] — see
         * [dev.kuml.layout.bridge.UmlContentSizeProvider.connectionPuffer] for
         * the full rationale. Growth direction follows the docking side ELK
         * is expected to use: vertical layout flow → width grows; horizontal
         * layout flow → height grows.
         */
        private fun connectionPuffer(nodeId: String): Pair<Float, Float> {
            val n = connectionsById[nodeId] ?: 0
            if (n == 0) return 0f to 0f
            val raw = (n * CONNECTION_PUFFER_PX).coerceAtMost(CONNECTION_PUFFER_MAX_PX)
            return when (layoutDirection) {
                LayoutDirection.TopToBottom, LayoutDirection.BottomToTop -> raw to 0f
                LayoutDirection.LeftToRight, LayoutDirection.RightToLeft -> 0f to raw
            }
        }

        private fun estimateWidth(
            text: String,
            charPx: Float,
        ): Float = text.length * charPx

        public companion object {
            // ── Defaults (match SizeProvider.constant() defaults) ─────────────
            public const val DEFAULT_W: Float = 160f
            public const val DEFAULT_H: Float = 80f

            // ── Vertical line metrics — MUST match dev.kuml.io.svg.erm.ErmSizing ─
            public const val TITLE_ROW_H: Float = 26f
            public const val ROW_H: Float = 18f
            public const val DIVIDER_GAP: Float = 14f
            public const val BOX_BOTTOM_PAD: Float = 12f

            // ── Horizontal metrics — MUST match dev.kuml.io.svg.erm.ErmSizing ────
            public const val MARKER_COL_W: Float = 26f
            public const val PAD_X: Float = 10f
            public const val TITLE_CHAR_PX: Float = 8.4f
            public const val BODY_CHAR_PX: Float = 6.6f
            public const val SMALL_CHAR_PX: Float = 5.6f

            // ── Connection-aware sizing ────────────────────────────────────────
            public const val CONNECTION_PUFFER_PX: Float = 14f
            public const val CONNECTION_PUFFER_MAX_PX: Float = 200f
        }
    }
