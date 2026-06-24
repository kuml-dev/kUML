package dev.kuml.io.svg.blueprint

import dev.kuml.blueprint.model.ChannelKind

/**
 * Centralised SVG icon fragments per [ChannelKind], normalised to a 24×24 box
 * (analogous to BpmnEventSymbols). Scaled into the touchpoint via transform.
 *
 * V3.1.23
 */
internal object BlueprintChannelIcons {
    fun fragmentFor(kind: ChannelKind): String =
        when (kind) {
            ChannelKind.WEB ->
                """<circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" stroke-width="1.5"/>""" +
                    """<line x1="3" y1="12" x2="21" y2="12" stroke="currentColor" stroke-width="1"/>""" +
                    """<ellipse cx="12" cy="12" rx="4" ry="9" fill="none" stroke="currentColor" stroke-width="1"/>"""
            ChannelKind.APP ->
                """<rect x="7" y="3" width="10" height="18" rx="2" fill="none" stroke="currentColor" stroke-width="1.5"/>""" +
                    """<line x1="10" y1="18" x2="14" y2="18" stroke="currentColor" stroke-width="1.5"/>"""
            ChannelKind.PHONE ->
                """<path d="M6 3c1 4 3 8 6 11s7 5 11 6l-2 3c-5-1-10-4-13-7S4 9 3 5z" fill="none" stroke="currentColor" stroke-width="1.5"/>"""
            ChannelKind.EMAIL ->
                """<rect x="2" y="5" width="20" height="14" rx="1" fill="none" stroke="currentColor" stroke-width="1.5"/>""" +
                    """<polyline points="2,5 12,13 22,5" fill="none" stroke="currentColor" stroke-width="1.5"/>"""
            ChannelKind.IN_PERSON ->
                """<circle cx="12" cy="7" r="4" fill="none" stroke="currentColor" stroke-width="1.5"/>""" +
                    """<path d="M4 21c0-5 4-7 8-7s8 2 8 7" fill="none" stroke="currentColor" stroke-width="1.5"/>"""
            ChannelKind.MAIL ->
                """<rect x="4" y="4" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.5"/>""" +
                    """<line x1="4" y1="8" x2="20" y2="8" stroke="currentColor" stroke-width="1"/>"""
            ChannelKind.SOCIAL ->
                """<path d="M4 5h16v10H9l-4 4v-4H4z" fill="none" stroke="currentColor" stroke-width="1.5"/>"""
            ChannelKind.CHAT ->
                """<path d="M3 5h18v11H8l-5 4z" fill="none" stroke="currentColor" stroke-width="1.5"/>"""
            ChannelKind.OTHER ->
                """<circle cx="12" cy="12" r="3" fill="currentColor"/>"""
        }
}
