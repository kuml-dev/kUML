package dev.kuml.io.svg.erm

/**
 * Shared pixel-metric constants for ERM/Chen rendering (V3.4.4).
 *
 * Both `dev.kuml.layout.bridge.erm.ErmChenSizeProvider` (module
 * `kuml-layout-bridge`) and the SVG renderer routines in this package
 * (module `kuml-io-svg`) MUST use these exact values — the size provider
 * pre-computes box/oval/diamond dimensions before ELK ever runs, and the
 * renderer later draws shapes and text sized against the very same
 * constants. If the two drift apart, labels overflow their shape (the same
 * failure mode documented for `ErmSizing` / ERM-Martin and, before that,
 * `UmlContentSizeProvider` — see CLAUDE.md's "Renderer-Sizing-Heuristik").
 *
 * `kuml-io-svg` intentionally does not depend on `kuml-layout-bridge` *for
 * its own sizing constants* (see `ErmSizing`'s KDoc for the house
 * convention) — so these constants are mirrored, not shared. When changing
 * a value here, update the companion object of `ErmChenSizeProvider` in
 * lock-step.
 *
 * Chen notation shape vocabulary, unlike Martin/Bachman's single "entity
 * box with an attribute list inside":
 *
 * - **Entity**: a rectangle containing only the entity name (title row) —
 *   no attribute compartment. [ENTITY_H] is a fixed single-row height.
 * - **Attribute**: its own ellipse, connected to the owning entity by a
 *   plain line. Primary-key attributes get an underlined name.
 * - **Relationship**: its own diamond (rhombus), connected to both its
 *   source and target entity by a plain line carrying a cardinality label
 *   near the entity end.
 */
internal object ErmChenSizing {
    // ── Default fallback (matches SizeProvider.constant() defaults) ──────
    const val DEFAULT_W: Float = 160f
    const val DEFAULT_H: Float = 80f

    // ── Character-width estimates ──────────────────────────────────────────

    /** Avg width of a 13px bold sans-serif char (entity/view title, diamond/oval labels use [BODY_CHAR_PX]). */
    const val TITLE_CHAR_PX: Float = 8.4f

    /** Avg width of an 11px regular sans-serif char (attribute/relationship/view-preview labels). */
    const val BODY_CHAR_PX: Float = 6.6f

    // ── Entity (title-only box) ────────────────────────────────────────────
    const val ENTITY_MIN_W: Float = 120f
    const val ENTITY_W_PAD: Float = 24f
    const val ENTITY_H: Float = 44f

    // ── Attribute oval ──────────────────────────────────────────────────────
    const val OVAL_MIN_W: Float = 90f
    const val OVAL_PAD_X: Float = 16f
    const val OVAL_H: Float = 40f

    // ── Relationship diamond ────────────────────────────────────────────────
    const val DIAMOND_MIN_W: Float = 110f
    const val DIAMOND_PAD: Float = 30f
    const val DIAMOND_H: Float = 60f

    // ── View box (dashed, reused from Martin/Bachman look) ──────────────────
    const val VIEW_H: Float = 60f

    /** Double-border inset for `weak` entities (drawn as a second inner rect) — shared with ERM/Martin. */
    const val WEAK_BORDER_INSET: Float = 3f

    /** Distance a cardinality label sits back from the entity-side end of a diamond↔entity connector. */
    const val CARDINALITY_LABEL_OFFSET_PX: Float = 14f

    /**
     * Perpendicular lift of a cardinality label off the connector line —
     * mirrors `ERM_ROLE_LABEL_PERP_PX` in `ErmEdgeLabels.kt`. Bug-fix
     * (fix/erm-chen-label-collisions, V3.4.7): before this constant existed,
     * `renderChenConnector` offset the label purely along the edge tangent
     * with `text-anchor="middle"`, so the glyph straddled the polyline for
     * any near-vertical/-horizontal connector.
     */
    const val CARDINALITY_LABEL_PERP_PX: Float = 9f

    /**
     * Per-sibling along-edge step for cardinality labels that share the same
     * entity endpoint (e.g. three foreign keys converging on one hub entity)
     * — mirrors `ERM_LABEL_STACK_OFFSET_PX`. Applied on top of
     * [CARDINALITY_LABEL_OFFSET_PX], capped at
     * [dev.kuml.io.svg.erm.CHEN_CARDINALITY_MAX_STACK_INDEX] steps so a
     * dense hub doesn't fling labels far from their entity.
     */
    const val CARDINALITY_LABEL_STACK_PX: Float = 14f

    /**
     * Belt-and-suspenders margin: if a computed cardinality-label point still
     * falls inside the owning entity's box (expanded by this margin), the
     * label is pushed further out along the edge until it clears. Guards
     * against ELK emitting a port slightly inset from the entity face or a
     * near-degenerate first/last polyline segment — by construction the
     * SOURCE/TARGET offset direction fix already clears well-formed layouts.
     */
    const val CARDINALITY_TITLE_CLEARANCE_PX: Float = 6f
}
