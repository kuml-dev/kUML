package dev.kuml.core.model

/**
 * Supported diagram types.
 *
 * V1 implements: [CLASS], [SEQUENCE], [STATE], [COMPONENT], [USE_CASE] and all C4 types.
 * V1.1 adds the remaining UML 2.x types.
 */
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
}
