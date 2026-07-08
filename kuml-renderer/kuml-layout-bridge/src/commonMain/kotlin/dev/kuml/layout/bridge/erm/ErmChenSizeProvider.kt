package dev.kuml.layout.bridge.erm

import dev.kuml.erm.model.ErmDiagram
import dev.kuml.erm.model.ErmModel
import dev.kuml.erm.model.ErmView
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.SizeProvider

/**
 * Content-aware [SizeProvider] for ERM/Chen diagrams (V3.4.4).
 *
 * Dispatches purely on the [ErmChenLayoutBridge] id prefix — never by
 * looking the stripped id up in the model and guessing its kind from which
 * lookup happens to succeed first (entity/attribute/relationship ids are
 * independent id spaces and could collide).
 *
 * Deliberately does **not** reuse [ErmContentSizeProvider]: that provider's
 * entity size bakes in the attribute-list compartment height, which Chen
 * does not want (Chen entities are a title-only box; attributes are their
 * own oval nodes). It also skips the "connection buffer" hub-crowding
 * heuristic entirely — in Chen, attributes and relationship diamonds each
 * dock individually via their own dedicated edge, so there is no shared
 * docking side to crowd the way a Martin/Bachman hub entity's foreign keys
 * do.
 *
 * **Size-sync invariant**: every constant in the companion object MUST stay
 * numerically identical to `dev.kuml.io.svg.erm.ErmChenSizing` in module
 * `kuml-io-svg` — that module cannot depend on this one for its own internal
 * sizing constants (mirrored, not shared, exactly like
 * [ErmContentSizeProvider] / `dev.kuml.io.svg.erm.ErmSizing`). Changing a
 * value here without updating the other reproduces the classic overflow bug
 * documented for UML boxes in CLAUDE.md's "Renderer-Sizing-Heuristik".
 *
 * Usage:
 * ```kotlin
 * val sizes = ErmChenSizeProvider(model, diagram)
 * val graph = ErmChenLayoutBridge.toChenLayoutGraph(model, diagram, sizes)
 * ```
 */
public class ErmChenSizeProvider(
    model: ErmModel,
    diagram: ErmDiagram,
) : SizeProvider {
    private val byId: Map<String, Size> =
        buildMap {
            for (entity in model.entities) {
                put(ErmChenLayoutBridge.ENTITY_PREFIX + entity.id, entitySize(entity.name ?: entity.id))
                for (attr in entity.attributes) {
                    put(ErmChenLayoutBridge.ATTR_PREFIX + attr.id, ovalSize(attr.name ?: attr.id))
                }
            }
            for (rel in model.relationships) {
                put(ErmChenLayoutBridge.REL_PREFIX + rel.id, diamondSize(rel.name ?: rel.id))
            }
            if (diagram.showViews) {
                for (view in model.views) {
                    put(ErmChenLayoutBridge.VIEW_PREFIX + view.id, viewSize(view))
                }
            }
        }

    override fun sizeOf(
        elementId: String,
        elementKind: String,
    ): Size = byId[elementId] ?: Size(DEFAULT_W, DEFAULT_H)

    // ── Size computations ─────────────────────────────────────────────────

    private fun entitySize(name: String): Size {
        val w = maxOf(ENTITY_MIN_W, estimateWidth(name, TITLE_CHAR_PX) + ENTITY_W_PAD)
        return Size(w, ENTITY_H)
    }

    private fun ovalSize(name: String): Size {
        val w = maxOf(OVAL_MIN_W, estimateWidth(name, BODY_CHAR_PX) + OVAL_PAD_X)
        return Size(w, OVAL_H)
    }

    private fun diamondSize(name: String): Size {
        // A rhombus needs roughly twice the plain text width to keep the
        // label clear of the shape's slanted edges — see ErmChenSizing's
        // KDoc for the matching renderer-side geometry.
        val w = maxOf(DIAMOND_MIN_W, estimateWidth(name, BODY_CHAR_PX) * 2f + DIAMOND_PAD)
        return Size(w, DIAMOND_H)
    }

    private fun viewSize(view: ErmView): Size {
        val titleW = estimateWidth("«view» ${view.name ?: view.id}", TITLE_CHAR_PX)
        val previewW = estimateWidth(view.query.replace("\n", " ").trim().take(60), BODY_CHAR_PX)
        val w = maxOf(ENTITY_MIN_W, maxOf(titleW, previewW) + ENTITY_W_PAD)
        return Size(w, VIEW_H)
    }

    private fun estimateWidth(
        text: String,
        charPx: Float,
    ): Float = text.length * charPx

    public companion object {
        // ── Defaults (match SizeProvider.constant() defaults) ─────────────
        public const val DEFAULT_W: Float = 160f
        public const val DEFAULT_H: Float = 80f

        // ── Character-width estimates — MUST match dev.kuml.io.svg.erm.ErmChenSizing ─
        public const val TITLE_CHAR_PX: Float = 8.4f
        public const val BODY_CHAR_PX: Float = 6.6f

        // ── Entity (title-only box) — MUST match ErmChenSizing ────────────
        public const val ENTITY_MIN_W: Float = 120f
        public const val ENTITY_W_PAD: Float = 24f
        public const val ENTITY_H: Float = 44f

        // ── Attribute oval — MUST match ErmChenSizing ─────────────────────
        public const val OVAL_MIN_W: Float = 90f
        public const val OVAL_PAD_X: Float = 16f
        public const val OVAL_H: Float = 40f

        // ── Relationship diamond — MUST match ErmChenSizing ───────────────
        public const val DIAMOND_MIN_W: Float = 110f
        public const val DIAMOND_PAD: Float = 30f
        public const val DIAMOND_H: Float = 60f

        // ── View box (dashed, reused from Martin/Bachman look) ────────────
        public const val VIEW_H: Float = 60f
    }
}
