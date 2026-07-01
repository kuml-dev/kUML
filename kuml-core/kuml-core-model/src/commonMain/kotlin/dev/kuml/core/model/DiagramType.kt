package dev.kuml.core.model

import kotlinx.serialization.Serializable

/**
 * Supported diagram types.
 *
 * V1 implements: [CLASS], [SEQUENCE], [STATE], [COMPONENT], [USE_CASE] and all C4 types.
 * V1.1 adds the remaining UML 2.x types.
 */
@Serializable
enum class DiagramType {
    // ── UML 2.x — V1 (5 types) ──────────────────────────────────────────────
    CLASS,
    SEQUENCE,
    STATE,
    COMPONENT,
    USE_CASE,

    // ── UML 2.x — V1.1 ──────────────────────────────────────────────────────
    OBJECT,
    COMPOSITE_STRUCTURE,
    PACKAGE,
    DEPLOYMENT,
    PROFILE,
    ACTIVITY,
    COMMUNICATION,
    TIMING,
    INTERACTION_OVERVIEW,

    // ── BPMN 2.0 — V3.1.3 ───────────────────────────────────────────────────
    BPMN_PROCESS,

    // ── BPMN 2.0 — V3.1.4 ───────────────────────────────────────────────────
    BPMN_COLLABORATION,

    // ── BPMN 2.0 — V3.2.2 ───────────────────────────────────────────────────
    BPMN_CHOREOGRAPHY,

    // ── BPMN 2.0 — V3.2.3 ───────────────────────────────────────────────────
    BPMN_CONVERSATION,

    // ── Blueprint / Journey Map — V3.1.24 ───────────────────────────────────
    JOURNEY,
    BLUEPRINT,
}
