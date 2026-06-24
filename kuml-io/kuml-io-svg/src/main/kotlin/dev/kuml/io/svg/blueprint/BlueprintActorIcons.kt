package dev.kuml.io.svg.blueprint

import dev.kuml.blueprint.model.ActorRole

/**
 * Centralised SVG icon fragments per [ActorRole], normalised to a 16×16 box
 * (V3.1.24). Drawn into the top-right corner of a step card so the reader can
 * tell at a glance *who* performs a backstage/support action — a staff person,
 * a system, or a partner.
 *
 * - CUSTOMER → person bust (rarely shown; customer steps are obvious by band)
 * - STAFF    → person bust with a headset hint
 * - SYSTEM   → server/database cylinder
 * - PARTNER  → handshake-style two-circle glyph
 *
 * Scaled by the caller via a `transform`. Uses `currentColor` so the parent
 * `<g color=…>` controls the stroke colour.
 */
internal object BlueprintActorIcons {
    fun fragmentFor(role: ActorRole): String =
        when (role) {
            ActorRole.CUSTOMER ->
                """<circle cx="8" cy="5" r="3" fill="none" stroke="currentColor" stroke-width="1.3"/>""" +
                    """<path d="M2 15c0-3.5 2.7-5 6-5s6 1.5 6 5" fill="none" stroke="currentColor" stroke-width="1.3"/>"""
            ActorRole.STAFF ->
                """<circle cx="8" cy="5" r="3" fill="none" stroke="currentColor" stroke-width="1.3"/>""" +
                    """<path d="M2 15c0-3.5 2.7-5 6-5s6 1.5 6 5" fill="none" stroke="currentColor" stroke-width="1.3"/>""" +
                    """<path d="M4 5a4 4 0 0 1 8 0" fill="none" stroke="currentColor" stroke-width="1.1"/>"""
            ActorRole.SYSTEM ->
                """<ellipse cx="8" cy="4" rx="5" ry="2" fill="none" stroke="currentColor" stroke-width="1.3"/>""" +
                    """<path d="M3 4v8c0 1.1 2.2 2 5 2s5-0.9 5-2V4" fill="none" stroke="currentColor" stroke-width="1.3"/>""" +
                    """<path d="M3 8c0 1.1 2.2 2 5 2s5-0.9 5-2" fill="none" stroke="currentColor" stroke-width="1"/>"""
            ActorRole.PARTNER ->
                """<circle cx="5.5" cy="8" r="3" fill="none" stroke="currentColor" stroke-width="1.3"/>""" +
                    """<circle cx="10.5" cy="8" r="3" fill="none" stroke="currentColor" stroke-width="1.3"/>"""
        }
}
