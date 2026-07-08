package dev.kuml.io.svg.erm

/**
 * Shared pixel-metric constants for ERM/Martin rendering (V3.4.2).
 *
 * Both `dev.kuml.layout.bridge.erm.ErmContentSizeProvider` (module
 * `kuml-layout-bridge`) and the SVG renderer routines in this package
 * (module `kuml-io-svg`) MUST use these exact values — the size provider
 * pre-computes box dimensions before ELK ever runs, and the renderer later
 * draws text at cumulative `cy` offsets derived from the very same
 * increments. If the two drift apart, attribute rows overflow the box
 * (the same failure mode documented for `UmlContentSizeProvider` in
 * CLAUDE.md's "Renderer-Sizing-Heuristik").
 *
 * `kuml-io-svg` intentionally does not depend on `kuml-layout-bridge` (see
 * the C4Description/UmlComponent house convention referenced throughout
 * the codebase) — so these constants are mirrored, not shared via a common
 * dependency. When changing a value here, update the companion object of
 * `ErmContentSizeProvider` in lock-step.
 *
 * Vertical layout of an entity box (top to bottom):
 * ```
 * [ TITLE_ROW_H ]                — entity name, centered, bold
 * [ DIVIDER_GAP ] [ PK rows ]    — only if the entity has a primary key
 * [ DIVIDER_GAP ] [ other rows ] — only if the entity has non-PK attributes
 * [ DIVIDER_GAP ] [ idx/check ]  — only if `showIndexes` and any exist
 * [ BOX_BOTTOM_PAD ]
 * ```
 * Each bracketed compartment is only emitted (by both the renderer and the
 * size provider) when it actually has content — an empty entity is just a
 * title bar.
 */
internal object ErmSizing {
    /** Height reserved for the entity-name title row (bold, centered). */
    const val TITLE_ROW_H: Float = 26f

    /** Height of a single attribute / index / check-constraint text row. */
    const val ROW_H: Float = 18f

    /** Space allotted for a divider line plus surrounding breathing room, before a non-empty compartment. */
    const val DIVIDER_GAP: Float = 14f

    /** Bottom padding after the last compartment. */
    const val BOX_BOTTOM_PAD: Float = 12f

    /** Horizontal text padding (both sides) inside the entity box, in addition to [MARKER_COL_W]. */
    const val PAD_X: Float = 10f

    /** Width reserved for the left-hand PK/FK/U marker column. */
    const val MARKER_COL_W: Float = 26f

    /** Avg width of a 13px bold sans-serif char (entity title). */
    const val TITLE_CHAR_PX: Float = 8.4f

    /** Avg width of an 11px regular sans-serif char (attribute rows). */
    const val BODY_CHAR_PX: Float = 6.6f

    /** Avg width of a 10px italic sans-serif char (index/check rows, view query preview). */
    const val SMALL_CHAR_PX: Float = 5.6f

    /** Default entity/view box size when no content is known (fallback, matches SizeProvider.constant defaults). */
    const val DEFAULT_W: Float = 160f
    const val DEFAULT_H: Float = 80f

    // ── Connection-aware sizing (mirrors UmlContentSizeProvider) ──────────

    /** Extra px per adjoining relationship, added on the ELK-facing docking side. */
    const val CONNECTION_PUFFER_PX: Float = 14f

    /** Upper bound for the connection buffer — protects hub entities (many FKs) from growing unbounded. */
    const val CONNECTION_PUFFER_MAX_PX: Float = 200f

    /** Double-border inset for `weak` entities (drawn as a second inner rect). */
    const val WEAK_BORDER_INSET: Float = 3f
}
