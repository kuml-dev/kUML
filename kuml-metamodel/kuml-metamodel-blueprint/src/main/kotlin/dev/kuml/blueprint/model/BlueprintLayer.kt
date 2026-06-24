package dev.kuml.blueprint.model

import kotlinx.serialization.Serializable

/**
 * The four Shostack service-blueprint layers (vertical axis), top to bottom.
 *
 * The declaration order is semantic — the renderer draws bands and separator
 * lines in exactly this order (CUSTOMER on top, SUPPORT at the bottom).
 *
 * V3.1.21
 */
@Serializable
enum class BlueprintLayer {
    /** What the customer does. */
    CUSTOMER_ACTIONS,

    /** Visible staff interaction, above the Line of Visibility. */
    FRONTSTAGE,

    /** Invisible internal staff action. */
    BACKSTAGE,

    /** IT systems and partners. */
    SUPPORT_PROCESSES,
}

/**
 * The three separator lines that sit *between* adjacent [BlueprintLayer]s:
 * - [INTERACTION]          between CUSTOMER_ACTIONS and FRONTSTAGE
 * - [VISIBILITY]           between FRONTSTAGE and BACKSTAGE
 * - [INTERNAL_INTERACTION] between BACKSTAGE and SUPPORT_PROCESSES
 *
 * V3.1.21
 */
@Serializable
enum class BlueprintLine { INTERACTION, VISIBILITY, INTERNAL_INTERACTION }
